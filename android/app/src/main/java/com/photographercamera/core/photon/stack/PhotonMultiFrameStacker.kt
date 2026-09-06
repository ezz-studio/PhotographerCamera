/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.processor/MultiFrameStacker.kt (YUV 部分)
 * Licensed under the Apache License, Version 2.0.
 *
 * 0.5.0 移植精简：只保留 YUV 多帧堆栈编排（processBurst / HDR 编排类型），
 * RAW 空间堆栈（processBurstRaw 等）不在我方管线范围内，未移植。
 * 算法本身全部在 GlesYuvStacker（帧间对齐 + 时域合并 + 空间降噪）。
 */
package com.photographercamera.core.photon.stack

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import java.nio.ByteBuffer

enum class MgcSpatialOutputMode {
    BAYER,
    RGB,
}

enum class YuvHdrStackFrameRole {
    ZERO_EV,
    HIGH_EV,
    LOW_EV,
}

data class YuvHdrStackFrame(
    val image: SafeImage,
    val exposureProduct: Float,
    val role: YuvHdrStackFrameRole,
)

/**
 * Multi-Frame Stacker (YUV)
 *
 * Manages the stacking process for burst captures.
 * Aligns and merges multiple frames to reduce noise and improve quality.
 */
object PhotonMultiFrameStacker {
    private const val TAG = "PhotonMultiFrameStacker"

    /**
     * Process a burst of images and return a stacked Bitmap.
     *
     * @param images List of captured Images (YUV_420_888).
     * @return Stacked Bitmap (ARGB_8888), or null if failed.
     */
    @Synchronized
    fun processBurst(
        images: List<SafeImage>,
        rotation: Int,
        aspectRatio: AspectRatio?,
        enableSuperResolution: Boolean = false,
        colorSpace: ColorSpace,
    ): Bitmap? {
        if (images.isEmpty()) return null

        val width = images[0].width
        val height = images[0].height

        val scale = if (enableSuperResolution) 2 else 1
        val dimensions = BitmapUtils.calculateProcessedRect(width, height, aspectRatio, null, rotation)
        val targetW = dimensions.width() * scale
        val targetH = dimensions.height() * scale

        val inputFormat = images[0].format
        if (!GlesYuvStacker.supportsImageFormat(inputFormat)) {
            PLog.w(TAG, "GLES streaming stacker does not support image format=$inputFormat")
            images.forEach { it.close() }
            return null
        }
        PLog.i(TAG, "Starting GLES streaming stacking process for ${images.size} frames ($width x $height). SR=$enableSuperResolution")
        return try {
            GlesYuvStacker(
                width = width,
                height = height,
                outputWidth = targetW,
                outputHeight = targetH,
                rotation = rotation,
                colorSpace = colorSpace,
                inputFormat = inputFormat,
                enableSuperResolution = enableSuperResolution,
            ).process(images).also { result ->
                if (result == null) {
                    PLog.w(TAG, "GLES streaming stacker failed")
                }
            }
        } finally {
            images.forEach { it.close() }
        }
    }

    /**
     * HDR 融合编排（0-EV/高 EV/低 EV 三帧 Mertens 融合）。0.5.0 预留：
     * 我方采集端尚未产出包围曝帧，接口先随移植保留。
     */
    @Synchronized
    fun processHdrBurstYuv(
        frames: List<YuvHdrStackFrame>,
        fusionExposureProducts: FloatArray?,
        rotation: Int,
        aspectRatio: AspectRatio?,
        colorSpace: ColorSpace,
    ): Bitmap? {
        if (frames.size < 3) return null
        val images = frames.map { it.image }
        val width = images[0].width
        val height = images[0].height
        val dimensions = BitmapUtils.calculateProcessedRect(width, height, aspectRatio, null, rotation)
        val inputFormat = images[0].format

        if (!GlesYuvStacker.supportsImageFormat(inputFormat)) {
            PLog.w(TAG, "GLES HDR YUV stacker does not support image format=$inputFormat")
            images.forEach { it.close() }
            return null
        }

        val result = try {
            GlesYuvStacker(
                width = width,
                height = height,
                outputWidth = dimensions.width(),
                outputHeight = dimensions.height(),
                rotation = rotation,
                colorSpace = colorSpace,
                inputFormat = inputFormat,
            ).processHdr(
                frames = frames.map {
                    GlesYuvStacker.HdrInputFrame(
                        image = it.image,
                        exposureProduct = it.exposureProduct,
                        role = when (it.role) {
                            YuvHdrStackFrameRole.ZERO_EV -> GlesYuvStacker.HdrFrameRole.ZERO_EV
                            YuvHdrStackFrameRole.HIGH_EV -> GlesYuvStacker.HdrFrameRole.HIGH_EV
                            YuvHdrStackFrameRole.LOW_EV -> GlesYuvStacker.HdrFrameRole.LOW_EV
                        },
                    )
                },
                exposureProducts = fusionExposureProducts,
            )
        } finally {
            images.forEach { it.close() }
        }
        return result
    }
}
