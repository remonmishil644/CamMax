package com.romio.cammax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

class RecordingService : Service() {

    enum class State { IDLE, STARTING, RECORDING, STOPPING }

    private class Segment(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val name: String,
        val startMs: Long
    ) {
        var firstFrameNs = 0L      // exact, from the camera; 0 when the clip began by a file switch
        var gyroT0Ns = 0L          // clock time of t = 0 in this clip's .gcsv
    }

    private val camThread = HandlerThread("cammax").apply { start() }
    private val h = Handler(camThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executor { h.post(it) }

    private lateinit var camMgr: CameraManager
    private lateinit var p: Prefs
    private lateinit var cfg: Config

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var current: Segment? = null
    private var next: Segment? = null
    private var wake: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private var gyro: GyroLogger? = null
    private var fallbackNote = ""
    private var sessionId = ""
    private var sessionFirstFrameNs = 0L
    private var recStartNs = 0L
    private var clipAnchorPending = false
    private var realtimeClock = false
    private var lastInterruption = ""
    private var lastStartId = 0
    private var openGen = 0
    private var rotation = 0
    private var startAttempts = 0
    private var retries = 0
    private var autoStopMs = 0L
    private var thermal = 0
    private var storageFull = false
    private var seq = 0
    private var clips = 0
    private var frames = 0L
    private var framesSeen = -1L
    private var stalls = 0
    private var settleTs = 0L
    private val fpsTimeline = ArrayList<Int>()
    private var dropped = 0L
    private var secBucket = -1L
    private var secCount = 0
    @Volatile private var appliedStab = ""
    private var firstTs = 0L
    private var lastTs = 0L

    private val retryRunnable = Runnable {
        if (state == State.RECORDING || state == State.STARTING) openAndRecord()
    }
    // Runs on the main thread so it still fires when the camera thread is stuck.
    private val watchdog = Runnable {
        if (state == State.STARTING) {
            log("watchdog: still starting after 15 s")
            Buzz.error(this)
            h.post { if (state == State.STARTING) finish("Camera did not start within 15 s", buzz = false) }
        }
    }
    private val stallCheck = object : Runnable {
        override fun run() {
            if (state == State.RECORDING && camera != null) {
                if (frames == framesSeen) stalls++ else { stalls = 0; framesSeen = frames }
                if (stalls >= 2) { stalls = 0; interrupted("no frames for 10 s") }
            }
            h.postDelayed(this, 5_000)
        }
    }
    private val autoStop = Runnable {
        if (state == State.RECORDING) { state = State.STOPPING; finish(null) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) {
                    Prefs(this).lastStatus = "Android refused to start the camera service. Open CamMax and try again."
                    Buzz.error(this)
                    // Let the error buzz finish before the service goes away.
                    main.postDelayed({ if (state == State.IDLE) stopSelfResult(lastStartId) }, 800)
                    return START_NOT_STICKY
                }
                if (state == State.IDLE) {
                    state = State.STARTING
                    active = this
                    autoStopMs = intent.getLongExtra(EXTRA_AUTO_STOP_MS, 0L)
                    acquireWake()
                    main.postDelayed(watchdog, 15_000)
                    h.post { begin() }
                } else {
                    log("start ignored: state=$state")
                }
            }
            ACTION_STOP -> {
                if (state == State.RECORDING) {
                    // The RECORDING to STOPPING change happens only on the camera thread.
                    h.post { if (state == State.RECORDING) { state = State.STOPPING; finish(null) } }
                } else if (state == State.IDLE) {
                    stopSelfResult(startId)
                }
            }
            else -> if (state == State.IDLE) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        p = Prefs(this)
        cfg = p.snapshot()
        camMgr = getSystemService(CAMERA_SERVICE) as CameraManager
        startAttempts = 0; retries = 0; storageFull = false; clips = 0; fallbackNote = ""; lastInterruption = ""
        sessionId = "S" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionFirstFrameNs = 0L
        realtimeClock = try {
            camMgr.getCameraCharacteristics(cfg.cameraId).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        } catch (_: Exception) { false }
        log("camera clock: ${if (realtimeClock) "realtime (shared with the gyro)" else "unknown"}")
        log("begin: cam${cfg.cameraId} ${cfg.width}x${cfg.height}@${cfg.fps} " +
                "${if (cfg.hevc) "hevc" else "h264"} ${cfg.bitrateMbps}Mbps hs=${cfg.highSpeed} " +
                "stab=${Stab.label(cfg.stabMode)}(${cfg.stabMode}) iso=${cfg.iso} autoStop=$autoStopMs")
        try { Salvage.run(this, force = true) } catch (e: Exception) { log("salvage failed: $e") }
        try { watchThermal() } catch (e: Exception) { log("thermal watch failed: $e") }
        if (cfg.gyro) {
            try {
                val g = GyroLogger(this) { p.readoutMs(readoutKey()) }
                if (g.start()) gyro = g else { g.stop(); log("no gyroscope sensor") }
            } catch (e: Exception) { log("gyro start failed: $e") }
        }
        h.removeCallbacks(stallCheck)
        h.postDelayed(stallCheck, 5_000)
        try {
            detectRotation { rot ->
                rotation = rot
                openAndRecord()
            }
        } catch (e: Exception) {
            log("rotation failed: $e")
            rotation = 90
            openAndRecord()
        }
    }

