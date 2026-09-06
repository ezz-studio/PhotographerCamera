package com.photographercamera.core.device

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import com.photographercamera.core.gpu.RawCalibration
import com.photographercamera.core.debug.DebugLog

/**
 * DeviceAdapter - 设备适配层（Device Adaptation Layer）v1。
 *
 * 目标架构（用户定义）："统一上层算法 + 设备适配层 + GPU 执行" ——
 * 上层的动态计算引擎 / 风格引擎永远不直接面对各品牌 Camera2 的差异，
 * 只面对本层产出的标准化结果：
 *
 *   Samsung/Xiaomi/Pixel/OPPO ... Camera2
 *        ↓
 *   DeviceAdapter（能力矩阵 + RAW 元数据标准化 + 降级策略）
 *        ↓
 *   标准化帧（RawCalibration / StillFrame）
 *        ↓
 *   RAW ISP 或 YUV/ISP 基础处理
 *        ↓
 *   Unified Image Engine（同一套 profile 函数 → 任何设备同风格）
 *
 * 适配矩阵的调研依据见仓库根 raw_adapter_research.md。
 */
object DeviceAdapter {

    /** 能力矩阵快照（bind 时探测一次，缓存）。 */
    data class Caps(
        val rawSensor: Boolean,        // REQUEST_AVAILABLE_CAPABILITIES 含 RAW_SENSOR
        val rawOutput: Boolean,        // ImageCapture 支持 OUTPUT_FORMAT_RAW
        val rawJpegBundle: Boolean,    // 支持 OUTPUT_FORMAT_RAW_JPEG（DNG+ISP JPEG）
        val quadBayer: Boolean,        // Quad Bayer / Tetracell 传感器（binning factor > 1）
        val binningFactor: Int,        // 未知 = 1；API 31+ 才可靠
        val pixelArrayW: Int,          // SENSOR_INFO_PIXEL_ARRAY_SIZE（quad 判定用）
        val pixelArrayH: Int,
        val whiteLevel: Int,           // 标准化后的白电平（含兜底）
        val blackLevelMax: Int,        // 黑阶 pattern 最大值（日志/诊断）
        val ccmFromMetadata: Boolean,  // CCM 来自 SENSOR_COLOR_TRANSFORM2（否则单位阵兜底）
        val ultraHRes: Boolean,        // ULTRA_HIGH_RESOLUTION_SENSOR（quad 全尺寸 remosaic 能力）
        val remosaic: Boolean,         // REMOSAIC_REPROCESSING（HAL remosaic 重处理支持）
    )

