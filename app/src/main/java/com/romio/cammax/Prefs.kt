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
    val stabilize: Boolean,
    val highSpeed: Boolean
)

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

    var highSpeed: Boolean
        get() = sp.getBoolean("highSpeed", false)
        set(v) = sp.edit().putBoolean("highSpeed", v).apply()

    var customFps: Int
        get() = sp.getInt("customFps", 0)
        set(v) = sp.edit().putInt("customFps", v).apply()

    var lastStatus: String
        get() = sp.getString("lastStatus", "No recordings yet") ?: ""
        set(v) = sp.edit().putString("lastStatus", v).apply()

    fun snapshot() = Config(cameraId, width, height, fps, bitrateMbps, iso, hevc, stabilize, highSpeed)
}
