package com.romio.cammax

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.romio.cammax.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var camMgr: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var modes: List<CamMode> = emptyList()
    private var highSpeed: List<CamMode> = emptyList()
    private val camThread = HandlerThread("cam").apply { start() }
    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camMgr = getSystemService(CAMERA_SERVICE) as CameraManager
        ensurePermissions()

        b.cameraSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            CameraCaps.listRearCameras(this))
        b.cameraSpinner.onItemSelectedListener = simple { refreshModes() }
        refreshModes()

        b.startBtn.setOnClickListener { toggleRecord() }
        b.blackOverlay.setOnClickListener { b.blackOverlay.visibility = View.GONE }
        b.hideScreenBtn.setOnClickListener {
            b.blackOverlay.visibility = View.VISIBLE
            window.attributes = window.attributes.also { it.screenBrightness = 0.01f }
        }
    }

    private fun refreshModes() {
        val camId = b.cameraSpinner.selectedItem as? String ?: return
        modes = CameraCaps.enumerate(this, camId)
        highSpeed = CameraCaps.highSpeedModes(this, camId)
        val all = modes.map { it.toString() } + highSpeed.map { "SLOMO ${it}" }
        b.modeSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, all)
    }

    private fun toggleRecord() {
        if (recording) stopRecord() else startRecord()
    }

    private fun startRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) { ensurePermissions(); return }

        val idx = b.modeSpinner.selectedItemPosition
        val isHs = idx >= modes.size
        val mode = if (isHs) highSpeed[idx - modes.size] else modes[idx]
        val fps = mode.maxFps.coerceAtMost(
            mode.fpsRanges.maxOfOrNull { it.upper } ?: 30)
        val bitrate = b.bitrateSeek.progress.coerceAtLeast(10) * 1_000_000

        startService(Intent(this, RecordingService::class.java))

        val outFile = File(
            getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "cammax_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")

        val rec = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(
                if (b.hevcSwitch.isChecked) MediaRecorder.VideoEncoder.HEVC
                else MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(mode.size.width, mode.size.height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrate)
            setOutputFile(outFile.absolutePath)
            prepare()
        }
        recorder = rec

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return
        camMgr.openCamera(mode.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(dev: CameraDevice) {
                cameraDevice = dev
                val surface = rec.surface
                val cfg = OutputConfiguration(surface)
                val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                    val iso = b.isoSeek.progress
                    if (iso > 0) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                }.build()
                val session = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(cfg), exec,
                    object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: android.hardware.camera2.CameraCaptureSession) {
                            sess.setRepeatingRequest(req, null, null)
                            rec.start()
                            runOnUiThread {
                                recording = true
                                b.startBtn.text = "STOP"
                                b.statusText.text = "Recording ${mode.size.width}x${mode.size.height} @${fps}fps"
                            }
                        }
                        override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                            runOnUiThread { b.statusText.text = "Session failed" }
                        }
                    })
                dev.createCaptureSession(session)
            }
            override fun onDisconnected(dev: CameraDevice) { dev.close() }
            override fun onError(dev: CameraDevice, err: Int) { dev.close() }
        }, null)
    }

    private fun stopRecord() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        cameraDevice?.close(); cameraDevice = null
        stopService(Intent(this, RecordingService::class.java))
        recording = false
        b.startBtn.text = "REC"
        b.statusText.text = "Saved to Movies/"
        window.attributes = window.attributes.also { it.screenBrightness = -1f }
        b.blackOverlay.visibility = View.GONE
    }

    private fun ensurePermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val miss = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (miss.isNotEmpty()) ActivityCompat.requestPermissions(this, miss.toTypedArray(), 1)
    }

    private fun simple(cb: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = cb()
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }
}
