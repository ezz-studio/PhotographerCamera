package com.photographercamera.core.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * StillFrame - 标准化成片帧（Unified Engine 的统一输入）。
 *
 * 目标架构："RAW 和 YUV 只是两个不同的输入/基础 ISP，后面的动态计算、
 * 风格引擎和最终输出统一。" 三条成片路径在此收敛为一种类型：
 *
 *   RAW  路径：Camera2 RAW_SENSOR → [Raw] → GPU RAW ISP → 统一引擎
 *   YUV  路径：HAL ISP YUV_420_888 直采 → [Yuv] → GPU EGLImage/EXTERNAL_OES
 *              免拷贝转换 → 统一引擎（"禁止 JPEG 路线"的主通道）
 *   ISP  路径：HAL ISP JPEG → [Isp] → 统一引擎（闪光灯联动 / 能力回退兜底）
 *
 * 下游（renderStill → effect 链 → JPEG）不感知来源差异。
 */
sealed class StillFrame {
    /** 未处理的 Bayer 帧，由我们的 GPU RAW ISP 发展成 RGB。 */
    data class Raw(val frame: RawFrame) : StillFrame()

    /**
     * 厂商 ISP 的 YUV_420_888 帧（ImageAnalysis 直采，未经 JPEG 有损压缩）。
     * [proxy] 的生命周期移交给渲染端：GL 渲染 + 读回完成后由
     * CameraPreviewView 统一 close。 [rotDeg] = ImageInfo.rotationDegrees
     * （buffer→upright 顺时针角度）；[mirror] = 前置摄像头水平翻转。
     */
    data class Yuv(
        val proxy: ImageProxy,
        val rotDeg: Int,
        val mirror: Boolean,
    ) : StillFrame()

    /** 设备 ISP 输出（已 demosaic/已 gamma 的 upright 位图，JPEG 兜底路径）。 */
    data class Isp(val bitmap: Bitmap) : StillFrame()
}
