/*
 * AppSettingsScreen — 0.6.0 全屏设置页（替代 0.5.x 的底部半透明 SettingsSheet）。
 *
 * UI 风格参考 PhotonCamera ui/settings/SettingsScreen（全屏列表 + 分组卡片），
 * 但内容只保留本 App 的实际功能：
 *   拍摄：快门声音 / 拍摄震动 / 音量键功能 / HDR 显示（预留）/ 保存地址位置
 *   镜头：镜头信息（系统 CameraManager 直读，含焦段）
 *   成像与色彩：色彩映射（sRGB 输出说明）+ 修复开关（YUV 直采 / 多帧连拍 /
 *               RAW ISP —— 对应 pc_yuv_off / pc_burst_off / pc_raw_isp_off 后门）
 *   维护：检查更新 / 调试日志
 * 按用户要求不包含：AI 服务、界面样式、幻影、内容管理、数据维护、多重曝光、
 * 画面比例（固定 4:3）、工具箱；构图网格开关已移除（功能重复）。
 */
package com.photographercamera.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import java.io.File

// 与 CameraScreen 一致的暗色观感
private val SettingsBg = Color(0xFF121212)
private val CardBg = Color(0xFF1E1E1E)
private val TextPrimary = Color(0xFFEDEDED)
private val TextSecondary = Color(0xFF8A8A8A)

/** 音量键功能取值：拍照 / 变焦 / 无。 */
val VOLUME_KEY_OPTIONS = listOf("拍照", "变焦", "无")

@Composable
fun AppSettingsScreen(
    onDismiss: () -> Unit,
    onDebugClick: () -> Unit,
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("pc_settings", Context.MODE_PRIVATE) }

    var shutterSound by remember { mutableStateOf(sp.getBoolean("shutter_sound", true)) }
    var captureVibrate by remember { mutableStateOf(sp.getBoolean("capture_vibrate", true)) }
    var volumeKeyFn by remember { mutableStateOf(sp.getString("volume_key_function", "拍照") ?: "拍照") }
    var hdrDisplay by remember { mutableStateOf(sp.getBoolean("hdr_display", false)) }
    var saveLocation by remember { mutableStateOf(sp.getBoolean("save_location", false)) }
    var yuvDirect by remember { mutableStateOf(!File(context.filesDir, "pc_yuv_off.txt").exists()) }
    var burstOn by remember { mutableStateOf(!File(context.filesDir, "pc_burst_off.txt").exists()) }
    var rawIspOn by remember { mutableStateOf(!File(context.filesDir, "pc_raw_isp_off.txt").exists()) }

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
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextPrimary,
                )
            }
            Text("设置", color = TextPrimary, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        }

        // ---- 拍摄 ------------------------------------------------------------
        SectionLabel("拍摄")
        SettingsCard {
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
                "相册对 UltraHDR 照片的增益图显示（当前管线未生成增益图，预留）",
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

        // ---- 镜头 ------------------------------------------------------------
        SectionLabel("镜头")
        SettingsCard {
            LensInfoRows(context)
        }

        // ---- 成像与色彩 -------------------------------------------------------
        SectionLabel("成像与色彩")
        SettingsCard {
            InfoRow("色彩映射", "输出 sRGB（4:4:4 全色度采样待 libjpeg 集成）")
            SwitchRow("YUV 直采主通道", "关闭后回退 HAL JPEG 路线（应急修复开关）", yuvDirect) {
                yuvDirect = it
                File(context.filesDir, "pc_yuv_off.txt").let { f ->
                    if (it) f.delete() else f.writeText("off")
                }
                DebugLog.log("SETTINGS", "yuv direct -> $it (rebind on next open)")
            }
            SwitchRow("多帧连拍（JPEG MAX）", "关闭后单帧直采（应急修复开关）", burstOn) {
                burstOn = it
                File(context.filesDir, "pc_burst_off.txt").let { f ->
                    if (it) f.delete() else f.writeText("off")
                }
                DebugLog.log("SETTINGS", "burst -> $it")
            }
            SwitchRow("RAW ISP 引擎", "关闭后 RAW 模式回退 HAL 开发（应急修复开关）", rawIspOn) {
                rawIspOn = it
                File(context.filesDir, "pc_raw_isp_off.txt").let { f ->
                    if (it) f.delete() else f.writeText("off")
                }
                DebugLog.log("SETTINGS", "raw isp engine -> $it")
            }
        }

        // ---- 维护 ------------------------------------------------------------
        SectionLabel("维护")
        SettingsCard {
            // 复用 CameraScreen.kt 中的 UpdateCheckRow（internal，同包可见），
            // 状态机与 0.4.0 完全一致，避免两份实现漂移。
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
        }
        Spacer(Modifier.height(24.dp))
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
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = content,
    )
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
        options.forEach { opt ->
            val selected = opt == current
            Text(
                opt,
                color = if (selected) MaterialTheme.colorScheme.primary else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(opt) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
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
@Composable
private fun LensInfoRows(context: Context) {
    val rows = remember {
        runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.mapNotNull { id ->
                runCatching {
                    val c = cm.getCameraCharacteristics(id)
                    val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "后置"
                        CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                        else -> return@runCatching null
                    }
                    val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.joinToString("/") { "%.1fmm".format(it) } ?: "未知"
                    val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val mp = if (px != null) "%.1fMP".format(px.width * px.height / 1_000_000.0) else ""
                    "$facing $focals $mp"
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
    if (rows.isEmpty()) {
        InfoRow("镜头信息", "读取失败")
    } else {
        rows.forEach { InfoRow("镜头", it) }
    }
}
