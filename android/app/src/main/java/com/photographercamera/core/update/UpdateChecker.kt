/**
 * UpdateChecker — in-app OTA update check + APK download (0.7.1).
 *
 * 0.7.1 变更：
 *  1) APK 托管迁移到 Cloudflare R2（公共域名 https://app.tybtool.top）。
 *     版本清单 = {R2_BASE}/version.json（字段与旧服务器 /api/version 完全一致），
 *     APK = 绝对 URL，由 R2 CDN 直发，不再依赖更新服务器的小水管出口带宽。
 *     R2 不可达时回退旧服务器 /api/version（remote_log_endpoint 资产推导）。
 *  2) 下载从应用内协程 HttpURLConnection 改为系统 DownloadManager：
 *     黑屏 / 退后台 / 进程被杀都不会中断（系统进程托管 + 通知栏进度），
 *     应用侧重启后凭 SharedPreferences 持久化的 downloadId 续接状态。
 *     完成后仍走 FileProvider + 系统包安装器（不变）。
 *
 * version.json 契约：
 *   {"versionName":"0.7.1","versionCode":21,"url":"https://app.tybtool.top/PhotographerCamera-0.7.1.apk",
 *    "size":53000000,"ts":1788657600000,"notes":"..."}
 *   versionCode <= 0 视为"暂无发布"。
 */
package com.photographercamera.core.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.photographercamera.photon.utils.PLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val url: String,      // absolute download URL
    val sizeBytes: Long,
    val notes: String,
)

object UpdateChecker {

    /** 0.7.1: R2 公共域名（app-update 桶的自定义域）。 */
    private const val R2_BASE = "https://app.tybtool.top"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private const val PREFS = "update_state"
    private const val PREF_DL_ID = "download_id"
    private const val PREF_DL_CODE = "download_version_code"

    /** Legacy fallback: base URL from the shared log-shipper asset. */
    private fun legacyBase(context: Context): String {
        val raw = runCatching {
            context.assets.open("remote_log_endpoint.txt").bufferedReader().use { it.readLine() }
        }.getOrNull()?.trim().orEmpty()
        return if (raw.endsWith("/log")) raw.removeSuffix("/log") else raw.removeSuffix("/")
    }

    private fun fetchJson(urlStr: String): String? = try {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        body
    } catch (e: Exception) {
        android.util.Log.w("UpdateChecker", "fetch failed: $urlStr", e)
        null
    }

    private fun parseVersionBody(body: String, fallbackBase: String): UpdateInfo? {
        val obj = JSONObject(body)
        val code = obj.optLong("versionCode", 0)
        if (code <= 0) return null
        val url = obj.optString("url", "")
        if (url.isEmpty()) return null
        return UpdateInfo(
            versionName = obj.optString("versionName", code.toString()),
            versionCode = code,
            url = if (url.startsWith("http")) url else "$fallbackBase$url",
            sizeBytes = obj.optLong("size", 0),
            notes = obj.optString("notes", ""),
        )
    }

    /** Installed version code (PackageInfoCompat - works on every API level). */
    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
        }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /**
     * Query the current release: R2 version.json first, legacy server as
     * fallback. Null/empty = unreachable or no release.
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        parseVersionBody(fetchJson("$R2_BASE/version.json") ?: return@withContext null, R2_BASE)
            ?: run {
                val legacy = legacyBase(context)
                if (legacy.isEmpty()) null
                else fetchJson("$legacy/api/version")?.let { parseVersionBody(it, legacy) }
            }
    }

    // ---------------------------------------------------------------------
    // DownloadManager-backed download (0.7.1)
    // ---------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun destFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        return File(dir, "update.apk")
    }

    /**
     * Enqueue the APK download with the system DownloadManager. If a pending
     * download for the same versionCode already exists (e.g. after process
     * death), its id is returned instead of re-enqueueing.
     */
    fun startDownload(context: Context, info: UpdateInfo): Long {
        pendingDownload(context)?.let { (id, code) ->
            if (code == info.versionCode) return id
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = destFile(context)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val req = DownloadManager.Request(Uri.parse(info.url)).apply {
            setTitle("PhotographerCamera v${info.versionName}")
            setDescription("更新包下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationUri(Uri.fromFile(dest))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val id = dm.enqueue(req)
        prefs(context).edit()
            .putLong(PREF_DL_ID, id)
            .putLong(PREF_DL_CODE, info.versionCode)
            .apply()
        return id
    }

    /** Persisted (downloadId, versionCode) of a still-pending download, if any. */
    fun pendingDownload(context: Context): Pair<Long, Long>? {
        val p = prefs(context)
        if (!p.contains(PREF_DL_ID)) return null
        val id = p.getLong(PREF_DL_ID, -1)
        val code = p.getLong(PREF_DL_CODE, 0)
        if (id < 0) return null
        val st = queryDownload(context, id) ?: return null
        return when (st.status) {
            DownloadManager.STATUS_SUCCESSFUL,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED,
            DownloadManager.STATUS_PENDING -> id to code
            else -> null  // FAILED / unknown -> let caller clear and restart
        }
    }

    fun clearDownloadState(context: Context) {
        prefs(context).edit().remove(PREF_DL_ID).remove(PREF_DL_CODE).apply()
    }

    /** The finished APK for [id] if the system download already succeeded. */
    fun downloadedFileNow(context: Context, id: Long): File? {
        val st = queryDownload(context, id) ?: return null
        if (st.status != DownloadManager.STATUS_SUCCESSFUL) return null
        val f = destFile(context)
        return if (f.exists() && f.length() > 0) f else null
    }

    data class DlState(val status: Int, val received: Long, val total: Long)

    /** Snapshot a download's status/progress; null when the id is unknown. */
    fun queryDownload(context: Context, id: Long): DlState? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            return DlState(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                received = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    /**
     * Poll [id] until the system DownloadManager finishes; [onProgress] gets
     * (received, total). Returns the completed APK file, or null on failure.
     * Survives screen-off / backgrounding: the download itself lives in the
     * system process — only this lightweight poll loop runs in-app.
     */
    suspend fun awaitDownload(
        context: Context,
        id: Long,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        while (true) {
            val st = queryDownload(context, id)
            when (st?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    onProgress(st.total, st.total)
                    val f = destFile(context)
                    return@withContext if (f.exists() && f.length() > 0) f else null
                }
                DownloadManager.STATUS_FAILED -> return@withContext null
                null -> return@withContext null
                else -> {
                    onProgress(st.received, st.total)
                    delay(500)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** Fire the system package installer for the downloaded APK. */
    fun installApk(context: Context, apk: File) {
        // Android 8+：未授予"安装未知应用"时先跳系统开关页，避免静默失败/闪退
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", apk,
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            PLog.e("UpdateChecker", "installApk failed", e)
        }
    }
}
