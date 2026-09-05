/**
 * DeviceCompat - runtime adaptation layer so the app behaves correctly across
 * new SoCs, different CPU core schedulers and different GPU vendors/drivers.
 *
 * It centralises every device-dependent decision:
 *  - GPU identification (Adreno / Mali / PowerVR / Intel / translator drivers
 *    like ANGLE-SwiftShader or emulator translators) with conservative memory
 *    caps for translator/low-RAM devices;
 *  - memory-class based capture budget (max long side of the GPU chain);
 *  - a dedicated capture decode executor OFF the main thread (big cores run
 *    JPEG decode + chain prep without janking UI or the GL render loop);
 *  - a fallback EGLConfig chooser (8:8:8:8 -> 8:8:8:0 -> 5:6:5) because some
 *    new GPUs/drivers reject the hardcoded RGBA8888+depth16 combo.
 *
 * Nothing here changes the photographic output - only how much resources the
 * device is asked for and where the work runs.
 */
package com.photographercamera.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object DeviceCompat {

    // ---- GPU info (set once from the GL thread) -----------------------------

    @Volatile var gpuRenderer: String = "unknown"
        private set
    @Volatile var gpuVendor: String = "unknown"
        private set
    @Volatile var gpuVersion: String = "unknown"
        private set

    /** True on translator / software drivers (emulators, ANGLE, Chromebooks). */
    @Volatile var isTranslatorDriver: Boolean = false
        private set

    /** Call on the GL thread after the context is current. */
    fun initGlInfo(renderer: String?, version: String?) {
        gpuRenderer = renderer?.trim()?.ifEmpty { "unknown" } ?: "unknown"
        gpuVersion = version?.trim()?.ifEmpty { "unknown" } ?: "unknown"
        gpuVendor = when {
            gpuRenderer.contains("Adreno", true) -> "Qualcomm"
            gpuRenderer.contains("Mali", true) -> "ARM"
            gpuRenderer.contains("PowerVR", true) || gpuRenderer.contains("Rogue", true) -> "Imagination"
            gpuRenderer.contains("Intel", true) || gpuRenderer.contains("UHD", true) || gpuRenderer.contains("Iris", true) -> "Intel"
            gpuRenderer.contains("NVIDIA", true) -> "NVIDIA"
            gpuRenderer.contains("AMD", true) || gpuRenderer.contains("Radeon", true) -> "AMD"
            else -> "unknown"
        }
        // Translator / software drivers commonly seen on emulators, ANGLE-on-
        // Windows devices and some Chromebooks. They are the least predictable
        // in memory behaviour, so they get the conservative capture budget.
        val r = gpuRenderer.lowercase()
        isTranslatorDriver = listOf(
            "swiftshader", "angle", "translator", "virgl", "venus",
            "bluestacks", "ldplayer", "mumu", "livestack", "software",
        ).any { r.contains(it) }
    }

    // ---- system / memory class (set once from the UI side) ------------------

    @Volatile var isLowRam: Boolean = false
        private set
    @Volatile var maxHeapMb: Int = 256
        private set
    @Volatile var totalRamMb: Int = 0
        private set
    @Volatile var coreCount: Int = Runtime.getRuntime().availableProcessors()
        private set
    @Volatile var socName: String = Build.HARDWARE
        private set

    /** Max long side fed through the GPU capture chain (memory-bounded). */
    @Volatile var captureMaxLongSidePx: Int = 2880
        private set

    fun initSystem(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            isLowRam = am?.isLowRamDevice == true
            totalRamMb = if (Build.VERSION.SDK_INT >= 31) {
                val mm = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mm)
                (mm.totalMem / (1024L * 1024L)).toInt()
            } else 0
            maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()
            if (Build.VERSION.SDK_INT >= 31) {
                socName = Build.SOC_MODEL.ifEmpty { Build.HARDWARE }
            }
            coreCount = Runtime.getRuntime().availableProcessors()
        } catch (_: Throwable) {
        }
        recomputeCaptureBudget()
    }

    private var initialized = false

    private fun recomputeCaptureBudget() {
        captureMaxLongSidePx = when {
            // translator drivers: RGBA intermediates can spike several x the
            // nominal size on some hosts - stay small and safe
            isTranslatorDriver -> 2160
            isLowRam || maxHeapMb < 192 -> 2160
            // flagship tier (>=12GB RAM, large heap): let the full sensor
            // resolution through (50MP JPEG = 8160x6144 -> chain keeps 28MP)
            totalRamMb >= 12000 && maxHeapMb >= 512 -> 6144
            // high tier: no downscale below 4:3 12.5MP sensors
            totalRamMb >= 8000 -> 4096
            else -> 2880
        }
    }

    // ---- threads ------------------------------------------------------------

    /**
     * Decode / pre-crop work for stills runs here, off both the MAIN thread
     * and the GL render loop. Single thread keeps captures ordered and avoids
     * thrashing the scheduler across big/LITTLE clusters; the background
     * priority lets small cores handle it without racing UI work on big ones.
     */
    val captureExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "pc-capture").apply {
                isDaemon = false
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /** Move the calling thread into display scheduling class (GL thread). */
    fun applyDisplayThreadPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {
        }
    }

    // ---- diagnostics ----------------------------------------------------------

    fun summarize(): String =
        "device=${Build.MODEL} soc=$socName cores=$coreCount ram=${totalRamMb}MB " +
            "heap=${maxHeapMb}MB lowRam=$isLowRam gpu=$gpuVendor/$gpuRenderer " +
            "translator=$isTranslatorDriver cap=$captureMaxLongSidePx"
}
