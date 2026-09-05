package com.photographercamera.core.debug

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DebugLog — device-side diagnostics written to the PUBLIC Downloads folder
 * (Downloads/PhotographerCamera_debug.txt), so real-device issues can be
 * diagnosed without adb / USB debugging.
 *
 * Design (per android_camera_performance_optimization_agent.json):
 *  - every pipeline stage is timestamped (T0 shutter -> ... -> storage done);
 *  - camera/lens enumeration, session lifecycle, zoom and errors are logged;
 *  - logging NEVER throws and NEVER blocks the caller thread — lines are
 *    buffered in memory and flushed on a single background IO thread, with a
 *    minimum interval between file rewrites to coalesce log bursts (pinch zoom).
 *
 * The whole file is rewritten from the in-memory ring buffer (max ~1500 lines)
 * so the log never grows unbounded on the device.
 */
object DebugLog {

    private const val FILE_NAME = "PhotographerCamera_debug.txt"
    private const val MAX_LINES = 1500
    private const val MIN_FLUSH_INTERVAL_MS = 400L

    private val lines = ArrayDeque<String>()
    private val lock = Any()

    @Volatile private var appContext: Context? = null
    @Volatile private var fileUri: Uri? = null
    @Volatile private var lastFlush = 0L
    @Volatile private var flushPending = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pc-debug-io").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        log(
            "BOOT",
            "device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                "cores=${Runtime.getRuntime().availableProcessors()}",
        )
    }

    fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            lines.addLast("$ts [$tag] $msg")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        scheduleFlush()
    }

    /**
     * Coalescing flush: one IO task per burst, delayed by the min interval so a
     * log burst writes ONCE — and the TAIL of the burst is guaranteed to land
     * (a plain interval-skip would leave the final lines buffered forever when
     * the app goes idle right after, e.g. the last SAVE line after a shot).
     */
    private fun scheduleFlush() {
        if (flushPending) return
        flushPending = true
        io.execute {
            try { Thread.sleep(MIN_FLUSH_INTERVAL_MS) } catch (_: InterruptedException) { }
            flushPending = false
            flush(force = true)
        }
    }

    fun logError(tag: String, msg: String, t: Throwable) {
        log(tag, "$msg :: ${t.javaClass.simpleName}: ${t.message}")
    }

    /** Force an immediate flush (used by the crash handler so the tail survives). */
    fun flushNow() {
        io.execute { flush(force = true) }
    }

    /**
     * SYNCHRONOUS flush for the crash path: the async [flushNow] may never run
     * because the process dies right after the uncaught handler returns, so the
     * crash handler calls this on the crashing thread to guarantee the log tail
     * (including the crash itself) hits Downloads.
     */
    fun flushSync() {
        flush(force = true)
    }

    private fun flush(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < MIN_FLUSH_INTERVAL_MS) return
        lastFlush = now
        try {
            val text = synchronized(lock) { lines.joinToString("\n") }
            if (text.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                var uri = fileUri ?: queryExisting(resolver)
                if (uri == null) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    }
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    fileUri = uri
                }
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = java.io.File(dir, FILE_NAME)
                f.parentFile?.mkdirs()
                if (f.canWrite() || (!f.exists() && dir.canWrite())) f.writeText(text)
            }
        } catch (_: Throwable) {
            // diagnostics must never take the app down
        }
    }

    private fun queryExisting(resolver: android.content.ContentResolver): Uri? = runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(FILE_NAME),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null }
    }.getOrNull()
}
