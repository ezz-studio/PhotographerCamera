/**
 * MainActivity — Compose UI host for PhotographerCamera.
 *
 * Responsibilities:
 *   - Set up the Compose Navigation host;
 *   - Provide the dark film-camera theme.
 *
 * All camera, preset and gallery logic lives in the screen composables.
 */
package com.photographercamera.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photographercamera.ui.screens.CameraScreen
import com.photographercamera.ui.screens.GalleryScreen
import com.photographercamera.ui.screens.PresetListScreen
import com.photographercamera.ui.theme.PhotographerCameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remote shipping must be configured BEFORE the first line is logged,
        // otherwise the BOOT record would never reach the dev server.
        com.photographercamera.core.debug.RemoteLog.bootstrap(this)
        // Device-side diagnostics to Downloads/PhotographerCamera_debug.txt:
        // must init BEFORE any camera/GL code logs (buffered lines flush after).
        com.photographercamera.core.debug.DebugLog.init(this)
        installCrashLog()
        enableEdgeToEdge()
        maybeRequestAllFilesAccess()
        applyPreferredWindowColorMode()
        setContent {
            PhotographerCameraTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = "camera",
                    ) {
                        composable("camera") { CameraScreen(navController) }
                        composable("presets") { PresetListScreen(navController) }
                        composable("gallery") { GalleryScreen(navController) }
                    }
                }
            }
        }
    }

    /**
     * HDR 显示 / P3 色域（上游 applyPreferredWindowColorMode 照搬）：
     * HDR 模式 = Android 14+ 且屏幕支持 HDR 且非鸿蒙设备；否则 P3 宽色域；
     * 都不支持时回退默认 sRGB。
     */
    private fun applyPreferredWindowColorMode() {
        val sp = getSharedPreferences("pc_settings", MODE_PRIVATE)
        val useHdrScreenMode = sp.getBoolean("hdr_display", false)
        val useP3ColorSpace = sp.getBoolean("use_p3_color_space", false)
        val configuration = resources.configuration
        window.colorMode = when {
            useHdrScreenMode &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !com.photographercamera.core.device.DeviceUtil.isHarmonyOS &&
                configuration.isScreenHdr -> android.content.pm.ActivityInfo.COLOR_MODE_HDR
            useP3ColorSpace && configuration.isScreenWideColorGamut ->
                android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            else -> android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后即时生效（HDR 显示 / P3 色域开关）
        applyPreferredWindowColorMode()
    }

    /**
     * Public inbox import needs All-Files-Access on Android 11+. Ask ONCE
     * (flag in pc_settings): jump to the system settings page; the user can
     * grant or skip. When not granted, inbox import simply no-ops (logged).
     */
    private fun maybeRequestAllFilesAccess() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        if (android.os.Environment.isExternalStorageManager()) return
        val prefs = getSharedPreferences("pc_settings", MODE_PRIVATE)
        if (prefs.getBoolean("inbox_perm_asked", false)) return
        prefs.edit().putBoolean("inbox_perm_asked", true).apply()
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.fromParts("package", packageName, null),
                ),
            )
        }
    }

    /**
     * 0.6.0 音量键功能（设置页可选：拍照/变焦/无，默认拍照）。
     * 经 VolumeKeyBus 派发到前台 CameraScreen；相机页未注册回调时放行系统音量。
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val fn = getSharedPreferences("pc_settings", MODE_PRIVATE)
                .getString("volume_key_function", "拍照") ?: "拍照"
            when (fn) {
                "拍照" -> {
                    com.photographercamera.core.util.VolumeKeyBus.onCapture?.invoke()
                    return true // 吞掉按键：拍照场景不应触发音量
                }
                "变焦" -> {
                    com.photographercamera.core.util.VolumeKeyBus.onZoomStep
                        ?.invoke(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
                    return true
                }
                // "无" → fall through，系统音量
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        com.photographercamera.core.debug.DebugLog.log("LIFECYCLE", "app foreground (onStart)")
    }

    override fun onStop() {
        com.photographercamera.core.debug.DebugLog.log("LIFECYCLE", "app background (onStop)")
        super.onStop()
    }

    /**
     * Write every uncaught exception to filesDir/crash_log.txt (appended, with
     * timestamp) so real-device crashes can be diagnosed without adb. Chained
     * to the previous handler so the system crash dialog still shows.
     *
     * A COPY is also exported to the PUBLIC Downloads folder (via MediaStore on
     * API 29+, direct file on legacy): app-private files are unreachable for the
     * user on modern devices, so this public copy is the only way to get the
     * stack to us without USB debugging.
     */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val text = runCatching {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                "\n==== $stamp [${thread.name}] ====\n$sw"
            }.getOrNull()
            runCatching {
                if (text != null) {
                    val file = filesDir.resolve("crash_log.txt")
                    file.appendText(text)
                    // keep the file bounded — drop the oldest half beyond ~256KB
                    if (file.length() > 256 * 1024) {
                        val lines = file.readLines()
                        file.writeText(lines.takeLast(lines.size / 2).joinToString("\n"))
                    }
                    exportPublicCrashCopy(text)
                }
            }
            // the debug log must reach Downloads before the process dies —
            // append the crash and flush SYNCHRONOUSLY on this dying thread
            com.photographercamera.core.debug.DebugLog.logError("CRASH", "uncaught exception", e)
            com.photographercamera.core.debug.DebugLog.flushSync()
            previous?.uncaughtException(thread, e)
        }
    }

    /** Best-effort public copy of the crash into Downloads (never throws). */
    private fun exportPublicCrashCopy(text: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "PhotographerCamera_crash.txt")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                java.io.File(dir, "PhotographerCamera_crash.txt").appendText(text)
            }
        } catch (_: Throwable) {
        }
    }
}
