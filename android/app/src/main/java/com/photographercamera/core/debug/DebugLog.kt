package com.photographercamera.core.debug

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DebugLog — device-side diagnostics written to the APP-PRIVATE directory
 * (filesDir/logs/PhotographerCamera_debug.txt) so real-device issues can be
 * diagnosed without adb / USB debugging.
 *
 * 1.0.0 privacy rework (user directive):
 *  - logs NO LONGER stream to any server in near-real-time (the RemoteLog
 *    background shipper is detached from the logging path entirely);
 *  - the buffer flushes into app-private storage ONLY — nothing leaves the
 *    device until the user explicitly taps "上传日志" in the maintenance page,
 *    which posts the buffered lines once via [manualUpload] and reports the
 *    result inline (成功/失败提示).
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

    /** Snapshot of the in-memory ring buffer (used by the manual uploader). */
    fun snapshotLines(): List<String> = synchronized(lock) { lines.toList() }

    fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [$tag] $msg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        // 1.0.0: no automatic shipping. Lines stay in-memory and flush to the
        // app-private log file; upload is a user-initiated action only.
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
     * (including the crash itself) lands in the app-private log file.
     */
    fun flushSync() {
        flush(force = true)
    }

    /**
     * 1.0.0 手动上传（用户指令：去实时上送，改为隐私目录存储 + 手动上传 + 结果提示）。
     * 把内存 ring 里的日志一次性 POST 到配置的 endpoint（SharedPreferences
     * pc_debug.remote_log_endpoint 优先，其次 assets/remote_log_endpoint.txt）。
     * 返回 null = 上传成功（服务器 2xx），否则返回给用户看的失败原因。
     * 在 IO 协程中调用；绝不抛异常。
     */
    fun manualUpload(context: Context): String? {
        val endpoint = RemoteLog.storedEndpoint(context)
        if (endpoint.isBlank()) {
            return "未配置日志服务器地址"
        }
        val payload = snapshotLines()
        if (payload.isEmpty()) {
            return "暂无日志可上传"
        }
        return RemoteLog.uploadOnce(endpoint, payload)
    }

    private fun flush(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < MIN_FLUSH_INTERVAL_MS) return
        lastFlush = now
        try {
            val text = synchronized(lock) { lines.joinToString("\n") }
            if (text.isEmpty()) return
            // 1.0.0：写入 APP 隐私目录（context.filesDir/logs/），不再落公共 Downloads
            val dir = java.io.File(ctx.filesDir, "logs")
            dir.mkdirs()
            java.io.File(dir, FILE_NAME).writeText(text)
        } catch (_: Throwable) {
            // diagnostics must never take the app down
        }
    }
}
