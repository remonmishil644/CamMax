package com.romio.cammax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Lens(val id: String, val name: String, val detail: String)

data class FpsOption(val fps: Int, val highSpeed: Boolean, val listed: Boolean)

object CameraCaps {

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun lenses(ctx: Context): List<Lens> {
        val m = mgr(ctx)
        val raw = m.cameraIdList.mapNotNull { id ->
            val ch = m.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val equiv = if (f != null && s != null) {
                (f * 43.27 / sqrt((s.width * s.width + s.height * s.height).toDouble())).roundToInt()
            } else 0
            id to equiv
        }
        return raw.map { (id, equiv) ->
            val name = when {
                equiv == 0 -> "Camera $id"
                equiv < 20 -> "Ultrawide"
                equiv <= 35 -> "Main"
                else -> "Tele"
            }
            Lens(id, name, if (equiv > 0) "$equiv mm" else "")
        }
    }

    fun sizes(ctx: Context, cameraId: String): List<Size> {
        val map = mgr(ctx).getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val normal = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        return normal
            .filter { it.width * 9 == it.height * 16 && it.width in STANDARD_WIDTHS }
            .distinct()
            .sortedByDescending { it.width }
    }

    fun sizeLabel(w: Int, h: Int) = when (w) {
        7680 -> "8K"
        3840 -> "4K"
        2560 -> "1440p"
        1920 -> "1080p"
        1280 -> "720p"
        else -> "${w}x$h"
    }

    // Samsung lists only 24 and 30 fps, but it delivers 60 when asked. So 60 is always offered.
    fun fpsOptions(ctx: Context, cameraId: String, size: Size): List<FpsOption> {
        val ch = mgr(ctx).getCameraCharacteristics(cameraId)
        val listed = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.flatMap { listOf(it.lower, it.upper) }
            ?.filter { it >= 24 }?.distinct() ?: emptyList()
        val normal = (listed + 60).distinct().map { FpsOption(it, false, it in listed) }
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val high = try {
            if (map != null && map.highSpeedVideoSizes.any { it == size }) {
                map.getHighSpeedVideoFpsRangesFor(size)
                    .filter { it.lower == it.upper }.map { it.upper }.distinct()
                    .filter { hs -> normal.none { it.fps == hs } }
                    .map { FpsOption(it, true, true) }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
        return (normal + high).sortedBy { it.fps }
    }

    fun report(ctx: Context): String {
        val m = mgr(ctx)
        val sb = StringBuilder()
        val ids = m.cameraIdList.toMutableList()
        m.cameraIdList.forEach { id ->
            m.getCameraCharacteristics(id).physicalCameraIds.forEach { if (it !in ids) ids.add(it) }
        }
        for (id in ids) {
            val ch = try { m.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                else -> "other"
            }
            val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "other"
            }
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString { "%.1f".format(it) }
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
            val ae = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString { "[${it.lower},${it.upper}]" }
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val hs = map?.highSpeedVideoSizes?.joinToString("; ") { s ->
                "${s.width}x${s.height}: " +
                        map.getHighSpeedVideoFpsRangesFor(s).joinToString { "[${it.lower},${it.upper}]" }
            }
            val uhd = map?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width >= 3840 }
                ?.joinToString { s ->
                    val d = map.getOutputMinFrameDuration(MediaRecorder::class.java, s)
                    "${s.width}x${s.height} max ${if (d > 0) 1_000_000_000L / d else 0}fps"
                }
            sb.append("Camera $id ($facing, $level, ${focal}mm)\n")
            sb.append("  AE fps: $ae\n")
            sb.append("  High-speed: ${if (hs.isNullOrEmpty()) "none" else hs}\n")
            sb.append("  4K+ sizes: ${uhd ?: "none"}\n")
            sb.append("  Capabilities: $caps\n\n")
        }
        return sb.toString().trim()
    }

    private val STANDARD_WIDTHS = setOf(7680, 3840, 2560, 1920, 1280)
}
