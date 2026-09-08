/*
 * AppSettingsScreen — 0.7.4 分层设置（导航结构对齐上游，菜单项以指导手册为准）。
 *
 * 顶级页只列分组入口（小字分组标题 + 子页，子页顶栏返回 + 手势返回均回顶级页）。
 *   拍摄：快门声音 / 拍摄震动 / 音量键功能 / HDR 显示 / 保存地址位置
 *   对焦与镜头：自动对焦 / 镜头选择 / 默认焦段 / 相机校正 / 镜头发现 / 镜头信息
 *               （上游 autofocus + lens_selection + default_focal_length +
 *                calibration + lens_discovery 对齐；不含虚拟镜头与景深）
 *   成像与色彩：RAW MAX 画质调优 / Ultra HDR 增益图 / 融合模式 / 降噪等级 / 锐化等级 /
 *               RAW MAX 锐化 / 亮度降噪 / 色度降噪 / 输出倍率 /
 *               JPEG 4:4:4 / RAW 渲染引擎（0.9.9 默认 AgX）/ 配置文件色调映射
 *               （0.9.0 移除引擎无对应物的开关：tonemap sRGB、修复预览/拍摄异常、HLG×2；
 *                0.9.9 移除 P3 色域与 P010 10位YUV——用户指令固定为关）
 *   维护：检查更新 / 调试日志 / 恢复内置预设
 *   关于相机：写死成像参数清单 + 每项的管线参与状态（代码级验证结论）
 * 按指导手册不包含：AI 服务、界面样式、幻影、内容管理、数据维护、多重曝光、
 * 画面比例（固定 4:3）、工具箱、构图网格。
 *
 * 0.9.0 接线轮：全部开关直连 photon 引擎（pvm.setXxx 双写 sp + DataStore）；
 * 镜头绑定改黑名单制（物理黑名单，旧白名单键 lens_binding_whitelist 废弃）；
 * CameraEngine 参数删除（旧引擎退役）。
 */
package com.photographercamera.ui.screens

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photographercamera.core.debug.DebugLog
import com.photographercamera.core.profile.ProfileLoader
import com.photographercamera.photon.camera.CameraInfo
import com.photographercamera.photon.camera.LensType
import com.photographercamera.photon.camera.MultiFrameConfig
import com.photographercamera.photon.data.VolumeKeyAction
import com.photographercamera.photon.processor.DenoiseStrength
import com.photographercamera.photon.raw.RawRenderingEngine
import com.photographercamera.photon.viewmodel.CameraViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 与 CameraScreen 一致的暗色观感
private val SettingsBg = Color(0xFF121212)
private val CardBg = Color(0xFF1E1E1E)
private val TextPrimary = Color(0xFFEDEDED)
private val TextSecondary = Color(0xFF8A8A8A)
private val Accent = Color(0xFFFF9500)
private val OkGreen = Color(0xFF6BCB77)

/** 音量键功能取值：拍照 / 变焦 / 无。 */
val VOLUME_KEY_OPTIONS = listOf("拍照", "变焦", "无")

/** 设置子页。 */
private enum class SettingsPage { CAPTURE, LENS, IMAGING, MAINTENANCE, ABOUT }

/** 上游默认焦段选项（default_focal_length：0 = 不设置）。 */
private val FOCAL_OPTIONS = listOf(0f, 24f, 28f, 35f, 50f, 85f)
private fun focalLabel(f: Float) = if (f <= 0f) "不设置" else "${f.toInt()}mm"

/** 降噪等级（上游 nr_level 0-4，默认 2；文案对齐上游 OFF/FAST/HIGH_QUALITY/ZSL/MINIMAL）。 */
private val NR_LEVELS = listOf("关闭", "快速", "高质量", "零快门延迟", "最小")

/** 锐化等级（上游 edge_level 0-3，默认 1；文案对齐上游 OFF/FAST/HIGH_QUALITY/ZSL）。 */
private val EDGE_LEVELS = listOf("关闭", "快速", "高质量", "零快门延迟")

/** RAW 渲染引擎显示名（上游引擎枚举顺序）。 */
private fun engineLabel(e: RawRenderingEngine): String = when (e) {
    RawRenderingEngine.AdobeCurve -> "Adobe 曲线"
    RawRenderingEngine.HncsCcm -> "HNCS CCM"
    RawRenderingEngine.HncsLut -> "HNCS LUT"
    RawRenderingEngine.AgX -> "AgX"
    RawRenderingEngine.Spektrafilm -> "Spektrafilm"
    RawRenderingEngine.DarktableSigmoid -> "Sigmoid"
    RawRenderingEngine.DarktableFilmic -> "Filmic"
}

