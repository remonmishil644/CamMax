package com.romio.cammax

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import java.io.BufferedWriter
import java.util.Locale

// Writes one Gyroflow .gcsv file per video clip, into Documents/CamMax.
class GyroLogger(private val ctx: Context, private val readoutMs: () -> Float) : SensorEventListener {

    private class Sample(val ts: Long, val gx: Float, val gy: Float, val gz: Float,
                         val ax: Float, val ay: Float, val az: Float)

    private val thread = HandlerThread("cammax-imu").apply { start() }
    private val handler = Handler(thread.looper)
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lock = Any()
    private val ring = ArrayDeque<Sample>()
    private var out: BufferedWriter? = null
    private var t0 = 0L
    private var ax = 0f
    private var ay = 0f
    private var az = 0f

    @Volatile var samples = 0L
        private set
    // Timestamp (sensor clock) of the first sample written into the current clip file.
    @Volatile var clipFirstSampleNs = 0L
        private set
    @Volatile var firstTs = 0L
        private set
    @Volatile var lastTs = 0L
        private set

    fun start(): Boolean {
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return false
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
        }
        return true
    }

    // Every file starts one second before the video. When firstFrameNs is known (camera and IMU share
    // the elapsedRealtime clock), the first video frame sits at exactly t = 1000 ms. Returns t0.
    fun beginClip(videoName: String, firstFrameNs: Long?): Long {
        synchronized(lock) {
            closeLocked()
            clipFirstSampleNs = 0L
            t0 = (firstFrameNs ?: SystemClock.elapsedRealtimeNanos()) - PRE_ROLL_NS
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, videoName.removeSuffix(".mp4") + ".gcsv")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CamMax")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: return t0
                val w = ctx.contentResolver.openOutputStream(uri)?.bufferedWriter() ?: return t0
                w.write("GYROFLOW IMU LOG\n")
                w.write("version,1.3\n")
                w.write("id,cammax\n")
                // Best guess for a back camera. If Gyroflow moves the wrong way, right-click its
                // timeline and pick "Guess IMU orientation here".
                w.write("orientation,YxZ\n")
                w.write(if (firstFrameNs != null) "note,first video frame at t=1000.000 ms on the same clock\n"
                        else "note,video starts near t=1000 ms; exact offset is in the json sidecar\n")
                w.write("vendor,samsung\n")
                w.write("videofilename,$videoName\n")
                val ro = readoutMs()
                if (ro > 0f) {
                    w.write(String.format(Locale.US, "frame_readout_time,%.3f\n", ro))
                    w.write("frame_readout_direction,0\n")
                }
                w.write("tscale,0.001\n")
                w.write("gscale,1.0\n")
                w.write("ascale,0.101971621\n")
                w.write("t,gx,gy,gz,ax,ay,az\n")
                out = w
                ring.filter { it.ts >= t0 }.forEach { writeLocked(it) }
            } catch (e: Exception) {
                EventLog.add(ctx, "gyro file failed: $e")
                out = null
            }
            return t0
        }
    }

    fun endClip() = synchronized(lock) { closeLocked() }

    fun stop() {
        sm.unregisterListener(this)
        endClip()
        thread.quitSafely()
    }

    fun rateHz(): Double =
        if (samples < 2 || lastTs <= firstTs) 0.0 else (samples - 1) * 1e9 / (lastTs - firstTs)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            ax = e.values[0]; ay = e.values[1]; az = e.values[2]
            return
        }
        val s = Sample(e.timestamp, e.values[0], e.values[1], e.values[2], ax, ay, az)
        if (firstTs == 0L) firstTs = s.ts
        lastTs = s.ts
        samples++
        synchronized(lock) {
            ring.addLast(s)
            while (ring.isNotEmpty() && s.ts - ring.first().ts > RING_NS) ring.removeFirst()
            if (out != null) writeLocked(s)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun writeLocked(s: Sample) {
        if (clipFirstSampleNs == 0L) clipFirstSampleNs = s.ts
        try {
            out?.write(String.format(Locale.US, "%.3f,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f\n",
                (s.ts - t0) / 1e6, s.gx, s.gy, s.gz, s.ax, s.ay, s.az))
        } catch (_: Exception) {}
    }

    private fun closeLocked() {
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        out = null
    }

    companion object {
        const val PRE_ROLL_NS = 1_000_000_000L
        const val RING_NS = 2_000_000_000L
    }
}
