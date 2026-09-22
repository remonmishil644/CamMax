package com.romio.cammax

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

// Dark mode: Samsung's own camera records (UHD 60 + Samsung stabilization) under a black overlay.
// On an AMOLED screen black pixels are off, so the display draws almost no power.
object Blackout {
    const val DELAY_MS = 5_000L

    fun canRun(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun start(ctx: Context) {
        EventLog.add(ctx, "dark mode: start")
        ctx.startForegroundService(Intent(ctx, BlackoutService::class.java)
            .setAction(BlackoutService.ACTION_SHOW))
        val cam = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(Intent(cam).setPackage("com.sec.android.app.camera"))
        } catch (_: Exception) {
            try { ctx.startActivity(cam) } catch (e: Exception) { EventLog.add(ctx, "dark mode: no camera app: $e") }
        }
    }
}

class BlackoutActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Blackout.canRun(this)) {
            Blackout.start(this)
        } else {
            EventLog.add(this, "dark mode: overlay permission missing")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
    }
}

class BlackoutService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val showRunnable = Runnable { show() }
    private var taps = 0
    private val resetTaps = Runnable { taps = 0 }
    // Samsung's camera stops when the screen turns off (cover closed, power key), so the overlay goes too.
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            EventLog.add(this@BlackoutService, "dark mode: screen off, exiting")
            hide()
        }
    }
    private var receiverOn = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!receiverOn) {
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverOn = true
        }
        when (intent?.action) {
            ACTION_SHOW -> if (overlay == null) {
                main.removeCallbacks(showRunnable)
                main.postDelayed(showRunnable, Blackout.DELAY_MS)
            }
            else -> hide()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun show() {
        if (!Settings.canDrawOverlays(this) || overlay != null) { if (overlay == null) stopSelf(); return }
        val hint = TextView(this).apply {
            text = "Hold 1 second, or tap 3 times, to exit"
            setTextColor(Color.rgb(110, 110, 110))
            textSize = 15f
            visibility = View.INVISIBLE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val exit = Runnable { Buzz.stopped(this); hide() }
        val hideHint = Runnable { hint.visibility = View.INVISIBLE }
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hint.visibility = View.VISIBLE
                    main.removeCallbacks(hideHint)
                    main.postDelayed(exit, 1_000)
                    taps++
                    main.removeCallbacks(resetTaps)
                    main.postDelayed(resetTaps, 1_200)
                    if (taps >= 3) { taps = 0; exit.run() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(exit)
                    main.postDelayed(hideHint, 1_500)
                }
            }
            true
        }
        // Not focusable: the volume keys still reach Samsung's camera, which uses them to record.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE).apply {
            screenBrightness = 0.01f
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root
            Buzz.stopped(this)
            EventLog.add(this, "dark mode: screen covered")
        } catch (e: Exception) {
            EventLog.add(this, "dark mode: overlay failed: $e")
            stopSelf()
        }
    }

    private fun hide() {
        main.removeCallbacksAndMessages(null)
        if (receiverOn) { try { unregisterReceiver(screenOff) } catch (_: Exception) {}; receiverOn = false }
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        EventLog.add(this, "dark mode: ended")
        stopSelf()
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "Dark mode", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            val exitPi = PendingIntent.getService(this, 1,
                Intent(this, BlackoutService::class.java).setAction(ACTION_HIDE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n: Notification = Notification.Builder(this, CH)
                .setContentTitle("CamMax dark mode")
                .setContentText("Hold the screen 1 s, tap 3 times, or use this button")
                .setSmallIcon(android.R.drawable.presence_invisible)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Exit dark mode", exitPi).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(2, n)
            }
        } catch (e: Exception) {
            EventLog.add(this, "dark mode: startForeground failed: $e")
        }
    }

    override fun onDestroy() {
        if (receiverOn) { try { unregisterReceiver(screenOff) } catch (_: Exception) {}; receiverOn = false }
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        super.onDestroy()
    }

    companion object {
        const val CH = "cammax_dark"
        const val ACTION_SHOW = "com.romio.cammax.DARK_SHOW"
        const val ACTION_HIDE = "com.romio.cammax.DARK_HIDE"
    }
}