@Composable
fun AppSettingsScreen(
    onDismiss: () -> Unit,
    onDebugClick: () -> Unit,
    // 0.9.0：所有开关直连 photon 引擎（CameraEngine 已退役，参数移除）
    photonVm: CameraViewModel? = null,
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("pc_settings", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // 子页导航（remember saveable：进程恢复后停留在原子页）；
    // 手势/系统返回：子页先回顶级页，顶级页才关闭设置层。
    var page by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<SettingsPage?>(null)
    }
    BackHandler(enabled = page != null) { page = null }

    // ---- 拍摄组状态 ---------------------------------------------------------
    var shutterSound by remember { mutableStateOf(sp.getBoolean("shutter_sound", true)) }
    var captureVibrate by remember { mutableStateOf(sp.getBoolean("capture_vibrate", true)) }
    var volumeKeyFn by remember { mutableStateOf(sp.getString("volume_key_function", "拍照") ?: "拍照") }
    var hdrDisplay by remember { mutableStateOf(sp.getBoolean("hdr_display", false)) }
    var saveLocation by remember { mutableStateOf(sp.getBoolean("save_location", false)) }
    // 0.9.0：前置镜像（上游 mirror_front_camera 自拍镜像；引擎级消费）
    var frontMirror by remember { mutableStateOf(sp.getBoolean("front_mirror", false)) }

    // ---- 对焦与镜头组状态 ----------------------------------------------------
    var lensDiscovery by remember { mutableStateOf(sp.getBoolean("lens_discovery", false)) }
    // 镜头发现扩展（上游 lens_discovery 组）：逻辑多摄探测 / 黑名单 / 微距镜头ID
    var logicalProbe by remember { mutableStateOf(sp.getBoolean("logical_multi_camera_discovery", false)) }
    // 0.9.0：镜头绑定改黑名单制（引擎 setLensIdBlacklist 同语义；旧白名单键废弃）
    var lensBlacklist by remember { mutableStateOf(sp.getString("lens_binding_blacklist", "") ?: "") }
    var macroLensId by remember { mutableStateOf(sp.getString("macro_camera_id", "auto") ?: "auto") }
    // 0.9.0：镜头源 = photon 引擎（订阅 state，镜头发现/黑名单变化即时反映到列表）
    val availableCameras = photonVm?.state?.collectAsState()?.value?.availableCameras ?: emptyList()
    val minFocusDistanceDiopters = photonVm?.state?.collectAsState()?.value?.minimumFocusDistance ?: 0f
    val blacklistIds = remember(lensBlacklist) {
        lensBlacklist.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    var lenses by remember(lensDiscovery, availableCameras, blacklistIds) {
        mutableStateOf(
            // 0.9.0：直接持有 photon CameraInfo（LensRef 旧桥已随 CameraEngine 退役）
            availableCameras
                .filter { lensDiscovery || it.lensType != LensType.FRONT }
                .filter { it.cameraId !in blacklistIds }
                .filter { it.focalLength > 0f || it.focalLength35mmEquivalent > 0f }
        )
    }
    var afEnabled by remember { mutableStateOf(photonVm?.state?.value?.isAutoFocus ?: true) }
    var focusDiopters by remember { mutableStateOf(photonVm?.state?.value?.focusDistance ?: 0f) }
    val maxDiopters = minFocusDistanceDiopters
    var selectedLensId by remember { mutableStateOf(photonVm?.state?.value?.currentCameraId) }
    // 默认焦段（上游 default_focal_length，0 = 不设置；影响启动变焦）
    var defaultFocal by remember { mutableStateOf(sp.getFloat("default_focal_length", 0f)) }
    // 人脸对焦（上游 eye focus 语义：EyeFocus 眼对焦，预览链逐帧检测）
    var faceFocus by remember { mutableStateOf(sp.getBoolean("face_focus", false)) }
    // 照片方向校正（上游 camera_orientation_offsets：按镜头 ID 存 0/90/180/270）
    val orientationLensId = selectedLensId ?: "0"
    var orientationDeg by remember(orientationLensId) {
        mutableStateOf(photonVm?.getOrientationOffset(orientationLensId) ?: 0)
    }

    // ---- 成像与色彩组状态（与上游同名 key 同默认值；0.9.0 移除引擎无对应物的开关）--
    // 0.9.9：P3 / P010 开关移除（用户指令），两个能力固定为关（引擎端映射已强制 false）。
    var useProfileToneMap by remember { mutableStateOf(sp.getBoolean("use_profile_tone_map", true)) }

    // ---- 维护组状态 ----------------------------------------------------------
    var restoreMsg by remember { mutableStateOf<String?>(null) }
    // 1.0.0：手动上传日志的状态（null=未上传/初始提示；"上传中…"期间禁点）
    var uploading by remember { mutableStateOf(false) }
    var uploadMsg by remember { mutableStateOf<String?>(null) }

    // 位置权限：开启"保存地址位置"时请求；拒绝则回落关闭态
    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.any { it }
        saveLocation = granted
        sp.edit().putBoolean("save_location", granted).apply()
        photonVm?.setSaveLocation(granted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SettingsBg)
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- 顶栏 ------------------------------------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
        ) {
            IconButton(onClick = { if (page == null) onDismiss() else page = null }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (page == null) "关闭设置" else "返回上级",
                    tint = TextPrimary,
                )
            }
            Text(
                when (page) {
                    null -> "设置"
                    SettingsPage.CAPTURE -> "拍摄"
                    SettingsPage.LENS -> "对焦与镜头"
                    SettingsPage.IMAGING -> "成像与色彩"
                    SettingsPage.MAINTENANCE -> "维护"
                    SettingsPage.ABOUT -> "关于相机"
                },
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }

        when (page) {
            null -> {
                // ---- 顶级分组入口 ------------------------------------------------
                SectionLabel("拍摄")
                SettingsCard {
                    NavRow("拍摄", "快门声音 · 震动 · 音量键 · 多帧帧数 · 保存地址位置") { page = SettingsPage.CAPTURE }
                }
                SectionLabel("对焦与镜头")
                SettingsCard {
                    NavRow("对焦与镜头", "自动对焦 · 镜头选择 · 默认焦段 · 镜头发现") { page = SettingsPage.LENS }
                }
                SectionLabel("成像与色彩")
                SettingsCard {
                    NavRow("成像与色彩", "色彩映射 · 色调映射 · HLG · 兼容性修复") { page = SettingsPage.IMAGING }
                }
                SectionLabel("维护")
                SettingsCard {
                    NavRow("维护", "检查更新 · 手动上传日志 · 恢复内置预设") { page = SettingsPage.MAINTENANCE }
                }
                // 1.0.0 用户指令：移除"关于相机"设置项（不再展示成像参数与管线参与状态）
                Spacer(Modifier.height(24.dp))
            }

            SettingsPage.CAPTURE -> SettingsCard {
                SwitchRow("快门声音", "拍摄时播放快门音（引擎级，与上游一致）", shutterSound) {
                    shutterSound = it
                    sp.edit().putBoolean("shutter_sound", it).apply()
                    photonVm?.setShutterSoundEnabled(it)
                }
                SwitchRow("拍摄震动", "拍摄完成时短震动反馈", captureVibrate) {
                    captureVibrate = it
                    sp.edit().putBoolean("capture_vibrate", it).apply()
                    photonVm?.setVibrationEnabled(it)
                }
                ChoiceRow("音量键功能", VOLUME_KEY_OPTIONS, volumeKeyFn) {
                    volumeKeyFn = it
                    sp.edit().putString("volume_key_function", it).apply()
                    photonVm?.setVolumeKeyAction(
                        when (it) {
                            "变焦" -> VolumeKeyAction.ZOOM
                            "无" -> VolumeKeyAction.NONE
                            else -> VolumeKeyAction.CAPTURE
                        }
                    )
                }
                    SwitchRow(
                        "HDR 显示",
                        "在屏幕窗口上启用高动态范围 (HDR) 显示（Android 14+ 且屏幕支持时生效）。若部分设备开启该模式导致屏幕发黄、变暗，可将其关闭。",
                        hdrDisplay,
                    ) {
                    hdrDisplay = it
                    sp.edit().putBoolean("hdr_display", it).apply()
                }
                SwitchRow("前置镜像", "自拍镜像：前置镜头拍摄结果按镜像保存（与上游一致）", frontMirror) {
                    frontMirror = it
                    sp.edit().putBoolean("front_mirror", it).apply()
                    photonVm?.setMirrorFrontCamera(it)
                }
                SwitchRow("保存地址位置", "拍摄时把 GPS 坐标写入照片 EXIF（需位置权限）", saveLocation) {
                    if (it) {
                        locPermLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    } else {
                        saveLocation = false
                        sp.edit().putBoolean("save_location", false).apply()
                        photonVm?.setSaveLocation(false)
                    }
                }
                // 0.9.6：多帧帧数（上游 SettingsScreen 同款滑杆，VM StateFlow 实时订阅 + DataStore 持久化）
                // 0.9.10 修复滑块跳动：pending 确认模式锁定提交值直至 flow 回流追上
                val jpgFrameFlow = photonVm?.jpgMultiFrameDenoiseFrameCount?.collectAsState()
                var jpgFrameDrag by remember { mutableStateOf<Int?>(null) }
                var jpgFramePending by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(jpgFrameFlow?.value) {
                    if (jpgFramePending != null && jpgFrameFlow?.value == jpgFramePending) {
                        jpgFramePending = null
                    }
                }
                val jpgFrameCount = jpgFrameDrag
                    ?: jpgFramePending
                    ?: jpgFrameFlow?.value
                    ?: MultiFrameConfig.DEFAULT_DENOISE_FRAME_COUNT
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("JPG max 帧数", color = TextPrimary, fontSize = 14.sp)
                            Text(
                                "多帧降噪合成张数（越大噪点越低、拍摄越慢）",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            "$jpgFrameCount",
                            color = Accent,
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = jpgFrameCount.toFloat(),
                        onValueChange = { jpgFrameDrag = it.roundToInt() },
                        onValueChangeFinished = {
                            jpgFrameDrag?.let { v ->
                                jpgFramePending = v
                                photonVm?.setJpgMultiFrameDenoiseFrameCount(v)
                            }
                            jpgFrameDrag = null
                        },
                        valueRange = MultiFrameConfig.MIN_DENOISE_FRAME_COUNT.toFloat()..
                            MultiFrameConfig.MAX_FRAME_COUNT.toFloat(),
                        steps = MultiFrameConfig.MAX_FRAME_COUNT - MultiFrameConfig.MIN_DENOISE_FRAME_COUNT - 1,
                    )
                }
                val hdrFrameFlow = photonVm?.hdrPlusFrameCount?.collectAsState()
                var hdrFrameDrag by remember { mutableStateOf<Int?>(null) }
                var hdrFramePending by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(hdrFrameFlow?.value) {
                    if (hdrFramePending != null && hdrFrameFlow?.value == hdrFramePending) {
                        hdrFramePending = null
                    }
                }
                val hdrFrameCount = hdrFrameDrag
                    ?: hdrFramePending
                    ?: hdrFrameFlow?.value
                    ?: MultiFrameConfig.DEFAULT_HDR_PLUS_FRAME_COUNT
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("HDR+ 帧数", color = TextPrimary, fontSize = 14.sp)
                            Text(
                                "RAW max / HDR+ 合成张数（越大动态范围越高）",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            "$hdrFrameCount",
                            color = Accent,
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = hdrFrameCount.toFloat(),
                        onValueChange = { hdrFrameDrag = it.roundToInt() },
                        onValueChangeFinished = {
                            hdrFrameDrag?.let { v ->
                                hdrFramePending = v
                                photonVm?.setHdrPlusFrameCount(v)
                            }
                            hdrFrameDrag = null
                        },
                        valueRange = MultiFrameConfig.MIN_HDR_PLUS_FRAME_COUNT.toFloat()..
                            MultiFrameConfig.MAX_FRAME_COUNT.toFloat(),
                        steps = MultiFrameConfig.MAX_FRAME_COUNT - MultiFrameConfig.MIN_HDR_PLUS_FRAME_COUNT - 1,
                    )
                }
            }

            SettingsPage.LENS -> {
                // 自动对焦（上游 autofocus）：关 = 手动对焦距离滑块
                SettingsCard {
                    SwitchRow(
                        "自动对焦",
                        if (afEnabled) "连续自动对焦" else "手动对焦（拖动滑块设定对焦距离）",
                        afEnabled,
                    ) {
                        afEnabled = it
                        if (it) {
                            photonVm?.setAutoFocus(true)
                        } else if (maxDiopters <= 0f) {
                            afEnabled = true
                        } else {
                            photonVm?.setAutoFocus(false)
                            photonVm?.setFocusDistance(focusDiopters)
                        }
                    }
                    if (!afEnabled && maxDiopters > 0f) {
                        Text(
                            "对焦距离（屈光度 0.0 – %.1f）".format(maxDiopters),
                            color = TextSecondary, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Slider(
                            value = focusDiopters,
                            onValueChange = {
                                focusDiopters = it
                                photonVm?.setFocusDistance(it)
                            },
                            valueRange = 0f..maxDiopters,
                        )
                    }
                }
                // 人脸对焦（上游 eye focus 组语义：EyeFocus 眼对焦，预览链逐帧检测）
                SettingsCard {
                    SwitchRow(
                        "人脸对焦",
                        "检测画面中的人脸，并优先对最大人脸自动对焦（设备需支持人脸检测）",
                        faceFocus,
                    ) {
                        faceFocus = it
                        sp.edit().putBoolean("face_focus", it).apply()
                        photonVm?.setEyeFocusEnabled(it)
                        DebugLog.log("SETTINGS", "face focus -> $it")
                    }
                }
                // 镜头选择（上游 lens_selection）
                SettingsCard {
                    if (lenses.isEmpty()) {
                        InfoRow("镜头", if (photonVm == null) "引擎未就绪" else "未发现镜头")
                    } else {
                        lenses.forEach { lens ->
                            val focal = lens.focalLength.takeIf { it > 0f }
                                ?: (lens.focalLength35mmEquivalent / 43.27f)
                            val desc = buildString {
                                append("%.1fmm".format(focal))
                                if (lens.focalLength35mmEquivalent > 0f) {
                                    append(" · 等效${lens.focalLength35mmEquivalent.toInt()}mm")
                                }
                                append(" · ${lens.cameraId}")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedLensId = lens.cameraId
                                        photonVm?.switchToLens(lens.cameraId)
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(desc, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                if (selectedLensId == lens.cameraId) {
                                    Text("使用中", color = Accent, fontSize = 12.sp)
                                }
                            }
                        }
                        if (selectedLensId != null) {
                            Text(
                                "恢复默认镜头",
                                color = Accent,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable {
                                        selectedLensId = null
                                        // 0.9.0：回切主摄（photon 引擎无"未选择"态，默认即主摄）
                                        availableCameras
                                            .firstOrNull { it.lensType == LensType.BACK_MAIN }
                                            ?.let { photonVm?.switchToLens(it.cameraId) }
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
                // 默认焦段（上游 default_focal_length）：启动时应用设定焦段
                SettingsCard {
                    ChoiceRow(
                        "默认焦段",
                        FOCAL_OPTIONS.map { focalLabel(it) },
                        focalLabel(defaultFocal),
                    ) { label ->
                        val v = FOCAL_OPTIONS.firstOrNull { focalLabel(it) == label } ?: 0f
                        defaultFocal = v
                        sp.edit().putFloat("default_focal_length", v).apply()
                        photonVm?.setDefaultFocalLength(v)
                    }
                }
                // 照片方向校正（上游 camera_orientation_offsets）：按镜头存 0/90/180/270
                SettingsCard {
                    ChoiceRow(
                        "照片方向校正",
                        listOf("0°", "90°", "180°", "270°"),
                        "${orientationDeg}°",
                    ) { label ->
                        val deg = label.removeSuffix("°").toIntOrNull() ?: 0
                        orientationDeg = deg
                        photonVm?.setOrientationOffset(orientationLensId, deg)
                    }
                    Text(
                        "镜头 $orientationLensId · 成片统一加转该角度",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 镜头发现（上游 lens_discovery：逻辑多摄探测 + 黑名单 + 微距镜头ID）
                SettingsCard {
                    SwitchRow("镜头发现", "列出设备的全部摄像头（含前置）", lensDiscovery) {
                        lensDiscovery = it
                        sp.edit().putBoolean("lens_discovery", it).apply()
                    }
                    SwitchRow(
                        "逻辑多摄探测",
                        "探测逻辑多摄 ID 暴露的物理镜头。默认关闭以提高兼容性。",
                        logicalProbe,
                    ) {
                        logicalProbe = it
                        sp.edit().putBoolean("logical_multi_camera_discovery", it).apply()
                        photonVm?.setEnableLogicalMultiCameraDiscovery(it)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Text("物理黑名单", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.OutlinedTextField(
                            value = lensBlacklist,
                            onValueChange = { s ->
                                lensBlacklist = s
                                sp.edit().putString("lens_binding_blacklist", s).apply()
                                photonVm?.setLensIdBlacklist(s)
                            },
                            placeholder = { Text("如 0/2,0/3", color = TextSecondary, fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = TextPrimary, fontSize = 13.sp,
                            ),
                            modifier = Modifier.width(150.dp),
                        )
                    }
                    Text(
                        "0.9.0 改黑名单制（与引擎一致）：从镜头列表中排除这些 ID，留空不排除。",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                    // 微距镜头 ID（上游 macro_camera_id）
                    val idOptions = mutableListOf("自动识别")
                    lenses.forEach { idOptions.add(it.cameraId) }
                    ChoiceRow(
                        "微距镜头 ID",
                        idOptions,
                        if (macroLensId == "auto") "自动识别" else macroLensId,
                    ) { label ->
                        val v = if (label == "自动识别") "auto" else label
                        macroLensId = v
                        sp.edit().putString("macro_camera_id", v).apply()
                        photonVm?.setPreferredMacroCameraId(v.takeIf { it != "auto" })
                    }
                    Text(
                        "选择一个后置相机 ID 强制作为微距镜头。自动识别会保留当前相机发现逻辑。",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                }
            }

            SettingsPage.IMAGING -> {
                // 降噪 / 锐化等级（上游 PHOTO_MODE 页顺序；0.9.9 按上游 UI 与接线放回，
                // 全链路 nr_level / edge_level 本就存在，此前仅缺 UI 入口）
                SettingsCard {
                    val nrFlow = photonVm?.nrLevel?.collectAsState()
                    val nrIdx = (nrFlow?.value ?: 2).coerceIn(0, NR_LEVELS.lastIndex)
                    ChoiceRow("降噪等级", NR_LEVELS, NR_LEVELS[nrIdx]) { picked ->
                        val level = NR_LEVELS.indexOf(picked)
                        photonVm?.setNRLevel(level)
                        DebugLog.log("SETTINGS", "nr level -> $level")
                    }
                    val edgeFlow = photonVm?.edgeLevel?.collectAsState()
                    val edgeIdx = (edgeFlow?.value ?: 1).coerceIn(0, EDGE_LEVELS.lastIndex)
                    ChoiceRow("锐化等级", EDGE_LEVELS, EDGE_LEVELS[edgeIdx]) { picked ->
                        val level = EDGE_LEVELS.indexOf(picked)
                        photonVm?.setEdgeLevel(level)
                        DebugLog.log("SETTINGS", "edge level -> $level")
                    }
                }
                // 0.9.10：RAW MAX 组头部（上游 release MAX&HDR 菜单同款三控件）
                SettingsCard {
                    // RAWmax 画质调优总开关（默认开；关 = 忽略下方调优滑杆，按默认成像参数出片）
                    val tuningFlow = photonVm?.rawMaxQualityTuning?.collectAsState()
                    SwitchRow(
                        "RAW MAX 画质调优",
                        "按当前物理传感器尺寸应用融合、降噪和锐化调优；关闭时使用默认成像参数",
                        tuningFlow?.value ?: true,
                    ) {
                        photonVm?.setRawMaxQualityTuning(it)
                        DebugLog.log("SETTINGS", "raw max quality tuning -> $it")
                    }
                    // Ultra HDR 增益图（上游默认关；链路已有：prefs + setUltraHdrGainMapEnabled
                    // + professional 模式消费端，本轮仅补 UI 入口）
                    val gainmapFlow = photonVm?.ultraHdrGainMapEnabled?.collectAsState()
                    SwitchRow(
                        "Ultra HDR 增益图",
                        "成片嵌入 Ultra HDR 增益图（专业模式 RAW 出图时生效）",
                        gainmapFlow?.value ?: false,
                    ) {
                        photonVm?.setUltraHdrGainMapEnabled(it)
                        DebugLog.log("SETTINGS", "ultra hdr gainmap -> $it")
                    }
                    // RAWmax 融合模式（上游 MAX&HDR 菜单 Sabre/Spatial；替代 0.9.x SPATIAL 硬编码）
                    val mergeFlow = photonVm?.rawMaxMergeMode?.collectAsState()
                    ChoiceRow(
                        "RAW MAX 融合模式",
                        listOf("Spatial", "Sabre"),
                        if (mergeFlow?.value == "SABRE") "Sabre" else "Spatial",
                    ) { picked ->
                        photonVm?.setRawMaxMergeMode(if (picked == "Sabre") "SABRE" else "SPATIAL")
                        DebugLog.log("SETTINGS", "raw max merge mode -> $picked")
                    }
                }
                // RAW MAX 画质组（上游 PROFESSIONAL「画质」组：锐化 / 亮度降噪 / 色度降噪 / 输出倍率；
                // 均为连续滑杆 + 松手提交，与上游 SliderSettingItem 交互一致）
                SettingsCard {
                    val sharpeningFlow = photonVm?.rawMaxSharpening?.collectAsState()
                    FloatSliderRow(
                        title = "RAW MAX 锐化",
                        subtitle = "RAW max 合成后的锐化强度",
                        flowValue = sharpeningFlow?.value,
                        fallback = 0.4f,
                        range = 0f..1f,
                    ) { v ->
                        photonVm?.setRawMaxSharpening(v)
                        DebugLog.log("SETTINGS", "raw max sharpening -> $v")
                    }
                    val lumaFlow = photonVm?.rawMaxNoiseReduction?.collectAsState()
                    FloatSliderRow(
                        title = "亮度降噪",
                        subtitle = "RAW max 亮度通道降噪强度",
                        flowValue = lumaFlow?.value,
                        fallback = 1.0f,
                        range = DenoiseStrength.valueRange,
                    ) { v ->
                        photonVm?.setRawMaxNoiseReduction(v)
                        DebugLog.log("SETTINGS", "raw max luma nr -> $v")
                    }
                    val chromaFlow = photonVm?.rawMaxChromaNoiseReduction?.collectAsState()
                    FloatSliderRow(
                        title = "色度降噪",
                        subtitle = "RAW max 色度通道降噪强度",
                        flowValue = chromaFlow?.value,
                        fallback = 1.0f,
                        range = DenoiseStrength.valueRange,
                    ) { v ->
                        photonVm?.setRawMaxChromaNoiseReduction(v)
                        DebugLog.log("SETTINGS", "raw max chroma nr -> $v")
                    }
                    val scaleFlow = photonVm?.rawMaxOutputScale?.collectAsState()
                    FloatSliderRow(
                        title = "输出倍率",
                        subtitle = "RAW max 输出分辨率倍率（1x = 传感器原生尺寸）",
                        flowValue = scaleFlow?.value,
                        fallback = 1.0f,
                        range = MultiFrameConfig.MIN_OUTPUT_SCALE..MultiFrameConfig.MAX_OUTPUT_SCALE,
                        display = { v -> "x%.1f".format(v) },
                    ) { v ->
                        photonVm?.setRawMaxOutputScale(v)
                        DebugLog.log("SETTINGS", "raw max output scale -> $v")
                    }
                }
                // JPEG 4:4:4（上游 CAPTURE_STORAGE 组 use_jpeg_444_export，默认关；
                // 无 StateFlow，走 pc_settings 镜像 + VM setter）
                SettingsCard {
                    var useJpeg444 by remember {
                        mutableStateOf(sp.getBoolean("use_jpeg_444_export", false))
                    }
                    SwitchRow(
                        "JPEG 4:4:4",
                        "JPEG 保存使用 4:4:4 色度全采样，减少色彩损失（文件更大）",
                        useJpeg444,
                    ) {
                        useJpeg444 = it
                        sp.edit().putBoolean("use_jpeg_444_export", it).apply()
                        photonVm?.setUseJpeg444Export(it)
                        DebugLog.log("SETTINGS", "jpeg 444 -> $it")
                    }
                }
                // RAW 渲染引擎（0.9.9 默认 AgX，参数沿用上游默认；切换入口对齐上游）
                SettingsCard {
                    val engineFlow = photonVm?.rawRenderingEngine?.collectAsState()
                    val engine = engineFlow?.value ?: RawRenderingEngine.AgX
                    ChoiceRow(
                        "RAW 渲染引擎",
                        RawRenderingEngine.entries.map { engineLabel(it) },
                        engineLabel(engine),
                    ) { picked ->
                        val pickedEngine = RawRenderingEngine.entries
                            .firstOrNull { engineLabel(it) == picked } ?: RawRenderingEngine.AgX
                        photonVm?.setRawColorEngine(pickedEngine)
                        DebugLog.log("SETTINGS", "raw engine -> ${pickedEngine.name}")
                    }
                }
                // 配置文件色调映射（上游 use_profile_tone_map，默认开；0.9.9 确认有用：
                // 控制 RAW 显影走配置文件色调映射曲线 vs 默认曲线，保留）
                SettingsCard {
                    SwitchRow("配置文件色调映射", "RAW 显影使用配置文件色调映射曲线", useProfileToneMap) {
                        useProfileToneMap = it
                        sp.edit().putBoolean("use_profile_tone_map", it).apply()
                        photonVm?.setUseProfileToneMap(it)
                        DebugLog.log("SETTINGS", "profile tone map -> $it")
                    }
                }
                // 0.9.0 移除（引擎无对应物，上游亦无）：tonemap_mode / fix_preview_anomaly /
                // fix_capture_anomaly / use_hlg10 / hlg_compatibility（旧引擎时代遗留）。
                // 0.9.9：P3 / P010 开关移除（用户指令），能力固定为关
                // （引擎端 UserPreferencesRepository 映射已强制 useP010=false / useP3ColorSpace=false）。
            }

            SettingsPage.MAINTENANCE -> SettingsCard {
                // 复用 CameraScreen.kt 中的 UpdateCheckRow（internal，同包可见）
                UpdateCheckRow()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !uploading) {
                            uploading = true
                            uploadMsg = "上传中…"
                            scope.launch {
                                // 1.0.0：日志先存 APP 隐私目录（filesDir/logs），此处用户
                                // 手动触发一次上传；完成后行内直接提示结果。
                                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.photographercamera.core.debug.DebugLog.manualUpload(context)
                                }
                                uploading = false
                                uploadMsg = result ?: "已上传 ✓"
                            }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.BugReport,
                        contentDescription = null,
                        tint = TextPrimary.copy(alpha = 0.9f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("上传日志", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        uploadMsg ?: "手动上传 ›",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                ProfileLoader.restoreBundled(context)
                                restoreMsg = "已恢复"
                            }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Text("恢复内置预设", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(restoreMsg ?: "点击恢复 ›", color = TextSecondary, fontSize = 12.sp)
                }
            }

            // 1.0.0 用户指令：SettingsPage.ABOUT 分支移除——"关于相机"设置项已删，
            // 不再展示成像参数与管线参与状态（AboutCard 组件一并退役）。
            else -> {}
        }
    }
}

// ---- 分组基元 ---------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)   // 0.7.4 修复：多卡片连排挤压
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content,
    )
}

/** 顶级分组入口行：标题 + 摘要 + 右箭头。 */
@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 15.sp)
            Text(summary, color = TextSecondary, fontSize = 11.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextSecondary,
        )
    }
}

// 1.0.0：AboutCard 组件随"关于相机"设置项一并移除（用户指令：不再展示参数与状态）。

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            if (subtitle != null) {
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 连续值滑杆行（上游 SliderSettingItem 交互：拖动暂存 UI 状态，松手一次性提交）。 */
@Composable
private fun FloatSliderRow(
    title: String,
    subtitle: String,
    flowValue: Float?,
    fallback: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    display: (Float) -> String = { v -> "%.2f".format(v) },
    onCommit: (Float) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    // 0.9.10 修复滑块跳动（pending 确认模式）：松手后 setter 异步写 DataStore，
    // flow 回流滞后一拍——drag=null 直接回退旧值再跳新值 = "反复跳动"。
    // pending 锁定提交值直至 flow 追上（0.005 容差覆盖 normalize 舍入）。
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(flowValue) {
        val p = pending
        if (p != null && flowValue != null && kotlin.math.abs(p - flowValue) < 0.005f) {
            pending = null
        }
    }
    val shown = drag ?: pending ?: flowValue ?: fallback
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
            Text(
                display(shown),
                color = Accent,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }
        Slider(
            value = shown,
            onValueChange = { drag = it },
            onValueChangeFinished = {
                drag?.let { v ->
                    pending = v
                    onCommit(v)
                }
                drag = null
            },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(title, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(current, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        var expanded by remember { mutableStateOf(false) }
        Box {
            Text(
                "选择 ›",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { opt ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(opt, color = if (opt == current) Accent else TextPrimary) },
                        onClick = {
                            expanded = false
                            onPick(opt)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(title, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextSecondary, fontSize = 12.sp)
    }
}

/** 镜头信息：CameraManager 直读（不依赖相机引擎实例，设置页独立可用）。 */
