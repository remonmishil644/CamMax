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
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    )

    private val camThread = HandlerThread("cammax").apply { start() }
    private val h = Handler(camThread.looper)
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

    private var openGen = 0
    private var rotation = 0
    private var retries = 0
    private var storageFull = false
    private var seq = 0
    private var clips = 0
    private var frames = 0L
    private var firstTs = 0L
    private var lastTs = 0L

    private val retryRunnable = Runnable { if (state == State.RECORDING) openAndRecord() }
    private val watchdog = Runnable { if (state == State.STARTING) finish("Camera did not start") }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (state == State.IDLE) {
                    state = State.STARTING
                    acquireWake()
                    h.post { begin() }
                }
            }
            ACTION_STOP -> {
                if (state == State.RECORDING) {
                    state = State.STOPPING
                    h.post { finish(null) }
                } else if (state == State.IDLE) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        p = Prefs(this)
        cfg = p.snapshot()
        camMgr = getSystemService(CAMERA_SERVICE) as CameraManager
        retries = 0; storageFull = false; clips = 0
        h.postDelayed(watchdog, 10_000)
        detectRotation { rot ->
            rotation = rot
            openAndRecord()
        }
    }

    private fun openAndRecord() {
        if (!hasSpace()) {
            finish("Storage full. Delete some files, then tap CamMax REC again.")
            return
        }
        frames = 0; firstTs = 0; lastTs = 0
        val gen = ++openGen
        try {
            val seg = newSegment()
            current = seg
            recorder = buildRecorder(seg)
            camMgr.openCamera(cfg.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    if (gen != openGen) { dev.close(); return }
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
            finish("Camera permission missing. Open CamMax and allow it.")
        } catch (e: Exception) {
            if (state == State.STARTING) finish("Could not start: ${e.message}")
            else interrupted("restart failed: ${e.message}")
        }
    }

    private fun buildRecorder(seg: Segment): MediaRecorder {
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
        r.setMaxFileSize(segmentBytes())
        r.setOutputFile(seg.pfd.fileDescriptor)
        r.setOnInfoListener { _, what, _ -> h.post { onInfo(what) } }
        r.setOnErrorListener { _, what, extra -> h.post { interrupted("recorder error $what/$extra") } }
        r.prepare()
        return r
    }

    private fun configure(dev: CameraDevice, gen: Int) {
        val rec = recorder ?: return
        val surface = rec.surface
        val ch = camMgr.getCameraCharacteristics(cfg.cameraId)
        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(cfg.fps, cfg.fps))
            // High-speed sessions reject stabilization and manual exposure.
            if (cfg.stabilize && !cfg.highSpeed) {
                val eis = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                if (eis != null && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eis) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
                val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (ois != null && CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in ois) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
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
                val ts = res.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                if (firstTs == 0L) firstTs = ts
                lastTs = ts
                frames++
            }
        }

        val sessionType = if (cfg.highSpeed) SessionConfiguration.SESSION_HIGH_SPEED
                          else SessionConfiguration.SESSION_REGULAR
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
                        finish("Could not record ${cfg.width}x${cfg.height} @ ${cfg.fps} fps: ${e.message}")
                        return
                    }
                    h.removeCallbacks(watchdog)
                    if (state == State.STARTING) {
                        state = State.RECORDING
                        vibrate(600)
                    }
                    p.lastStatus = "Recording ${cfg.width}x${cfg.height} @ ${cfg.fps} fps"
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == openGen) finish("Camera refused ${cfg.width}x${cfg.height} @ ${cfg.fps} fps")
                }
            }))
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
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                if (storageFull) finish("Storage full. Clips saved. Delete some files, then tap again.")
                else interrupted("clip switch missed")
            }
        }
    }

    // Save what exists, then try to keep recording.
    private fun interrupted(reason: String) {
        if (state == State.STARTING) { finish("Could not start: $reason"); return }
        if (state != State.RECORDING) return
        teardown()
        retries++
        if (retries > 5) {
            finish("Stopped after repeated errors ($reason). Clips saved.")
            return
        }
        p.lastStatus = "Interrupted ($reason). Saved and resumed."
        h.postDelayed(retryRunnable, 2_000)
    }

    private fun teardown() {
        openGen++
        h.removeCallbacks(retryRunnable)
        try { session?.stopRepeating() } catch (_: Exception) {}
        val ok = try { recorder?.stop(); true } catch (_: Exception) { false }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        current?.let { closeSegment(it, ok) }
        current = null
        next?.let { discardSegment(it) }
        next = null
    }

    private fun finish(error: String?) {
        h.removeCallbacks(watchdog)
        val fps = measuredFps()
        teardown()
        state = State.IDLE
        val got = if (fps > 0) ", measured %.1f fps".format(fps) else ""
        if (error == null) {
            vibrate(120)
            p.lastStatus = "Saved $clips clip(s) to DCIM/CamMax. " +
                    "${cfg.width}x${cfg.height}, asked ${cfg.fps} fps$got."
        } else {
            vibrateError()
            p.lastStatus = error + got
        }
        releaseWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            return
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (!ok) put(MediaStore.MediaColumns.DISPLAY_NAME, s.name.removeSuffix(".mp4") + "_broken.mp4")
        }
        try { contentResolver.update(s.uri, v, null, null) } catch (_: Exception) {}
        clips++
        writeSidecar(s, if (ok) "complete" else "broken")
    }

    private fun discardSegment(s: Segment) {
        try { s.pfd.close() } catch (_: Exception) {}
        try { contentResolver.delete(s.uri, null, null) } catch (_: Exception) {}
        Sidecars.delete(this, s.name)
    }

    // Repair (next build) needs the exact encoder settings of each clip.
    private fun writeSidecar(s: Segment, status: String) {
        try {
            val j = JSONObject().apply {
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
                put("stabilize", cfg.stabilize)
                put("highSpeed", cfg.highSpeed)
                put("startMs", s.startMs)
                put("endMs", if (status == "recording") 0 else System.currentTimeMillis())
            }
            Sidecars.write(this, s.name, j)
        } catch (_: Exception) {}
    }

    private fun measuredFps(): Double =
        if (frames < 2 || lastTs <= firstTs) 0.0
        else (frames - 1) * 1_000_000_000.0 / (lastTs - firstTs)

    private fun segmentBytes(): Long =
        ((cfg.bitrateMbps * 1_000_000L + AUDIO_BPS) / 8 * SEGMENT_SECONDS).coerceAtMost(3_900_000_000L)

    private fun hasSpace(): Boolean {
        val path = getExternalFilesDir(null)?.path ?: return true
        return StatFs(path).availableBytes > segmentBytes() + 300L * 1024 * 1024
    }

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
        val sensor = camMgr.getCameraCharacteristics(cfg.cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return (sensor + deviceDeg) % 360
    }

    private fun goForeground() {
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
    }

    private fun acquireWake() {
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

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator

    private fun vibrate(ms: Long) =
        vibrator().vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun vibrateError() =
        vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 110, 90, 110, 90), -1))

    override fun onDestroy() {
        if (state != State.IDLE) {
            try { teardown() } catch (_: Exception) {}
            state = State.IDLE
        }
        releaseWake()
        camThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val CH = "cammax_silent"
        const val ACTION_START = "com.romio.cammax.START"
        const val ACTION_STOP = "com.romio.cammax.STOP"
        const val AUDIO_BPS = 192_000
        const val SEGMENT_SECONDS = 120L
        @Volatile var state = State.IDLE
    }
}
