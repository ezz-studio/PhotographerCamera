package com.photographercamera.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves captured frames into the SYSTEM gallery via MediaStore
 * (Pictures/PhotographerCamera), so photos are visible to other gallery apps.
 *
 * Permissions:
 *  - API 29+  : scoped storage — NO runtime storage permission needed.
 *  - API 26–28: WRITE_EXTERNAL_STORAGE (and READ for re-listing) requested at
 *    runtime by CameraScreen.
 */
object CaptureSaver {

    private const val DIR_NAME = "PhotographerCamera"
    private const val REL_PATH = "Pictures/$DIR_NAME"

    /** One saved capture, addressable by its MediaStore URI. */
    data class SavedPhoto(val uri: Uri, val name: String, val addedAt: Long)

    private fun newName(): String =
        "PC_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"

    /** Save a bitmap into the system gallery. Returns null (with a log) on failure;
     *  any MediaStore row created along the way is DELETED, so a failed save never
     *  leaves a 0-byte ghost entry (those showed up as thumbless, unopenable photos).
     *  0.6.0: [gps] 非空时写入 EXIF GPS（"保存地址位置"开关，默认关；权限未授予
     *  时上层取不到坐标，直接跳过）。 */
    fun save(context: Context, bmp: Bitmap, gps: Pair<Double, Double>? = null): SavedPhoto? {
        val t0 = android.os.SystemClock.elapsedRealtime()
        var pendingUri: Uri? = null
        return runCatching {
        val resolver = context.contentResolver
        val name = newName()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, REL_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val pubDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES,
                )
                val dir = java.io.File(pubDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }
                put(MediaStore.Images.Media.DATA, java.io.File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        pendingUri = uri
        var written = false
        resolver.openOutputStream(uri)?.use { os ->
            // 0.3.5: 95 -> 100。"禁止 JPEG 路线"语境下的编码是链路终点，
            // 压缩伪影不应再叠加在 12.6MP YUV 直采成片上（95 档 4:2:0 采样
            // 与二次量化在高倍放大下可见；100 档仅保留基线熵编码）。
            written = bmp.compress(Bitmap.CompressFormat.JPEG, 100, os)
            os.flush()
        } ?: return@runCatching null
        if (!written) error("compress failed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        pendingUri = null // committed — the row now owns a valid image
        if (gps != null) {
            writeGpsExif(context, uri, gps.first, gps.second)
        }
        Log.i("CaptureSaver", "saved $uri")
        com.photographercamera.core.debug.DebugLog.log(
            "SAVE",
            "$name ${bmp.width}x${bmp.height} in ${android.os.SystemClock.elapsedRealtime() - t0}ms -> $uri",
        )
        SavedPhoto(uri, name, System.currentTimeMillis() / 1000)
        }.onFailure {
            Log.e("CaptureSaver", "save failed", it)
            com.photographercamera.core.debug.DebugLog.logError("SAVE", "save failed", it)
            // remove the half-written / 0-byte row so it can't appear as a broken photo
            pendingUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }.getOrNull()
    }

    /** EXIF GPS 写入（DMS 度分秒格式）。失败只记日志，不回滚保存。 */
    private fun writeGpsExif(context: Context, uri: Uri, lat: Double, lon: Double) {
        runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return
            pfd.use {
                val exif = androidx.exifinterface.media.ExifInterface(it.fileDescriptor)
                exif.setLatLong(lat, lon)
                exif.saveAttributes()
            }
            Log.i("CaptureSaver", "gps exif written $lat,$lon")
        }.onFailure {
            Log.e("CaptureSaver", "gps exif failed", it)
            com.photographercamera.core.debug.DebugLog.logError("SAVE", "gps exif failed", it)
        }
    }

    /** List this app's captures (newest first) from MediaStore. */
    fun list(context: Context): List<SavedPhoto> = runCatching {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$REL_PATH%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/$DIR_NAME/%")
        }
        val out = mutableListOf<SavedPhoto>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            while (c.moveToNext()) {
                // skip 0-byte ghosts (crashed/interrupted saves from old versions)
                if (c.getLong(sizeCol) <= 0) continue
                out += SavedPhoto(
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)),
                    name = c.getString(nameCol),
                    addedAt = c.getLong(dateCol),
                )
            }
        }
        out
    }.onFailure { Log.e("CaptureSaver", "list failed", it) }.getOrDefault(emptyList())
}