    /**
     * 探测指定摄像头的 RAW 能力与传感器特性。任何异常都按「不支持 RAW」
     * 处理 —— 适配层的铁律：宁可降级，不给上层脏数据。
     */
    @SuppressLint("DefaultLocale")
    fun probeCaps(cameraManager: CameraManager, lensId: String?): Caps {
        var rawSensor = false
        var pixelW = 0
        var pixelH = 0
        var binning = 1
        var white = 0
        var blackMax = 0
        var ccmOk = false
        var ultraHRes = false
        var remosaic = false
        try {
            val id = lensId ?: cameraManager.cameraIdList.firstOrNull()
            if (id != null) {
                val c = cameraManager.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                rawSensor = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                val pa = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                pixelW = pa?.width ?: 0
                pixelH = pa?.height ?: 0
                // Quad Bayer 判定：API 31+ 的 SENSOR_INFO_BINNING_FACTOR 是权威；
                // SDK 36 该字段类型为 Size（binning 倍数宽高，如 quad → 2x2），
                // 取 max(w,h) 作为合并倍数；旧 API 标"未知"（=1），交由运行时
                // 分辨率守卫兜底。
                if (Build.VERSION.SDK_INT >= 31) {
                    val bf = c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)
                    binning = if (bf != null) maxOf(bf.width, bf.height) else 1
                }
                white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
                // SENSOR_BLACK_LEVEL_PATTERN 是封装类 BlackLevelPattern（非数组），
                // getOffsetForIndex(col,row) 直接返回该 CFA 位的黑阶值。
                val blp = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                if (blp != null) {
                    for (row in 0..1) for (col in 0..1) {
                        blackMax = maxOf(blackMax, blp.getOffsetForIndex(col, row))
                    }
                }
                ccmOk = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) != null
                // Quad Bayer 全尺寸路线的能力储备（remosaic 仍走预览回退，
                // 真机标定后启用 GPU remosaic pass）。
                ultraHRes = caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR,
                ) == true
                remosaic = caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING,
                ) == true
            }
        } catch (t: Throwable) {
            DebugLog.log("ADAPT", "probeCaps failed: ${t.message}")
        }
        val quad = binning > 1
        DebugLog.log(
            "ADAPT",
            "caps rawSensor=$rawSensor quad=$quad binning=$binning pixel=${pixelW}x${pixelH} " +
                "white=$white blackMax=$blackMax ccm=$ccmOk ultraHRes=$ultraHRes remosaic=$remosaic",
        )
        return Caps(
            rawSensor = rawSensor,
            rawOutput = rawSensor,   // ImageCapture 格式支持在 bind 时再精确复核
            rawJpegBundle = rawSensor,
            quadBayer = quad,
            binningFactor = binning,
            pixelArrayW = pixelW,
            pixelArrayH = pixelH,
            whiteLevel = normalizeWhiteLevel(white),
            blackLevelMax = blackMax,
            ccmFromMetadata = ccmOk,
            ultraHRes = ultraHRes,
            remosaic = remosaic,
        )
    }

    /** 白电平兜底：缺失/非法时按 10bit 经验值（调研：多数传感器 1023）。 */
    fun normalizeWhiteLevel(raw: Int): Int =
        if (raw in 256..65535) raw else 1023

    /**
     * 运行时 RAW 帧守卫：Quad Bayer 传感器只有在其输出已经是 2×2 binning
     * （≈1/4 像素阵列面积）时才可安全喂给标准 Malvar demosaic —— binning
     * 合并同色 2×2 后就是标准 Bayer。若 HAL 直接吐全尺寸 quad 排列
     * （未 remosaic），标准 demosaic 会出 2×2 伪彩，必须降级。
     */
    fun acceptsRawFrame(caps: Caps, rawW: Int, rawH: Int): Boolean {
        if (!caps.quadBayer) return true
        val full = caps.pixelArrayW.toLong() * caps.pixelArrayH
        val frame = rawW.toLong() * rawH
        if (full <= 0) return true
        val binned = frame * 4 < full * 9 / 10   // 帧面积 < 90% 阵列面积 → 已 binning
        if (!binned) {
            DebugLog.log(
                "ADAPT",
                "quad bayer full-res RAW ($rawW x $rawH vs array ${caps.pixelArrayW}x${caps.pixelArrayH}) " +
                    "- not remosaic-safe, RAW ISP refuses this frame (fallback path)",
            )
        }
        return binned
    }

    /**
     * RAW 元数据标准化：CameraCharacteristics → RawCalibration。
     * 与品牌无关的字段兜底顺序：metadata → 经验表 → 安全默认。
     * CFA、黑阶 pattern、CCM 逐项独立兜底 —— 任一缺失不影响其它字段。
     */
    fun normalizeCalibration(c: CameraCharacteristics): RawCalibration? {
        return try {
            val rawArrangement = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
            // 0.3.7: HAL 上报值即为真。0.3.6 曾按"MTK BGGR->RGGB 180° quirk"
            // 强改排列，实测 0.3.6 全红——那是 R/B 互换的直接后果（蓝天全变红），
            // 反证 BGGR 上报本来就对。旧证据链是误读：0.3.5 gray-world 双撞
            // 上限 R2.5/B2.5 与 0.3.6 as-shot 实测 R2.33/B1.58 吻合 —— R/B 桶
            // 落的是真实弱通道（正常 daylight WB 增益需求），不是排列错位。
            // （"偏绿"的真正根因是当年 wbGains 恒中性，0.3.4 起 WB 通道已修。）
            val cfaOff = when {
                rawArrangement == 1 -> intArrayOf(1, 0)  // GRBG
                rawArrangement == 2 -> intArrayOf(0, 1)  // GBRG
                rawArrangement == 3 -> intArrayOf(1, 1)  // BGGR
                else -> intArrayOf(0, 0) // RGGB（0 与未知默认）
            }
            val blPat = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val black = if (blPat != null) {
                val tmp = IntArray(4)
                var k = 0
                for (row in 0..1) for (col in 0..1) tmp[k++] = blPat.getOffsetForIndex(col, row)
                FloatArray(4) { tmp[it].toFloat() }
            } else {
                // 经验兜底：传感器黑阶常见 64（10bit）；偏绿/偏粉先怀疑这里
                floatArrayOf(64f, 64f, 64f, 64f)
            }
            val white = normalizeWhiteLevel(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0).toFloat()
            // As-shot WB：SDK 36 移除 SENSOR_NEUTRAL_COLOR_POINT 且 CameraX 拿不到
            // per-shot CaptureResult —— 传感器增益保持中性，风格 WB（u_wb）在
            // 统一引擎里驱动。各品牌一致，风格不漂移。
            val wb = floatArrayOf(1f, 1f, 1f)
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
                    for (k in 0 until 3) acc += xyzToSrgb[row * 3 + k] * el[k * 3 + col].toFloat()
                    acc
                }
            } else {
                floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            RawCalibration(cfaOff, black, white, wb, ccm)
        } catch (t: Throwable) {
            DebugLog.log("ADAPT", "normalizeCalibration failed: ${t.message}")
            null
        }
    }
}
