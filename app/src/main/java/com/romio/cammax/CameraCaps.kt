package com.romio.cammax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder

data class CamInfo(val id: String, val label: String)

data class CamMode(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val guaranteed: Boolean,
    val highSpeed: Boolean = false,
    val probe: Boolean = false
) {
    override fun toString() = "${width}x$height @ ${fps} fps" + when {
        highSpeed -> "   (high-speed)"
        probe -> "   (test: not listed)"
        !guaranteed -> "   (not guaranteed)"
        else -> ""
    }
}

object CameraCaps {
    fun rearCameras(ctx: Context): List<CamInfo> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.cameraIdList.mapNotNull { id ->
            val ch = mgr.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                return@mapNotNull null
            }
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            CamInfo(id, if (f != null) "Camera $id  (%.1f mm)".format(f) else "Camera $id")
        }
    }

    fun modes(ctx: Context, cameraId: String): List<CamMode> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = mgr.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
            ?: emptyList()
        val fpsList = ranges.flatMap { listOf(it.lower, it.upper) }
            .filter { it >= 24 }.distinct().sortedDescending()
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width >= 1280 && it.width * 9 == it.height * 16 }
            ?.sortedByDescending { it.width }
            ?: emptyList()

        val normal = sizes.flatMap { s ->
            val minDur = map.getOutputMinFrameDuration(MediaRecorder::class.java, s)
            val safeFps = if (minDur > 0) (1_000_000_000L / minDur).toInt() else 30
            val listed = fpsList.map { fps ->
                CamMode(cameraId, s.width, s.height, fps, fps <= safeFps + 1)
            }
            val probe = if ((s.width == 3840 || s.width == 1920) && 60 !in fpsList)
                listOf(CamMode(cameraId, s.width, s.height, 60, false, probe = true))
            else emptyList()
            probe + listed
        }

        val high = (map.highSpeedVideoSizes ?: emptyArray()).flatMap { s ->
            map.getHighSpeedVideoFpsRangesFor(s)
                .filter { it.lower == it.upper }
                .map { it.upper }.distinct().sortedDescending()
                .map { fps -> CamMode(cameraId, s.width, s.height, fps, true, highSpeed = true) }
        }.sortedWith(compareByDescending<CamMode> { it.width }.thenByDescending { it.fps })

        return normal + high
    }

    fun report(ctx: Context): String {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        val ids = mgr.cameraIdList.toMutableList()
        mgr.cameraIdList.forEach { id ->
            mgr.getCameraCharacteristics(id).physicalCameraIds.forEach { if (it !in ids) ids.add(it) }
        }
        for (id in ids) {
            val ch = try { mgr.getCameraCharacteristics(id) } catch (_: Exception) { continue }
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
}
