/**
 * UpdateChecker — in-app OTA update check + APK download (0.4.0).
 *
 * Server contract (the same lightweight box that receives debug logs,
 * 106.53.7.242:18888 /root/photographer_log):
 *
 *   GET  {base}/api/version
 *        -> {"versionName":"0.4.0","versionCode":17,"url":"/apk/PhotographerCamera-0.4.0.apk",
 *            "size":23456789,"ts":1788657600000,"notes":"..."}
 *        (missing file -> {"versionCode":0} = "no release yet")
 *
 *   GET  {base}/apk/<file>   raw APK bytes (Content-Length set, direct stream)
 *
 *   POST {base}/api/upload/apk?token=<secret>&vname=&code=&notes=
 *        raw APK body -> server replaces current release, prunes old APKs and
 *        old debug logs. Token lives in /root/photographer_log/upload.token.
 *
 * The base URL is derived from the SAME asset the remote log shipper uses
 * (assets/remote_log_endpoint.txt, "http://host:port/log" -> strip "/log"),
 * so server relocation is a one-file rebuild away, nothing hardcoded here.
 *
 * Download runs on Dispatchers.IO with 15s connect / 30s read timeouts and
 * streams to externalCacheDir/update.apk (no permission needed for the
 * app-private cache dir). Installation goes through FileProvider +
 * ACTION_VIEW(application/vnd.android.package-archive); on API 26+ the
 * system asks the user to allow installs from this app the first time.
 */
package com.photographercamera.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Base URL from the shared endpoint asset: ".../log" -> "...". */
    fun baseUrl(context: Context): String {
        val raw = runCatching {
            context.assets.open("remote_log_endpoint.txt").bufferedReader().use { it.readLine() }
        }.getOrNull()?.trim().orEmpty()
        return if (raw.endsWith("/log")) raw.removeSuffix("/log") else raw.removeSuffix("/")
    }

    /** Installed version code (PackageInfoCompat - works on every API level). */
    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)
        }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Query the server for the current release. Null/empty = unreachable or none. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val base = baseUrl(context)
        if (base.isEmpty()) return@withContext null
        try {
            val conn = URL("$base/api/version").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val obj = JSONObject(body)
            val code = obj.optLong("versionCode", 0)
            if (code <= 0) return@withContext null
            val url = obj.optString("url", "")
            if (url.isEmpty()) return@withContext null
            UpdateInfo(
                versionName = obj.optString("versionName", code.toString()),
                versionCode = code,
                url = if (url.startsWith("http")) url else "$base$url",
                sizeBytes = obj.optLong("size", 0),
                notes = obj.optString("notes", ""),
            )
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "check failed", e)
            null
        }
    }

    /**
     * Stream the APK into externalCacheDir/update.apk. [onProgress] gets
     * (receivedBytes, totalBytes) with totalBytes <= 0 when unknown.
     * @return the downloaded file, or null on failure.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val dest = File(context.externalCacheDir ?: context.cacheDir, "update.apk")
        try {
            val conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        // throttle progress callbacks to ~every 256KB
                        if (received - lastReport >= 256 * 1024 || received == total) {
                            lastReport = received
                            onProgress(received, total)
                        }
                    }
                }
            }
            conn.disconnect()
            if (dest.length() > 0) dest else null
        } catch (e: Exception) {
            android.util.Log.w("UpdateChecker", "download failed", e)
            dest.delete()
            null
        }
    }

    /** Fire the system package installer for the downloaded APK. */
    fun installApk(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
