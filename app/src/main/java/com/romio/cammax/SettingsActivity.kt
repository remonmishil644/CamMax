package com.romio.cammax

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.romio.cammax.databinding.ActivityMainBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var p: Prefs
    private var cams: List<CamInfo> = emptyList()
    private var modes: List<CamMode> = emptyList()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        p = Prefs(this)
        requestPerms()

        cams = CameraCaps.rearCameras(this)
        b.cameraSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, cams.map { it.label })
        b.cameraSpinner.setSelection(cams.indexOfFirst { it.id == p.cameraId }.coerceAtLeast(0))
        b.cameraSpinner.onItemSelectedListener = onSelect { refreshModes() }

        b.bitrateSeek.progress = p.bitrateMbps
        b.bitrateSeek.setOnSeekBarChangeListener(onSeek { updateLabels() })
        b.isoSeek.progress = p.iso
        b.isoSeek.setOnSeekBarChangeListener(onSeek { updateLabels() })
        b.hevcSwitch.isChecked = p.hevc
        b.stabilizeSwitch.isChecked = p.stabilize
        updateLabels()

        refreshModes()
        b.saveBtn.setOnClickListener { save() }
        b.infoBtn.setOnClickListener {
            if (b.infoText.visibility == View.VISIBLE) {
                b.infoText.visibility = View.GONE
            } else {
                b.infoText.text = CameraCaps.report(this)
                b.infoText.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val recovered = Salvage.run(this)
        val now = if (RecordingService.state == RecordingService.State.RECORDING)
            "Recording now. Changes apply to the next recording.\n" else ""
        val rec = if (recovered > 0)
            "\nKept $recovered interrupted clip(s) as _broken.mp4 in DCIM/CamMax." else ""
        b.statusText.text = now + "Last: " + p.lastStatus + rec
    }

    private fun refreshModes() {
        val cam = cams.getOrNull(b.cameraSpinner.selectedItemPosition) ?: return
        modes = CameraCaps.modes(this, cam.id)
        b.modeSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, modes.map { it.toString() })
        val saved = modes.indexOfFirst {
            it.width == p.width && it.height == p.height && it.fps == p.fps && it.highSpeed == p.highSpeed
        }
        if (saved >= 0) b.modeSpinner.setSelection(saved)
    }

    private fun updateLabels() {
        val mbps = b.bitrateSeek.progress
        val gb = (mbps * 1_000_000.0 + RecordingService.AUDIO_BPS) / 8 *
                RecordingService.SEGMENT_SECONDS / 1_000_000_000.0
        b.bitrateLabel.text = "Bitrate: $mbps Mbps  (about %.1f GB per 2-minute clip)".format(gb)
        val iso = b.isoSeek.progress
        b.isoLabel.text = if (iso == 0) "ISO: auto" else "ISO: $iso (manual)"
    }

    private fun save() {
        val mode = modes.getOrNull(b.modeSpinner.selectedItemPosition) ?: return
        p.cameraId = mode.cameraId
        p.width = mode.width
        p.height = mode.height
        p.fps = mode.fps
        p.highSpeed = mode.highSpeed
        p.bitrateMbps = b.bitrateSeek.progress
        p.iso = b.isoSeek.progress
        p.hevc = b.hevcSwitch.isChecked
        p.stabilize = b.stabilizeSwitch.isChecked
        p.configured = true
        Toast.makeText(this, "Saved. Tap CamMax REC to start or stop.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun requestPerms() {
        val miss = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (miss.isNotEmpty()) ActivityCompat.requestPermissions(this, miss.toTypedArray(), 1)
    }

    private fun onSelect(cb: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(pa: AdapterView<*>?, v: View?, pos: Int, id: Long) = cb()
        override fun onNothingSelected(pa: AdapterView<*>?) {}
    }

    private fun onSeek(cb: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) = cb()
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
