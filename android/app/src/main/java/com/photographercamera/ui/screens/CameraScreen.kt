package com.photographercamera.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.photographercamera.core.camera.CameraEngine
import com.photographercamera.core.camera.StillFrame
import com.photographercamera.core.debug.DebugLog
import com.photographercamera.core.profile.ProfileLoader
import com.photographercamera.core.storage.CaptureSaver
import com.photographercamera.core.storage.CaptureSaver.SavedPhoto
import com.photographercamera.ui.CameraPreviewView
import com.photographercamera.ui.theme.AccentOrange
import com.photographercamera.ui.theme.DarkBackground
import com.photographercamera.ui.theme.ShutterRing
import com.photographercamera.ui.theme.SurfaceDark
import com.photographercamera.ui.theme.TextPrimary
import com.photographercamera.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.photographercamera.core.update.UpdateChecker
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // CAMERA is mandatory; on legacy devices (API <= 28) MediaStore saving also
    // needs READ/WRITE_EXTERNAL_STORAGE — requested together, camera gate wins.
    val neededPerms = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> hasPermission = grants[Manifest.permission.CAMERA] == true }

    val profiles = remember { mutableStateListOf<String>() }
    var selected by remember { mutableStateOf("") }
    var previewRef by remember { mutableStateOf<CameraPreviewView?>(null) }
    var engine by remember { mutableStateOf<CameraEngine?>(null) }
    var lastCapture by remember { mutableStateOf<SavedPhoto?>(null) }
    var showGrid by remember {
        // Persisted: the grid survives cold starts (the quick-control icon is
        // the primary toggle now, users expect it to remember their choice).
        val sp = context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
        mutableStateOf(sp.getBoolean("show_grid", false))
    }
    // 远程调试日志连接入口（默认关闭，不影响任何功能；从设置面板进入）
    var showDebug by remember { mutableStateOf(false) }
    // 设置面板（顶栏齿轮）
    var showSettings by remember { mutableStateOf(false) }
    // 闪光灯三态（顶栏循环切换：关 -> 开 -> 自动）
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }

    // RAW ISP 开关（实验性功能，0.3.5 起默认关；目标架构：设备支持 RAW_SENSOR
    // 才显示该键）。持久化在 pc_settings.raw_isp_enabled（CameraEngine.setRawIspEnabled
    // 同步写入并 rebind；0.3.5 迁移会把历史遗留的 true 一次性重置为关）。
    val sp = context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
    var rawOn by remember { mutableStateOf(sp.getBoolean("raw_isp_enabled", false)) }

    // Manual metering state, mirrored from CameraEngine via onMeteringChanged.
    // Default = whole-frame average metering; a tap on the frame switches to
    // tap-to-focus+meter, a long press cancels back to average.
    var meteringManual by remember { mutableStateOf(false) }

    // total zoom across lenses (mirrors engine state for recomposition)
    var zoomState by remember { mutableFloatStateOf(1f) }
    // bumped by the engine when lenses are (re)enumerated — recomposes the
    // viewfinder geometry + focal readout with REAL lens data (they are plain
    // engine calls, not Compose state, and the engine fills in asynchronously)
    var lensEpoch by remember { mutableIntStateOf(0) }
    // follow the engine's zoom whenever lenses (re)load: the default zoom is
    // the WIDE lens base (0.5x on multi-lens devices, 1x on single-lens)
    LaunchedEffect(lensEpoch) { engine?.let { zoomState = it.zoomRatio } }
    // RAW capability = probe result for the current lens (engine fills
    // rawCapable during lens enumeration; lensEpoch triggers recomposition)
    val rawCapable = remember(engine, lensEpoch) { engine?.rawCapable ?: false }
    // self-timer: 0 = off, else seconds
    var timerSec by remember { mutableIntStateOf(0) }
    var shotPending by remember { mutableStateOf(false) }
    // 防重入：一次快门 = 一次拍摄。即便 UI 在短时间内触发两次 doCapture，也只拍一张。
    var capturing by remember { mutableStateOf(false) }
    var countdownSec by remember { mutableIntStateOf(0) }

    // Capture feedback animations (replaced the old Toast):
    // white flash over the viewfinder + shutter press bounce + thumb pop-in.
    val scope = rememberCoroutineScope()
    val flashAnim = remember { Animatable(0f) }
    val shutterAnim = remember { Animatable(1f) }
    val thumbAnim = remember { Animatable(1f) }

    fun triggerCaptureFeedback() {
        scope.launch {
            flashAnim.snapTo(0.8f)
            flashAnim.animateTo(0f, tween(durationMillis = 220, easing = LinearOutSlowInEasing))
        }
    }

    // Live QuickControl adjustments (WB / Grain) applied on top of the chosen preset.
    var sheetTarget by remember { mutableStateOf<String?>(null) }
    var adjEv by remember { mutableFloatStateOf(0f) }
    var adjWbTemp by remember { mutableFloatStateOf(0f) }
    var adjWbTint by remember { mutableFloatStateOf(0f) }
    // Grain is a MULTIPLIER on the profile's own grain amount: 1.0 = keep the
    // preset's grain character unchanged, 0 = no grain, 2 = double it.
    var adjGrain by remember { mutableFloatStateOf(1f) }

    val sheetState = rememberModalBottomSheetState()

    fun applyAdjustments() {
        if (selected.isEmpty()) return
        val params = try {
            ProfileLoader.toGpuParams(selected, aspect = 1.0f)
        } catch (t: Throwable) {
            // an unknown/invalid profile must never kill the camera screen
            com.photographercamera.core.debug.DebugLog.logError("PROFILE", "toGpuParams('$selected') failed", t)
            return
        }
        val applied = params.withAdjustments(adjEv, adjWbTemp, adjWbTint, adjGrain)
        // proves the chain params actually reach the renderer (the "all filters
        // look identical" symptom must be attributable from the log alone)
        com.photographercamera.core.debug.DebugLog.log(
            "PROFILE",
            "applied '$selected' exposure=${applied.exposure} wb=(${applied.wbTemp},${applied.wbTint}) " +
                "cm=${applied.colorMatrixGL.joinToString() { "%.2f".format(it) }} " +
                "sharpen=${applied.sharpenAmount} bloom=${applied.bloomAmount} halation=${applied.halationAmount} " +
                "grain=${applied.grainVec[0]} noise=(${applied.noiseVec[0]},${applied.noiseVec[1]}) " +
                "vignette=${applied.vignetteAmount} film=${applied.filmEnabled}",
        )
        previewRef?.setProfile(applied)
    }

    fun saveAndNotify(bmp: Bitmap) {
        capturing = false
        val t0 = android.os.SystemClock.elapsedRealtime()
        val saved = CaptureSaver.save(context, bmp)
        (context as? ComponentActivity)?.runOnUiThread {
            if (saved != null) {
                DebugLog.log(
                    "SHOT",
                    "saved ${bmp.width}x${bmp.height} -> ${saved.name} " +
                        "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                )
                lastCapture = saved
                triggerCaptureFeedback()
                scope.launch {
                    thumbAnim.snapTo(0.6f)
                    thumbAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                }
            } else {
                DebugLog.log("SHOT", "SAVE FAILED (${bmp.width}x${bmp.height})")
                // failure is an exceptional path — keep a visible notice
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doCapture() {
        if (capturing) {
            DebugLog.log("SHOT", "capture already in progress — ignored (double-shutter guard)")
            return
        }
        capturing = true
        // 安全兜底：若某条回调丢失导致 capturing 卡死，8s 后自动复位，避免再也拍不了。
        scope.launch {
            kotlinx.coroutines.delay(8000)
            if (capturing) {
                DebugLog.log("SHOT", "capture guard timeout — auto reset")
                capturing = false
            }
        }
        val eng = engine
        val t0 = android.os.SystemClock.elapsedRealtime()
        DebugLog.log("SHOT", "shutter pressed (zoom=${eng?.zoomRatio}, focal=${eng?.currentEqFocal()})")
        // Digital-tail factor: past the optical max the still comes back at the
        // OPTICAL FOV (engine pins native zoom there) and must be center-cropped
        // by zoom/opticalMax to match the shrunken viewfinder box.
        val opticalMaxV = eng?.opticalMaxZoom() ?: 1f
        val digitalFactor = ((eng?.zoomRatio ?: 1f) / opticalMaxV).coerceAtLeast(1f)
        // 统一成片入口（目标架构）：RAW / ISP 两路都在 StillFrame 收敛，之后
        // 共用同一个 GPU 动态计算引擎 + 风格链 → JPEG。任一路失败退预览帧。
        val saveProcessed: (Bitmap) -> Unit = { processed ->
            saveAndNotify(centerCropZoom(centerCropToRatio(processed, 3f / 4f), digitalFactor))
        }
        val fallBackToPreview: () -> Unit = {
            DebugLog.log("SHOT", "still unavailable — falling back to preview frame")
            previewRef?.captureCurrentFrame {
                saveAndNotify(centerCropZoom(centerCropToRatio(it, 3f / 4f), digitalFactor))
            }
        }
        val issued = eng?.captureStill(
            onBitmap = { bmp ->
                if (bmp.width > 1 && bmp.height > 1) {
                    // The still was taken with NATIVE zoom (HAL lens calling around
                    // the shutter), so it already has the user-zoomed FOV at full
                    // sensor resolution - NO CPU crop here (crop would throw away
                    // resolution; the old crop-on-CPU path produced 374x499 stills
                    // at 5.8x).
                    previewRef?.renderStill(StillFrame.Isp(bmp)) { processed ->
                        DebugLog.log(
                            "SHOT",
                            "GPU chain done (ISP): ${processed.width}x${processed.height} " +
                                "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                        )
                        if (processed.width > 1 && processed.height > 1) saveProcessed(processed)
                        else fallBackToPreview()
                    }
                } else {
                    fallBackToPreview()
                }
            },
            // RAW ISP mode: the untouched Bayer frame is developed on OUR GPU
            // (raw_isp.frag) then the SAME unified engine applies. Any failure
            // inside the RAW path degrades to the 1x1-bitmap fallback below.
            onRawFrame = { frame ->
                previewRef?.renderStill(StillFrame.Raw(frame)) { processed ->
                    DebugLog.log(
                        "SHOT",
                        "GPU chain done (RAW): ${processed.width}x${processed.height} " +
                            "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                    )
                    if (processed.width > 1 && processed.height > 1) saveProcessed(processed)
                    else fallBackToPreview()
                }
            },
            // YUV 直采（禁止 JPEG 主通道）：HAL 后 ISP YUV 帧零拷贝进 GPU，
            // 同一统一引擎出片。proxy 生命周期由渲染端收尾。
            onYuvFrame = { proxy, rot, mirror ->
                previewRef?.renderStill(StillFrame.Yuv(proxy, rot, mirror)) { processed ->
                    DebugLog.log(
                        "SHOT",
                        "GPU chain done (YUV): ${processed.width}x${processed.height} " +
                            "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                    )
                    if (processed.width > 1 && processed.height > 1) saveProcessed(processed)
                    else fallBackToPreview()
                }
            },
            // 0.5.0 多帧堆栈（PhotonCamera 管线移植）：引擎连拍 N 张 YUV，
            // GlesYuvStacker 对齐合并降噪后走统一风格链。UI/动效零改动；
            // 堆栈不可用时回调 1x1 位图 → 预览帧兜底。
            onStackFrame = { proxies, rot, mirror ->
                previewRef?.renderStill(StillFrame.Stack(proxies, rot, mirror)) { processed ->
                    DebugLog.log(
                        "SHOT",
                        "GPU chain done (Stack): ${processed.width}x${processed.height} " +
                            "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                    )
                    if (processed.width > 1 && processed.height > 1) saveProcessed(processed)
                    else fallBackToPreview()
                }
            },
        ) ?: false
        if (!issued) {
            // Fallback: grab the current preview frame through the GL chain.
            DebugLog.log("SHOT", "captureStill not issued — preview frame fallback")
            previewRef?.captureCurrentFrame {
                    saveAndNotify(centerCropZoom(centerCropToRatio(it, 3f / 4f), digitalFactor))
                }
        }
    }

    // Self-timer countdown, then capture.
    LaunchedEffect(shotPending) {
        if (shotPending) {
            var left = timerSec
            while (left > 0) {
                countdownSec = left
                delay(1000)
                left--
            }
            countdownSec = 0
            shotPending = false
            doCapture()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission && profiles.isEmpty()) {
            // IO off the main thread — runBlocking here stalled first-frame
            // composition (profile disk IO inside the composition pass).
            withContext(Dispatchers.IO) {
                ProfileLoader.init(context)
                // pick up profiles the user dropped into /sdcard/PhotographerCamera
                ProfileLoader.importFromPublicInbox(context)
            }
            profiles.clear(); profiles.addAll(ProfileLoader.listProfiles())
            com.photographercamera.core.debug.DebugLog.log(
                "PROFILE",
                "loaded ${profiles.size}: ${profiles.joinToString()}",
            )
            if (selected.isEmpty() && profiles.isNotEmpty()) {
                // Cold start restores the LAST preset used (persisted below);
                // factory default VINTAGE 400 only when nothing is saved yet.
                val saved = context
                    .getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
                    .getString("last_preset", null)
                selected = if (!saved.isNullOrEmpty() && profiles.contains(saved)) {
                    com.photographercamera.core.debug.DebugLog.log("PROFILE", "restored last preset '$saved'")
                    saved
                } else {
                    profiles.firstOrNull { it.equals("VINTAGE 400", ignoreCase = true) } ?: profiles.first()
                }
            }
        }
    }

    // Read the profile picked from PresetListScreen and apply it.
    LaunchedEffect(Unit) {
        navController.currentBackStackEntry?.savedStateHandle?.get<String>("selected_preset")?.let {
            if (it.isNotEmpty()) selected = it
        }
    }

    // Keyed on previewRef AND profiles.size: on cold start this effect used to
    // run BEFORE the AndroidView factory created the preview (previewRef null →
    // setProfile silently dropped), and BEFORE the async ProfileLoader.init
    // finished (toGpuParams threw "Unknown profile"). With both as keys the
    // effect re-runs the moment the view exists AND the moment the profile
    // list lands — every cold-start race converges to a successful apply.
    LaunchedEffect(selected, previewRef, profiles.size) {
        if (selected.isNotEmpty()) {
            context
                .getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("last_preset", selected)
                .apply()
        }
        applyAdjustments()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastCapture = CaptureSaver.list(context).firstOrNull()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // release the camera BEFORE the GL view tears down its SurfaceTexture
            engine?.close()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DarkBackground)
            // two-finger pinch zoom — cross-lens, mirrored in the in-frame HUD
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val eng = engine ?: return@detectTransformGestures
                    eng.setZoom(eng.zoomRatio * zoom)
                    zoomState = eng.zoomRatio
                }
            },
    ) {
        if (!hasPermission) {
            PermissionGate { permLauncher.launch(neededPerms) }
            return@BoxWithConstraints
        }

        val density = LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()

        // ---- default viewfinder frame (3:4) ---------------------------------
        // The GL preview surface is EXACTLY this frame: the camera image never
        // extends beyond it — everything outside stays the dark background.
        // Bottom-anchored between the top HUD row and the bottom panel.
        val bottomReserve = with(density) { 248.dp.toPx() } // quick controls + zoom rotor + shutter row (raised 24dp per target UI)
        val topReserve = with(density) { 100.dp.toPx() }    // top bar (EV / flash / metering / settings)
        val slotH = (maxH - topReserve - bottomReserve).coerceAtLeast(1f)
        val fw = min(maxW * 0.96f, slotH * 3f / 4f)
        val fh = fw * 4f / 3f
        val fx = (maxW - fw) / 2f
        val fy = topReserve + (slotH - fh)                   // anchor to slot bottom

        // ---- inner capture box -----------------------------------------------
        // Hybrid zoom: up to the OPTICAL max the native preview IS the capture
        // FOV, so the box stays full-frame (photo = what you see). PAST the
        // optical max the engine pins the sensor zoom (see CameraEngine.setZoom)
        // and the extra digital reach is shown the DAZZ way — the capture box
        // shrinks by opticalMax/zoom with a scrim outside, and the still gets
        // the SAME centered crop, so box and photo can never disagree.
        val eqBase = remember(lensEpoch) {
            engine?.mainEq()?.takeIf { it in 18f..40f } ?: 26f
        }
        val opticalMax = remember(engine, lensEpoch) { engine?.opticalMaxZoom() ?: 1f }
        val fTarget = when {
            // digital tail (> optical max): strict box=photo — the box shrinks
            // by opticalMax/zoom and the still gets the same centered crop.
            zoomState > opticalMax * 1.001f -> (opticalMax / zoomState).coerceIn(0.25f, 1f)
            // optical zoom segment (1×..opticalMax): a SUBTLE visual shrink as a
            // zoom indicator (≈12% at the optical limit). The still stays at the
            // full optical frame — zero quality loss, the box is just a hair
            // larger than the capture (a ~12% framing margin the user allows).
            zoomState > 1.001f -> {
                val t = ((zoomState - 1f) / (opticalMax - 1f).coerceAtLeast(0.001f))
                    .coerceIn(0f, 1f)
                1f - 0.12f * t
            }
            else -> 1f
        }
        // Optimized motion: stiffer spring than the old sluggish low-stiffness —
        // follows pinch/rotor closely, still settles smoothly.
        val f by animateFloatAsState(
            fTarget,
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
            label = "vfBox",
        )
        val bw = fw * f
        val bh = fh * f
        val bx = fx + (fw - bw) / 2f
        val by = fy + (fh - bh) / 2f

        // keep the GL vignette aligned with the capture box (uv window relative
        // to the GL surface = default frame), so the vignette darkens the
        // CAPTURE range exactly like the saved photo
        LaunchedEffect(fx, fy, fw, fh, bx, by, bw, bh) {
            previewRef?.setVignetteWindow(
                ((bx - fx) + bw / 2f) / fw,
                ((by - fy) + bh / 2f) / fh,
                (bw / fw).coerceAtLeast(1e-4f),
                (bh / fh).coerceAtLeast(1e-4f),
            )
        }

        // ---- preview surface container = the default viewfinder frame -------
        Box(
            Modifier
                .offset { IntOffset(fx.roundToInt(), fy.roundToInt()) }
                .size(with(density) { fw.toDp() }, with(density) { fh.toDp() }),
        ) {
            AndroidView(
                factory = { ctx ->
                    CameraPreviewView(ctx).also { view ->
                        // Bind the camera to the NAV BACK STACK ENTRY lifecycle (not the
                        // activity): leaving this screen releases the camera immediately
                        // (no leaked devices, no open/close churn, indicator dot off).
                        val eng = CameraEngine(ctx, lifecycleOwner)
                        eng.onMeteringChanged = { manual -> meteringManual = manual }
                        eng.onLensesChanged = { lensEpoch++ }
                        engine = eng
                        view.setCameraEngine(eng)
                        previewRef = view
                        // The first LaunchedEffect(selected) run may execute
                        // BEFORE this factory (previewRef still null →
                        // setProfile silently dropped). Re-apply now that the
                        // view exists — without this, the first open showed an
                        // unstyled preview until the profile was re-selected.
                        applyAdjustments()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // focus-ring state
            var ringPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
            val ringAlpha = remember { Animatable(0f) }
            // EV slider (sun icon right of the focus ring): -1..1 across the
            // device's exposure-compensation index range; 0 = no adjustment.
            var evFrac by remember { mutableFloatStateOf(0f) }
            var evRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
            LaunchedEffect(ringPos) {
                if (ringPos != null) {
                    ringAlpha.snapTo(1f)
                    delay(900)
                    ringAlpha.animateTo(0f, tween(durationMillis = 350))
                }
            }

            // dimming scrim OUTSIDE the capture box (drawn ABOVE the GL surface —
            // everything outside the box is a translucent black cover)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val scrim = Color.Black.copy(alpha = 0.45f)
                        val l = bx - fx; val t = by - fy
                        drawRect(scrim, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, t))
                        drawRect(scrim, Offset(0f, t + bh), androidx.compose.ui.geometry.Size(size.width, size.height - t - bh))
                        drawRect(scrim, Offset(0f, t), androidx.compose.ui.geometry.Size(l, bh))
                        drawRect(scrim, Offset(l + bw, t), androidx.compose.ui.geometry.Size(size.width - l - bw, bh))
                    },
            )

            // tap = focus + meter there; long press = back to average metering.
            // Ring + corner brackets drawn ABOVE the GL view (sibling layer).
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { off ->
                                val nx = (off.x / size.width).coerceIn(0f, 1f)
                                val ny = (off.y / size.height).coerceIn(0f, 1f)
                                ringPos = Pair(nx, ny)
                                engine?.tapFocusAndMeter(nx, ny)
                                // new tap: EV restarts from the middle (0 EV)
                                engine?.setExposureCompensationIndex(0)
                                evFrac = 0f
                                evRange = engine?.exposureCompensationRange()
                            },
                            onLongPress = { engine?.cancelManualMetering() },
                        )
                    }
                    .drawBehind {
                        // pulsing focus ring at the tapped spot
                        val ring = ringPos
                        val a = ringAlpha.value
                        if (ring != null && a > 0.01f) {
                            val cx = ring.first * size.width
                            val cy = ring.second * size.height
                            val rc = AccentOrange.copy(alpha = a)
                            drawCircle(rc, radius = 26.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                            drawCircle(rc, radius = 3.dp.toPx(), center = Offset(cx, cy))
                        }
                        // corner brackets track the animated capture box
                        val color = Color.White.copy(alpha = 0.9f)
                        val stroke = 2.dp.toPx()
                        val corner = 18.dp.toPx().coerceAtMost(bw * 0.22f)
                        val l = bx - fx; val t = by - fy
                        val r = l + bw; val btm = t + bh
                        drawLine(color, Offset(l, t + corner), Offset(l, t), stroke)
                        drawLine(color, Offset(l, t), Offset(l + corner, t), stroke)
                        drawLine(color, Offset(r - corner, t), Offset(r, t), stroke)
                        drawLine(color, Offset(r, t), Offset(r, t + corner), stroke)
                        drawLine(color, Offset(r, btm - corner), Offset(r, btm), stroke)
                        drawLine(color, Offset(r, btm), Offset(r - corner, btm), stroke)
                        drawLine(color, Offset(l + corner, btm), Offset(l, btm), stroke)
                        drawLine(color, Offset(l, btm), Offset(l, btm - corner), stroke)
                    },
            )

            // EV sun slider: a vertical RAIL right of the focus ring (per the
            // white-UI reference). The sun rides the rail; evFrac=0 → sun center
            // EXACTLY on the ring's horizontal center line; +EV (brighter) goes
            // UP. The old version had no rail and the sun drifted off-line.
            if (meteringManual && ringPos != null && evRange != null &&
                (engine?.exposureCompensationSupported() == true)
            ) {
                val ring = ringPos!!
                val range = evRange ?: Pair(0, 0)
                val span = range.second - range.first
                val evStep = engine?.exposureCompensationStep() ?: 0f
                val trackH = with(density) { 156.dp.toPx() }   // rail height = 3× the 52dp focus ring
                val containerW = with(density) { 48.dp.toPx() }
                val sunSize = with(density) { 36.dp.toPx() }
                val sunRange = (trackH - sunSize).coerceAtLeast(1f) // sun center travel range
                val evOffsetX = with(density) { 44.dp.toPx() }     // rail center offset right of ring
                val readoutLift = with(density) { 22.dp.toPx() }
                // ring 坐标是相对预览框（fw×fh）的归一化值（onTap 的 size 就是
                // 预览框），EV 容器也在预览框内定位 —— 必须乘 fw/fh 而不是
                // maxW/maxH，否则垂直偏差 ny*(maxH-fh) 会把太阳推到右下角。
                val baseCx = ring.first * fw
                val baseCy = ring.second * fh
                // evFrac=0 → centered on the ring line; +1 → top (brighter); -1 → bottom
                val sunOffsetY = sunRange / 2f - evFrac * sunRange / 2f
                val containerX = baseCx + evOffsetX - containerW / 2f
                val containerY = baseCy - trackH / 2f
                Box(
                    Modifier
                        .offset { IntOffset(containerX.roundToInt(), containerY.roundToInt()) }
                        .size(with(density) { 48.dp }, with(density) { 156.dp })
                        .pointerInput(span, sunRange) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                if (span > 0) {
                                    evFrac = (evFrac - dragAmount / sunRange).coerceIn(-1f, 1f)
                                    engine?.setExposureCompensationIndex((evFrac * span / 2f).roundToInt())
                                }
                            }
                        }
                        .drawBehind {
                            val cx = size.width / 2f
                            val rail = Color.White.copy(alpha = 0.5f)
                            val cap = Color.White.copy(alpha = 0.75f)
                            val stroke = 2.dp.toPx()
                            val capW = 12.dp.toPx()
                            drawLine(rail, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = stroke)
                            drawLine(cap, Offset(cx - capW / 2f, 0f), Offset(cx + capW / 2f, 0f), strokeWidth = stroke)
                            drawLine(cap, Offset(cx - capW / 2f, size.height), Offset(cx + capW / 2f, size.height), strokeWidth = stroke)
                        },
                ) {
                    // sun handle riding the rail (white disc + rays)
                    Box(
                        Modifier
                            .offset { IntOffset(0, sunOffsetY.roundToInt()) }
                            .fillMaxWidth()
                            .height(with(density) { 36.dp }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(with(density) { 22.dp })) {
                            val c = Color.White
                            val r = size.minDimension / 2f
                            drawCircle(c, radius = r * 0.42f)
                            for (i in 0 until 8) {
                                val ang = i * (PI.toFloat() / 4f)
                                val dx = kotlin.math.cos(ang)
                                val dy = kotlin.math.sin(ang)
                                drawLine(
                                    c,
                                    Offset(center.x + dx * r * 0.58f, center.y + dy * r * 0.58f),
                                    Offset(center.x + dx * r, center.y + dy * r),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                )
                            }
                        }
                        // EV readout floating above the sun handle
                        Text(
                            text = "%+.1f".format(evFrac * span / 2f * evStep),
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset { IntOffset(0, (-readoutLift).roundToInt()) }
                                .width(60.dp),
                        )
                    }
                }
            }

            // grid lives INSIDE the capture box → always within the shot range
            if (showGrid) {
                Box(
                    Modifier
                        .offset { IntOffset((bx - fx).roundToInt(), (by - fy).roundToInt()) }
                        .size(with(density) { bw.toDp() }, with(density) { bh.toDp() }),
                ) {
                    GridOverlay()
                }
            }

            // capture feedback: brief white flash over the box content only
            if (flashAnim.value > 0f) {
                Box(
                    Modifier
                        .offset { IntOffset((bx - fx).roundToInt(), (by - fy).roundToInt()) }
                        .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                        .background(Color.White.copy(alpha = flashAnim.value)),
                )
            }
        }

        // top bar: EV / flash / metering indicator / settings — sits in the
        // reserved strip ABOVE the viewfinder frame
        TopBar(
            meteringManual = meteringManual,
            flashMode = flashMode,
            onFlashToggle = {
                val next = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                flashMode = next
                engine?.setFlashMode(next)
            },
            onEvClick = { sheetTarget = "EV" },
            onSettingsClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                // 目标 UI：顶栏整体下移（原 24dp 贴顶过高）。取景框顶在
                // topReserve(100dp)，44+40=84dp 仍留 16dp 不压框。
                .padding(top = 44.dp),
        )

        if (countdownSec > 0) {
            Text(
                "$countdownSec",
                color = Color.White,
                fontSize = 96.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        val minZoom = remember(engine, lensEpoch) { engine?.minZoom() ?: 1f }
        val maxZoom = remember(engine, lensEpoch) { engine?.maxZoom() ?: 5f }
        BottomPanel(
            selected = selected,
            onPresetClick = { navController.navigate("presets") },
            sheetTarget = sheetTarget,
            onSheetTarget = { sheetTarget = it },
            timerSec = timerSec,
            onTimerToggle = { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } },
            onFlip = { engine?.switchFacing() },
            lastCapture = lastCapture,
            thumbScale = thumbAnim.value,
            shutterScale = shutterAnim.value,
            onGalleryClick = { navController.navigate("gallery") },
            focalMm = (eqBase * zoomState).roundToInt(),
            gridOn = showGrid,
            onGridToggle = {
                showGrid = !showGrid
                context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("show_grid", showGrid).apply()
            },
            zoomX = zoomState,
            minZoom = minZoom,
            maxZoom = maxZoom,
            // engine stores the UNCLAMPED total zoom (native part clamps at
            // opticalMax, the digital tail is the UI crop) → rotor readout and
            // viewfinder box stay consistent past the optical limit
            onZoom = { z ->
                engine?.setZoom(z)
                zoomState = engine?.zoomRatio ?: z
            },
            onShutter = {
                if (!shotPending) {
                    shotPending = true
                    scope.launch {
                        shutterAnim.snapTo(0.86f)
                        shutterAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (sheetTarget != null) {
            ModalBottomSheet(
                onDismissRequest = { sheetTarget = null },
                sheetState = sheetState,
                dragHandle = {},
                containerColor = SurfaceDark.copy(alpha = 0.92f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    when (sheetTarget) {
                        "EV" -> {
                            Text("曝光补偿 EV", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            AdjustSlider(
                                value = adjEv,
                                center = 0f,
                                range = -2f..2f,
                                onValueChange = { adjEv = it; applyAdjustments() },
                            )
                            Text("${"%.2f".format(adjEv)} EV", color = TextSecondary, fontSize = 13.sp)
                        }
                        "WB" -> {
                            Text("色温", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            AdjustSlider(
                                value = adjWbTemp,
                                center = 0f,
                                range = -1f..1f,
                                onValueChange = { adjWbTemp = it; applyAdjustments() },
                            )
                            Text("色调", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            AdjustSlider(
                                value = adjWbTint,
                                center = 0f,
                                range = -1f..1f,
                                onValueChange = { adjWbTint = it; applyAdjustments() },
                            )
                        }
                        "Grain" -> {
                            Text("颗粒强度", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            AdjustSlider(
                                value = adjGrain,
                                center = 1f,
                                range = 0f..2f,
                                onValueChange = { adjGrain = it; applyAdjustments() },
                            )
                            Text("×" + "%.2f".format(adjGrain), color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // settings sheet: grid toggle + debug log entry (kept OUT of the main
        // UI — the old floating button pushed the whole bottom panel up)
        if (showSettings) {
            SettingsSheet(
                gridOn = showGrid,
                onGridToggle = {
                    showGrid = !showGrid
                    context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("show_grid", showGrid).apply()
                },
                rawCapable = rawCapable,
                rawOn = rawOn,
                onRawToggle = {
                    rawOn = !rawOn
                    sp.edit().putBoolean("raw_isp_enabled", rawOn).apply()
                    // OUTPUT_FORMAT 是 bind 时属性 → engine 内部走 rebind 生效
                    engine?.setRawIspEnabled(rawOn)
                },
                onDebugClick = { showSettings = false; showDebug = true },
                onDismiss = { showSettings = false },
            )
        }

        if (showDebug) DebugConnectDialog(onDismiss = { showDebug = false })
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onRequest) {
                Text("授权相机")
            }
        }
    }
}

/**
 * Top bar above the viewfinder: EV sheet entry, 3-state flash toggle, metering
 * indicator (STATUS ONLY — average = outline icon, manual tap = filled orange)
 * and the settings entry. Evenly spaced, per the target UI.
 */
@Composable
private fun TopBar(
    meteringManual: Boolean,
    flashMode: Int,
    onFlashToggle: () -> Unit,
    onEvClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onEvClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.WbSunny,
                contentDescription = "曝光补偿",
                tint = TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onFlashToggle, modifier = Modifier.size(40.dp)) {
            val icon = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                else -> Icons.Default.FlashOff
            }
            val tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                TextPrimary.copy(alpha = 0.9f)
            } else AccentOrange
            Icon(icon, contentDescription = "闪光灯", tint = tint, modifier = Modifier.size(22.dp))
        }
        // metering status indicator (not clickable)
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (meteringManual) Icons.Outlined.CenterFocusStrong else Icons.Outlined.CenterFocusWeak,
                contentDescription = if (meteringManual) "手动测光" else "平均测光",
                tint = if (meteringManual) AccentOrange else TextPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "设置",
                tint = TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Settings sheet: grid toggle + remote debug log entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    gridOn: Boolean,
    onGridToggle: () -> Unit,
    rawCapable: Boolean,
    rawOn: Boolean,
    onRawToggle: () -> Unit,
    onDebugClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {},
        containerColor = SurfaceDark.copy(alpha = 0.92f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("设置", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.GridOn,
                    contentDescription = null,
                    tint = TextPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("构图网格", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = gridOn, onCheckedChange = { onGridToggle() })
            }
            // RAW ISP 开关（RAW-capable 设备才显示）：从快捷行迁入设置页——
            // 目标 UI 图的快捷行固定 5 项，RAW 属于进阶拍摄选项。
            if (rawCapable) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = TextPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("RAW（实验性功能）", color = TextPrimary, fontSize = 14.sp)
                        Text(
                            if (rawOn) "已开启：GPU RAW ISP 直出 Bayer 开发" else "默认关闭：YUV 直采（无 JPEG 压缩）",
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = rawOn, onCheckedChange = { onRawToggle() })
                }
            }
            Spacer(Modifier.height(6.dp))
            UpdateCheckRow()
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDebugClick)
                    .padding(vertical = 10.dp),
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = TextPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("调试日志", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("远程日志 ›", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

/** 0.4.0 "检查更新"行状态机：IDLE→(检查)→(下载)→READY→拉起系统安装器。 */
private enum class UpdPhase { IDLE, CHECKING, DOWNLOADING, READY }

/**
 * 设置页"检查更新"：查询更新服务器 /api/version，发现新版本直接流式下载到
 * 应用缓存（进度实时显示），完成后再次点击拉起系统包安装器。全部逻辑走
 * UpdateChecker（core/update），UI 只做状态呈现——符合架构功能最小 UI 入口。
 */
@Composable
private fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(UpdPhase.IDLE) }
    var status by remember {
        mutableStateOf("当前 v" + UpdateChecker.installedVersionName(context))
    }
    var apkFile by remember { mutableStateOf<File?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = phase == UpdPhase.IDLE || phase == UpdPhase.READY) {
                when (phase) {
                    UpdPhase.READY -> apkFile?.let { UpdateChecker.installApk(context, it) }
                    UpdPhase.IDLE -> scope.launch {
                        phase = UpdPhase.CHECKING
                        status = "检查更新中…"
                        val info = UpdateChecker.check(context)
                        when {
                            info == null -> {
                                status = "检查失败：无法连接更新服务器"
                                phase = UpdPhase.IDLE
                            }
                            info.versionCode <= UpdateChecker.installedVersionCode(context) -> {
                                status = "已是最新版本（服务器 v${info.versionName}）"
                                phase = UpdPhase.IDLE
                            }
                            else -> {
                                status = "发现新版本 v${info.versionName}，下载中…"
                                phase = UpdPhase.DOWNLOADING
                                val f = UpdateChecker.downloadApk(context, info) { rec, tot ->
                                    status = if (tot > 0) {
                                        "下载中 ${rec * 100 / tot}%"
                                    } else {
                                        "下载中 ${rec / 1024 / 1024}MB"
                                    }
                                }
                                if (f != null) {
                                    apkFile = f
                                    phase = UpdPhase.READY
                                    status = "下载完成，点击安装 v${info.versionName}"
                                } else {
                                    status = "下载失败，点击重试"
                                    phase = UpdPhase.IDLE
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            .padding(vertical = 10.dp),
    ) {
        Icon(
            Icons.Default.SystemUpdateAlt,
            contentDescription = null,
            tint = TextPrimary.copy(alpha = 0.9f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("检查更新", color = TextPrimary, fontSize = 14.sp)
            Text(status, color = TextSecondary, fontSize = 11.sp)
        }
        Text(
            when (phase) {
                UpdPhase.READY -> "安装 ›"
                UpdPhase.DOWNLOADING, UpdPhase.CHECKING -> "…"
                else -> "›"
            },
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * Minimal horizontal slider: tap to seek, drag to scrub, double-tap to snap
 * back to [center]. Far simpler than the material slider and gives us the
 * double-tap-to-recenter behavior the camera UI needs.
 */
@Composable
private fun AdjustSlider(
    value: Float,
    center: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val span = range.endInclusive - range.start
    var trackW by remember { mutableStateOf(1) }
    var lastTap by remember { mutableStateOf(0L) }
    val thumbR = with(density) { 6.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val now = System.currentTimeMillis()
                    val x0 = down.position.x.coerceIn(0f, size.width.toFloat())
                    if (now - lastTap < 280) {
                        // double-tap → recenter, then swallow the rest of this gesture
                        onValueChange(center)
                        lastTap = 0L
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.all { it.changedToUp() }) break
                        }
                        return@awaitEachGesture
                    }
                    lastTap = now
                    onValueChange(range.start + (x0 / size.width) * span)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        if (ch.positionChanged()) {
                            ch.consume()
                            val x = ch.position.x.coerceIn(0f, size.width.toFloat())
                            onValueChange(range.start + (x / size.width) * span)
                        }
                        if (ch.changedToUp()) { ch.consume(); break }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { trackW = it.width }
                .height(34.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // base track
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp)),
            )
            // center marker (where the neutral / default value sits)
            val cx = ((center - range.start) / span * trackW).toInt()
            Box(
                Modifier
                    .offset { IntOffset(cx, 0) }
                    .width(2.dp).height(10.dp)
                    .background(Color.White.copy(alpha = 0.5f)),
            )
            // thumb
            val tx = ((value - range.start) / span * trackW).toInt()
            Box(
                Modifier
                    .offset { IntOffset((tx - thumbR).toInt(), 0) }
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun GridOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val stroke = 1.dp.toPx()
                val color = Color.White.copy(alpha = 0.5f)
                drawLine(color, start = Offset(size.width * 0.33f, 0f), end = Offset(size.width * 0.33f, size.height), strokeWidth = stroke)
                drawLine(color, start = Offset(size.width * 0.66f, 0f), end = Offset(size.width * 0.66f, size.height), strokeWidth = stroke)
                drawLine(color, start = Offset(0f, size.height * 0.33f), end = Offset(size.width, size.height * 0.33f), strokeWidth = stroke)
                drawLine(color, start = Offset(0f, size.height * 0.66f), end = Offset(size.width, size.height * 0.66f), strokeWidth = stroke)
            },
    )
}

/**
 * iPhone-style zoom rotor: drag horizontally to change zoom. Ticks are a
 * visual ruler with even screen spacing (0.1x per tick), integer multiples
 * taller/thicker, alpha fades to both ends. NO snap on release — fractional
 * zoom stays put exactly like the pinch gesture. Lives on its own row between
 * the quick controls and the shutter row.
 */
@Composable
private fun ZoomRotor(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }
    val tickStep = 0.1f            // zoom represented by each tick (screen-even spacing)
    val sideTicks = 9             // ticks each side of center → tapered ruler width
    Box(
        modifier = modifier
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val newZoom = (zoom - delta / spacingPx * tickStep).coerceIn(minZoom, maxZoom)
                    onZoom(newZoom)
                },
                // NO snap on release: fractional zoom (1.4x) must stay put like the
                // pinch gesture does. The ticks are a visual ruler, not detents.
            ),
        contentAlignment = Alignment.Center,
    ) {
        // center selector pill behind the live multiplier
        Box(
            Modifier
                .width(46.dp).height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.12f)),
        )
        // live current multiplier (always the live value, centered)
        Text(
            formatZoom(zoom),
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        // tapered ruler: vertical ticks; integer multiples are tallest + thickest,
        // alpha fades to 0 at both ends (cone look). The center position (i==0) is the
        // live value itself, so it is shown as the text above instead of a mark.
        for (i in -sideTicks..sideTicks) {
            if (i == 0) continue
            val tZoom = zoom + i * tickStep
            val edgeFade = (1f - abs(i.toFloat()) / (sideTicks + 1)).coerceIn(0f, 1f)
            val rangeFade = if (tZoom < minZoom || tZoom > maxZoom) 0.12f else 1f
            val alpha = (0.7f * edgeFade * rangeFade).coerceIn(0f, 1f)
            if (alpha <= 0.02f) continue
            val isInt = abs(tZoom - tZoom.roundToInt()) < 0.04f
            val thick = if (isInt) 2.dp else 1.dp
            val tall = if (isInt) 16.dp else 9.dp
            Box(
                Modifier
                    .offset { IntOffset((i * spacingPx).roundToInt(), 0) }
                    .width(thick).height(tall)
                    .background(Color.White.copy(alpha = alpha), shape = RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** Format a zoom multiplier the iPhone way: 1×, 2×, 0.5, 1.4×. */
private fun formatZoom(z: Float): String =
    if (abs(z - 1f) < 0.05f) "1×"
    else if (z < 1f) "%.1f".format(z)
    else if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×"
    else "%.1f".format(z)

@Composable
private fun BottomPanel(
    selected: String,
    onPresetClick: () -> Unit,
    sheetTarget: String?,
    onSheetTarget: (String?) -> Unit,
    timerSec: Int,
    onTimerToggle: () -> Unit,
    onFlip: () -> Unit,
    lastCapture: SavedPhoto?,
    thumbScale: Float,
    shutterScale: Float,
    onGalleryClick: () -> Unit,
    focalMm: Int,
    gridOn: Boolean,
    onGridToggle: () -> Unit,
    zoomX: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 目标 UI：快捷行+快门行整体上移 24dp（bottomReserve 同步 272→248，
            // 取景框底部下探补回高度，缩放条与取景框的间距不变）。
            .padding(bottom = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 目标 UI 顺序（严格比对用户提供的 UI 图）：取景框 → 缩放条 → 快捷行
        // → 快门行。缩放条紧贴取景框下方，快捷行在其下（旧实现把快捷行放在
        // 缩放条上面，顺序与图相反）。
        ZoomRotor(
            zoom = zoomX,
            minZoom = minZoom,
            maxZoom = maxZoom,
            onZoom = onZoom,
            modifier = Modifier
                .width(200.dp)
                .height(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        QuickControls(
            sheetTarget = sheetTarget,
            onSheetTarget = onSheetTarget,
            timerSec = timerSec,
            onTimerToggle = onTimerToggle,
            onFlip = onFlip,
            focalMm = focalMm,
            gridOn = gridOn,
            onGridToggle = onGridToggle,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 目标 UI：预设卡与相册缩略图内收、贴近快门（原 14dp 顶到屏幕
                // 两侧边）。SpaceBetween 对称布局 → padding 加大自然靠近快门。
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Preset selector (left)
            PresetShortcut(
                name = selected.ifEmpty { "预设" },
                onClick = onPresetClick,
            )

            // Shutter (center)
            ShutterButton(onClick = onShutter, scale = shutterScale)

            // Last capture thumbnail (right)
            LastCaptureThumb(lastCapture, onGalleryClick, scale = thumbScale)
        }
    }
}

@Composable
private fun QuickControls(
    sheetTarget: String?,
    onSheetTarget: (String?) -> Unit,
    timerSec: Int,
    onTimerToggle: () -> Unit,
    onFlip: () -> Unit,
    focalMm: Int,
    gridOn: Boolean,
    onGridToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickButton(
            label = "WB",
            icon = Icons.Default.WbSunny,
            selected = sheetTarget == "WB",
            onClick = { onSheetTarget("WB") },
        )
        QuickButton(
            label = "Grain",
            icon = Icons.Default.Grain,
            selected = sheetTarget == "Grain",
            onClick = { onSheetTarget("Grain") },
        )
        // RAW ISP 开关不在快捷行——目标 UI 图的快捷行固定 5 项（WB/Grain/焦距/
        // 计时/翻转），RAW 入口收敛到设置页（rawCapable 才显示，功能不变）。
        // grid toggle (icon) + live focal readout (text below): the icon IS
        // the primary grid switch now (it was non-interactive before, so taps
        // did nothing → "grid button broken"). Selected mirrors QuickButton.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clickable(onClick = onGridToggle)
                .padding(horizontal = 4.dp),
        ) {
            val gridTint = if (gridOn) AccentOrange else TextSecondary
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (gridOn) Color.White.copy(alpha = 0.08f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.GridOn,
                    contentDescription = "网格",
                    tint = gridTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "${focalMm}mm",
                color = if (gridOn) AccentOrange else TextPrimary.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        QuickButton(
            label = if (timerSec == 0) "关" else "${timerSec}s",
            icon = Icons.Default.Timer,
            selected = timerSec > 0,
            onClick = onTimerToggle,
        )
        QuickButton(
            label = "翻转",
            icon = Icons.Default.FlipCameraAndroid,
            selected = false,
            onClick = onFlip,
        )
    }
}

@Composable
private fun QuickButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) AccentOrange else TextSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, color = tint, fontSize = 10.sp)
    }
}

@Composable
private fun PresetShortcut(name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = "预设",
                tint = AccentOrange,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = name.uppercase().takeIf { it.length <= 8 } ?: name.uppercase().take(7) + "…",
                color = TextPrimary,
                fontSize = 8.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit, scale: Float = 1f) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(ShutterRing)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun LastCaptureThumb(photo: SavedPhoto?, onClick: () -> Unit, scale: Float = 1f) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(58.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            // Coil: async MediaStore load with built-in downsampling + caching
            coil.compose.AsyncImage(
                model = photo.uri,
                contentDescription = "最近照片",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "相册",
                tint = TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** Center-crop a bitmap to the given width/height ratio (e.g. 3:4 portrait). */
private fun centerCropToRatio(src: Bitmap, wOverH: Float): Bitmap {
    val cur = src.width.toFloat() / src.height
    val cw: Int
    val ch: Int
    if (cur > wOverH) {
        ch = src.height
        cw = (src.height * wOverH).toInt().coerceAtMost(src.width)
    } else {
        cw = src.width
        ch = (src.width / wOverH).toInt().coerceAtMost(src.height)
    }
    val x = (src.width - cw) / 2
    val y = (src.height - ch) / 2
    return Bitmap.createBitmap(src, x, y, cw, ch)
}

/**
 * DAZZ-pattern zoom crop: centered 1/zoom of the frame — the same fraction
 * the viewfinder capture box shows. Used by the preview-frame fallback path
 * (the main still path crops inside renderBitmapThroughChain).
 */
private fun centerCropZoom(src: Bitmap, zoom: Float): Bitmap {
    val z = zoom.coerceAtLeast(1f)
    if (z <= 1.001f) return src
    val cw = (src.width / z).toInt().coerceIn(64, src.width)
    val ch = (src.height / z).toInt().coerceIn(64, src.height)
    return Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
}
