package com.romio.cammax

import android.content.Context

data class Config(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateMbps: Int,
    val iso: Int,
    val hevc: Boolean,
    val stabMode: Int,
    val highSpeed: Boolean,
    val gyro: Boolean
)

object Stab {
    const val AUTO = -1
    const val OFF = 0
    const val OPTICAL = 1
    const val ELECTRONIC = 2
    const val BOTH = 3
    const val ENHANCED = 4

    fun label(mode: Int) = when (mode) {
        OPTICAL -> "Optical"
        ELECTRONIC -> "Electronic"
        BOTH -> "Optical + electronic"
        ENHANCED -> "Enhanced"
        else -> "Off"
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cammax", Context.MODE_PRIVATE)

    var configured: Boolean
        get() = sp.getBoolean("configured", false)
        set(v) = sp.edit().putBoolean("configured", v).apply()

    var cameraId: String
        get() = sp.getString("cameraId", "0") ?: "0"
        set(v) = sp.edit().putString("cameraId", v).apply()

    var width: Int
        get() = sp.getInt("width", 3840)
        set(v) = sp.edit().putInt("width", v).apply()

    var height: Int
        get() = sp.getInt("height", 2160)
        set(v) = sp.edit().putInt("height", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) = sp.edit().putInt("fps", v).apply()

    var bitrateMbps: Int
        get() = sp.getInt("bitrate", 100)
        set(v) = sp.edit().putInt("bitrate", v).apply()

    var iso: Int
        get() = sp.getInt("iso", 0)
        set(v) = sp.edit().putInt("iso", v).apply()

    var hevc: Boolean
        get() = sp.getBoolean("hevc", true)
        set(v) = sp.edit().putBoolean("hevc", v).apply()

    var stabilize: Boolean
        get() = sp.getBoolean("stabilize", true)
        set(v) = sp.edit().putBoolean("stabilize", v).apply()

    // Older builds stored an on/off switch: off stays off, on becomes "pick the best mode".
    var stabMode: Int
        get() = sp.getInt("stabMode", if (stabilize) Stab.AUTO else Stab.OFF)
        set(v) = sp.edit().putInt("stabMode", v).apply()

    var gyro: Boolean
        get() = sp.getBoolean("gyro", true)
        set(v) = sp.edit().putBoolean("gyro", v).apply()

    var highSpeed: Boolean
        get() = sp.getBoolean("highSpeed", false)
        set(v) = sp.edit().putBoolean("highSpeed", v).apply()

    // The chip choice. `fps` is what the recorder uses: customFps when set, else chipFps.
    var chipFps: Int
        get() = sp.getInt("chipFps", if (customFps == 0) fps else 60)
        set(v) = sp.edit().putInt("chipFps", v).apply()

    var customFps: Int
        get() = sp.getInt("customFps", 0)
        set(v) = sp.edit().putInt("customFps", v).apply()

    var lastStatus: String
        get() = sp.getString("lastStatus", "No recordings yet") ?: ""
        set(v) = sp.edit().putString("lastStatus", v).apply()

    // Build 6: the 80 fps test proved 60 is the ceiling, so drop the leftover custom value once.
    fun migrate() {
        if (sp.getBoolean("m6", false)) return
        if (customFps > 60 && !highSpeed) {
            customFps = 0
            fps = 60
            chipFps = 60
        }
        sp.edit().putBoolean("m6", true).apply()
    }

    fun snapshot() = Config(cameraId, width, height, fps, bitrateMbps, iso, hevc, stabMode, highSpeed, gyro)
}