    private fun openAndRecord() {
        if (freeBytes() < MIN_START_BYTES) {
            finish("Phone storage is full: only %.1f GB free. Delete some files, then tap CamMax REC again."
                .format(freeBytes() / 1e9))
            return
        }
        appliedStab = ""; fpsTimeline.clear(); dropped = 0; secBucket = -1; secCount = 0; liveFps = 0; frames = 0; framesSeen = -1; stalls = 0; settleTs = 0; firstTs = 0; lastTs = 0
        val gen = ++openGen
        try {
            val seg = newSegment()
            current = seg
            recorder = buildRecorder(seg, gen)
            camMgr.openCamera(cfg.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    if (gen != openGen) { dev.close(); return }
                    log("camera opened")
                    camera = dev
                    configure(dev, gen)
                }
                override fun onDisconnected(dev: CameraDevice) {
                    dev.close()
                    if (gen == openGen) { camera = null; interrupted("camera disconnected") }
                }
                override fun onError(dev: CameraDevice, error: Int) {
                    dev.close()
                    if (gen == openGen) { camera = null; interrupted("camera error $error") }
                }
            }, h)
        } catch (e: SecurityException) {
            log("open failed: $e")
            finish("Camera permission missing. Open CamMax and allow it.")
        } catch (e: Exception) {
            interrupted("open failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun buildRecorder(seg: Segment, gen: Int): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(if (cfg.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(48_000)
        r.setAudioChannels(2)
        r.setAudioEncodingBitRate(AUDIO_BPS)
        r.setVideoSize(cfg.width, cfg.height)
        r.setVideoFrameRate(cfg.fps)
        r.setVideoEncodingBitRate(cfg.bitrateMbps * 1_000_000)
        r.setOrientationHint(rotation)
        // On a nearly full phone the first clip is shorter, so recording still starts.
        r.setMaxFileSize(minOf(segmentBytes(), freeBytes() - RESERVE_BYTES))
        r.setOutputFile(seg.pfd.fileDescriptor)
        r.setOnInfoListener { _, what, _ -> h.post { if (gen == openGen) onInfo(what) } }
        r.setOnErrorListener { _, what, extra ->
            h.post { if (gen == openGen) interrupted("recorder error $what/$extra") }
        }
        r.prepare()
        return r
    }

    private fun configure(dev: CameraDevice, gen: Int) {
        try {
            configureUnsafe(dev, gen)
        } catch (e: Exception) {
            interrupted("configure failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun configureUnsafe(dev: CameraDevice, gen: Int) {
        val rec = recorder ?: return
        val surface = rec.surface
        val ch = camMgr.getCameraCharacteristics(cfg.cameraId)
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
            run {
                val options = CameraCaps.stabOptions(this@RecordingService, cfg.cameraId)
                var mode = if (cfg.stabMode == Stab.AUTO) CameraCaps.bestStab(options, cfg.width) else cfg.stabMode
                if (mode !in options) mode = CameraCaps.bestStab(options, cfg.width)
                val eisList = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
                val oisList = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
                val eis = when (mode) { Stab.ENHANCED -> 2; Stab.ELECTRONIC, Stab.BOTH -> 1; else -> 0 }
                if (eis in eisList) set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eis)
                // Enhanced mode lets the camera drive OIS itself, so leave OIS untouched there.
                if (mode != Stab.ENHANCED) {
                    val ois = if (mode == Stab.OPTICAL || mode == Stab.BOTH) 1 else 0
                    if (ois in oisList) set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, ois)
                }
                log("stabilization asked: ${Stab.label(mode)}")
            }
            if (cfg.iso > 0 && !cfg.highSpeed) {
                val range = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, range?.clamp(cfg.iso) ?: cfg.iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000_000L / (2L * cfg.fps))
                set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / cfg.fps)
            }
        }.build()

        val frameCounter = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                if (appliedStab.isEmpty()) {
                    res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { ns ->
                        if (ns > 0) {
                            p.setReadoutMs(readoutKey(), ns / 1e6f)
                            log("sensor readout: %.2f ms".format(ns / 1e6))
                        }
                    }
                    val e = res.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                    val o = res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                    appliedStab = "electronic " + (when (e) { 2 -> "enhanced"; 1 -> "on"; 0 -> "off"; else -> "unknown" }) +
                            ", optical " + (when (o) { 1 -> "on"; 0 -> "off"; else -> "unknown" })
                    log("stabilization applied by camera: $appliedStab")
                }
                val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                // First frame the recorder can have encoded: anchor the clip and its gyro file to it.
                if (clipAnchorPending && (!realtimeClock || ts >= recStartNs)) {
                    clipAnchorPending = false
                    val anchor = if (realtimeClock) ts else SystemClock.elapsedRealtimeNanos()
                    current?.let { seg ->
                        seg.firstFrameNs = anchor
                        if (sessionFirstFrameNs == 0L) sessionFirstFrameNs = anchor
                        seg.gyroT0Ns = gyro?.beginClip(seg.name, anchor) ?: 0L
                    }
                }
                // Skip the first second while exposure settles.
                if (settleTs == 0L) settleTs = ts
                if (ts - settleTs < 1_000_000_000L) return
                if (firstTs == 0L) firstTs = ts
                val frameNs = 1_000_000_000L / cfg.fps
                if (lastTs > 0 && ts - lastTs > frameNs * 3 / 2) dropped += (ts - lastTs + frameNs / 2) / frameNs - 1
                lastTs = ts
                frames++
                val sec = (ts - firstTs) / 1_000_000_000L
                if (sec != secBucket) {
                    if (secBucket >= 0) { fpsTimeline.add(secCount); liveFps = secCount }
                    secBucket = sec; secCount = 0
                    liveThermal = thermal
                    liveSeconds = ((SystemClock.elapsedRealtime() - recordingSince) / 1000).toInt()
                }
                secCount++
            }
        }

        val sessionType = if (cfg.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED
                          else SessionConfiguration.SESSION_REGULAR
        try {
            dev.createCaptureSession(SessionConfiguration(
                sessionType,
                listOf(OutputConfiguration(surface)), exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != openGen) { s.close(); return }
                        session = s
                        try {
                            if (s is CameraConstrainedHighSpeedCaptureSession) {
                                s.setRepeatingBurst(s.createHighSpeedRequestList(req), frameCounter, h)
                            } else {
                                s.setRepeatingRequest(req, frameCounter, h)
                            }
                            rec.start()
                        } catch (e: Exception) {
                            interrupted("recorder refused ${cfg.width}x${cfg.height} @ ${cfg.fps} fps: ${e.message}")
                            return
                        }
                        main.removeCallbacks(watchdog)
                        recStartNs = SystemClock.elapsedRealtimeNanos()
                        clipAnchorPending = true
                        if (state == State.STARTING) {
                            recordingSince = SystemClock.elapsedRealtime()
                            state = State.RECORDING
                            Buzz.started(this@RecordingService)
                            if (autoStopMs > 0) h.postDelayed(autoStop, autoStopMs)
                        }
                        log("recording")
                        p.lastStatus = "Recording ${cfg.width}x${cfg.height} @ ${cfg.fps} fps"
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == openGen) {
                            interrupted("camera refused ${cfg.width}x${cfg.height} @ ${cfg.fps} fps")
                        }
                    }
                }))
        } catch (e: Exception) {
            interrupted("session failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun onInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (next != null) return
                if (!hasSpace()) { storageFull = true; return }
                val n = try { newSegment() } catch (_: Exception) { return }
                try {
                    recorder?.setNextOutputFile(n.pfd.fileDescriptor)
                    next = n
                } catch (_: Exception) {
                    discardSegment(n)
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                current?.let { closeSegment(it, ok = true) }
                current = next
                next = null
                retries = 0
                current?.let { it.gyroT0Ns = gyro?.beginClip(it.name, null) ?: 0L }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                if (storageFull || freeBytes() < MIN_START_BYTES)
                    finish("Phone storage is full. Clips saved. Delete some files, then tap again.")
                else interrupted("clip switch missed")
            }
        }
    }

    // Save what exists, then try again: up to 3 times while starting, 5 times while recording.
    private fun interrupted(reason: String) {
        val why = if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) "$reason (phone is hot)" else reason
        when (state) {
            State.STARTING -> {
                teardown()
                startAttempts++
                log("start attempt $startAttempts failed: $why")
                // Record something instead of nothing: each retry asks the camera for less.
                when (startAttempts) {
                    1 -> if (cfg.stabMode != Stab.OPTICAL && cfg.stabMode != Stab.OFF) {
                        fallbackNote = " The camera refused ${Stab.label(cfg.stabMode)} stabilization here, so CamMax used Optical."
                        cfg = cfg.copy(stabMode = Stab.OPTICAL)
                    }
                    2 -> if (cfg.stabMode != Stab.OFF) {
                        fallbackNote = " The camera refused stabilization here, so CamMax recorded without it."
                        cfg = cfg.copy(stabMode = Stab.OFF)
                    }
                    3 -> if (cfg.fps > 30 && !cfg.highSpeed) {
                        fallbackNote = " The camera refused ${cfg.fps} fps here, so CamMax recorded 30 fps without stabilization."
                        cfg = cfg.copy(stabMode = Stab.OFF, fps = 30)
                    }
                }
                if (startAttempts >= 4) finish("Could not start: $why")
                else {
                    log("retrying with stab=${Stab.label(cfg.stabMode)} fps=${cfg.fps}")
                    h.postDelayed(retryRunnable, 800)
                }
            }
            State.RECORDING -> {
                teardown()
                retries++
                log("interrupted ($retries): $why")
                lastInterruption = why
                if (retries > 5) {
                    finish("Stopped after repeated errors ($why). Clips saved.")
                } else {
                    p.lastStatus = "Interrupted ($why). Saved and resumed."
                    h.postDelayed(retryRunnable, 2_000)
                }
            }
            else -> {}
        }
    }

    private fun teardown() {
        openGen++
        clipAnchorPending = false
        h.removeCallbacks(retryRunnable)
        gyro?.endClip()
        try { session?.stopRepeating() } catch (_: Exception) {}
        val ok = try { recorder?.stop(); true } catch (_: Exception) { false }
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        // Release last: it destroys the surface the camera session was using.
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        current?.let { closeSegment(it, ok) }
        current = null
        next?.let { discardSegment(it) }
        next = null
    }

    private fun finish(error: String?, buzz: Boolean = true) {
        if (state == State.IDLE) return
        main.removeCallbacks(watchdog)
        h.removeCallbacks(autoStop)
        h.removeCallbacks(stallCheck)
        val fps = measuredFps()
        val g = gyro
        val motion = if (g != null && g.samples > 0 && (clips > 0 || current != null))
            ". Motion data: %.0f Hz, saved to Documents/CamMax".format(g.rateHz()) else ""
        val stab = if (appliedStab.isNotEmpty()) ". Stabilization: $appliedStab" else ""
        val drops = if (frames > 0) ". Dropped frames: $dropped (%.1f%%)".format(100.0 * dropped / (frames + dropped)) else ""
        teardown()
        try { gyro?.stop() } catch (_: Exception) {}
        gyro = null
        val got = (if (fps > 0) ", measured %.1f fps".format(fps) else "") + drops + stab + motion + "." + fallbackNote
        if (error == null) {
            Buzz.stopped(this)
            p.lastStatus = "Saved $clips clip(s) to DCIM/CamMax. " +
                    "${cfg.width}x${cfg.height}, asked ${cfg.fps} fps$got"
        } else {
            if (buzz) Buzz.error(this)
            p.lastStatus = error + got
        }
        log("finish: ${error ?: "ok"} clips=$clips$got")
        unwatchThermal()
        state = State.IDLE
        // stopSelfResult keeps the service alive when a newer start is already queued.
        main.post {
            if (state == State.IDLE) {
                releaseWake()
                stopSelfResult(lastStartId)
            }
        }
    }

    private fun newSegment(): Segment {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "CamMax_${stamp}_${++seq}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CamMax")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create file")
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: run { contentResolver.delete(uri, null, null); throw IOException("could not open file") }
        val seg = Segment(uri, pfd, name, System.currentTimeMillis())
        writeSidecar(seg, "recording")
        return seg
    }

    private fun closeSegment(s: Segment, ok: Boolean) {
        val size = try { s.pfd.statSize } catch (_: Exception) { -1L }
        try { s.pfd.close() } catch (_: Exception) {}
        if (size in 0..1023) {
            try { contentResolver.delete(s.uri, null, null) } catch (_: Exception) {}
            Sidecars.delete(this, s.name)
            gyro?.discardClip()
            if (!ok) writePublicSidecar(s, ok = false, noVideo = true)
            return
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (!ok) put(MediaStore.MediaColumns.DISPLAY_NAME, s.name.removeSuffix(".mp4") + "_broken.mp4")
        }
        try { contentResolver.update(s.uri, v, null, null) } catch (_: Exception) {}
        clips++
        log("clip closed: ${s.name} ${size / 1_000_000} MB ok=$ok")
        writeSidecar(s, if (ok) "complete" else "broken")
        writePublicSidecar(s, ok)
    }

    private fun discardSegment(s: Segment) {
        try { s.pfd.close() } catch (_: Exception) {}
        try { contentResolver.delete(s.uri, null, null) } catch (_: Exception) {}
        Sidecars.delete(this, s.name)
    }

    private fun clipJson(s: Segment, status: String) = JSONObject().apply {
                put("name", s.name)
                put("status", status)
                put("cameraId", cfg.cameraId)
                put("width", cfg.width)
                put("height", cfg.height)
                put("fpsRequested", cfg.fps)
                put("fpsMeasured", measuredFps())
                put("codec", if (cfg.hevc) "hevc" else "h264")
                put("videoBitrate", cfg.bitrateMbps * 1_000_000)
                put("audioBitrate", AUDIO_BPS)
                put("audioSampleRate", 48_000)
                put("audioChannels", 2)
                put("rotation", rotation)
                put("iso", cfg.iso)
                put("stabMode", cfg.stabMode)
                put("stabApplied", appliedStab)
                put("highSpeed", cfg.highSpeed)
                put("startMs", s.startMs)
                put("endMs", if (status == "recording") 0 else System.currentTimeMillis())
    }

    // Repair needs the exact encoder settings of each clip.
    private fun writeSidecar(s: Segment, status: String) {
        try { Sidecars.write(this, s.name, clipJson(s, status)) } catch (_: Exception) {}
    }

    // The PC editor reads this file. The contract is INTEGRATION.md in the project root: keep them in step.
    private fun writePublicSidecar(s: Segment, ok: Boolean, noVideo: Boolean = false) {
        try {
            val base = s.name.removeSuffix(".mp4")
            val j = clipJson(s, if (ok) "complete" else "broken").apply {
                put("schema", "cammax.clip/3")
                put("clock", if (realtimeClock) "realtime" else "unknown")
                put("firstFrameNs", s.firstFrameNs)
                put("sessionFirstFrameNs", sessionFirstFrameNs)
                put("gyroT0Ns", s.gyroT0Ns)
                put("gyroFirstSampleNs", gyro?.clipFirstSampleNs ?: 0L)
                put("gyroRateHz", gyro?.rateHz() ?: 0.0)
                put("fpsTimeline", org.json.JSONArray(fpsTimeline))
                put("droppedFrames", dropped)
                put("lens", CameraCaps.lensJson(this@RecordingService, cfg.cameraId, cfg.width, cfg.height, cfg.highSpeed))
                put("sessionId", sessionId)
                put("clipIndex", clips)
                put("videoFile", if (noVideo) JSONObject.NULL else if (ok) s.name else base + "_broken.mp4")
                put("gyroFile", if (!noVideo && cfg.gyro && gyro != null) "$base.gcsv" else JSONObject.NULL)
                put("gyroPreRollMs", 1000)
                put("readoutMs", p.readoutMs(readoutKey()).toDouble())
                put("thermalStatus", thermal)
                put("interruption", lastInterruption)
                put("device", Build.MODEL)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.json")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CamMax")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            contentResolver.openOutputStream(uri)?.use { it.write(j.toString(2).toByteArray()) }
        } catch (e: Exception) { log("public sidecar failed: $e") }
    }

    private fun readoutKey() = "${cfg.cameraId}_${cfg.width}x${cfg.height}_${cfg.fps}_${cfg.highSpeed}"

    private fun measuredFps(): Double =
        if (frames < 2 || lastTs <= firstTs) 0.0
        else (frames - 1) * 1_000_000_000.0 / (lastTs - firstTs)

    private fun segmentBytes(): Long =
        ((cfg.bitrateMbps * 1_000_000L + AUDIO_BPS) / 8 * SEGMENT_SECONDS).coerceAtMost(3_900_000_000L)

    private fun freeBytes(): Long = try {
        StatFs(getExternalFilesDir(null)!!.path).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE / 2 }

    // Room for one more whole clip after the current one finishes.
    private fun hasSpace(): Boolean = freeBytes() > segmentBytes() * 11 / 10 + RESERVE_BYTES

    private fun detectRotation(cb: (Int) -> Unit) {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) { cb(hintFor(270)); return }
        var done = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (done) return
                done = true
                sm.unregisterListener(this)
                val x = e.values[0]
                val y = e.values[1]
                val deg = if (abs(y) >= abs(x)) (if (y >= 0) 0 else 180) else (if (x < 0) 90 else 270)
                cb(hintFor(deg))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL, h)
        h.postDelayed({
            if (!done) { done = true; sm.unregisterListener(listener); cb(hintFor(270)) }
        }, 500)
    }

    private fun hintFor(deviceDeg: Int): Int {
        val sensor = try {
            camMgr.getCameraCharacteristics(cfg.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) { 90 }
        return (sensor + deviceDeg) % 360
    }

    private fun goForeground(): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Recording", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            })
        val n: Notification = Notification.Builder(this, CH)
            .setContentTitle("CamMax")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        true
    } catch (e: Exception) {
        log("startForeground failed: $e")
        false
    }

    private fun watchThermal() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        thermal = pm.currentThermalStatus
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            log("thermal status $status")
        }
        thermalListener = l
        pm.addThermalStatusListener(exec, l)
    }

    private fun unwatchThermal() {
        val l = thermalListener ?: return
        try { (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(l) } catch (_: Exception) {}
        thermalListener = null
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamMax:rec").apply {
                setReferenceCounted(false)
                acquire(12L * 60 * 60 * 1000)
            }
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
    }

    private fun log(msg: String) = EventLog.add(this, msg)

    override fun onDestroy() {
        if (active === this) {
            if (state != State.IDLE) {
                log("service destroyed while $state")
                try { teardown() } catch (_: Exception) {}
                state = State.IDLE
            }
            active = null
        }
        releaseWake()
        camThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val CH = "cammax_silent"
        const val ACTION_START = "com.romio.cammax.START"
        const val ACTION_STOP = "com.romio.cammax.STOP"
        const val EXTRA_AUTO_STOP_MS = "autoStopMs"
        const val AUDIO_BPS = 192_000
        const val SEGMENT_SECONDS = 120L
        const val RESERVE_BYTES = 300L * 1024 * 1024
        const val MIN_START_BYTES = 700L * 1024 * 1024
        @Volatile var state = State.IDLE
        @Volatile var recordingSince = 0L
        @Volatile var liveFps = 0
        @Volatile var liveThermal = 0
        @Volatile var liveSeconds = 0
        @Volatile private var active: RecordingService? = null
    }
}
