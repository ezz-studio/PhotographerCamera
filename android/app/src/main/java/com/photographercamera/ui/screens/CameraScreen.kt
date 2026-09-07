package com.photographercamera.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MotionPhotosOn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import android.hardware.camera2.CameraCharacteristics
import com.photographercamera.photon.viewmodel.CameraViewModel
import com.photographercamera.photon.camera.LensType
import com.photographercamera.photon.camera.MeteringMode
import com.photographercamera.photon.ui.camera.CameraPreviewGL
import com.photographercamera.photon.lut.LutManager
import com.photographercamera.core.photon.color.ProfileToRecipeMapper
import com.photographercamera.core.debug.DebugLog
import com.photographercamera.core.profile.ProfileLoader
import com.photographercamera.core.storage.CaptureSaver
import com.photographercamera.core.storage.CaptureSaver.SavedPhoto
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

    // ---- photon 引擎接线（CameraViewModel + Camera2Controller）------------
    val pvm: CameraViewModel = viewModel()
    val state by pvm.state.collectAsState()
    val isCameraInitialized by pvm.isInitialized.collectAsState()
    val currentLutId by pvm.currentLutId.collectAsState()
    val currentRecipeParams by pvm.currentRecipeParams.collectAsState()
    val currentBaselineRecipeParams by pvm.currentBaselineRecipeParams.collectAsState()
    val calibrationOffset by pvm.getCameraOrientationOffset(state.currentCameraId)
        .collectAsState(initial = 0)

    // SurfaceTexture → openCamera 接线（照搬上游 CameraScreen 模式）
    var previewSurfaceTexture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var isCameraPrepared by remember { mutableStateOf(false) }
    LaunchedEffect(isCameraInitialized) {
        isCameraPrepared = isCameraInitialized && pvm.prepareCamera()
    }
    LaunchedEffect(isCameraInitialized, isCameraPrepared, previewSurfaceTexture) {
        val st = previewSurfaceTexture ?: return@LaunchedEffect
        if (!isCameraInitialized || !isCameraPrepared) return@LaunchedEffect
        pvm.openCamera(st)
    }

    // CAMERA is mandatory; on legacy devices (API <= 28) MediaStore saving also
    // needs READ/WRITE_EXTERNAL_STORAGE — requested together, camera gate wins.
    val neededPerms = buildList {
        add(Manifest.permission.CAMERA)
        // 0.9.8 修复：Live Photo / 视频录制需要麦克风权限录制音轨，否则
        // LivePhotoRecorder.initAudio 提前返回 → 只有图片没有视频。
        add(Manifest.permission.RECORD_AUDIO)
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
    // 闪光灯三态：0=关 1=开 2=手电（由 photon state.flashMode 驱动）
    val flashMode = state.flashMode

    // RAW ISP 开关（实验性功能，0.3.5 起默认关；目标架构：设备支持 RAW_SENSOR
    // 才显示该键）。持久化在 pc_settings.raw_isp_enabled（CameraEngine.setRawIspEnabled
    // 同步写入并 rebind；0.3.5 迁移会把历史遗留的 true 一次性重置为关）。
    val sp = context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
    var rawOn by remember { mutableStateOf(sp.getBoolean("raw_isp_enabled", false)) }
    // 引擎 useRaw 状态回流顶栏（上游互斥矩阵会改写 useRaw——如 RAWmax/多次曝光
    // 联动；不回流则顶栏显示与引擎实际状态脱节）
    LaunchedEffect(state.useRaw) { rawOn = state.useRaw }

    // 手动测光态：非系统默认测光模式即视为手动（用于 EV 滑块显隐）
    val meteringManual = state.meteringMode != MeteringMode.SYSTEM_DEFAULT

    // 0.6.0 测光模式（顶栏图标循环切换，引擎侧同步应用 AE 区域）。
    val meteringMode = state.meteringMode

    // LIVE 图开关（0.7.3，指导手册 #2）：顶栏开关，持久化 sp.use_live_photo。
    // 功能接线（LivePhotoRecorder 并发录制）随引擎切换轮落地；本开关先占位，
    // 打开时 toast 提示"将在下版生效"。
    var liveOn by remember {
        mutableStateOf(
            context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("use_live_photo", false),
        )
    }
    // 0.8.3 冷启动同步：把持久化的开关状态灌入 photon 引擎（并发录制 + 拍摄偏好）
    // 0.9.0 扩展：设置页接线开关的 sp → 引擎 DataStore 幂等迁移（旧安装升级对齐）
    LaunchedEffect(Unit) {
        pvm.setUseLivePhoto(liveOn)
        val sp = context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)
        pvm.setShutterSoundEnabled(sp.getBoolean("shutter_sound", true))
        pvm.setVibrationEnabled(sp.getBoolean("capture_vibrate", true))
        pvm.setVolumeKeyAction(
            when (sp.getString("volume_key_function", "拍照") ?: "拍照") {
                "变焦" -> com.photographercamera.photon.data.VolumeKeyAction.ZOOM
                "无" -> com.photographercamera.photon.data.VolumeKeyAction.NONE
                else -> com.photographercamera.photon.data.VolumeKeyAction.CAPTURE
            }
        )
        pvm.setEyeFocusEnabled(sp.getBoolean("face_focus", false))
        pvm.setEnableLogicalMultiCameraDiscovery(
            sp.getBoolean("logical_multi_camera_discovery", false)
        )
        pvm.setLensIdBlacklist(sp.getString("lens_binding_blacklist", "") ?: "")
        sp.getString("macro_camera_id", "auto")?.let { id ->
            pvm.setPreferredMacroCameraId(id.takeIf { it != "auto" })
        }
        pvm.setDefaultFocalLength(sp.getFloat("default_focal_length", 0f))
        pvm.setUseP010(sp.getBoolean("use_p010", false))
        pvm.setUseP3ColorSpace(sp.getBoolean("use_p3_color_space", false))
        pvm.setSaveLocation(sp.getBoolean("save_location", false))
        pvm.setMirrorFrontCamera(sp.getBoolean("front_mirror", false))
    }

    // total zoom across lenses (mirrors photon state for recomposition)
    var zoomState by remember { mutableFloatStateOf(1f) }
    // 跟随 photon state.zoomRatio（镜头枚举由 photon 内部完成，state 已含真实数据）
    LaunchedEffect(state.zoomRatio) { zoomState = state.zoomRatio }
    // RAW 能力 = photon 当前镜头是否支持 RAW_SENSOR
    val rawCapable = state.isRawSupported
    // self-timer: 0 = off, else seconds
    var timerSec by remember { mutableIntStateOf(0) }
    var shotPending by remember { mutableStateOf(false) }
    // 0.9.0 快门音改引擎级（ShutterSoundPlayer + capture 请求 playShutterSound，
    // 与上游一致；设置页开关走 pvm.setShutterSoundEnabled），UI 层 MediaActionSound 移除
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
        // 0.9.0 快门音 + 拍摄震动均由引擎级回调（onPlayShutterSound → ShutterSoundPlayer /
        // vibrationHelper）播放，与上游一致；设置页开关走 pvm.setShutterSoundEnabled /
        // setVibrationEnabled，UI 层重复反馈已移除。
    }

    // ---- zoom soft-snap（照搬上游 ContinuousZoomStopSettlement 语义）--------
    // 档位源 = VM allZoomStops（镜头固有倍率 + 自定义焦段；zoomSteps 字段两边
    // 都是死字段不可用）。软吸附：距档位 ≤0.05 才吸，绝不跨镜头切换（0.8.3
    // 架构：HAL 自动路由物理镜头，小步吸附不会换摄；switchToLensAndSetZoomRatio
    // 会触发 session 重建=0.8.2 乒乓灾难，吸附热路径禁用）。
    val zoomStops = remember(state.availableCameras, state.currentCameraId) {
        val cam = state.getCurrentCameraInfo()
        val main = state.availableCameras.firstOrNull {
            it.lensType != LensType.FRONT && it.lensType != LensType.BACK_MACRO
        }
        pvm.allZoomStops(pvm.calculateLensZoomStops(state.availableCameras, cam), main, cam)
    }
    val settleZoomStop = {
        val snap = settleContinuousZoomStop(zoomStops, pvm.zoomRatioByMain).snapZoomStop
        if (snap != null) pvm.setZoomRatio(snap)
    }
    var wasZooming by remember { mutableStateOf(false) }
    // 捏合停手 2s 后软吸附（对齐上游 ZoomControlBar 语义：拖拽条松手立即吸，捏合停手 2s 吸）
    LaunchedEffect(pvm.isZooming) {
        val was = wasZooming
        wasZooming = pvm.isZooming
        if (was && !pvm.isZooming) {
            delay(2000)
            settleZoomStop()
        }
    }
    // 引擎保存完成事件 → 实时刷新右下角缩略图（引擎写 DCIM/PhotonCamera，
    // CaptureSaver.list 已修复覆盖该目录；事件驱动，无轮询）。
    LaunchedEffect(Unit) {
        pvm.imageSavedEvent.collect {
            val latest = CaptureSaver.list(context).firstOrNull()
            if (latest != null) {
                lastCapture = latest
                triggerCaptureFeedback()
                thumbAnim.snapTo(0.6f)
                thumbAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
        }
    }

    // Live QuickControl adjustments (WB / Grain) applied on top of the chosen preset.
    var sheetTarget by remember { mutableStateOf<String?>(null) }
    // 0.9.3 EV：滑条始终可用，直接叠加在 profile 基准曝光上
    //（原 0.8.2 AE-L 门控已按用户指令整体移除）。
    var adjEv by remember { mutableFloatStateOf(0f) }
    // 0.8.2 WB 绝对值滑条：色温真实开尔文、色调 ±20（引擎 CCT/tint 语义）；
    // AWB 关闭瞬间以引擎冻结的实测值为起点（LaunchedEffect 同步）。
    var adjWbTemp by remember { mutableFloatStateOf(5000f) }
    var adjWbTint by remember { mutableFloatStateOf(0f) }
    // 0.6.0 AWB 开关：开启=相机自动白平衡，色温/色调滑块灰置不可调；
    // 关闭=用户接管（GPU 后段相对调整），滑块可用。持久化到 pc_settings。
    var awbOn by remember { mutableStateOf(sp.getBoolean("awb_on", true)) }
    // Grain is a MULTIPLIER on the profile's own grain amount: 1.0 = keep the
    // preset's grain character unchanged, 0 = no grain, 2 = double it.
    var adjGrain by remember { mutableFloatStateOf(1f) }

    val sheetState = rememberModalBottomSheetState()

    // AWB 关闭瞬间：滑块以引擎冻结的实测色温/色调为起点（绝对值语义）
    LaunchedEffect(awbOn) {
        if (!awbOn) {
            val s = pvm.state.value
            adjWbTemp = s.awbTemperature.toFloat()
            adjWbTint = s.awbTint.toFloat()
        }
    }

    // 风格注入：把选中的 profile 通过 ProfileToRecipeMapper 映射到 ColorRecipeParams，
    // 写入 LutManager（按 lutId 存 DataStore），再 pvm.setLut 让预览+成片套用。
    // 原 adjEv/adjWbTemp/adjWbTint/adjGrain 滑块仅作 UI 占位（EV/WB/grain 已由 recipe 覆盖）。
    fun applyAdjustments() {
        if (selected.isEmpty()) {
            // 未选 profile：halation/grain 等单例通道回默认，防上一 profile 残留
            com.photographercamera.core.photon.color.FilmParamsStore.reset()
            return
        }
        val profile = ProfileLoader.getProfile(selected) ?: run {
            com.photographercamera.core.debug.DebugLog.log("PROFILE", "getProfile('$selected') returned null")
            return
        }
        val mapping = ProfileToRecipeMapper.map(profile)
        // 快捷面板叠加：EV（recipe 曝光偏移，始终生效）与 Grain 乘数
        val adjustedRecipe = mapping.recipe.copy(
            exposure = mapping.recipe.exposure + adjEv,
            filmGrain = mapping.recipe.filmGrain * adjGrain,
        )
        val lutId = "profile:$selected"
        com.photographercamera.core.debug.DebugLog.log(
            "PROFILE",
            "inject '$selected' -> lut=$lutId grain=${adjustedRecipe.filmGrain} " +
                "wb=(${adjustedRecipe.temperature},${adjustedRecipe.tint}) ev=${adjustedRecipe.exposure}",
        )
        scope.launch {
            try {
                val lm = LutManager(context)
                // 类型转换：core.photon.color.ColorRecipeParams → photon.model.ColorRecipeParams
                // 两者字段完全一致（同源移植），用 JSON 序列化桥接。
                val photonRecipe = com.photographercamera.photon.model.ColorRecipeParams
                    .fromJson(adjustedRecipe.toJson())
                lm.saveColorRecipeParams(lutId, photonRecipe)
                // lens 光学阶段参数（distortion/falloff/vignette/bloom/flare）——
                // 预览 LutRenderer 与成片 LutImageProcessor 从单例读取
                com.photographercamera.core.photon.lens.LensParamsStore.current = mapping.residual.lens
                // 0.9.1 film/halation/grain 参数单例下发：halation 强度经 JSON 桥
                // 会被上游 toJson() 强制抹零（兼容设计），film_curve 端点/grain 颗粒
                // 度/原色矩阵/阴影饱和度在 recipe 无字段——经 FilmParamsStore 补通
                com.photographercamera.core.photon.color.FilmParamsStore.current =
                    com.photographercamera.core.photon.color.FilmParamsStore.FilmParams(
                        halationStrength = mapping.residual.halationAmount,
                        halationRadius = mapping.residual.halationRadius,
                        halationThreshold = mapping.residual.halationThreshold,
                        halationWarmth = mapping.residual.halationWarmth,
                        grainSize = mapping.residual.grainSize,
                        grainDensity = mapping.residual.grainDensity,
                        shadowSaturation = mapping.residual.shadowSaturation,
                        filmCurveShadowFloor = mapping.residual.filmCurveShadowFloor,
                        filmCurveHighlightCeiling = mapping.residual.filmCurveHighlightCeiling,
                        colorMatrix3x3 = mapping.residual.colorMatrix3x3,
                        // 0.9.2：film_curve 消费端门控标记（默认端点非恒等，须显式区分）
                        profileActive = true,
                    )
                pvm.setLut(lutId)
            } catch (t: Throwable) {
                com.photographercamera.core.debug.DebugLog.logError("PROFILE", "recipe inject failed for '$selected'", t)
            }
        }
    }

    fun doCapture() {
        if (capturing) {
            DebugLog.log("SHOT", "capture already in progress — ignored (double-shutter guard)")
            return
        }
        capturing = true
        // 安全兜底：guard 绑定本次拍摄（0.8.3 修复——旧实现 8s 后无条件复位，
        // 会在下一次拍摄进行中误杀 capturing，表现为"拍了但没照片，只有动画"）。
        val guardJob = scope.launch {
            kotlinx.coroutines.delay(8000)
            if (capturing) {
                DebugLog.log("SHOT", "capture guard timeout — auto reset")
                capturing = false
            }
        }
        DebugLog.log("SHOT", "shutter pressed (zoom=${state.zoomRatio}, focal=${state.getCurrentCameraInfo()?.focalLength35mmEquivalent ?: 26f})")
        // photon 引擎全链路拍照：多帧融合/RAW 开发/色彩配方/MediaStore 保存
        // 全部由 CameraViewModel.capture() 内部完成，UI 仅触发快门动画。
        // 0.9.8 修复：处理中动画的停止与"存入相册闪屏"改由 imageSavedEvent 驱动，
        // 与真实存储进度严格对齐（旧实现固定 delay(1200)，动画早停很久才落盘）。
        scope.launch {
            try {
                pvm.capture()
            } catch (t: Throwable) {
                DebugLog.logError("SHOT", "photon capture failed", t)
            } finally {
                guardJob.cancel()
            }
        }
    }

    // 0.9.8 修复：存储完成事件驱动——停止处理中动画 + 播放存入相册闪屏，
    // 与真实落盘进度严格对齐（详见上方 doCapture 说明）。
    LaunchedEffect(Unit) {
        pvm.imageSavedEvent.collect {
            capturing = false
            triggerCaptureFeedback()
        }
    }

    // 0.6.0 音量键：MainActivity 经 VolumeKeyBus 派发（拍照=doCapture 全语义，
    // 含连拍守卫/计时器；变焦=乘除 1.2 步进）。doCapture 闭包捕获的均为
    // remember 的稳定 State 引用，读取即时值，无陈旧闭包问题。
    DisposableEffect(Unit) {
        com.photographercamera.core.util.VolumeKeyBus.onCapture = { doCapture() }
        com.photographercamera.core.util.VolumeKeyBus.onZoomStep = { zoomIn ->
            val cur = zoomState
            val next = if (zoomIn) cur * 1.2f else (cur / 1.2f)
            pvm.setZoomRatio(next)
        }
        onDispose {
            com.photographercamera.core.util.VolumeKeyBus.onCapture = null
            com.photographercamera.core.util.VolumeKeyBus.onZoomStep = null
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

    // Keyed on isCameraInitialized AND profiles.size: photon 引擎就绪后即可注入风格。
    LaunchedEffect(selected, isCameraInitialized, profiles.size) {
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
                // 0.9.2 修复（黑屏 Bug）：后台/锁屏期间 GL surface 销毁会触发
                // closeCamera，或系统强制断开相机（onDisconnected）。回前台后
                // 重开链 LaunchedEffect(isCameraInitialized, isCameraPrepared,
                // previewSurfaceTexture) 三个 key 均未变化 → 不会重跑 → 预览黑屏，
                // 只能切换摄像头恢复。ON_RESUME 显式补开；VM.openCamera 对
                // "同 SurfaceTexture 且预览活跃/正在打开"自带幂等跳过，可安全重复调用。
                if (isCameraInitialized && isCameraPrepared) {
                    previewSurfaceTexture?.let { pvm.openCamera(it) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // release the camera — photon VM owns the Camera2Controller lifecycle
            pvm.closeCamera()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DarkBackground)
            // two-finger pinch zoom — cross-lens, mirrored in the in-frame HUD.
            // 基准用 VM 同步值 zoomRatioByMain（上游 CameraScreen:1082 同款）：
            // 避免 StateFlow 异步回环（zoomState 滞后 1-2 帧）在高报点率下
            // 丢增量 → 欠冲"不跟手"。
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) {
                        pvm.isZooming = true
                        pvm.setZoomRatio(pvm.zoomRatioByMain * zoom)
                    }
                }
            }
            // 手势结束检测：双指全部抬起 → isZooming=false（上游 awaitEachGesture
            // 同款），驱动 2s 延迟软吸附计时。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var sawPinch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) sawPinch = true
                        if (event.changes.all { !it.pressed }) break
                    }
                    if (sawPinch) pvm.isZooming = false
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
        // 取景框表示"实际拍摄画面"：预览 GL 在 >1x 钳到 1x 显示广角静止画面
        // （见 Camera2Controller.applyZoomRequestSettings forPreview），取景框则按
        // 1/zoomRatio 缩小，范围与成片 CONTROL_ZOOM_RATIO / SCALER_CROP_REGION 裁切
        // 完全一致；≤1x 取景框不缩放（预览即拍摄框，所见即所得）。
        val camInfo = state.getCurrentCameraInfo()
        val eqBase = camInfo?.focalLength35mmEquivalent?.takeIf { it in 18f..40f } ?: 26f
        // 取景框 = 实际拍摄画面：>1x 时缩放到 1/zoomRatio（与 CONTROL_ZOOM_RATIO /
        // SCALER_CROP_REGION 实际裁切一致——预览 GL 在 >1x 钳到 1x 显示广角，
        // 故取景框范围即真实裁切范围）；≤1x 不缩放（预览即拍摄框，所见即所得）。
        val f = if (zoomState > 1.001f) (1f / zoomState) else 1f
        val bw = fw * f
        val bh = fh * f
        val bx = fx + (fw - bw) / 2f
        val by = fy + (fh - bh) / 2f

        // vignette 由 photon CameraPreviewGL 内部 GL 管线处理（ColorRecipeParams.vignette），
        // 不再需要外部 setVignetteWindow 调用。

        // ---- preview surface container = the default viewfinder frame -------
        Box(
            Modifier
                .offset { IntOffset(fx.roundToInt(), fy.roundToInt()) }
                .size(with(density) { fw.toDp() }, with(density) { fh.toDp() }),
        ) {
            CameraPreviewGL(
                aspectRatio = state.getPreviewAspectRatio(),
                previewSize = state.currentPreviewSize,
                captureSize = state.currentCaptureSize,
                captureMode = state.captureMode,
                sensorOrientation = state.getCurrentCameraInfo()?.sensorOrientation ?: 0,
                lensFacing = if (state.getCurrentCameraInfo()?.lensFacing == CameraCharacteristics.LENS_FACING_FRONT) 0 else 1,
                calibrationOffset = calibrationOffset,
                baselineLut = pvm.currentBaselineLutConfig,
                currentLut = pvm.currentLutConfig,
                baselineColorRecipeParams = currentBaselineRecipeParams,
                colorRecipeParams = currentRecipeParams,
                focusPoint = state.focusPoint,
                focusPointSource = state.focusPointSource,
                isFocusLocked = state.isFocusLocked,
                isFocusing = state.isFocusing,
                focusSuccess = state.focusSuccess,
                meteringMode = state.meteringMode,
                onSurfaceTextureReady = { previewSurfaceTexture = it },
                onSurfaceDestroyed = {
                    if (previewSurfaceTexture === it) previewSurfaceTexture = null
                    pvm.closeCamera(it)
                },
                onTap = { x, y, w, h ->
                    if (state.isFocusLocked) pvm.unlockFocus()
                    else pvm.focusOnPoint(x, y, w, h)
                },
                onLongPress = { x, y, w, h -> pvm.lockFocusOnPoint(x, y, w, h) },
                onGLSurfaceViewReady = { pvm.glSurfaceView = it },
                livePhotoRecorder = pvm.livePhotoRecorder,
                isAutoFocus = state.isAutoFocus,
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
                                // photon 对焦：像素坐标 + 视口尺寸
                                pvm.focusOnPoint(off.x, off.y, size.width, size.height)
                                pvm.setExposureCompensation(0)
                                evFrac = 0f
                                evRange = state.getExposureCompensationRange().let { Pair(it.lower, it.upper) }
                            },
                            onLongPress = { pvm.unlockFocus() },
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
                (evRange?.let { it.second - it.first > 0 } == true)
            ) {
                val ring = ringPos!!
                val range = evRange ?: Pair(0, 0)
                val span = range.second - range.first
                val evStep = state.getExposureCompensationStep()
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
                                    pvm.setExposureCompensation((evFrac * span / 2f).roundToInt())
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

        // top bar: EV / flash / RAW / metering / settings — sits in the
        // reserved strip ABOVE the viewfinder frame
        TopBar(
            flashMode = flashMode,
            rawCapable = rawCapable,
            rawOn = rawOn,
            meteringMode = meteringMode,
            liveOn = liveOn,
            onFlashToggle = {
                pvm.toggleFlash()
            },
            onEvClick = { sheetTarget = "EV" },
            onRawToggle = {
                rawOn = !rawOn
                if (rawOn) {
                    // RAW 与动态照片互斥：开启 RAW 时关闭动态照片（避免并发占用相机
                    // 在部分机型上触发 ERROR_CAMERA_DISABLED 导致无法保存）
                    liveOn = false
                    sp.edit().putBoolean("use_live_photo", false).apply()
                    pvm.setUseLivePhoto(false)
                }
                sp.edit().putBoolean("raw_isp_enabled", rawOn).apply()
                pvm.setUseRaw(rawOn)
                Toast.makeText(
                    context,
                    if (rawOn) "RAW（实验性功能）已开启" else "RAW 已关闭",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onMeteringClick = {
                // 循环测光模式（对齐 photon MeteringMode 枚举）
                val order = listOf(
                    MeteringMode.SYSTEM_DEFAULT,
                    MeteringMode.CENTER_WEIGHTED,
                    MeteringMode.SPOT,
                    MeteringMode.AVERAGE,
                    MeteringMode.HIGHLIGHT_PRIORITY,
                )
                val next = order[(order.indexOf(state.meteringMode) + 1) % order.size]
                pvm.setMeteringMode(next)
                Toast.makeText(context, "测光：${meteringLabel(next)}", Toast.LENGTH_SHORT).show()
            },
            onLiveToggle = {
                liveOn = !liveOn
                if (liveOn) {
                    // 动态照片与 RAW 互斥：开启动态照片时关闭 RAW（避免并发占用相机
                    // 在部分机型上触发 ERROR_CAMERA_DISABLED 导致无法保存）
                    rawOn = false
                    sp.edit().putBoolean("raw_isp_enabled", false).apply()
                    pvm.setUseRaw(false)
                }
                sp.edit().putBoolean("use_live_photo", liveOn).apply()
                // 0.8.3 实装：驱动 photon 引擎并发录制 + 拍摄链路偏好（DataStore）
                pvm.setUseLivePhoto(liveOn)
                Toast.makeText(
                    context,
                    if (liveOn) "动态照片已开启" else "动态照片已关闭",
                    Toast.LENGTH_SHORT,
                ).show()
            },
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

        // 0.8.2 变焦范围用全局值（跨镜头连续变焦 0.6x~10x），不再取当前相机的局部范围
        val minZoom = pvm.globalMinZoom
        val maxZoom = pvm.globalMaxZoom
        BottomPanel(
            selected = selected,
            onPresetClick = { navController.navigate("presets") },
            sheetTarget = sheetTarget,
            onSheetTarget = { sheetTarget = it },
            timerSec = timerSec,
            onTimerToggle = { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 } },
            onFlip = { pvm.switchCamera() },
            lastCapture = lastCapture,
            // 0.9.9：处理中缩略图显示第一帧原始预览（VM 快门早期 generateThumbnail 产出）
            processingPreview = pvm.previewThumbnail,
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
            onZoom = { z ->
                pvm.setZoomRatio(z)
            },
            zoomBase = { pvm.zoomRatioByMain },
            onZoomStart = { pvm.isZooming = true },
            onZoomSettle = {
                pvm.isZooming = false
                settleZoomStop()
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
            isProcessing = capturing,
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
                            Spacer(Modifier.height(6.dp))
                            AdjustSlider(
                                value = adjEv,
                                center = 0f,
                                range = -2f..2f,
                                onValueChange = { adjEv = it; applyAdjustments() },
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${"%.2f".format(adjEv)} EV",
                                color = TextSecondary,
                                fontSize = 13.sp,
                            )
                        }
                        "WB" -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "AWB 自动白平衡",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = awbOn,
                                    onCheckedChange = {
                                        awbOn = it
                                        sp.edit().putBoolean("awb_on", it).apply()
                                        pvm.setAwbMode(
                                            if (it) android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
                                            else android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF
                                        )
                                    },
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                // 0.9.8 修复：AUTO(AWB 开) 下显示相机实测色温（actualAwbTemperature），
                                // 默认不再显示静态 5000K；手动(AWB 关) 才用冻结的 adjWbTemp。
                                "色温 ${(if (awbOn) (state.actualAwbTemperature ?: state.awbTemperature) else adjWbTemp.roundToInt())}K",
                                color = if (awbOn) TextSecondary else TextPrimary,
                                fontSize = 13.sp,
                            )
                            AdjustSlider(
                                // 0.9.8 修复：AUTO 下滑块值跟随实测色温（仅显示，滑块灰置）
                                value = if (awbOn) (state.actualAwbTemperature ?: state.awbTemperature).toFloat() else adjWbTemp,
                                center = 5000f,
                                // 0.9.1 对齐引擎常量 AWB_TEMPERATURE_MIN/MAX = 2000..8000
                                //（旧 2500..9900 右端 1900K 是引擎钳制死区，拖动无效）
                                range = 2000f..8000f,
                                enabled = !awbOn && state.canAdjustWhiteBalance,
                                onValueChange = {
                                    adjWbTemp = it
                                    pvm.setAwbTemperature(it.roundToInt())
                                },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "色调 " + (if (adjWbTint > 0f) "+" else "") + "${adjWbTint.roundToInt()}",
                                color = if (awbOn) TextSecondary else TextPrimary,
                                fontSize = 13.sp,
                            )
                            AdjustSlider(
                                value = adjWbTint,
                                center = 0f,
                                range = -20f..20f,
                                enabled = !awbOn && state.canAdjustWhiteBalance,
                                onValueChange = {
                                    adjWbTint = it
                                    pvm.setAwbTint(it.roundToInt())
                                },
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

        // 0.6.0 全屏设置页（PhotonCamera 风格）替代底部半透明弹层；
        // RAW 开关已迁至顶栏，构图网格开关移除（功能重复）。
        // 0.7.2: 系统返回键拦截——设置/调试层打开时返回只关闭当前层，
        // 不再把整个 APP 退回桌面。
        androidx.activity.compose.BackHandler(enabled = showSettings) {
            showSettings = false
        }
        if (showSettings) {
            AppSettingsScreen(
                onDismiss = { showSettings = false },
                onDebugClick = { showSettings = false; showDebug = true },
                // 0.8.3：接 photon 引擎（镜头列表 / 微距 ID 选项 / 引擎就绪状态）
                photonVm = pvm,
            )
        }

        if (showDebug) {
            androidx.activity.compose.BackHandler(enabled = true) { showDebug = false }
            DebugConnectDialog(onDismiss = { showDebug = false })
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

/**
 * Top bar above the viewfinder: EV sheet entry, 3-state flash toggle, metering
 * indicator (STATUS ONLY — average = outline icon, manual tap = filled orange)
 * and the settings entry. Evenly spaced, per the target UI.
 */
/** 0.6.0 测光模式 → 图标/文案（循环切换用，语义对齐仓库测光设置）。 */
private fun meteringIcon(mode: MeteringMode) = when (mode) {
    MeteringMode.SYSTEM_DEFAULT -> Icons.Outlined.CenterFocusWeak      // 系统默认
    MeteringMode.CENTER_WEIGHTED -> Icons.Default.Adjust               // 中央重点
    MeteringMode.AVERAGE -> Icons.Default.BlurOn                       // 平均测光
    MeteringMode.HIGHLIGHT_PRIORITY -> Icons.Default.WbSunny           // 高光优先
    MeteringMode.SPOT -> Icons.Default.MyLocation                      // 点测光
}

private fun meteringLabel(mode: MeteringMode) = when (mode) {
    MeteringMode.SYSTEM_DEFAULT -> "系统默认"
    MeteringMode.CENTER_WEIGHTED -> "中央重点"
    MeteringMode.AVERAGE -> "平均测光"
    MeteringMode.HIGHLIGHT_PRIORITY -> "高光优先"
    MeteringMode.SPOT -> "点测光"
}

@Composable
private fun TopBar(
    flashMode: Int,
    rawCapable: Boolean,
    rawOn: Boolean,
    meteringMode: MeteringMode,
    liveOn: Boolean,
    onFlashToggle: () -> Unit,
    onEvClick: () -> Unit,
    onRawToggle: () -> Unit,
    onMeteringClick: () -> Unit,
    onLiveToggle: () -> Unit,
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
            // photon flashMode: 0=关, 1=开, 2=手电
            val icon = when (flashMode) {
                1 -> Icons.Default.FlashOn
                2 -> Icons.Default.FlashAuto
                else -> Icons.Default.FlashOff
            }
            val tint = if (flashMode == 0) {
                TextPrimary.copy(alpha = 0.9f)
            } else AccentOrange
            Icon(icon, contentDescription = "闪光灯", tint = tint, modifier = Modifier.size(22.dp))
        }
        // 0.6.0 RAW 开关从设置迁入顶栏（仅 RAW-capable 设备显示）
        if (rawCapable) {
            IconButton(onClick = onRawToggle, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "RAW",
                    tint = if (rawOn) AccentOrange else TextPrimary.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 0.7.3 LIVE 图开关（指导手册 #2）：拍摄同时录制动态照片短视频
        IconButton(onClick = onLiveToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Default.MotionPhotosOn,
                contentDescription = "动态照片",
                tint = if (liveOn) AccentOrange else TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
        // 0.6.0 测光模式：点击循环切换（系统默认→中央重点→平均→高光优先→点测）
        IconButton(onClick = onMeteringClick, modifier = Modifier.size(40.dp)) {
            Icon(
                meteringIcon(meteringMode),
                contentDescription = "测光：${meteringLabel(meteringMode)}",
                tint = if (meteringMode == MeteringMode.SYSTEM_DEFAULT) {
                    TextPrimary.copy(alpha = 0.85f)
                } else AccentOrange,
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

/** 0.4.0 "检查更新"行状态机：IDLE→(检查)→(下载)→READY→拉起系统安装器。 */
private enum class UpdPhase { IDLE, CHECKING, DOWNLOADING, READY }

/**
 * 设置页"检查更新"：查询更新服务器 /api/version，发现新版本直接流式下载到
 * 应用缓存（进度实时显示），完成后再次点击拉起系统包安装器。全部逻辑走
 * UpdateChecker（core/update），UI 只做状态呈现——符合架构功能最小 UI 入口。
 */
@Composable
internal fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(UpdPhase.IDLE) }
    var status by remember {
        mutableStateOf("当前 v" + UpdateChecker.installedVersionName(context))
    }
    var apkFile by remember { mutableStateOf<File?>(null) }

    // 0.7.1: DownloadManager 托管下载——进程被杀/黑屏后重进本页时续接状态。
    LaunchedEffect(Unit) {
        // 0.9.5：更新已装好（或无挂起下载）时清掉下载目录残留安装包，
        // 满足"安装完成后旧安装包不留存"。
        val pending = UpdateChecker.pendingDownload(context)
        if (pending == null) {
            UpdateChecker.purgeDownloadedApks(context)
            return@LaunchedEffect
        }
        if (pending.second <= UpdateChecker.installedVersionCode(context)) {
            UpdateChecker.clearDownloadState(context)
            UpdateChecker.purgeDownloadedApks(context)
            return@LaunchedEffect
        }
        val (id, code) = pending
        phase = UpdPhase.DOWNLOADING
        status = "恢复下载（系统下载器接管）…"
        val st = UpdateChecker.queryDownload(context, id)
        when {
            st == null -> { UpdateChecker.clearDownloadState(context); phase = UpdPhase.IDLE }
            st.status == android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                apkFile = UpdateChecker.downloadedFileNow(context, id)
                if (apkFile != null) {
                    phase = UpdPhase.READY
                    status = "下载完成，点击安装"
                } else {
                    UpdateChecker.clearDownloadState(context); phase = UpdPhase.IDLE
                }
            }
            st.status == android.app.DownloadManager.STATUS_FAILED -> {
                UpdateChecker.clearDownloadState(context); phase = UpdPhase.IDLE
                status = "下载失败，点击重试"
            }
            else -> scope.launch {
                val f = UpdateChecker.awaitDownload(context, id) { rec, tot ->
                    status = if (tot > 0) "下载中 ${rec * 100 / tot}%" else "下载中 ${rec / 1024 / 1024}MB"
                }
                if (f != null) {
                    apkFile = f; phase = UpdPhase.READY; status = "下载完成，点击安装"
                } else {
                    UpdateChecker.clearDownloadState(context)
                    phase = UpdPhase.IDLE; status = "下载失败，点击重试"
                }
            }
        }
    }

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
                                // 0.7.1: 系统 DownloadManager 托管——黑屏/退后台/进程
                                // 被杀都不断，这里只做轻量进度轮询。
                                val id = UpdateChecker.startDownload(context, info)
                                val f = UpdateChecker.awaitDownload(context, id) { rec, tot ->
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
                                    UpdateChecker.clearDownloadState(context)
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
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val span = range.endInclusive - range.start
    var trackW by remember { mutableStateOf(1) }
    var lastTap by remember { mutableStateOf(0L) }
    val thumbR = with(density) { 6.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .height(34.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
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
 * taller/thicker, alpha fades to both ends. 拖拽基准用 zoomBase()（VM 同步值，
 * 非 StateFlow 异步回环）；松手立即软吸附（onZoomSettle，上游 ZoomControlBar
 * closeContinuousZoomBar 同款），连续拖拽过程中绝不吸附。
 */
@Composable
private fun ZoomRotor(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
    zoomBase: () -> Float,
    onZoomStart: () -> Unit,
    onZoomSettle: () -> Unit,
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
                    val newZoom = (zoomBase() - delta / spacingPx * tickStep).coerceIn(minZoom, maxZoom)
                    onZoom(newZoom)
                },
                onDragStarted = { onZoomStart() },
                onDragStopped = { onZoomSettle() },
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
    // 0.9.9 保存动画（上游语义）：处理中缩略图先显示第一帧原始预览
    processingPreview: Bitmap? = null,
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
    // 变焦拖拽的同步基准 / 生命周期钩子（软吸附用）
    zoomBase: () -> Float,
    onZoomStart: () -> Unit,
    onZoomSettle: () -> Unit,
    onShutter: () -> Unit,
    // 0.8.3 拍摄处理动画：处理中快门外圈白弧旋转
    isProcessing: Boolean = false,
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
            zoomBase = zoomBase,
            onZoomStart = onZoomStart,
            onZoomSettle = onZoomSettle,
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
            ShutterButton(onClick = onShutter, scale = shutterScale, isProcessing = isProcessing)

            // Last capture thumbnail (right)
            // 0.9.9 保存动画：处理中优先显示第一帧原始预览，处理完成（lastCapture 刷新）后显示成片
            LastCaptureThumb(
                lastCapture,
                onGalleryClick,
                scale = thumbScale,
                processingPreview = processingPreview,
                isProcessing = isProcessing,
            )
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
private fun ShutterButton(onClick: () -> Unit, scale: Float = 1f, isProcessing: Boolean = false) {
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
        // 0.8.3 拍摄处理动画：处理中在快门按钮外圈绘制拖尾白条旋转
        // （主弧 270° + 尾部渐隐小弧），表示正在处理图片。
        if (isProcessing) {
            val transition = rememberInfiniteTransition(label = "shutterProcessing")
            val sweepAngle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                ),
                label = "shutterProcessingSweep",
            )
            Canvas(Modifier.matchParentSize()) {
                val stroke = 3.dp.toPx()
                val radius = (size.minDimension - stroke * 2f) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawArc(
                    color = Color.White,
                    startAngle = sweepAngle,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color.White.copy(alpha = 0.35f),
                    startAngle = sweepAngle + 285f,
                    sweepAngle = 55f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun LastCaptureThumb(
    photo: SavedPhoto?,
    onClick: () -> Unit,
    scale: Float = 1f,
    // 0.9.9 保存动画（上游语义）：快门后处理期间先显示第一帧原始预览缩略图，
    // 处理完成后 lastCapture 刷新、isProcessing 置否，自动切回成片。
    processingPreview: Bitmap? = null,
    isProcessing: Boolean = false,
) {
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
        if (isProcessing && processingPreview != null) {
            Image(
                bitmap = processingPreview.asImageBitmap(),
                contentDescription = "正在处理",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (photo != null) {
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

/**
 * 软吸附结算（照搬上游 ContinuousZoomStopSettlement，仅包名改写）：
 * 距最近档位 ≤ snapThreshold → 吸附；> threshold → 保留分数倍率不强制吸。
 * customZoomStop/replacedStopIndex 为上游 ZoomControlBar 临时档位显示保留，
 * 自研 UI 无档位条，当前只消费 snapZoomStop。
 */
private data class ContinuousZoomStopSettlement(
    val customZoomStop: Float?,
    val replacedStopIndex: Int,
    val originalStopRatio: Float,
    val snapZoomStop: Float?,
)

private fun settleContinuousZoomStop(
    zoomStops: List<Float>,
    zoomRatio: Float,
    snapThreshold: Float = 0.05f,
): ContinuousZoomStopSettlement {
    val closestIndex = zoomStops.indices.minByOrNull { abs(zoomStops[it] - zoomRatio) } ?: -1
    if (closestIndex == -1) {
        return ContinuousZoomStopSettlement(
            customZoomStop = null,
            replacedStopIndex = -1,
            originalStopRatio = 0f,
            snapZoomStop = null,
        )
    }
    val closestStop = zoomStops[closestIndex]
    return if (abs(closestStop - zoomRatio) > snapThreshold) {
        ContinuousZoomStopSettlement(
            customZoomStop = zoomRatio,
            replacedStopIndex = closestIndex,
            originalStopRatio = closestStop,
            snapZoomStop = null,
        )
    } else {
        ContinuousZoomStopSettlement(
            customZoomStop = null,
            replacedStopIndex = -1,
            originalStopRatio = 0f,
            snapZoomStop = closestStop,
        )
    }
}


