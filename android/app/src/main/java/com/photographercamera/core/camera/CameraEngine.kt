/**
 * CameraEngine — the camera core, built on **CameraX** (androidx.camera.*).
 *
 * WHY CAMERAX (per the reused-wheels mandate + android_camera_performance_optimization_agent.json):
 * the previous hand-rolled Camera2 wrapper re-implemented the exact things
 * CameraX already solved, and each re-implementation caused real-device bugs:
 *
 *   - repeated CameraDevice open/close churn (the "continuous OIS click" symptom)
 *     and leaked devices across navigation  -> CameraX `bindToLifecycle()` owns
 *     the whole open/configure/close lifecycle;
 *   - black preview on devices that reject hand-built stream configurations
 *     -> CameraX negotiates valid stream combos with the HAL;
 *   - green-noise stills from the hand-rolled RAW/YUV converters
 *     -> ImageCapture uses the ISP-encoded full-resolution JPEG (the doc's
 *     "ISP" stage — hardware demosaic/WB/encode, correct on every device);
 *   - zoom wrongness from per-lens switching
 *     -> zoom is a GPU/CPU CROP (DAZZ pattern, no CONTROL_ZOOM_RATIO): the
 *     session always runs at its native 1x FOV; the preview samples a centered
 *     1/zoom uv window and the still is cropped with the same factor, so the
 *     viewfinder, the focal label and the saved photo can never disagree.
 *
 * DAZZ-pattern framing: the viewfinder frame shows exactly what gets saved at
 * every zoom level - no pinned-wide preview, no shrinking capture box, no
 * zoom dance around the shutter.
 *
 * The GL preview view owns the OES SurfaceTexture and feeds it via
 * [setSurfaceTexture]; CameraX streams camera frames into it through a
 * SurfaceProvider, so the 15-layer GPU look chain is untouched.
 *
 * AGENTS.md rule 12: no AI runtime.
 */
package com.photographercamera.core.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.photographercamera.core.debug.DebugLog
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.sqrt

/** One physical camera of the active facing, ordered by focal length (UI info only). */
class LensRef(
    val id: String,
    val focal: Float,        // native focal length (mm)
    val maxDigitalZoom: Float,
    val eqFocal: Float,      // 35mm-equivalent focal at 1x
    val isMain: Boolean,
)

