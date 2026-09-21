package com.romio.cammax

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.romio.cammax.databinding.ActivityMainBinding
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var p: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var lenses: List<Lens> = emptyList()
    private var building = false
    private var askedPerms = false

    private val ticker = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        p = Prefs(this)
        lenses = CameraCaps.lenses(this)

        b.bitrateSlider.value = snap(p.bitrateMbps, 10, 300, 5)
        b.isoSlider.value = snap(p.iso, 0, 3200, 50)
        b.hevcSwitch.isChecked = p.hevc
        b.gyroSwitch.isChecked = p.gyro
        if (p.customFps > 0) b.customFps.setText(p.customFps.toString())

        buildLensChips()

        b.lensChips.setOnCheckedStateChangeListener { g, _ ->
            if (building) return@setOnCheckedStateChangeListener
            p.cameraId = (checkedTag(g) as? Lens)?.id ?: return@setOnCheckedStateChangeListener
            buildStabChips()
            buildSizeChips()
            changed()
        }
        b.sizeChips.setOnCheckedStateChangeListener { g, _ ->
            if (building) return@setOnCheckedStateChangeListener
            val size = checkedTag(g) as? Size ?: return@setOnCheckedStateChangeListener
            p.width = size.width
            p.height = size.height
            buildFpsChips()
            changed()
        }
        b.fpsChips.setOnCheckedStateChangeListener { g, _ ->
            if (building) return@setOnCheckedStateChangeListener
            val o = checkedTag(g) as? FpsOption ?: return@setOnCheckedStateChangeListener
            p.chipFps = o.fps
            p.highSpeed = o.highSpeed
            changed()
        }
        b.bitrateSlider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { p.bitrateMbps = v.roundToInt(); changed() }
        }
        b.isoSlider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { p.iso = v.roundToInt(); changed() }
        }
        b.stabChips.setOnCheckedStateChangeListener { g, _ ->
            if (building) return@setOnCheckedStateChangeListener
            p.stabMode = checkedTag(g) as? Int ?: return@setOnCheckedStateChangeListener
            changed()
        }
        b.hevcSwitch.setOnCheckedChangeListener { _, on -> p.hevc = on; changed() }
        b.gyroSwitch.setOnCheckedChangeListener { _, on -> p.gyro = on; changed() }
        b.customFps.doAfterTextChanged {
            p.customFps = it?.toString()?.toIntOrNull()?.takeIf { v -> v in 1..960 } ?: 0
            changed()
        }

        b.gyroPresetBtn.setOnClickListener {
            p.stabMode = Stab.OFF
            p.chipFps = 60
            p.highSpeed = false
            p.gyro = true
            b.gyroSwitch.isChecked = true
            b.customFps.setText("")
            buildLensChips()
            changed()
            Toast.makeText(this, "Gyroflow preset applied", Toast.LENGTH_SHORT).show()
        }
        b.darkBtn.setOnClickListener {
            if (Blackout.canRun(this)) {
                Blackout.start(this)
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
        }
        b.testBtn.setOnClickListener { runTest() }
        b.advancedBtn.setOnClickListener {
            val show = b.advancedBox.visibility != View.VISIBLE
            b.advancedBox.visibility = if (show) View.VISIBLE else View.GONE
            b.advancedBtn.text = if (show) "Hide advanced" else "Show advanced"
        }
        b.logBtn.setOnClickListener { showMono(EventLog.tail(this).ifEmpty { "No events yet." }) }
        b.infoBtn.setOnClickListener { showMono(CameraCaps.report(this)) }
        b.copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CamMax", b.monoText.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        changed()
    }

    override fun onResume() {
        super.onResume()
        try { Salvage.run(this) } catch (_: Exception) {}
        if (missingPerms().isNotEmpty() && !askedPerms) {
            askedPerms = true
            ActivityCompat.requestPermissions(this, missingPerms().toTypedArray(), 1)
        }
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ---- chips ----

    private fun buildLensChips() {
        building = true
        b.lensChips.removeAllViews()
        val selected = lenses.firstOrNull { it.id == p.cameraId } ?: lenses.firstOrNull()
        lenses.forEach { l ->
            val label = if (l.detail.isEmpty()) l.name else "${l.name}  ${l.detail}"
            addChip(b.lensChips, label, l, l == selected)
        }
        selected?.let { p.cameraId = it.id }
        building = false
        buildStabChips()
        buildSizeChips()
    }

    private fun buildStabChips() {
        building = true
        b.stabChips.removeAllViews()
        val options = CameraCaps.stabOptions(this, p.cameraId)
        val selected = if (p.stabMode in options) p.stabMode else CameraCaps.bestStab(options, p.width)
        options.forEach { m -> addChip(b.stabChips, Stab.label(m), m, m == selected) }
        p.stabMode = selected
        building = false
    }

    private fun buildSizeChips() {
        building = true
        b.sizeChips.removeAllViews()
        val sizes = CameraCaps.sizes(this, p.cameraId)
        val selected = sizes.firstOrNull { it.width == p.width && it.height == p.height }
            ?: sizes.firstOrNull()
        sizes.forEach { s ->
            addChip(b.sizeChips, CameraCaps.sizeLabel(s.width, s.height), s, s == selected)
        }
        selected?.let { p.width = it.width; p.height = it.height }
        building = false
        buildFpsChips()
    }

    private fun buildFpsChips() {
        building = true
        b.fpsChips.removeAllViews()
        val opts = CameraCaps.fpsOptions(this, p.cameraId, Size(p.width, p.height))
        val selected = opts.firstOrNull { it.fps == p.chipFps && it.highSpeed == p.highSpeed }
            ?: opts.firstOrNull { it.fps == 30 } ?: opts.firstOrNull()
        opts.forEach { o -> addChip(b.fpsChips, o.fps.toString(), o, o == selected) }
        selected?.let { p.chipFps = it.fps; p.highSpeed = it.highSpeed }
        building = false
    }

    private fun addChip(group: ChipGroup, label: String, tag: Any, checked: Boolean) {
        val chip = layoutInflater.inflate(R.layout.chip_filter, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.tag = tag
        group.addView(chip)
        if (checked) group.check(chip.id)
    }

    private fun checkedTag(g: ChipGroup): Any? =
        g.findViewById<Chip>(g.checkedChipId)?.tag

    // ---- state ----

    private fun changed() {
        p.fps = if (p.customFps > 0) p.customFps else p.chipFps
        p.configured = true
        renderSummary()
    }

    @SuppressLint("SetTextI18n")
    private fun renderSummary() {
        val lens = lenses.firstOrNull { it.id == p.cameraId }?.name ?: "Camera ${p.cameraId}"
        b.summaryBig.text = "${CameraCaps.sizeLabel(p.width, p.height)} · ${p.fps} fps"
        b.summarySub.text = listOf(
            lens,
            if (p.hevc) "HEVC" else "H.264",
            "${p.bitrateMbps} Mbps",
            if (p.stabMode != Stab.OFF) "${Stab.label(p.stabMode).lowercase()} stabilization" else "no stabilization"
        ).joinToString("  ·  ")

        val bytesPerSec = (p.bitrateMbps * 1_000_000.0 + RecordingService.AUDIO_BPS) / 8
        val gbPerHour = bytesPerSec * 3600 / 1e9
        b.bitrateLabel.text = "Bitrate: ${p.bitrateMbps} Mbps  ·  %.0f GB per hour".format(gbPerHour)
        b.isoLabel.text = if (p.iso == 0) "ISO: automatic" else "ISO: ${p.iso} (manual, shutter 1/${p.fps * 2})"

        val free = try { StatFs(getExternalFilesDir(null)!!.path).availableBytes } catch (_: Exception) { 0L }
        val minutes = (free / bytesPerSec / 60).toInt()
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        b.storageText.text = "%.0f GB free  ·  room for about %s".format(free / 1e9, time)

        b.stabNote.text = when {
            p.stabMode == Stab.ENHANCED -> "Strongest mode. Crops up to 20%. Android guarantees it only up to 1440p. At 4K the camera may refuse it, and CamMax then falls back to Optical."
            p.stabMode == Stab.ELECTRONIC -> "Software stabilization with a small crop. Android recommends this over mixing both."
            p.stabMode == Stab.BOTH -> "Lens and software together. Android warns they can fight each other and cause wobble."
            p.stabMode == Stab.OPTICAL -> "Lens stabilization only. No crop. Good for standing still, weak for walking." +
                    (if (p.gyro) "\nGyroflow cannot use clips recorded with lens stabilization. For Gyroflow, pick Off." else "")
            else -> "No stabilization. Use on a tripod, or stabilize later in Gyroflow."
        } + if (p.gyro && p.stabMode in listOf(Stab.ELECTRONIC, Stab.BOTH, Stab.ENHANCED))
            "\nGyroflow needs stabilization Off." else ""

        val opts = CameraCaps.fpsOptions(this, p.cameraId, Size(p.width, p.height))
        val chosen = opts.firstOrNull { it.fps == p.chipFps && it.highSpeed == p.highSpeed }
        b.fpsNote.text = when {
            p.customFps > 0 -> "Custom frame rate ${p.customFps} is on (see Advanced). The chips are ignored."
            chosen?.highSpeed == true -> "High-speed mode. Stabilization and manual ISO are off in this mode."
            chosen?.listed == false -> "Samsung does not list ${chosen.fps} fps for other apps. CamMax asks for it anyway. Use the test button to confirm."
            else -> ""
        }
        b.fpsNote.visibility = if (b.fpsNote.text.isEmpty()) View.GONE else View.VISIBLE
    }

    @SuppressLint("SetTextI18n", "BatteryLife")
    private fun renderStatus() {
        val st = RecordingService.state
        val (label, color) = when (st) {
            RecordingService.State.IDLE -> "Ready" to R.color.green
            RecordingService.State.STARTING -> "Starting" to R.color.amber
            RecordingService.State.RECORDING -> "Recording" to R.color.red
            RecordingService.State.STOPPING -> "Saving" to R.color.amber
        }
        b.statePill.text = label
        b.darkBtn.text = if (Blackout.canRun(this)) "Start dark mode now" else "Allow display over other apps"
        TextViewCompat.setCompoundDrawableTintList(b.statePill,
            ColorStateList.valueOf(ContextCompat.getColor(this, color)))
        b.lastText.text = "Last: ${p.lastStatus}"

        val idle = st == RecordingService.State.IDLE
        b.testBtn.isEnabled = idle && missingPerms().isEmpty()
        b.testBtn.text = if (idle) "Test these settings (10 s)" else "$label…"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val free = try { StatFs(getExternalFilesDir(null)!!.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        val perMin = (p.bitrateMbps * 1_000_000.0 + RecordingService.AUDIO_BPS) / 8 * 60
        when {
            missingPerms().isNotEmpty() -> warn(
                "CamMax needs the camera and the microphone. Nothing records until you allow both.",
                "Allow") {
                if (missingPerms().any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) } || !askedPerms) {
                    askedPerms = true
                    ActivityCompat.requestPermissions(this, missingPerms().toTypedArray(), 1)
                } else {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
            }
            free < RecordingService.MIN_START_BYTES -> warn(
                "Phone storage is full: %.1f GB free. CamMax cannot record. Delete files, starting with old clips in DCIM/CamMax.".format(free / 1e9),
                "Open storage settings") { openStorage() }
            free / perMin < 10 -> warn(
                "Storage is almost full: %.1f GB free, about %d minutes at this quality.".format(free / 1e9, (free / perMin).toInt()),
                "Open storage settings") { openStorage() }
            !pm.isIgnoringBatteryOptimizations(packageName) -> warn(
                "Samsung battery saving can stop a long recording. Let CamMax run without limits.",
                "Allow background running") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            else -> b.warnCard.visibility = View.GONE
        }
    }

    private fun openStorage() {
        try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun warn(text: String, action: String, onClick: () -> Unit) {
        b.warnCard.visibility = View.VISIBLE
        b.warnText.text = text
        b.warnBtn.text = action
        b.warnBtn.setOnClickListener { onClick() }
    }

    private fun runTest() {
        if (RecordingService.state != RecordingService.State.IDLE) return
        EventLog.add(this, "test button")
        try {
            startForegroundService(Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START)
                .putExtra(RecordingService.EXTRA_AUTO_STOP_MS, 10_000L))
        } catch (e: Exception) {
            EventLog.add(this, "test start failed: $e")
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMono(text: String) {
        b.monoText.text = text
        b.monoText.visibility = View.VISIBLE
        b.copyBtn.visibility = View.VISIBLE
    }

    private fun missingPerms() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun snap(v: Int, min: Int, max: Int, step: Int): Float =
        (((v - min).toFloat() / step).roundToInt() * step + min).coerceIn(min, max).toFloat()
}
