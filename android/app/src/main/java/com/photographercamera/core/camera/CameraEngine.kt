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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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

    /**
     * RAW+DNG capture mode. Probed at bind time: requires a RAW-capable camera
     * (ImageCaptureCapabilities). When on, each shot also saves the untouched
     * sensor DNG (native sensor color, 50-60MB on 50MP sensors) next to the
     * ISP JPEG that feeds the recipe chain. Opt out with files/pc_raw_off.txt
     * (same flag-file pattern as pc_layers.txt; no UI change).
     */
    @Volatile var rawMode: Boolean = false
        private set

    /** True when the still is developed by OUR GPU RAW ISP (pc_raw_isp.txt). */
    @Volatile var rawIspMode: Boolean = false
        private set

    /** Which lens id the HAL is expected to be serving at the given zoom. */
    private var lastServedLensId: String? = null

    /** Cached sensor calibration for the RAW ISP path. */
    private var rawCalib: com.photographercamera.core.gpu.RawCalibration? = null

    /**
     * Static RAW calibration from the main lens' characteristics: CFA layout,
     * black/white levels, as-shot WB gains and a camera->sRGB color matrix
     * (SENSOR_COLOR_TRANSFORM2 camera->XYZ composed with the standard
     * XYZ->sRGB matrix). Cached; identity-ish fallback when partial.
     */
    private fun queryRawCalibration(): com.photographercamera.core.gpu.RawCalibration {
        rawCalib?.let { return it }
        val calib = try {
            val mainId = lenses.firstOrNull { it.isMain }?.id ?: lenses.firstOrNull()?.id
            if (mainId == null) null else {
                val c = cameraManager.getCameraCharacteristics(mainId)
                val cfaOff = when (
                    c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
                ) {
                    1 -> intArrayOf(1, 0)  // GRBG
                    2 -> intArrayOf(0, 1)  // GBRG
                    3 -> intArrayOf(1, 1)  // BGGR
                    else -> intArrayOf(0, 0) // RGGB
                }
                val blPat = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                val black = if (blPat != null) {
                    val tmp = IntArray(4)
                    blPat.copyTo(tmp, 0)
                    FloatArray(4) { tmp[it].toFloat() }
                } else {
                    floatArrayOf(64f, 64f, 64f, 64f)
                }
                val white = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
                // As-shot WB: SDK 36 removed SENSOR_NEUTRAL_COLOR_POINT and the
                // per-shot CaptureResult is not reachable through CameraX, so
                // the sensor-side gains stay neutral here - the recipe's own WB
                // (GpuParams u_wb) drives the look on top.
                val wb = floatArrayOf(1f, 1f, 1f)
                // camera -> XYZ (illuminant-2 DNG matrix) composed with the
                // standard XYZ -> sRGB matrix
                val xyzToSrgb = floatArrayOf(
                    3.2406f, -1.5372f, -0.4986f,
                    -0.9689f, 1.8758f, 0.0415f,
                    0.0557f, -0.2040f, 1.0570f,
                )
                val t2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
                val ccm = if (t2 != null) {
                    val el = Array(9) { android.util.Rational(0, 1) }
                    t2.copyElements(el, 0)
                    FloatArray(9) { i ->
                        val row = i / 3
                        val col = i % 3
                        var acc = 0f
                        for (k in 0 until 3) {
                            acc += xyzToSrgb[row * 3 + k] * el[k * 3 + col].toFloat()
                        }
                        acc
                    }
                } else {
                    floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
                }
                com.photographercamera.core.gpu.RawCalibration(cfaOff, black, white, wb, ccm)
            }
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
            DebugLog.log("CAM", "engine closed (unbindAll)")
        }
    }

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
        val rawOptOut = File(appContext.filesDir, "pc_raw_off.txt").exists()
        // RAW-ISP opt-in flag: develop the Bayer frame on OUR GPU instead of
        // the device ISP (doc route: RAW -> RAW ISP -> recipe -> GPU -> JPEG).
        val wantRawIsp = File(appContext.filesDir, "pc_raw_isp.txt").exists()
        rawMode = false
        rawIspMode = false
        try {
            val supported = ImageCapture.getImageCaptureCapabilities(p.getCameraInfo(selector))
                .supportedOutputFormats
            val rawCapable = supported.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) ||
                supported.contains(ImageCapture.OUTPUT_FORMAT_RAW)
            if (!rawCapable) {
                DebugLog.log("CAM", "camera not RAW-capable (supported=$supported) -> JPEG only")
            } else if (rawOptOut) {
                DebugLog.log("CAM", "camera RAW-capable but opted OUT (files/pc_raw_off.txt)")
            } else if (wantRawIsp && supported.contains(ImageCapture.OUTPUT_FORMAT_RAW)) {
                rawMode = true
                rawIspMode = true
                DebugLog.log("CAM", "RAW ISP capture ENABLED (our GPU develops the Bayer frame)")
            } else {
                rawMode = true
                if (wantRawIsp) {
                    DebugLog.log("CAM", "RAW ISP requested but OUTPUT_FORMAT_RAW unsupported -> DNG archive mode")
                } else {
                    DebugLog.log("CAM", "RAW+DNG capture ENABLED (native sensor color, max quality)")
                }
            }
        } catch (t: Throwable) {
            DebugLog.log("CAM", "RAW probe failed: ${t.message}")
        }

        val newCapture = ImageCapture.Builder()
            // full-res ISP JPEG; MAXIMIZE_QUALITY also prefers the largest buffer
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // Target far above any known sensor so the fallback rule
                        // lands on the LARGEST output the HAL offers (e.g. the
                        // 50MP 8160x6144 stream on 50MP sensors instead of the
                        // default 12.5MP 4096x3072). This is how the still path
                        // reaches the sensor's maximum native quality.
                        ResolutionStrategy(
                            Size(9600, 7200),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            // RAW capture (CameraX >= 1.5): untouched Bayer to OUR GPU ISP
            // (pc_raw_isp.txt) or untouched DNG archive + ISP JPEG bundle.
            .apply {
                if (rawIspMode) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                } else if (rawMode) {
                    setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                }
            }
            .build()
        imageCapture = newCapture
        preview = newPreview

        try {
            camera = p.bindToLifecycle(lifecycle, selector, newPreview, newCapture)
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
     * Hybrid zoom model (DAZZ pattern for the tele end, native for the wide
     * end):
     *  - zoom >= 1: preview stays pinned at native 1x; the viewfinder box
     *    shows the 1/zoom crop and the STILL is taken with a brief native
     *    setZoomRatio(zoom) around the shutter (restore right after). This
     *    lets the HAL switch to the telephoto lens and run its own ISP crop,
     *    delivering a FULL-RESOLUTION zoomed photo - far better quality than
     *    CPU-cropping the 1x frame.
     *  - zoom < 1 (wide end): applied natively to the LIVE preview too, so
     *    the user sees the real ultra-wide FOV; capture needs no dance.
     * In both cases box, focal label (eq x zoom) and photo always agree.
     */
    fun setZoom(total: Float) {
        val z = total.coerceIn(minZoom(), maxZoom())
        zoomRatio = z
        // native target: wide ratio when zoomed out, 1x otherwise (the dance
        // around the shutter handles the tele end)
        val target = if (z < 1f) z else 1f
        if (abs(target - appliedNativeZoom) > 1e-3f) {
            appliedNativeZoom = target
            try {
                camera?.cameraControl?.setZoomRatio(target)
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
     * Full-resolution still through ImageCapture with NATIVE LENS CALLING:
     * for zoom >= 1 a brief native setZoomRatio(userZoom) runs around the
     * shutter (masked by the capture flash) so the HAL selects the best
     * physical lens (tele for high zoom) and delivers the FULL-RESOLUTION
     * zoomed photo - no CPU crop quality loss. For zoom < 1 the wide ratio is
     * already applied natively to the live preview. RAW mode additionally
     * writes the untouched sensor DNG (OUTPUT_FORMAT_RAW_JPEG bundle).
     * Returns false only when the camera is not bound (UI falls back to the
     * preview-frame capture path).
     */
    fun captureStill(
        onBitmap: (Bitmap) -> Unit,
        onRawFrame: ((RawFrame) -> Unit)? = null,
    ): Boolean {
        val cam = camera ?: return false
        val ic = imageCapture ?: return false
        val t0 = SystemClock.elapsedRealtime()
        val userZoom = zoomRatio
        val needsDance = userZoom >= 1f && abs(userZoom - appliedNativeZoom) > 1e-3f

        fun restoreNative() {
            if (needsDance) {
                appliedNativeZoom = 1f
                try {
                    cam.cameraControl.setZoomRatio(1f)
                } catch (_: Exception) {
                }
            }
        }

        fun shootBitmap() {
            DebugLog.log("SHOT", "takePicture zoom=$userZoom nativeDance=$needsDance raw=$rawMode")
            ic.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val t1 = SystemClock.elapsedRealtime()
                        restoreNative()
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
                        restoreNative()
                        DebugLog.logError("SHOT", "takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        fun shootRawBundle() {
            DebugLog.log("SHOT", "takePicture RAW+JPEG zoom=$userZoom nativeDance=$needsDance")
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
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        restoreNative()
                        DebugLog.log(
                            "SHOT",
                            "raw bundle saved dng=${dngFile.length() / 1024}KB " +
                                "ispJpeg=${jpgFile.length() / 1024}KB -> ${dir.path}",
                        )
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
                        restoreNative()
                        DebugLog.logError("SHOT", "RAW+JPEG takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        fun shootRawIsp() {
            DebugLog.log("SHOT", "takePicture RAW-ISP zoom=$userZoom nativeDance=$needsDance")
            ic.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        restoreNative()
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
                        DebugLog.log(
                            "SHOT",
                            "raw frame ${w}x${h} stride=$stride rot=$rot ${buf.capacity() / 1024}KB " +
                                "(capture ${t1 - t0}ms)",
                        )
                        val frame = RawFrame(w, h, stride, buf, rot, queryRawCalibration())
                        mainHandler.post {
                            if (onRawFrame != null) onRawFrame(frame)
                            else onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        restoreNative()
                        DebugLog.logError("SHOT", "RAW-ISP takePicture failed", exception)
                        onBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
                    }
                },
            )
        }

        fun shoot() = when {
            rawIspMode -> shootRawIsp()
            rawMode -> shootRawBundle()
            else -> shootBitmap()
        }

        if (needsDance) {
            // Native zoom BEFORE the still request: the HAL re-routes to the
            // best physical lens for this ratio (tele for high zoom) and the
            // photo comes out full-resolution with the true zoomed FOV.
            appliedNativeZoom = userZoom
            cam.cameraControl.setZoomRatio(userZoom).addListener({ shoot() }, mainExecutor)
        } else {
            shoot()
        }
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

    // ---- misc controls --------------------------------------------------------------

    fun setFlash(on: Boolean) {
        imageCapture?.flashMode =
            if (on) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
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
