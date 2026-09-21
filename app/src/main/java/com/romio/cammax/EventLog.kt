package com.romio.cammax

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
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

    fun error(ctx: Context) =
        play(ctx, VibrationEffect.createWaveform(longArrayOf(0, 90, 110, 90, 110, 90), -1))

    private fun once(ctx: Context, ms: Long) =
        play(ctx, VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))

    // Alarm usage: battery saver and Do Not Disturb mute ordinary vibrations, and these are the only feedback.
    private fun play(ctx: Context, effect: VibrationEffect) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator(ctx).vibrate(effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator(ctx).vibrate(effect, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: Exception) {
            EventLog.add(ctx, "vibrate failed: $e")
        }
    }
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
