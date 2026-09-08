package com.photographercamera.core.debug

import android.content.Context
import android.os.Build
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RemoteLog — ships the in-memory debug stream to a developer server in near
 * real time, so real-device issues can be diagnosed WITHOUT adb / USB.
 *
 * Hard requirements (a diagnostics channel must never become the outage):
 *  1. **Never blocks the caller.** `offer()` only appends to an in-memory queue
 *     under a short lock; all network I/O happens on one background daemon
 *     thread. Callers are camera preview / capture / GL threads.
 *  2. **Bounded memory.** The queue is a ring of [MAX_QUEUE_LINES] lines; older
 *     lines are dropped (and counted) instead of growing without limit when the
 *     network is down.
 *  3. **Bounded traffic.** Lines are coalesced into batches ([MAX_BATCH_LINES],
 *     [MIN_SEND_INTERVAL_MS]) and each request body is capped, so a log burst
 *     (pinch-zoom, frame-by-frame tracing) cannot flood the device or the server.
 *  4. **Never throws, never retries in a storm.** A failed batch stays queued for
 *     the next tick (cheap, bounded by rule 2) — no exponential retry loop, no
 *     wake-lock, no ANR.
 *  5. **Off by default.** Remote shipping only runs after [configure] is called
 *     with a non-blank endpoint, so release builds cost nothing.
 *
 * Wire format: `POST <endpoint>` with a JSON body
 * `{"device":..,"session":..,"seq":..,"lines":["HH:mm:ss.SSS [TAG] msg", ...]}`.
 */
object RemoteLog {

    // ---- tunables -----------------------------------------------------------
    private const val MAX_QUEUE_LINES = 2_000      // ring buffer; ~2k lines ≈ 400 KB worst case
    private const val MAX_BATCH_LINES = 150        // lines per HTTP request
    private const val MIN_SEND_INTERVAL_MS = 600L  // coalesce window (near-real-time)
    private const val MAX_IDLE_WAIT_MS = 1_000L    // wake at least this often when idle
    private const val MAX_LINE_CHARS = 400         // truncate pathological single lines
    private const val MAX_BODY_BYTES = 200 * 1024  // hard cap per request body
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 2_500

    // ---- state --------------------------------------------------------------
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var endpoint: String = ""
        private set
    @Volatile var deviceLabel: String = Build.MODEL ?: "unknown"
        private set

    /** Monotonic batch counter — the server uses it to detect gaps/drops. */
    @Volatile var sentBatches: Long = 0
        private set
    @Volatile var droppedLines: Long = 0
        private set
    @Volatile var lastError: String = ""
        private set

