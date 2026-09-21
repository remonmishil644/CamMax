package com.romio.cammax

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat

class ToggleActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private var t0 = 0L

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val st = RecordingService.state
        EventLog.add(this, "tap: state=$st")

        if (!Prefs(this).configured || missingPerms()) {
            EventLog.add(this, "tap: not configured or permission missing, opening settings")
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        when (st) {
            RecordingService.State.IDLE -> startRecording()
            RecordingService.State.RECORDING -> {
                val age = SystemClock.elapsedRealtime() - RecordingService.recordingSince
                if (age < 1500) {
                    EventLog.add(this, "tap ignored: double tap ${age} ms after start")
                } else {
                    startService(Intent(this, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_STOP))
                }
                finish()
            }
            RecordingService.State.STARTING -> finish()
            RecordingService.State.STOPPING -> {
                t0 = SystemClock.elapsedRealtime()
                waitIdleThenStart()
            }
        }
    }

    private fun waitIdleThenStart() {
        if (RecordingService.state == RecordingService.State.IDLE) { startRecording(); return }
        if (SystemClock.elapsedRealtime() - t0 > 6000) {
            EventLog.add(this, "tap dropped: previous clip still saving after 6 s")
            Buzz.error(this)
            finish()
            return
        }
        ui.postDelayed({ waitIdleThenStart() }, 100)
    }

    private fun startRecording() {
        try {
            startForegroundService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START))
        } catch (e: Exception) {
            EventLog.add(this, "startForegroundService failed: $e")
            Prefs(this).lastStatus = "Could not start: ${e.message}"
            Buzz.error(this)
            finish()
            return
        }
        t0 = SystemClock.elapsedRealtime()
        waitStarted()
    }

    // Android 14 needs the app in front while the camera service comes up.
    private fun waitStarted() {
        val dt = SystemClock.elapsedRealtime() - t0
        val st = RecordingService.state
        val done = st == RecordingService.State.RECORDING ||
                (st == RecordingService.State.IDLE && dt > 1500) || dt > 5000
        if (done) { finish(); return }
        ui.postDelayed({ waitStarted() }, 100)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun missingPerms() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
}