class CameraEngine(
    context: Context,
    /** CameraX binds the camera to THIS lifecycle — pass the NavBackStackEntry's
     *  lifecycle so leaving the camera screen releases the camera immediately. */
    private val lifecycle: LifecycleOwner,
    private val onResolution: (Int, Int) -> Unit = { _, _ -> },
) {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    // ---- CameraX objects (all touched on the MAIN thread) ----------------------
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var bindPending = false
    private var bound = false

    /** OES SurfaceTexture owned by CameraPreviewView (GL thread). */
    @Volatile private var surfaceTexture: SurfaceTexture? = null

    private var facing = CameraSelector.LENS_FACING_BACK

    /** Live preview buffer size (4:3, set by CameraX on bind). */
    @Volatile var previewSize: Size = Size(1600, 1200)
        private set

    // ---- zoom state (hybrid: native lens calling + DAZZ crop) ------------------
    /** Total user zoom (1.0 = main-lens FOV; <1 = ultra-wide end). Applied
     *  natively when <1 (live wide preview); >=1 the preview stays at 1x and
     *  the still is taken with a brief native zoom around the shutter. */
    var zoomRatio: Float = 1f
        private set

    private var zoomRatioMin = 1f
    private var zoomRatioMax = 5f
    private var nativeZoomMin = 1f
    private var nativeZoomMax = 5f

    /** The zoom ratio currently applied to Camera2 CONTROL_ZOOM_RATIO. */
    @Volatile private var appliedNativeZoom = 1f
    /** Re-entry guard: a single shutter press must produce exactly one takePicture. */
    @Volatile private var capturing = false

    /** User-selected flash mode (ImageCapture.FLASH_MODE_OFF/ON/AUTO); survives rebinds. */
    @Volatile private var flashModeState = ImageCapture.FLASH_MODE_OFF

    /**
     * RAW+DNG capture mode. Probed at bind time: requires a RAW-capable camera
     * (ImageCaptureCapabilities). When on, each shot also saves the untouched
     * sensor DNG (native sensor color, 50-60MB on 50MP sensors) next to the
     * ISP JPEG that feeds the recipe chain. Opt out with files/pc_raw_off.txt
     * (same flag-file pattern as pc_layers.txt; no UI change).
     */
    @Volatile var rawMode: Boolean = false
        private set

    /** True when the still is developed by OUR GPU RAW ISP (default as of
     *  0.2.4; UI RAW 开关 / pc_raw_isp_off.txt 应急后门可关). */
    @Volatile var rawIspMode: Boolean = false
        private set

    /** 当前摄像头 RAW 能力（设备适配层探测结果）：RAW 开关仅在 true 时显示。 */
    @Volatile var rawCapable: Boolean = false
        private set

    /** 设备适配层能力快照（quad bayer 判定 / 像素阵列尺寸等）。 */
    @Volatile private var deviceCaps: com.photographercamera.core.device.DeviceAdapter.Caps? = null

    // ---- YUV direct capture (the "no-JPEG" still path) -------------------------
    // ImageAnalysis(YUV_420_888) 常驻流 = "厂商 ISP 的 YUV"：HAL 后 ISP 原始
    // YUV 帧直达 GPU（HardwareBuffer→EGLImage 零拷贝），绕过 JPEG 有损压缩。
    // 仅在非 RAW 模式启用（RAW 有自己的完整管线）；闪光灯需要 ImageCapture
    // 硬件联动，因此 flash!=OFF 时自动回退 JPEG 拍摄。
    private var imageAnalysis: ImageAnalysis? = null

    /** Analyzer 持有的最新 YUV 帧（KEEP_LATEST；拍照时取走置 null）。 */
    private var latestYuv: ImageProxy? = null

    /** HAL 至少吐过一帧 YUV 才走直采（绑定了但无帧的设备自动回退 JPEG）。 */
    @Volatile private var yuvAlive = false

    // daemon 单线程：空闲开销可忽略，进程退出自动回收（engine 生命周期外不泄漏）
    private val yuvExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pc-yuv").apply { isDaemon = true }
    }

    /** pc_yuv_off.txt 应急后门（与 pc_raw_off.txt 同模式，无 UI）。 */
    private val yuvOptOut: Boolean by lazy {
        File(appContext.filesDir, "pc_yuv_off.txt").exists()
    }

    /**
     * 全分辨率 YUV 直采主通道（bind 时确定）：ImageCapture 以
     * OUTPUT_IMAGE_FORMAT_YUV_420_888 输出 12.5MP binned YUV，绕过 HAL JPEG
     * 压缩（analysis 流只有 1.6MP，不够成片）。RAW ISP / RAW+DNG 模式有
     * 自己的输出格式，其余一律走本模式（含 rawCapable=false 设备）。
     */
    @Volatile private var yuvCaptureOn = false

    /**
     * UI RAW 开关（任务：能力检测驱动显示/隐藏 + 持久化）。
     * 写入 pc_settings.raw_isp_enabled 并重建 ImageCapture —— RAW 输出格式
     * 是 bind 时属性，切换必须 rebind（与 switchFacing 同路径，<1s）。
     */
    fun setRawIspEnabled(on: Boolean) {
        appContext.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("raw_isp_enabled", on).apply()
        DebugLog.log("CAM", "RAW ISP setting -> $on (rebind)")
        mainHandler.post {
            bound = false
            bindPending = false
            startBind()
        }
    }

    /** Which lens id the HAL is expected to be serving at the given zoom. */
    private var lastServedLensId: String? = null

    /** Cached sensor calibration for the RAW ISP path. */
    private var rawCalib: com.photographercamera.core.gpu.RawCalibration? = null

    /**
     * Per-shot COLOR_CORRECTION_GAINS from the still capture's TotalCaptureResult
     * (camera2 interop session capture callback), float[3] = R,G,B with G=1.
     * Official HAL 3A as-shot WB — replaces the gray-world estimate when present.
     */
    @Volatile var lastAsShotGains: FloatArray? = null
        private set

    /**
     * Static RAW calibration from the main lens' characteristics: CFA layout,
     * black/white levels, as-shot WB gains and a camera->sRGB color matrix.
     * 标准化逻辑在设备适配层 DeviceAdapter（各品牌差异不外泄）；本方法只负责
     * 选镜头 + 缓存。
     */
    private fun queryRawCalibration(): com.photographercamera.core.gpu.RawCalibration {
        rawCalib?.let { return it }
        val calib = try {
            val mainId = lenses.firstOrNull { it.isMain }?.id ?: lenses.firstOrNull()?.id
            if (mainId == null) null
            else com.photographercamera.core.device.DeviceAdapter.normalizeCalibration(
                cameraManager.getCameraCharacteristics(mainId),
            )
        } catch (t: Throwable) {
            DebugLog.log("CAM", "RAW calibration query failed: ${t.message}")
            null
        } ?: com.photographercamera.core.gpu.RawCalibration.fallback()
        DebugLog.log(
            "CAM",
            "RAW calib cfa=(${calib.cfaOffset[0]},${calib.cfaOffset[1]}) " +
                "black=${calib.blackLevel[0].toInt()} white=${calib.whiteLevel.toInt()} " +
                "wb=${"%.2f,%.2f,%.2f".format(calib.wbGains[0], calib.wbGains[1], calib.wbGains[2])}",
        )
        rawCalib = calib
        return calib
    }

    // ---- UI callbacks ----------------------------------------------------------
    var isManualMetering: Boolean = false
        private set
    var onMeteringChanged: ((Boolean) -> Unit)? = null

    /** Invoked on the MAIN thread after lenses are (re)enumerated / camera bound. */
    var onLensesChanged: (() -> Unit)? = null

    /** Invoked on the MAIN thread when the preview resolution is set by CameraX
     *  (via providePreviewSurface). The GL view can use this to re-query
     *  [previewSize] and avoid rendering with a stale/default size. */
    var onPreviewResolutionChanged: ((Int, Int) -> Unit)? = null

    /** All facing-matching cameras (focal HUD info; the ACTIVE camera is flagged). */
    var lenses: List<LensRef> = emptyList()
        private set

    init {
        zoomRatioMin = 1f; zoomRatioMax = 5f; appliedNativeZoom = 1f
    }

    // ---- lifecycle of the engine itself ----------------------------------------

    /** Receive the GL-thread-created OES SurfaceTexture before [open]. */
    fun setSurfaceTexture(st: SurfaceTexture) {
        surfaceTexture = st
    }

    /**
     * Bind the camera (idempotent). Safe to call from the GL thread — the actual
     * CameraX work happens on the main thread, as required by ProcessCameraProvider.
     */
    fun open() {
        mainHandler.post { bindOnMain() }
    }

    /** Unbind everything and release (called from the UI's onDispose). */
    fun close() {
        mainHandler.post {
            bound = false
            bindPending = false
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
            camera = null; preview = null; imageCapture = null
            imageAnalysis = null
            latestYuv?.close()
            latestYuv = null
            yuvAlive = false
            DebugLog.log("CAM", "engine closed (unbindAll)")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindOnMain() {
        if (surfaceTexture == null) {
            DebugLog.log("CAM", "bind skipped — no SurfaceTexture yet")
            return
        }
        if (bound || bindPending) return
        bindPending = true
        val t0 = SystemClock.elapsedRealtime()
        refreshLensInfo()
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                provider = future.get()
                DebugLog.log("CAM", "provider ready in ${SystemClock.elapsedRealtime() - t0}ms")
                startBind()
            } catch (e: Exception) {
                bindPending = false
                DebugLog.logError("CAM", "ProcessCameraProvider init failed", e)
            }
        }, mainExecutor)
    }

    private fun startBind() {
        val p = provider ?: return
        val st = surfaceTexture
        if (st == null) {
            bindPending = false
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            p.unbindAll()
        } catch (_: Exception) {
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        val newPreview = Preview.Builder()
            // 4:3 (sensor-native): the GL surface is exactly 3:4 portrait, and a
            // rotated 4:3 buffer fills it perfectly — the Zoom Box geometry and
            // the tap mapping both rely on this. A resolution strategy is REQUIRED:
            // without one CameraX picks its 640x480 default (blurry preview).
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
        newPreview.setSurfaceProvider { request ->
            providePreviewSurface(request)
        }

        // ---- RAW capability probe (must run before building ImageCapture) ------
        // 设备适配层（DeviceAdapter）负责能力探测与标准化，上层不直接碰
        // CameraCharacteristics。RAW ISP 自 0.3.5 起为实验性功能、默认关
        // （开发冻结：YUV 直采成为唯一成片主通道；pc_settings.raw_isp_enabled
        // 由 UI"RAW（实验性功能）"开关写入）；pc_raw_isp_off.txt 保留为应急后门。
        val rawOptOut = File(appContext.filesDir, "pc_raw_off.txt").exists()
        val rawIspOptOut = File(appContext.filesDir, "pc_raw_isp_off.txt").exists()
        val sp0 = appContext.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
        // 0.3.5 一次性迁移：把历史版本（默认开）遗留的 true 重置为关。
        // 迁移只跑一次（raw_isp_default_off_migrated 标记），之后尊重用户手动选择。
        if (!sp0.getBoolean("raw_isp_default_off_migrated", false)) {
            sp0.edit()
                .putBoolean("raw_isp_enabled", false)
                .putBoolean("raw_isp_default_off_migrated", true)
                .apply()
            DebugLog.log("CAM", "0.3.5 migration: RAW ISP force-set OFF (experimental, frozen)")
        }
        val uiRawIspOn = sp0.getBoolean("raw_isp_enabled", false)
        val wantRawIsp = uiRawIspOn && !rawIspOptOut
        rawMode = false
        rawIspMode = false
        rawCapable = false
        try {
            val supported = ImageCapture.getImageCaptureCapabilities(p.getCameraInfo(selector))
                .supportedOutputFormats
            // per camera-samples camerax-rawcapture: capability is probed via
            // ImageCapture.getImageCaptureCapabilities().supportedOutputFormats
            // BEFORE binding (it guards OUTPUT_FORMAT_RAW_JPEG there); the same
            // sanctioned probe gates our YUV vs RAW selection (yuvCaptureOn below).
            val rawCapableNow = supported.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) ||
                supported.contains(ImageCapture.OUTPUT_FORMAT_RAW)
            rawCapable = rawCapableNow
            // 设备适配层：对当前 facing 的镜头做 RAW 传感器特性探测
            // （quad bayer / 像素阵列 / 白电平兜底），供运行时帧守卫使用。
            deviceCaps = run {
                val facingVal = if (facing == CameraSelector.LENS_FACING_FRONT)
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                val lid = try {
                    cameraManager.cameraIdList.firstOrNull { id ->
                        cameraManager.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == facingVal
                    }
                } catch (_: Throwable) { null }
                com.photographercamera.core.device.DeviceAdapter.probeCaps(cameraManager, lid)
            }
            if (!rawCapableNow) {
                DebugLog.log("CAM", "camera not RAW-capable (supported=$supported) -> JPEG only")
            } else if (rawOptOut) {
                DebugLog.log("CAM", "camera RAW-capable but opted OUT (files/pc_raw_off.txt)")
            } else if (wantRawIsp && supported.contains(ImageCapture.OUTPUT_FORMAT_RAW)) {
                rawMode = true
                rawIspMode = true
                DebugLog.log(
                    "CAM",
                    "RAW ISP capture ENABLED (our GPU develops the Bayer frame; setting=$uiRawIspOn " +
                        "backdoorOff=$rawIspOptOut)",
                )
            } else {
                // RAW ISP 关（UI 开关 off / RAW ISP 后门 off）→ YUV 直采主通道
                // （"禁止 JPEG"路线：ImageAnalysis 常驻流 → GPU BT.601 → 统一
                // 引擎，绕过 HAL JPEG 有损压缩）。旧 RAW+DNG 档案模式保留为
                // 后门旗标 files/pc_raw_bundle.txt（无 UI，与 pc_raw_off.txt 同模式）。
                rawMode = File(appContext.filesDir, "pc_raw_bundle.txt").exists()
                if (rawMode) {
                    DebugLog.log("CAM", "RAW+DNG archive mode (backdoor pc_raw_bundle.txt)")
                } else {
                    DebugLog.log("CAM", "YUV direct capture ENABLED (no-JPEG main channel; RAW ISP off)")
                }
            }
        } catch (t: Throwable) {
            DebugLog.log("CAM", "RAW probe failed: ${t.message}")
        }

        // 全分辨率 YUV 直采（"禁止 JPEG"）：RAW ISP / RAW+DNG 有专属输出格式，
        // 其余（RAW ISP 关、rawCapable=false、探测失败）都走 YUV 输出。
        yuvCaptureOn = !rawIspMode && !rawMode && !yuvOptOut
        if (yuvCaptureOn) {
            DebugLog.log("CAM", "yuv capture mode ON (full-res YUV_420_888 stills, no HAL JPEG)")
        }

        // capture config cross-checked against camera-samples
        // (vendor/camera-samples/samples/camerax-rawcapture, camerax-takeaphoto,
        // camerax-ultrahdr, camerax-effects; repo snapshot 2026-09-06).
        // NOTE: the upstream task referenced "CameraXAdvanced/Camera2Basic" — those
        // sample apps no longer exist in the current camera-samples layout; the
        // closest functional equivalents (YUV/RAW/ImageCapture + Camera2Interop) are
        // used as the reference below. No SUBSTANTIVE deviation was found; the
        // existing config is validated or exceeds the samples where they apply, so
        // no logic change is made — only this annotation + inline source tags.
        val newCapture = ImageCapture.Builder()
            // full-res ISP JPEG; MAXIMIZE_QUALITY also prefers the largest buffer.
            // per camera-samples camerax-ultrahdr (ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            // for the quality-priority still path); camerax-takeaphoto uses MINIMIZE_LATENCY
            // (basic snappy capture) — MAXIMIZE_QUALITY is the correct choice for a
            // photographer-quality app.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // 12.5MP binned (4096x3072) = the native camera app's
                        // DEFAULT still size. At this size the vendor HAL keeps
                        // its full ISP pipeline: multi-frame noise reduction AND
                        // local tone mapping (highlight compression). The 50MP
                        // full-size stream (9600x7200 target) made the HAL skip
                        // both — high-ISO color noise + blown highlights (255
                        // clipping) were artifacts of that mode, not of the app.
                        // 50MP can return later as an opt-in setting once we run
                        // our own RAW ISP with highlight recovery.
                        // Resolution strategy cross-checked: camerax-effects uses
                        // FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER and camerax-rawcapture
                        // (FULL_SENSOR) uses HIGHEST_AVAILABLE_STRATEGY; neither
                        // contradicts pinning to a known binned size for ISP-pipeline
                        // preservation. CLOSEST_LOWER_THEN_HIGHER keeps us at the
                        // validated 12.5MP bin on devices that lack the exact size.
                        ResolutionStrategy(
                            Size(4096, 3072),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            // RAW capture (CameraX >= 1.5): untouched Bayer to OUR GPU ISP
            // (default; opt out via pc_raw_isp_off.txt) or untouched DNG
            // archive + ISP JPEG bundle.
            .apply {
                if (rawIspMode) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                    // 官方 per-shot 元数据通道：camera2 interop 的 session
                    // capture callback 在每次 capture 完成时给 TotalCaptureResult，
                    // 里面是 HAL 3A 算好的 as-shot WB（COLOR_CORRECTION_GAINS）。
                    // RAW ISP 用它做白平衡主通道，gray-world 降级为兜底。
                    try {
                        Camera2Interop.Extender(this).setSessionCaptureCallback(
                            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: android.hardware.camera2.CameraCaptureSession,
                                    request: android.hardware.camera2.CaptureRequest,
                                    result: android.hardware.camera2.TotalCaptureResult,
                                ) {
                                    val g = result.get(android.hardware.camera2.CaptureResult.COLOR_CORRECTION_GAINS)
                                    if (g != null) {
                                        val green = maxOf(g.greenEven, 1e-6f)
                                        lastAsShotGains = floatArrayOf(
                                            g.red / green,
                                            1f,
                                            g.blue / green,
                                        )
                                    }
                                }
                            },
                        )
                        DebugLog.log("CAM", "RAW ISP: as-shot WB gains via per-shot CaptureResult enabled")
                    } catch (t: Throwable) {
                        DebugLog.log("CAM", "as-shot WB callback failed: ${t.message}")
                    }
                } else if (rawMode) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                } else if (yuvCaptureOn) {
                    // 全分辨率 YUV 直采输出（12.5MP binned，无 HAL JPEG 压缩）；
                    // 闪光 precapture 联动照常工作，输出仍是 YUV。
                    // CameraX 1.6 移除了 setOutputImageFormat，setBufferFormat
                    // 是替代 API（ImagePipeline 以此为输入格式）。
                    // per camera-samples: no sample in the current layout uses
                    // setBufferFormat(YUV_420_888) on ImageCapture (it is a newer
                    // CameraX 1.6 API than the samples); the camera2-* YUV_420_888
                    // usage (camerax-luminosity / camera2-qrscanner / camera2-hdrviewfinder)
                    // is on ImageReader/ImageAnalysis, confirming YUV_420_888 is the
                    // canonical non-JPEG still format — kept with the existing rationale.
                    setBufferFormat(android.graphics.ImageFormat.YUV_420_888)
                    // YUV 直采画质补强：HAL 对 YUV still 的 ISP 档位默认跟随
                    // FAST，绕过了 JPEG 路径隐含的高质量处理。这里用 camera2
                    // interop 显式请求 HIGH_QUALITY 档的降噪/锐化/色差校正，
                    // 让 HAL 端把 ISP 管线拉满（失败的设备忽略之，不阻塞）。
                    // per camera-samples camerax-rawcapture: its RAW path uses
                    // Camera2Interop.setCaptureRequestOption(SENSOR_PIXEL_MODE,
                    // MAXIMUM_RESOLUTION) — confirming Camera2Interop request
                    // injection is the sanctioned way to steer HAL ISP behavior
                    // from CameraX; our HQ NR/EDGE/ABERRATION trio is the same
                    // sanctioned mechanism applied to the YUV still. No sample
                    // contradicted this; kept.
                    try {
                        Camera2Interop.Extender(this)
                            .setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                            )
                            .setCaptureRequestOption(
                                CaptureRequest.EDGE_MODE,
                                CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                            )
                            .setCaptureRequestOption(
                                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                            )
                        DebugLog.log("CAM", "yuv capture: HQ noise-reduction/edge/aberration requested")
                    } catch (t: Throwable) {
                        DebugLog.log("CAM", "camera2 interop HQ options failed: ${t.message}")
                    }
                }
            }
            .build()
        imageCapture = newCapture
        // survive rebinds (front/back switch): the fresh ImageCapture always
        // starts at FLASH_MODE_OFF, so re-apply the user's chosen mode
        newCapture.flashMode = flashModeState
        preview = newPreview

        // ---- YUV direct-capture stream (non-RAW modes only) ---------------------
        // RAW 模式有自己的完整管线，不需要 analysis 流挤占带宽；非 RAW 模式
        // 下常驻 YUV_420_888 流作为"禁止 JPEG"成片主通道。绑定失败（三流
        // 组合不被 HAL 支持）自动降级为双流 bind —— 能力矩阵的自动 fallback。
        val newAnalysis = if (!rawMode && !yuvOptOut) {
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                        )
                        .setResolutionStrategy(
                            // 同 JPEG 成片目标（12.5MP binned）：HAL 保留完整
                            // ISP 管线；HAL 不支持时 CameraX 就近降级。
                            ResolutionStrategy(
                                Size(4096, 3072),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(yuvExecutor) { proxy ->
                        val old = latestYuv
                        latestYuv = proxy
                        old?.close()
                        if (!yuvAlive) {
                            yuvAlive = true
                            DebugLog.log(
                                "CAM",
                                "YUV analysis alive ${proxy.width}x${proxy.height} " +
                                    "(direct no-JPEG capture ready)",
                            )
                        }
                    }
                }
        } else {
            null
        }
        imageAnalysis = newAnalysis
        yuvAlive = false
        latestYuv?.close()
        latestYuv = null

        try {
            camera = if (newAnalysis != null) {
                try {
                    p.bindToLifecycle(lifecycle, selector, newPreview, newCapture, newAnalysis)
                } catch (e3: Exception) {
                    // 三流组合不被支持（部分 HAL 的 YUV+JPEG 并发限制）→ 双流
                    DebugLog.log(
                        "CAM",
                        "bind with YUV analysis failed (${e3.message}) - falling back to preview+capture",
                    )
                    imageAnalysis = null
                    p.bindToLifecycle(lifecycle, selector, newPreview, newCapture)
                }
            } else {
                p.bindToLifecycle(lifecycle, selector, newPreview, newCapture)
            }
        } catch (e: Exception) {
            bindPending = false
            DebugLog.logError("CAM", "bindToLifecycle failed (facing=$facing)", e)
            return
        }
        bound = true
        bindPending = false

        // Native zoom window from the bound camera (CONTROL_ZOOM_RATIO pipeline).
        val zs = camera?.cameraInfo?.zoomState?.value
        nativeZoomMin = zs?.minZoomRatio ?: 1f
        nativeZoomMax = zs?.maxZoomRatio ?: 5f
        // Adaptive zoom cap (data-driven, no hardcoded 10x): the maximum lens
        // multiplier (eq focal / main eq — the tele's optical reach) × 2. Beyond
        // that the capture is a deep digital crop the HAL upscales to the full
        // sensor size (12MP of mush). The cap bounds the digital part to ≤2x.
        val mainEqV = mainEq().coerceAtLeast(0.1f)
        val maxLensMult = (lenses.maxOfOrNull { it.eqFocal / mainEqV } ?: 1f).coerceAtLeast(1f)
        val adaptiveCap = maxLensMult * 2f
        // Wide end kept: nativeZoomMin < 1 means the logical camera exposes a
        // real ultra-wide FOV (e.g. 0.6x on OPPO PLG110). zoom < 1 is applied
        // NATIVELY (the 1x sensor frame cannot be CPU-widened); zoom >= 1
        // stays the DAZZ-style crop. The live preview always sits at 1x for
        // zoom >= 1 and at the native wide ratio for zoom < 1.
        zoomRatioMin = nativeZoomMin
        zoomRatioMax = minOf(nativeZoomMax, adaptiveCap).coerceAtLeast(1f)
        zoomRatio = 1f
        appliedNativeZoom = 1f

        DebugLog.log(
            "CAM",
            "bound in ${SystemClock.elapsedRealtime() - t0}ms facing=$facing " +
                "preview=${previewSize.width}x${previewSize.height}",
        )
        DebugLog.log(
            "ZOOM",
            "adaptive cap: maxLensMult=${"%.2f".format(maxLensMult)} " +
                "(2x=${"%.2f".format(adaptiveCap)}) nativeMax=${"%.2f".format(nativeZoomMax)} " +
                "-> zoomRange=[${"%.2f".format(zoomRatioMin)}..${"%.2f".format(zoomRatioMax)}]",
        )
        mainHandler.post { onLensesChanged?.invoke() }
    }

    /**
     * Give CameraX our GL-owned SurfaceTexture wrapped in a Surface. Runs on a
     * CameraX thread — setDefaultBufferSize is thread-safe, Surface(st) too.
     */
    private fun providePreviewSurface(request: SurfaceRequest) {
        val st = surfaceTexture
        if (st == null) {
            // no GL surface (screen already gone) — satisfy the request with a
            // dummy so CameraX doesn't time out waiting
            val dummy = try {
                SurfaceTexture(0).apply { detachFromGLContext() }
            } catch (_: Throwable) {
                SurfaceTexture(0)
            }
            val dummySurface = Surface(dummy)
            request.provideSurface(dummySurface, mainExecutor) { _ ->
                dummySurface.release()
                dummy.release()
            }
            return
        }
        val res = request.resolution
        try {
            st.setDefaultBufferSize(res.width, res.height)
        } catch (e: Exception) {
            DebugLog.logError("PREVIEW", "setDefaultBufferSize(${res.width}x${res.height}) failed", e)
        }
        previewSize = res
        DebugLog.log("PREVIEW", "surface ${res.width}x${res.height}")
        val surface = Surface(st)
        request.provideSurface(surface, mainExecutor) { _ ->
            // The SurfaceTexture itself is OWNED by CameraPreviewView (GL thread) —
            // only release the Surface wrapper here.
            try {
                surface.release()
            } catch (_: Exception) {
            }
        }
        mainHandler.post {
            onResolution(res.width, res.height)
            onPreviewResolutionChanged?.invoke(res.width, res.height)
        }
    }

    // ---- lens info (focal HUD; read-only, no camera control) --------------------

    /** SENSOR_ORIENTATION of the matching lenses — rotation provenance for stills. */
    @Volatile private var sensorOrientationDeg: Int = 90

    private fun refreshLensInfo() {
        val found = mutableListOf<LensRef>()
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                val facingVal = c.get(CameraCharacteristics.LENS_FACING)
                if (facingVal != facing) continue
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val isDepth =
                    caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) == true
                val isLogical =
                    caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val crop = if (phys != null) {
                    val diag = sqrt(phys.width * phys.width + phys.height * phys.height)
                    if (diag > 0f) 43.27f / diag else 1f
                } else 1f
                val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: floatArrayOf(4.3f)
                val maxDz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                val zr = if (Build.VERSION.SDK_INT >= 30) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null
                // Rotation provenance for the still-orientation diagnosis:
                // CameraX computes ImageInfo.rotationDegrees from THIS value
                // plus the device rotation, so log it alongside every capture.
                sensorOrientationDeg = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                DebugLog.log(
                    "LENS",
                    "id=$id facing=$facingVal depth=$isDepth logical=$isLogical " +
                        "focal=${focals.joinToString("/")}mm eq=${"%.1f".format(focals[0] * crop)}mm " +
                        "dZoom=$maxDz zoomRange=$zr sensorOrientation=$sensorOrientationDeg",
                )
                if (isDepth) continue
                found += LensRef(id, focals[0], maxDz, focals[0] * crop, false)
            }
        } catch (e: Exception) {
            DebugLog.logError("LENS", "enumeration failed", e)
        }
        // widest = UI's reference for the Zoom Box at default zoom
        val main = found.minByOrNull { abs(it.eqFocal - 26f) }
        lenses = found.sortedBy { it.focal }.map {
            if (main != null && it.id == main.id) LensRef(it.id, it.focal, it.maxDigitalZoom, it.eqFocal, true) else it
        }
        DebugLog.log("LENS", "usable=${lenses.size} main=${main?.id}")
    }

    fun mainEq(): Float =
        (lenses.firstOrNull { it.isMain } ?: lenses.firstOrNull())?.eqFocal?.takeIf { it > 0f } ?: 1f

    /** 35mm-equivalent focal of the widest available lens. */
    fun wideEq(): Float = lenses.firstOrNull()?.eqFocal ?: mainEq()

    /** 35mm-equivalent focal length of the CURRENT zoom (top HUD). */
    fun currentEqFocal(): Float = mainEq() * zoomRatio

    // ---- zoom -------------------------------------------------------------------

    fun minZoom(): Float = zoomRatioMin
    fun maxZoom(): Float = zoomRatioMax

    /**
     * Max OPTICAL zoom multiplier (tele eq-focal / main eq-focal, e.g. 69.8/24.2
     * = 2.88x). Up to this ratio the logical HAL serves a real lens and the
     * native preview is the capture FOV. BEYOND it the extra zoom is digital:
     * the engine pins the native zoom at the optical max and the UI expresses
     * the extra reach by shrinking the viewfinder capture box + a matching CPU
     * crop of the still — no HAL lossy upscale, box and photo always agree.
     */
    fun opticalMaxZoom(): Float {
        val mainEqV = mainEq().coerceAtLeast(0.1f)
        return (lenses.maxOfOrNull { it.eqFocal / mainEqV } ?: 1f).coerceAtLeast(1f)
    }

    /**
     * Continuous native zoom for BOTH preview and capture.
     *
     * The zoom ratio is applied to the LIVE preview at all times (wide end < 1,
     * tele end >= 1). The logical multi-camera HAL owns the actual per-ratio
     * physical-lens switching, so as the user zooms past a lens' optical reach
     * the HAL transparently engages the next lens (e.g. the 2x/5x telephoto) —
     * the preview shows REAL optical zoom and adapts to ANY device's camera
     * array automatically. Because the preview already sits at the user's zoom,
     * capture needs no last-moment ratio "dance": the still is taken at the
     * current native zoom and is pixel-identical to what was on screen (no
     * freeze, no lens flip-back). Box, focal label (eq x zoom) and photo agree.
     */
    fun setZoom(total: Float) {
        val z = total.coerceIn(minZoom(), maxZoom())
        zoomRatio = z
        // Native (CONTROL_ZOOM_RATIO) part stops at the OPTICAL max: past it
        // the HAL only does a lossy digital upscale, so we pin the sensor zoom
        // there and let the UI shrink the capture box + CPU-crop the still by
        // zoom/opticalMax instead (better quality, box = photo guaranteed).
        val native = minOf(z, opticalMaxZoom())
        if (abs(native - appliedNativeZoom) > 1e-3f) {
            appliedNativeZoom = native
            try {
                camera?.cameraControl?.setZoomRatio(native)
            } catch (_: Exception) {
            }
        }
        logLensServing(z)
    }

    /**
     * Which physical lens the logical multi-camera HAL is expected to serve
     * at this zoom: the most-tele lens whose optical reach (eqFocal) still
     * covers the target eq (mainEq x zoom); below the widest lens' reach the
     * widest lens serves. Purely informational (HUD/log) - the HAL owns the
     * actual per-ratio lens switching, so this adapts to any device
     * automatically.
     */
    private fun logLensServing(z: Float) {
        val main = lenses.firstOrNull { it.isMain } ?: lenses.firstOrNull() ?: return
        val targetEq = main.eqFocal * z
        val serving = lenses
            .filter { it.eqFocal <= targetEq * 1.05f }
            .maxByOrNull { it.eqFocal }
            ?: lenses.minByOrNull { it.eqFocal }
            ?: return
        if (serving.id != lastServedLensId) {
            lastServedLensId = serving.id
            DebugLog.log(
                "LENS",
                "serve zoom=${"%.2f".format(z)} targetEq=${"%.1f".format(targetEq)}mm " +
                    "-> id=${serving.id} (eq=${"%.1f".format(serving.eqFocal)}mm)",
            )
        }
    }

    // ---- capture -----------------------------------------------------------------

    /**
     * Full-resolution still through ImageCapture with NATIVE LENS CALLING.
     *
     * The zoom is applied to the LIVE PREVIEW continuously (see [setZoom]), so
     * by shutter time the HAL has already engaged the best physical lens for
     * the current ratio (tele for high zoom, wide for low) and the preview
     * shows the REAL optical FOV. We therefore capture at the CURRENT native
     * zoom — no last-moment ratio dance, no freeze, no lens flip-back, and the
     * saved photo is pixel-identical to what the user saw. RAW modes
     * additionally write the untouched sensor DNG / Bayer frame.
     *
     * A re-entry guard ([capturing]) ensures ONE shutter press = ONE
     * takePicture, even if the UI fires capture twice in a frame. Returns
     * false when the camera is not bound OR a capture is already running.
     */
    fun captureStill(
        onBitmap: (Bitmap) -> Unit,
        onRawFrame: ((RawFrame) -> Unit)? = null,
        onYuvFrame: ((ImageProxy, Int, Boolean) -> Unit)? = null,
    ): Boolean {
        if (capturing) {
            DebugLog.log("SHOT", "capture already in progress — ignored (guards double shutter)")
            return false
        }
        val cam = camera ?: return false
        val ic = imageCapture ?: return false
        val t0 = SystemClock.elapsedRealtime()
        val userZoom = zoomRatio
        capturing = true
        DebugLog.log(
            "SHOT",
            "takePicture zoom=$userZoom (native preview zoom, no dance) raw=$rawMode rawIsp=$rawIspMode",
        )

        fun finish() { capturing = false }

        fun shootBitmap() {
            DebugLog.log("SHOT", "takePicture zoom=$userZoom nativeZoom=${"%.2f".format(userZoom)} raw=$rawMode")
            ic.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val t1 = SystemClock.elapsedRealtime()
                        finish()
                        // Copy the bytes out and close the ImageProxy IMMEDIATELY
                        // (holding camera buffers open stalls the capture session
                        // on some HALs), then decode OFF the main thread: a 12-50MP
                        // JPEG decode is 100-400ms of big-core work that used to
                        // freeze the UI and starve the GL render loop.
                        val bytes: ByteArray?
                        val format = image.format
                        val w = image.width
                        val h = image.height
                        val rotDeg = image.imageInfo.rotationDegrees
                        bytes = try {
                            val buf = image.planes[0].buffer
                            val b = ByteArray(buf.remaining())
                            buf.get(b)
                            b
                        } catch (t: Throwable) {
                            DebugLog.logError("SHOT", "JPEG read failed", t)
                            null
                        } finally {
                            image.close()
                        }
                        DebugLog.log(
                            "SHOT",
                            "jpeg ${(bytes?.size ?: 0) / 1024}KB format=$format ${w}x$h rotDeg=$rotDeg " +
                                "sensorOri=$sensorOrientationDeg deviceRot=${deviceRotation()}",
                        )
                        // Rotation diagnosis: persist the UNTOUCHED HAL JPEG so a
                        // host-side decode can prove whether the buffer itself
                        // needs 90 vs 270 (HAL/metadata mismatch) — no app
                        // pipeline stage touches this file.
                        if (bytes != null) {
                            try {
                                java.io.File(
                                    appContext.getExternalFilesDir(null),
                                    "PC_debug_raw.jpg",
                                ).writeBytes(bytes)
                            } catch (_: Throwable) {
                            }
                        }
                        if (bytes == null) {
                            onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                            return
                        }
                        com.photographercamera.core.device.DeviceCompat.captureExecutor.execute {
                            // CameraX's rotationDegrees (verified against the
                            // HAL sensorOrientation) is the authoritative upright
                            // rotation for THIS buffer.
                            val bmp = (try {
                                decodeJpegUpright(bytes, rotDeg)
                            } catch (t: Throwable) {
                                DebugLog.logError("SHOT", "JPEG decode failed", t)
                                null
                            }) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            DebugLog.log(
                                "SHOT",
                                "decoded ${bmp.width}x${bmp.height} in ${SystemClock.elapsedRealtime() - t1}ms " +
                                    "(capture+decode ${SystemClock.elapsedRealtime() - t0}ms)",
                            )
                            mainHandler.post { onBitmap(bmp) }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finish()
                        DebugLog.logError("SHOT", "takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        fun shootRawBundle() {
            DebugLog.log("SHOT", "takePicture RAW+JPEG zoom=$userZoom nativeZoom=${"%.2f".format(userZoom)}")
            val dir = File(appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "RAW")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date())
            val dngFile = File(dir, "PC_$stamp.dng")
            val jpgFile = File(dir, "PC_${stamp}_isp.jpg")
            val dngOpts = ImageCapture.OutputFileOptions.Builder(dngFile).build()
            val jpgOpts = ImageCapture.OutputFileOptions.Builder(jpgFile).build()
            ic.takePicture(
                dngOpts,
                jpgOpts,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    // The dual-output API invokes onImageSaved ONCE PER SAVED
                    // FILE — the DNG and the ISP JPEG each deliver their own
                    // callback. Styled processing must run EXACTLY ONCE per
                    // shutter: the ISP-JPEG callback does the decode+chain,
                    // the DNG callback is archival only. Without this gate a
                    // single shutter press produced two decodes, two chain
                    // runs and two gallery JPEGs (observed on PLG110).
                    val styledDone = java.util.concurrent.atomic.AtomicBoolean(false)

                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        finish()
                        val seg = results.savedUri?.lastPathSegment ?: ""
                        val isDng = seg.endsWith(".dng", ignoreCase = true)
                        DebugLog.log(
                            "SHOT",
                            "raw output saved path=$seg dng=${dngFile.length() / 1024}KB " +
                                "ispJpeg=${jpgFile.length() / 1024}KB -> ${dir.path}",
                        )
                        if (isDng) {
                            DebugLog.log("SHOT", "DNG archived — styled output handled by ISP-JPEG callback")
                            return
                        }
                        if (!styledDone.compareAndSet(false, true)) {
                            DebugLog.log("SHOT", "duplicate saved callback — styled output already issued, skip")
                            return
                        }
                        com.photographercamera.core.device.DeviceCompat.captureExecutor.execute {
                            // The archived _isp.jpg is the HAL's JPEG written
                            // straight to disk with correct EXIF; decoding it
                            // upright for the recipe chain requires no rewrite.
                            val bmp = (try {
                                decodeJpegUpright(jpgFile.readBytes(), 0)
                            } catch (t: Throwable) {
                                DebugLog.logError("SHOT", "ISP JPEG decode failed", t)
                                null
                            }) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            DebugLog.log(
                                "SHOT",
                                "decoded ${bmp.width}x${bmp.height} in ${SystemClock.elapsedRealtime() - t0}ms",
                            )
                            mainHandler.post { onBitmap(bmp) }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finish()
                        DebugLog.logError("SHOT", "RAW+JPEG takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        fun shootRawIsp() {
            DebugLog.log("SHOT", "takePicture RAW-ISP zoom=$userZoom nativeZoom=${"%.2f".format(userZoom)}")
            ic.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        finish()
                        val t1 = SystemClock.elapsedRealtime()
                        val format = image.format
                        val w = image.width
                        val h = image.height
                        var rot = image.imageInfo.rotationDegrees
                        var buf: java.nio.ByteBuffer? = null
                        var stride = 0
                        try {
                            val pl = image.planes.getOrNull(0)
                            if (format == android.graphics.ImageFormat.RAW_SENSOR && pl != null) {
                                if (pl.pixelStride != 2) {
                                    DebugLog.log("SHOT", "RAW plane pixelStride=${pl.pixelStride} (packed) - unsupported")
                                } else {
                                    stride = pl.rowStride
                                    val src = pl.buffer
                                    src.position(0)
                                    buf = java.nio.ByteBuffer
                                        .allocateDirect(src.remaining())
                                        .order(java.nio.ByteOrder.nativeOrder())
                                    buf.put(src)
                                    buf.position(0)
                                }
                            } else {
                                DebugLog.log("SHOT", "unexpected RAW-ISP capture format=$format")
                            }
                        } catch (t: Throwable) {
                            DebugLog.logError("SHOT", "RAW read failed", t)
                            buf = null
                        } finally {
                            image.close()
                        }
                        if (buf == null) {
                            onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                            return
                        }
                        // 设备适配层运行时守卫：Quad Bayer 传感器吐全尺寸未
                        // remosaic 帧时，标准 demosaic 会出 2×2 伪彩 —— 拒帧，
                        // 走预览帧回退（见 CameraScreen 的 RAW fallback）。
                        val caps = deviceCaps
                        if (caps != null && !com.photographercamera.core.device.DeviceAdapter
                            .acceptsRawFrame(caps, w, h)
                        ) {
                            onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                            return
                        }
                        DebugLog.log(
                            "SHOT",
                            "raw frame ${w}x${h} stride=$stride rot=$rot ${buf.capacity() / 1024}KB " +
                                "(capture ${t1 - t0}ms)",
                        )
                        val frame = RawFrame(w, h, stride, buf, rot, queryRawCalibration(), lastAsShotGains)
                        mainHandler.post {
                            if (onRawFrame != null) onRawFrame(frame)
                            else onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finish()
                        DebugLog.logError("SHOT", "RAW-ISP takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        /**
         * YUV 直采（"禁止 JPEG 路线"主通道）：取走 analyzer 手里的最新
         * YUV_420_888 帧移交给 GL 渲染链。proxy 生命周期移交渲染端
         * （CameraPreviewView.renderYuvThroughChain 在读回后 close）。
         * 拿不到帧（HAL 未吐/流挂了）→ onFallback 回 JPEG 拍摄。
         */
        fun shootYuv(onYuv: (ImageProxy, Int, Boolean) -> Unit, onFallback: () -> Unit) {
            val proxy = latestYuv
            latestYuv = null   // taken for this shot; the analyzer refills on the next frame
            if (proxy == null) {
                DebugLog.log("SHOT", "no YUV frame available - JPEG fallback")
                onFallback()
                return
            }
            finish()
            val rot = proxy.imageInfo.rotationDegrees
            val mirror = facing == CameraSelector.LENS_FACING_FRONT
            DebugLog.log(
                "SHOT",
                "yuv direct frame ${proxy.width}x${proxy.height} rot=$rot mirror=$mirror zoom=$userZoom",
            )
            mainHandler.post { onYuv(proxy, rot, mirror) }
        }

        /**
         * 全分辨率 YUV 直采（RAW 关时的成片主通道）：ImageCapture 已在 bind
         * 时配置为 YUV_420_888 输出，takePicture 直接产出 12.5MP binned YUV
         * ImageProxy（闪光 precapture 联动照常）。proxy 生命周期移交渲染端
         * （renderYuvThroughChain 读回后 close）。失败回退链：analysis 流帧
         * （1.6MP）→ JPEG。
         */
        fun shootYuvCapture(onFallback: () -> Unit) {
            val cb = onYuvFrame
            if (cb == null) {
                DebugLog.log("SHOT", "yuv capture unavailable (no renderer) - fallback")
                onFallback()
                return
            }
            val t1 = SystemClock.elapsedRealtime()
            ic.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        finish()
                        val rot = image.imageInfo.rotationDegrees
                        val mirror = facing == CameraSelector.LENS_FACING_FRONT
                        DebugLog.log(
                            "SHOT",
                            "yuv capture frame ${image.width}x${image.height} rot=$rot mirror=$mirror " +
                                "(capture ${SystemClock.elapsedRealtime() - t1}ms)",
                        )
                        mainHandler.post { cb(image, rot, mirror) }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finish()
                        DebugLog.logError("SHOT", "yuv capture failed - fallback", exception)
                        onFallback()
                    }
                },
            )
        }

        fun shoot() = when {
            rawIspMode -> shootRawIsp()
            rawMode -> shootRawBundle()
            // 全分辨率 YUV 直采主通道（含闪光：precapture 由 ImageCapture 驱动，
            // 输出仍是 YUV）。回退链：analysis 流帧 → JPEG。
            yuvCaptureOn -> shootYuvCapture {
                if (onYuvFrame != null && yuvAlive) shootYuv(onYuvFrame!!) { shootBitmap() }
                else shootBitmap()
            }
            flashModeState != ImageCapture.FLASH_MODE_OFF -> shootBitmap()
            // analysis 流直采仅作 YUV 输出不可用时的降级（1.6MP）
            onYuvFrame != null && yuvAlive -> shootYuv(onYuvFrame!!) { shootBitmap() }
            else -> shootBitmap()
        }

        // Capture at the CURRENT native zoom — the HAL already has the right
        // physical lens engaged (tele for high zoom, wide for low), so the
        // still matches the preview exactly. No ratio dance, no freeze.
        shoot()
        return true
    }

    /** Display rotation at capture time (0/90/180/270 Surface constants). */
    private fun deviceRotation(): Int = try {
        val wm = appContext.getSystemService(android.content.Context.WINDOW_SERVICE)
            as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.rotation * 90
    } catch (_: Throwable) {
        -1
    }

    /**
     * Decode JPEG bytes to an UPRIGHT bitmap (BitmapFactory ignores EXIF).
     *
     * Three rotation sources, most-authoritative first:
     *  1. [rotationDegrees] — CameraX ImageInfo (per-buffer, derived from the
     *     HAL sensorOrientation + device rotation, verified correct on every
     *     device tested);
     *  2. EXIF orientation tag — fallback for HALs that don't populate
     *     rotationDegrees;
     *  3. Portrait-UI sanity check — the app is LOCKED to portrait, so an
     *    upright photo must be taller than wide. A landscape decode with
     *    layers 1+2 both absent means the HAL skipped orientation metadata
     *    entirely (observed on OPPO PLG110 / Android 16) — rotate 90°.
     *
     * No device/model tables: orientation is decided per-buffer from the
     * metadata above, which is what makes this correct across brands.
     */
    private fun decodeJpegUpright(bytes: ByteArray, rotationDegrees: Int = 0): Bitmap? {
        // Force software ARGB_8888: HARDWARE bitmaps cannot be GL-uploaded or
        // CPU-cropped, and some OEM decoders default to hardware config.
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        var deg = ((rotationDegrees % 360) + 360) % 360
        var exifTag = -1
        if (deg == 0) {
            deg = try {
                val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                exifTag = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL,
                )
                DebugLog.log("SHOT", "exif orientation tag=$exifTag")
                when (exifTag) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    android.media.ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (_: Throwable) {
                0
            }
        }
        // Layer 3: portrait-locked sanity net.
        if (deg == 0 && bmp.width > bmp.height) {
            DebugLog.log("SHOT", "orientation missing (rotDeg=0, no EXIF) — portrait fallback rotate 90")
            deg = 90
        }
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return try {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (_: Throwable) {
            bmp
        }
    }

    // ---- focus / metering ---------------------------------------------------------

    /**
     * Tap-to-focus + meter at display-normalized (nx, ny) in [0,1]². The tap is
     * mapped into the 4:3 preview buffer coordinates (rotated 90° — the display
     * x axis is the buffer y axis, matching the GL rotation) and handed to the
     * CameraX metering pipeline, which owns the AF/AE region plumbing.
     */
    fun tapFocusAndMeter(nx: Float, ny: Float) {
        val cam = camera ?: return
        try {
            val ps = previewSize
            // view (portrait 3:4) -> buffer (landscape 4:3), 90° rotation:
            // buffer x = display y, buffer y = 1 - display x (sensorOrientation-90 mapping)
            val bx = (ny.coerceIn(0f, 1f)) * ps.width
            val by = (1f - nx.coerceIn(0f, 1f)) * ps.height
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                ps.width.toFloat(),
                ps.height.toFloat(),
            )
            val point = factory.createPoint(bx, by)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
                .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
            isManualMetering = true
            mainHandler.post { onMeteringChanged?.invoke(true) }
        } catch (e: Exception) {
            DebugLog.logError("FOCUS", "tapFocusAndMeter failed", e)
        }
    }

    /** Long-press: cancel manual metering, back to whole-frame average. */
    fun cancelManualMetering() {
        val cam = camera ?: return
        try {
            cam.cameraControl.cancelFocusAndMetering()
            isManualMetering = false
            mainHandler.post { onMeteringChanged?.invoke(false) }
        } catch (_: Exception) {
        }
    }

    // ---- exposure compensation (EV) --------------------------------------------------
    // Driven by the sun-icon slider next to the tap-to-focus ring.

    /** True when the current camera supports AE exposure compensation. */
    fun exposureCompensationSupported(): Boolean =
        camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true

    /** (min, max) EV index range of the current camera, or null when unsupported. */
    fun exposureCompensationRange(): Pair<Int, Int>? =
        camera?.cameraInfo?.exposureState
            ?.takeIf { it.isExposureCompensationSupported }
            ?.exposureCompensationRange?.let { it.lower to it.upper }

    /** EV (in stops) per index step, e.g. 0.5f. 0f when unsupported. */
    fun exposureCompensationStep(): Float =
        camera?.cameraInfo?.exposureState?.exposureCompensationStep?.let {
            it.numerator.toFloat() / it.denominator.toFloat()
        } ?: 0f

    /** Apply an EV index (clamped into the device range). Returns true on success. */
    fun setExposureCompensationIndex(index: Int): Boolean {
        val cam = camera ?: return false
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) return false
        val clamped = index.coerceIn(st.exposureCompensationRange.lower, st.exposureCompensationRange.upper)
        return try {
            cam.cameraControl.setExposureCompensationIndex(clamped)
            DebugLog.log("EV", "index=$clamped ev=${"%.1f".format(clamped * exposureCompensationStep())}")
            true
        } catch (e: Exception) {
            DebugLog.logError("EV", "setExposureCompensationIndex failed", e)
            false
        }
    }

    // ---- misc controls --------------------------------------------------------------

    /** Current flash mode: ImageCapture.FLASH_MODE_OFF / ON / AUTO. */
    fun flashMode(): Int = flashModeState

    /** Set flash mode (FLASH_MODE_OFF/ON/AUTO); applied to the live use case and kept across rebinds. */
    fun setFlashMode(mode: Int) {
        flashModeState = mode
        imageCapture?.flashMode = mode
        DebugLog.log("CAM", "flashMode -> $mode")
    }

    /** Switch front/rear: rebind (CameraX closes and reopens cleanly). */
    fun switchFacing() {
        facing =
            if (facing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        zoomRatio = 1f
        mainHandler.post {
            bound = false
            bindPending = false
            startBind()
        }
    }
}