    private val queue = ArrayDeque<String>()
    private val lock = Any()
    private val monitor = Object()
    private val running = AtomicBoolean(false)
    private var session: String = ""
    private var seq: Long = 0

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pc-remotelog").apply { isDaemon = true }
    }

    /** Last-resort hook so a fatal error can be shipped before the process dies. */
    @Volatile var fatalHook: ((String) -> Unit)? = null

    // ---- configuration ------------------------------------------------------
    // Endpoint resolution order:
    //   1. SharedPreferences  — set at runtime from the debug panel, survives restarts
    //   2. assets/remote_log_endpoint.txt — baked in at build time; edit that plain
    //      text file to retarget a build WITHOUT touching Kotlin
    //   3. blank -> remote shipping stays off
    private const val PREFS_NAME = "pc_debug"
    private const val KEY_ENDPOINT = "remote_log_endpoint"
    private const val ASSET_ENDPOINT = "remote_log_endpoint.txt"

    /** Enable shipping at startup using the first non-blank configured endpoint. */
    fun bootstrap(context: Context) {
        val url = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ENDPOINT, null)
                ?.takeIf { it.isNotBlank() }
                ?: readAssetEndpoint(context)
        }.getOrDefault("") ?: ""
        if (url.isBlank()) disable() else configure(url)
    }

    /** Persist a new endpoint (blank disables). Takes effect immediately. */
    fun saveEndpoint(context: Context, url: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ENDPOINT, url.trim()).apply()
        }
        if (url.isBlank()) disable() else configure(url.trim())
    }

    /** The endpoint that will be used on the next cold start. */
    fun storedEndpoint(context: Context): String = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() }
            ?: readAssetEndpoint(context)
    }.getOrDefault("") ?: ""

    private fun readAssetEndpoint(context: Context): String? = runCatching {
        context.assets.open(ASSET_ENDPOINT)
            .bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            }
    }.getOrNull()

    /**
     * Enable remote shipping. Safe to call repeatedly (e.g. the user edits the
     * server address in the debug panel); the worker is (re)started on demand.
     */
    fun configure(endpointUrl: String, device: String? = null, sessionId: String? = null) {
        val url = endpointUrl.trim()
        if (url.isBlank()) {
            disable()
            return
        }
        endpoint = url
        deviceLabel = device?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER ?: "?"} ${Build.MODEL ?: "?"}"
        if (sessionId != null) session = sessionId
        if (session.isBlank()) session = "s${System.currentTimeMillis()}"
        lastError = ""
        enabled = true
        startWorker()
    }

    fun disable() {
        enabled = false
        synchronized(monitor) { monitor.notifyAll() }
    }

    /** Append one already-formatted line. Never blocks, never throws. */
    fun offer(line: String) {
        if (!enabled) return
        val text = if (line.length > MAX_LINE_CHARS) line.substring(0, MAX_LINE_CHARS) + "…" else line
        var wake = false
        synchronized(lock) {
            queue.addLast(text)
            while (queue.size > MAX_QUEUE_LINES) {
                queue.removeFirst()
                droppedLines++
            }
            // Ship immediately on a burst; otherwise let the tick handle it.
            if (queue.size >= MAX_BATCH_LINES) wake = true
        }
        if (wake) synchronized(monitor) { monitor.notifyAll() }
    }

    /** Best-effort synchronous push (crash path). Must never throw. */
    fun flushSync() {
        if (!enabled) return
        runCatching { drainOnce() }
    }

    /**
     * 1.0.0 手动一次上传（用户指令：日志默认只存 APP 隐私目录，由用户在维护页
     * 点击"上传日志"时才发送）。绕过常驻 worker 与 enabled 状态，直接把
     * [logLines] 打包 POST 到 [endpointUrl]；返回 null = 成功（2xx），
     * 否则返回可展示的失败原因。在调用方提供的 IO 线程上执行，绝不抛异常。
     */
    fun uploadOnce(endpointUrl: String, logLines: List<String>): String? {
        val url = endpointUrl.trim()
        if (url.isBlank()) return "未配置日志服务器地址"
        if (logLines.isEmpty()) return "暂无日志可上传"
        return runCatching {
            val body = buildBody(logLines.takeLast(MAX_BATCH_LINES * 4))
            // 1.0.1 修复：post 必须显式传入 url——旧实现无参调用 post(body)，post
            // 内部用 object 字段 endpoint（bootstrap 已从启动链移除，永远为空串），
            // URL("") 抛 MalformedURLException: no protocol（用户报障根因）。
            if (post(url, body)) null else lastError.ifBlank { "上传失败" }
        }.getOrElse { "上传失败：${it.javaClass.simpleName}" }
    }

    // ---- worker -------------------------------------------------------------
    private fun startWorker() {
        if (!running.compareAndSet(false, true)) {
            synchronized(monitor) { monitor.notifyAll() }
            return
        }
        worker.execute {
            try {
                while (enabled) {
                    synchronized(monitor) {
                        if (queueEmpty()) monitor.wait(MAX_IDLE_WAIT_MS)
                    }
                    if (!enabled) break
                    runCatching { drainOnce() }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
            }
        }
    }

    private fun queueEmpty(): Boolean = synchronized(lock) { queue.isEmpty() }

    private fun drainOnce() {
        val batch = synchronized(lock) {
            if (queue.isEmpty()) return
            val n = minOf(queue.size, MAX_BATCH_LINES)
            ArrayList<String>(n).also { out ->
                repeat(n) { out.add(queue.removeFirst()) }
            }
        }
        val body = buildBody(batch)
        val ok = post(endpoint, body)
        if (ok) {
            sentBatches++
            lastError = ""
        } else {
            // Keep the batch queued for the next tick (bounded by MAX_QUEUE_LINES).
            synchronized(lock) {
                // Re-insert at the FRONT so ordering is preserved; drop from the
                // tail if that would overflow the ring again.
                for (i in batch.indices.reversed()) queue.addFirst(batch[i])
                while (queue.size > MAX_QUEUE_LINES) {
                    queue.removeLast()
                    droppedLines++
                }
            }
        }
    }

    private fun buildBody(lines: List<String>): String {
        val sb = StringBuilder(256 + lines.size * 64)
        sb.append("{\"device\":").append(json(deviceLabel))
            .append(",\"session\":").append(json(session))
            .append(",\"seq\":").append(seq++)
            .append(",\"ts\":").append(System.currentTimeMillis())
            .append(",\"lines\":[")
        var budget = MAX_BODY_BYTES
        for (i in lines.indices) {
            val item = json(lines[i])
            if (budget - item.length < 64) break // leave room for the closing bracket
            budget -= item.length
            if (i > 0 && sb[sb.length - 1] != '[') sb.append(',')
            sb.append(item)
        }
        sb.append("]}")
        return sb.toString()
    }

    /** POST one batch to [targetEndpoint]. Returns true on 2xx. Never throws. */
    private fun post(targetEndpoint: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(targetEndpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Keepalive would hold the radio open during a debug session.
                setRequestProperty("Connection", "close")
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            val out: OutputStream = conn.outputStream
            out.write(bytes)
            out.flush()
            val code = conn.responseCode
            if (code in 200..299) {
                runCatching { conn.inputStream.close() }
                true
            } else {
                lastError = "HTTP $code"
                runCatching { conn.errorStream?.close() }
                false
            }
        } catch (t: Throwable) {
            // message 截短：异常原文可能携带超长 URL/上下文，行内提示显示不全（用户报障）。
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "").take(120)
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun json(s: String): String {
        val sb = StringBuilder(s.length + 16).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Diagnostics about the diagnostics channel itself. */
    fun status(): String = if (!enabled) "remote log: off" else
        "remote log: $endpoint  batches=$sentBatches dropped=$droppedLines queued=${synchronized(lock) { queue.size }}" +
            (lastError.takeIf { it.isNotBlank() }?.let { " last_err=$it" } ?: "")
}
