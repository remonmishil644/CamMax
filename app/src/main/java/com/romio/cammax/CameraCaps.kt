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
    val guaranteed: Boolean
) {
    override fun toString() =
        "${width}x$height @ ${fps} fps" + if (guaranteed) "" else "   (not guaranteed)"
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

    // Samsung reports a conservative minFrameDuration, so fps comes from the AE ranges, not from it.
    fun modes(ctx: Context, cameraId: String): List<CamMode> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = mgr.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
            ?: emptyList()
        val fixed = ranges.filter { it.lower == it.upper }.map { it.upper }
        val fpsList = fixed.ifEmpty { ranges.map { it.upper } }
            .filter { it >= 24 }.distinct().sortedDescending()
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width >= 1280 && it.width * 9 == it.height * 16 }
            ?.sortedByDescending { it.width }
            ?: emptyList()
        return sizes.flatMap { s ->
            val minDur = map.getOutputMinFrameDuration(MediaRecorder::class.java, s)
            val safeFps = if (minDur > 0) (1_000_000_000L / minDur).toInt() else 30
            fpsList.map { fps -> CamMode(cameraId, s.width, s.height, fps, fps <= safeFps + 1) }
        }
    }
}
