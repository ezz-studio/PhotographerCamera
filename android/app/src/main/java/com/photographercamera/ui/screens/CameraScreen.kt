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
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.min
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
    var showGrid by remember { mutableStateOf(false) }

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
    // self-timer: 0 = off, else seconds
    var timerSec by remember { mutableIntStateOf(0) }
    var shotPending by remember { mutableStateOf(false) }
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
                "grain=${applied.grainVec[0]} vignette=${applied.vignetteAmount} film=${applied.filmEnabled}",
        )
        previewRef?.setProfile(applied)
    }

    fun saveAndNotify(bmp: Bitmap) {
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
        val eng = engine
        val t0 = android.os.SystemClock.elapsedRealtime()
        DebugLog.log("SHOT", "shutter pressed (zoom=${eng?.zoomRatio}, focal=${eng?.currentEqFocal()})")
        // ISP-encoded full-resolution JPEG through the SAME GPU chain as the
        // preview: the camera's own demosaic/WB runs in hardware (fast, correct
        // on every device), the film look is applied by the profile chain.
        val onStill: (Bitmap) -> Unit = { bmp ->
            if (bmp.width > 1 && bmp.height > 1) {
                // The still was taken with NATIVE zoom (HAL lens calling around
                // the shutter), so it already has the user-zoomed FOV at full
                // sensor resolution - NO CPU crop here (crop would throw away
                // resolution; the old crop-on-CPU path produced 374x499 stills
                // at 5.8x).
                previewRef?.renderBitmapThroughChain(bmp, 1f) { processed ->
                    DebugLog.log(
                        "SHOT",
                        "GPU chain done: ${processed.width}x${processed.height} " +
                            "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                    )
                    saveAndNotify(centerCropToRatio(processed, 3f / 4f))
                }
            } else {
                DebugLog.log("SHOT", "still unavailable — falling back to preview frame")
                previewRef?.captureCurrentFrame {
                    saveAndNotify(centerCropZoom(centerCropToRatio(it, 3f / 4f), eng?.zoomRatio ?: 1f))
                }
            }
        }
        val issued = eng?.captureStill(
            onBitmap = onStill,
            // RAW ISP mode: the untouched Bayer frame is developed on OUR GPU
            // (raw_isp.frag) then the same recipe chain applies. Any failure
            // inside the RAW path degrades to the 1x1-bitmap fallback below.
            onRawFrame = { frame ->
                previewRef?.renderRawThroughChain(frame) { processed ->
                    DebugLog.log(
                        "SHOT",
                        "GPU chain done (RAW): ${processed.width}x${processed.height} " +
                            "(total ${android.os.SystemClock.elapsedRealtime() - t0}ms)",
                    )
                    if (processed.width > 1 && processed.height > 1) {
                        saveAndNotify(centerCropToRatio(processed, 3f / 4f))
                    } else {
                        DebugLog.log("SHOT", "RAW ISP produced nothing - preview frame fallback")
                        previewRef?.captureCurrentFrame {
                            saveAndNotify(centerCropZoom(centerCropToRatio(it, 3f / 4f), eng?.zoomRatio ?: 1f))
                        }
                    }
                }
            },
        ) ?: false
        if (!issued) {
            // Fallback: grab the current preview frame through the GL chain.
            DebugLog.log("SHOT", "captureStill not issued — preview frame fallback")
            previewRef?.captureCurrentFrame {
                    saveAndNotify(centerCropZoom(centerCropToRatio(it, 3f / 4f), eng?.zoomRatio ?: 1f))
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
            runBlocking { ProfileLoader.init(context) }
            profiles.clear(); profiles.addAll(ProfileLoader.listProfiles())
            com.photographercamera.core.debug.DebugLog.log(
                "PROFILE",
                "loaded ${profiles.size}: ${profiles.joinToString()}",
            )
            if (selected.isEmpty() && profiles.isNotEmpty()) {
                selected = profiles.firstOrNull { it.equals("VINTAGE 400", ignoreCase = true) } ?: profiles.first()
            }
        }
    }

    // Read the profile picked from PresetListScreen and apply it.
    LaunchedEffect(Unit) {
        navController.currentBackStackEntry?.savedStateHandle?.get<String>("selected_preset")?.let {
            if (it.isNotEmpty()) selected = it
        }
    }

    LaunchedEffect(selected) {
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

    // Zoom stops for the iPhone-style rotor: nice round marks, clamped to the
    // device's native zoom window (CONTROL_ZOOM_RATIO_RANGE). Keyed on zoomState
    // too: back-navigation recomposes while the engine is still closed, so the
    // stops re-derive once the camera is up.
    val zoomStops = remember(engine, zoomState) {
        val mn = engine?.minZoom() ?: 1f
        val mx = engine?.maxZoom() ?: 5f
        (listOf(mn, 1f, 2f, 3f, 5f, mx))
            .filter { it in mn..mx }
            .toMutableSet().apply { add(1f) }
            .toList().sorted()
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
        val bottomReserve = with(density) { 208.dp.toPx() }  // focal HUD + quick controls + shutter row
        val topReserve = with(density) { 64.dp.toPx() }      // metering capsule row
        val slotH = (maxH - topReserve - bottomReserve).coerceAtLeast(1f)
        val fw = min(maxW * 0.96f, slotH * 3f / 4f)
        val fh = fw * 4f / 3f
        val fx = (maxW - fw) / 2f
        val fy = topReserve + (slotH - fh)                   // anchor to slot bottom

        // ---- inner capture box (user UI design, unchanged) -------------------
        // Zoom technique follows dazz: the zoom is NOT applied to Camera2 —
        // the session runs at its native 1x FOV and the still is CPU-cropped
        // with 1/zoom. The box therefore shows the fraction of the 1x frame
        // the capture covers: f = 1/zoom. The focal HUD derives from the SAME
        // zoom (eq base x zoom, 26mm industry fallback), so the label, the box
        // and the saved photo can never disagree.
        val eqBase = remember(lensEpoch) {
            engine?.mainEq()?.takeIf { it in 18f..40f } ?: 26f
        }
        val fTarget = if (zoomState <= 0f) 1f else (1f / zoomState).coerceIn(0.12f, 1f)
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
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // focus-ring state
            var ringPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
            val ringAlpha = remember { Animatable(0f) }
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

            // metering indicator capsule: top-center of the preview frame
            MeteringIndicator(
                manual = meteringManual,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            )
        }

        if (countdownSec > 0) {
            Text(
                "$countdownSec",
                color = Color.White,
                fontSize = 96.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }

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
            onGridToggle = { showGrid = !showGrid },
            zoomStops = zoomStops,
            zoomX = zoomState,
            minZoom = engine?.minZoom() ?: 0.5f,
            maxZoom = engine?.maxZoom() ?: 5f,
            onZoom = { v ->
                engine?.setZoom(v)
                zoomState = engine?.zoomRatio ?: v
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
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    when (sheetTarget) {
                        "EV" -> {
                            Text("曝光补偿 (EV) · 双击滑块回中", color = TextPrimary, fontSize = 16.sp)
                            AdjustSlider(
                                value = adjEv,
                                center = 0f,
                                range = -2f..2f,
                                onValueChange = { adjEv = it; applyAdjustments() },
                            )
                            Text("${"%.2f".format(adjEv)} EV", color = TextSecondary, fontSize = 13.sp)
                        }
                        "WB" -> {
                            Text("色温 · 双击滑块回中", color = TextPrimary, fontSize = 16.sp)
                            AdjustSlider(
                                value = adjWbTemp,
                                center = 0f,
                                range = -1f..1f,
                                onValueChange = { adjWbTemp = it; applyAdjustments() },
                            )
                            Text("色调 · 双击滑块回中", color = TextPrimary, fontSize = 16.sp)
                            AdjustSlider(
                                value = adjWbTint,
                                center = 0f,
                                range = -1f..1f,
                                onValueChange = { adjWbTint = it; applyAdjustments() },
                            )
                        }
                        "Grain" -> {
                            Text("颗粒强度（相对当前预设）· 双击滑块回中", color = TextPrimary, fontSize = 16.sp)
                            AdjustSlider(
                                value = adjGrain,
                                center = 1f,
                                range = 0f..2f,
                                onValueChange = { adjGrain = it; applyAdjustments() },
                            )
                            Text("×" + "%.2f".format(adjGrain), color = TextSecondary, fontSize = 13.sp)
                        }
                        else -> {
                            Text(
                                "该参数由相机硬件控制，预览管线暂未暴露（后续版本接入）。",
                                color = TextSecondary,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun ZoomRotor(
    stops: List<Float>,
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
            val edgeFade = (1f - kotlin.math.abs(i).toFloat() / (sideTicks + 1)).coerceIn(0f, 1f)
            val rangeFade = if (tZoom < minZoom || tZoom > maxZoom) 0.12f else 1f
            val alpha = (0.7f * edgeFade * rangeFade).coerceIn(0f, 1f)
            if (alpha <= 0.02f) continue
            val isInt = kotlin.math.abs(tZoom - tZoom.roundToInt()) < 0.04f
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
    if (kotlin.math.abs(z - 1f) < 0.05f) "1×"
    else if (z < 1f) "%.1f".format(z)
    else if (kotlin.math.abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}×"
    else "%.1f".format(z)

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
    val thumbR = with(density) { 8.dp.toPx() }
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
                Modifier.fillMaxWidth().height(3.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp)),
            )
            // center marker (where the neutral / default value sits)
            val cx = ((center - range.start) / span * trackW).toInt()
            Box(
                Modifier
                    .offset { IntOffset(cx, 0) }
                    .width(2.dp).height(14.dp)
                    .background(Color.White.copy(alpha = 0.5f)),
            )
            // thumb
            val tx = ((value - range.start) / span * trackW).toInt()
            Box(
                Modifier
                    .offset { IntOffset((tx - thumbR).toInt(), 0) }
                    .size(16.dp)
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
                val color = Color.White.copy(alpha = 0.25f)
                drawLine(color, start = Offset(size.width * 0.33f, 0f), end = Offset(size.width * 0.33f, size.height), strokeWidth = stroke)
                drawLine(color, start = Offset(size.width * 0.66f, 0f), end = Offset(size.width * 0.66f, size.height), strokeWidth = stroke)
                drawLine(color, start = Offset(0f, size.height * 0.33f), end = Offset(size.width, size.height * 0.33f), strokeWidth = stroke)
                drawLine(color, start = Offset(0f, size.height * 0.66f), end = Offset(size.width, size.height * 0.66f), strokeWidth = stroke)
            },
    )
}

/**
 * Metering indicator — translucent capsule at the top-center of the viewfinder.
 * Outline center-focus icon + "平均测光" = whole-frame average metering;
 * filled orange icon + "手动测光" = a manually tapped metering spot is active.
 */
@Composable
private fun MeteringIndicator(manual: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (manual) Icons.Outlined.CenterFocusStrong else Icons.Outlined.CenterFocusWeak,
            contentDescription = if (manual) "手动测光" else "平均测光",
            tint = if (manual) AccentOrange else TextPrimary.copy(alpha = 0.85f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (manual) "手动测光" else "平均测光",
            color = TextPrimary.copy(alpha = 0.9f),
            fontSize = 11.sp,
        )
    }
}

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
    zoomStops: List<Float>,
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
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // flat HUD above the zoom rotor: grid toggle + focal readout
        // (no zoom multiplier here — the rotor already shows it)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp),
        ) {
            Icon(
                Icons.Default.GridOn,
                contentDescription = "网格",
                tint = if (gridOn) AccentOrange else TextPrimary.copy(alpha = 0.75f),
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onGridToggle),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${focalMm}mm",
                color = TextPrimary.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        QuickControls(
            sheetTarget = sheetTarget,
            onSheetTarget = onSheetTarget,
            timerSec = timerSec,
            onTimerToggle = onTimerToggle,
            onFlip = onFlip,
            zoomStops = zoomStops,
            zoomX = zoomX,
            minZoom = minZoom,
            maxZoom = maxZoom,
            onZoom = onZoom,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
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
    zoomStops: List<Float>,
    zoomX: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
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
        // iPhone-style physical zoom rotor sits in the CENTER of the button row
        ZoomRotor(
            stops = zoomStops,
            zoom = zoomX,
            minZoom = minZoom,
            maxZoom = maxZoom,
            onZoom = onZoom,
            modifier = Modifier.width(160.dp).height(40.dp),
        )
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
