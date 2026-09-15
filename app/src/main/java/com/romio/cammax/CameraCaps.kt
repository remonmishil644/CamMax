package com.romio.cammax

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Range
import android.util.Size

data class CamMode(
    val cameraId: String,
    val size: Size,
    val fpsRanges: List<Range<Int>>,
    val minFrameDurationNs: Long
) {
    val maxFps: Int get() = (1_000_000_000L / minFrameDurationNs.coerceAtLeast(1)).toInt()
    override fun toString(): String =
        "cam$cameraId  ${size.width}x${size.height}  up to ${maxFps}fps"
}

object CameraCaps {
    fun listRearCameras(ctx: Context): List<String> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return mgr.cameraIdList.filter {
            mgr.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    fun enumerate(ctx: Context, cameraId: String): List<CamMode> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = mgr.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList() ?: emptyList()

        val sizes: Array<Size> = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        return sizes.map { size ->
            val minDur = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            CamMode(cameraId, size, fpsRanges, if (minDur > 0) minDur else 33_333_333L)
        }.sortedByDescending { it.size.width.toLong() * it.size.height }
    }

    fun highSpeedModes(ctx: Context, cameraId: String): List<CamMode> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = mgr.getCameraCharacteristics(cameraId)
        val map: StreamConfigurationMap =
            ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes = map.highSpeedVideoSizes ?: return emptyList()
        return sizes.map { size ->
            val fps = map.getHighSpeedVideoFpsRangesFor(size).toList()
            CamMode(cameraId, size, fps, 1_000_000_000L / (fps.maxOfOrNull { it.upper } ?: 30))
        }
    }
}
