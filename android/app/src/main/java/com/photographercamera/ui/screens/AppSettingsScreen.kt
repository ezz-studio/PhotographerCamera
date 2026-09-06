/*
 * AppSettingsScreen — 0.7.4 分层设置（导航结构对齐上游，菜单项以指导手册为准）。
 *
 * 顶级页只列分组入口（小字分组标题 + 子页，子页顶栏返回 + 手势返回均回顶级页）。
 *   拍摄：快门声音 / 拍摄震动 / 音量键功能 / HDR 显示 / 保存地址位置
 *   对焦与镜头：自动对焦 / 镜头选择 / 默认焦段 / 相机校正 / 镜头发现 / 镜头信息
 *               （上游 autofocus + lens_selection + default_focal_length +
 *                calibration + lens_discovery 对齐；不含虚拟镜头与景深）
 *   成像与色彩：色彩映射(P3 色域) / 色调映射 / 修复预览异常 / 修复拍摄异常 /
 *               P010 10位YUV / HLG10 HDR / HLG 兼容性（全部与上游同名同默认值）
 *   维护：检查更新 / 调试日志 / 恢复内置预设
 *   关于相机：写死成像参数清单 + 每项的管线参与状态（代码级验证结论）
 * 按指导手册不包含：AI 服务、界面样式、幻影、内容管理、数据维护、多重曝光、
 * 画面比例（固定 4:3）、工具箱、构图网格。
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
import com.photographercamera.core.camera.CameraEngine
import com.photographercamera.core.debug.DebugLog
import com.photographercamera.core.profile.ProfileLoader
import kotlinx.coroutines.launch

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

@Composable
fun AppSettingsScreen(
    onDismiss: () -> Unit,
    onDebugClick: () -> Unit,
    engine: CameraEngine? = null,
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

    // ---- 对焦与镜头组状态 ----------------------------------------------------
    var lensDiscovery by remember { mutableStateOf(sp.getBoolean("lens_discovery", false)) }
    // 镜头发现扩展（上游 lens_discovery 组）：逻辑多摄探测 / 白名单 / 微距镜头ID
    var logicalProbe by remember { mutableStateOf(sp.getBoolean("logical_multi_camera_discovery", false)) }
    var lensWhitelist by remember { mutableStateOf(sp.getString("lens_binding_whitelist", "") ?: "") }
    var macroLensId by remember { mutableStateOf(sp.getString("macro_camera_id", "auto") ?: "auto") }
    var lenses by remember(lensDiscovery, logicalProbe, lensWhitelist, macroLensId) {
        mutableStateOf(
            engine?.listLenses(
                lensDiscovery,
                logicalProbe = logicalProbe,
                whitelist = lensWhitelist.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                macroId = macroLensId.takeIf { it != "auto" },
            ) ?: emptyList()
        )
    }
    var afEnabled by remember { mutableStateOf(engine?.manualFocusOn?.not() ?: true) }
    var focusDiopters by remember { mutableStateOf(engine?.manualFocusDiopters ?: 0f) }
    val maxDiopters = remember(lensDiscovery) { engine?.minFocusDistanceDiopters(null) ?: 0f }
    var selectedLensId by remember { mutableStateOf(engine?.selectedLensId) }
    // 默认焦段（上游 default_focal_length，0 = 不设置；影响启动变焦）
    var defaultFocal by remember { mutableStateOf(sp.getFloat("default_focal_length", 0f)) }
    // 人脸对焦（camera2 STATISTICS_FACE_DETECT_MODE + 最大人脸自动对焦）
    var faceFocus by remember { mutableStateOf(sp.getBoolean("face_focus", false)) }
    // 照片方向校正（上游 camera_orientation_offsets：按镜头 ID 存 0/90/180/270）
    val orientationLensId = selectedLensId ?: engine?.currentCameraId ?: "0"
    var orientationDeg by remember(orientationLensId) {
        mutableStateOf(engine?.orientationOffsetFor(orientationLensId) ?: 0)
    }

    // ---- 成像与色彩组状态（与上游同名 key 同默认值）---------------------------
    var useP3 by remember { mutableStateOf(sp.getBoolean("use_p3_color_space", false)) }
    // 硬件色调映射模式：srgb / default（上游 TONEMAP_MODE，系统默认 vs sRGB 曲线）
    var tonemapMode by remember { mutableStateOf(sp.getString("tonemap_mode", "default") ?: "default") }
    var useProfileToneMap by remember { mutableStateOf(sp.getBoolean("use_profile_tone_map", true)) }
    var fixPreview by remember { mutableStateOf(sp.getBoolean("fix_preview_anomaly", false)) }
    var fixCapture by remember { mutableStateOf(sp.getBoolean("fix_capture_anomaly", false)) }
    var useP010 by remember { mutableStateOf(sp.getBoolean("use_p010", false)) }
    var useHlg10 by remember { mutableStateOf(sp.getBoolean("use_hlg10", false)) }
    var hlgCompat by remember { mutableStateOf(sp.getBoolean("hlg_compatibility", false)) }

    // ---- 维护组状态 ----------------------------------------------------------
    var restoreMsg by remember { mutableStateOf<String?>(null) }

    // 位置权限：开启"保存地址位置"时请求；拒绝则回落关闭态
    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.any { it }
        saveLocation = granted
        sp.edit().putBoolean("save_location", granted).apply()
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
                    NavRow("拍摄", "快门声音 · 震动 · 音量键 · 保存地址位置") { page = SettingsPage.CAPTURE }
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
                    NavRow("维护", "检查更新 · 调试日志 · 恢复内置预设") { page = SettingsPage.MAINTENANCE }
                }
                SectionLabel("其他")
                SettingsCard {
                    NavRow("关于相机", "成像参数与管线参与状态") { page = SettingsPage.ABOUT }
                }
                Spacer(Modifier.height(24.dp))
            }

            SettingsPage.CAPTURE -> SettingsCard {
                SwitchRow("快门声音", "拍摄时播放系统快门音", shutterSound) {
                    shutterSound = it
                    sp.edit().putBoolean("shutter_sound", it).apply()
                }
                SwitchRow("拍摄震动", "拍摄完成时短震动反馈", captureVibrate) {
                    captureVibrate = it
                    sp.edit().putBoolean("capture_vibrate", it).apply()
                }
                ChoiceRow("音量键功能", VOLUME_KEY_OPTIONS, volumeKeyFn) {
                    volumeKeyFn = it
                    sp.edit().putString("volume_key_function", it).apply()
                }
                    SwitchRow(
                        "HDR 显示",
                        "在屏幕窗口上启用高动态范围 (HDR) 显示（Android 14+ 且屏幕支持时生效）。若部分设备开启该模式导致屏幕发黄、变暗，可将其关闭。",
                        hdrDisplay,
                    ) {
                    hdrDisplay = it
                    sp.edit().putBoolean("hdr_display", it).apply()
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
                    }
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
                            engine?.setManualFocus(false)
                        } else if (maxDiopters <= 0f) {
                            afEnabled = true
                        } else {
                            engine?.setManualFocus(true, focusDiopters)
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
                                engine?.setManualFocus(true, it)
                            },
                            valueRange = 0f..maxDiopters,
                        )
                    }
                }
                // 人脸对焦（上游 eye focus 组）：camera2 人脸检测 + 最大人脸自动对焦
                SettingsCard {
                    SwitchRow(
                        "人脸对焦",
                        "检测画面中的人脸，并优先对最大人脸自动对焦（设备需支持人脸检测）",
                        faceFocus,
                    ) {
                        faceFocus = it
                        sp.edit().putBoolean("face_focus", it).apply()
                        engine?.setFaceFocusEnabled(it)
                        DebugLog.log("SETTINGS", "face focus -> $it")
                    }
                }
                // 镜头选择（上游 lens_selection）
                SettingsCard {
                    if (lenses.isEmpty()) {
                        InfoRow("镜头", if (engine == null) "引擎未就绪" else "未发现镜头")
                    } else {
                        lenses.forEach { lens ->
                            val desc = buildString {
                                append("%.1fmm".format(lens.focal))
                                if (lens.eqFocal > 0f) append(" · 等效${lens.eqFocal.toInt()}mm")
                                append(" · ${lens.id}")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedLensId = lens.id
                                        engine?.selectLens(lens.id)
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(desc, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                if (selectedLensId == lens.id) {
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
                                        engine?.selectLens(null)
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
                        engine?.applyDefaultFocal(v)
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
                        engine?.setOrientationOffset(orientationLensId, deg)
                    }
                    Text(
                        "镜头 $orientationLensId · 成片统一加转该角度",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 镜头发现（上游 lens_discovery：逻辑多摄探测 + 白名单 + 微距镜头ID）
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
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Text("物理白名单", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.OutlinedTextField(
                            value = lensWhitelist,
                            onValueChange = { s ->
                                lensWhitelist = s
                                sp.edit().putString("lens_binding_whitelist", s).apply()
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
                        "强制启用类似 0/2、0/3 的逻辑/物理绑定。即使自动探测关闭也会生效。",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                    // 微距镜头 ID（上游 macro_camera_id）
                    val idOptions = mutableListOf("自动识别")
                    lenses.forEach { idOptions.add(it.id) }
                    ChoiceRow(
                        "微距镜头 ID",
                        idOptions,
                        if (macroLensId == "auto") "自动识别" else macroLensId,
                    ) { label ->
                        val v = if (label == "自动识别") "auto" else label
                        macroLensId = v
                        sp.edit().putString("macro_camera_id", v).apply()
                    }
                    Text(
                        "选择一个后置相机 ID 强制作为微距镜头。自动识别会保留当前相机发现逻辑。",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                }
            }

            SettingsPage.IMAGING -> {
                // 色彩映射（上游 use_p3_color_space）
                SettingsCard {
                    SwitchRow("P3 色域", "在支持的设备上启用 Display P3 输出。默认关闭。", useP3) {
                        useP3 = it
                        sp.edit().putBoolean("use_p3_color_space", it).apply()
                        DebugLog.log("SETTINGS", "p3 color space -> $it")
                    }
                }
                // 硬件色调映射（上游 TONEMAP_MODE：系统默认 vs sRGB 对比度/伽马曲线）
                SettingsCard {
                    ChoiceRow(
                        "色调映射",
                        listOf("系统默认", "sRGB"),
                        if (tonemapMode == "srgb") "sRGB" else "系统默认",
                    ) { label ->
                        val v = if (label == "sRGB") "srgb" else "default"
                        tonemapMode = v
                        sp.edit().putString("tonemap_mode", v).apply()
                        engine?.setSrgbToneMap(v == "srgb")
                        DebugLog.log("SETTINGS", "hardware tonemap -> $v")
                    }
                    Text(
                        "调整硬件色调映射（对比度、伽马）模式。",
                        color = TextSecondary, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // 色调映射（上游 use_profile_tone_map，默认开）
                SettingsCard {
                    SwitchRow("配置文件色调映射", "RAW 显影使用配置文件色调映射曲线", useProfileToneMap) {
                        useProfileToneMap = it
                        sp.edit().putBoolean("use_profile_tone_map", it).apply()
                        DebugLog.log("SETTINGS", "profile tone map -> $it")
                    }
                }
                // 兼容性修复开关（全部默认关，与上游一致）
                SettingsCard {
                    SwitchRow("修复预览异常", "个别设备预览渲染异常时开启", fixPreview) {
                        fixPreview = it
                        sp.edit().putBoolean("fix_preview_anomaly", it).apply()
                        DebugLog.log("SETTINGS", "fix preview -> $it")
                    }
                    SwitchRow("修复拍摄异常", "个别设备成片异常时开启", fixCapture) {
                        fixCapture = it
                        sp.edit().putBoolean("fix_capture_anomaly", it).apply()
                        DebugLog.log("SETTINGS", "fix capture -> $it")
                    }
                }
                // HDR / 高精度输出（上游同款）
                SettingsCard {
                    SwitchRow(
                        "P010 (10位 YUV)",
                        "启用 10位 YUV 输出以获得更好的图像数据精度。仅在支持的设备上且 RAW 关闭时生效。",
                        useP010,
                    ) {
                        useP010 = it
                        sp.edit().putBoolean("use_p010", it).apply()
                        DebugLog.log("SETTINGS", "p010 -> $it")
                    }
                    SwitchRow("HLG10 HDR", "预览与成片使用 HLG10 高动态范围", useHlg10) {
                        useHlg10 = it
                        sp.edit().putBoolean("use_hlg10", it).apply()
                        DebugLog.log("SETTINGS", "hlg10 -> $it")
                    }
                    SwitchRow("HLG 兼容性", "HLG 输出兼容性回退（个别设备绿屏时开启）", hlgCompat) {
                        hlgCompat = it
                        sp.edit().putBoolean("hlg_compatibility", it).apply()
                        DebugLog.log("SETTINGS", "hlg compat -> $it")
                    }
                }
            }

            SettingsPage.MAINTENANCE -> SettingsCard {
                // 复用 CameraScreen.kt 中的 UpdateCheckRow（internal，同包可见）
                UpdateCheckRow()
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

            SettingsPage.ABOUT -> {
                // 0.7.5：AboutCard 包进卡片留出两侧边距（此前直贴 Column 顶边）
                SettingsCard {
                    AboutCard("多帧融合", "JPEG MAX · 默认 6 帧 · Spatial 融合", OkGreen, "已参与成像管线（GlesYuvStacker）")
                    AboutCard("RAW MAX", "Spatial 融合模式 · 默认启用", OkGreen, "已参与成像管线（GlesMgcRawSpatialStacker）")
                    AboutCard("照片质量", "JPEG 质量 100", OkGreen, "已参与成像管线（ImageCapture.setJpegQuality）")
                    AboutCard("镜头阴影校正", "已开启（写死）", OkGreen, "RAW 显影管线内已实现")
                    AboutCard("拍摄后自动保存", "已开启（写死）", OkGreen, "已参与成像管线（saveAndNotify）")
                    AboutCard("RAW MAX 锐化", "默认 0.5 · 亮度降噪 1 · 色度降噪 1", OkGreen, "已参与成像管线（RawDemosaicProcessor）")
                    AboutCard("JPEG 4:4:4 导出", "目标启用", Color(0xFFB9A15A), "未参与：待 libjpeg-turbo 4:4:4 编码集成")
                    AboutCard("降噪 / 锐化", "高质量", Color(0xFFB9A15A), "HAL 层默认档；引擎切换后由移植管线接管")
                    AboutCard("RAW 渲染引擎", "Adobe 曲线 · Camera2 降噪模型", OkGreen, "已参与成像管线（RawRenderingEngine.AdobeCurve）")
                }
                Spacer(Modifier.height(12.dp))
            }
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

/** 关于相机页参数卡：参数名 + 取值 + 管线参与状态。 */
@Composable
private fun AboutCard(name: String, value: String, statusColor: Color, status: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = TextSecondary, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(statusColor),
            )
            Text(status, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

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
