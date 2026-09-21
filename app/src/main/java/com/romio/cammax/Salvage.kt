package com.romio.cammax

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

object Sidecars {
    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null), "segments").apply { mkdirs() }

    fun file(ctx: Context, clip: String) = File(dir(ctx), clip.removeSuffix(".mp4") + ".json")

    fun write(ctx: Context, clip: String, j: JSONObject) = file(ctx, clip).writeText(j.toString(2))

    fun setStatus(ctx: Context, clip: String, status: String) {
        val f = file(ctx, clip)
        if (!f.exists()) return
        val j = JSONObject(f.readText())
        j.put("status", status)
        f.writeText(j.toString(2))
    }

    fun delete(ctx: Context, clip: String) {
        file(ctx, clip).delete()
    }
}

// MediaStore deletes IS_PENDING rows after ~7 days, so crash leftovers must be un-pended to survive.
object Salvage {
    fun run(ctx: Context): Int {
        if (RecordingService.state != RecordingService.State.IDLE) return 0
        val cr = ctx.contentResolver
        val coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val sel = "${MediaStore.MediaColumns.IS_PENDING}=1 AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("DCIM/CamMax%")

        val cursor = try {
            if (Build.VERSION.SDK_INT >= 30) {
                val q = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                cr.query(coll, cols, q, null)
            } else {
                @Suppress("DEPRECATION")
                cr.query(MediaStore.setIncludePending(coll), cols, sel, args, null)
            }
        } catch (_: Exception) { null } ?: return 0

        var recovered = 0
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "CamMax.mp4"
                val uri = ContentUris.withAppendedId(coll, id)
                val size = try {
                    cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (_: Exception) { -1L }
                if (size == 0L) {
                    cr.delete(uri, null, null)
                    Sidecars.delete(ctx, name)
                    continue
                }
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name.removeSuffix(".mp4") + "_broken.mp4")
                }
                try {
                    cr.update(uri, v, null, null)
                    Sidecars.setStatus(ctx, name, "broken")
                    recovered++
                } catch (_: Exception) {}
            }
        }
        return recovered
    }
}
