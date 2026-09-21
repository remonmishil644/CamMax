package com.romio.cammax

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

class ToggleActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val p = Prefs(this)

        if (!p.configured || missingPerms()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        when (RecordingService.state) {
            RecordingService.State.IDLE -> {
                Salvage.run(this)
                startForegroundService(
                    Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
            }
            RecordingService.State.RECORDING -> startService(
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            else -> {}
        }
        finish()
    }

    private fun missingPerms() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
}
