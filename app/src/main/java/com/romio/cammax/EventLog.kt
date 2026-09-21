package com.romio.cammax

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventLog {
    private const val MAX_BYTES = 96 * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, "events.log")
            if (f.length() > MAX_BYTES) {
                f.writeText(f.readText().takeLast(MAX_BYTES / 2).substringAfter('\n'))
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun tail(ctx: Context, lines: Int = 150): String = try {
        File(ctx.filesDir, "events.log").readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) { "" }
}

object Buzz {
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun started(ctx: Context) = once(ctx, 600)
    fun stopped(ctx: Context) = once(ctx, 120)

    fun error(ctx: Context) = try {
        vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 110, 90, 110, 90), -1))
    } catch (_: Exception) {}

    private fun once(ctx: Context, ms: Long) = try {
        vibrator(ctx).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {}
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            EventLog.add(this, "CRASH on ${t.name}: " +
                    Log.getStackTraceString(e).lines().take(14).joinToString(" | "))
            prev?.uncaughtException(t, e)
        }
        Prefs(this).migrate()
        EventLog.add(this, "process start")
    }
}
