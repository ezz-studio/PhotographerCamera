/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.utils/BitmapUtils.kt
 * Licensed under the Apache License, Version 2.0. Algorithm code unchanged.
 */
package com.photographercamera.core.photon.stack

import android.graphics.*
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.floor

/**
 * Bitmap 处理工具类
 */
object BitmapUtils {
    private const val TAG = "BitmapUtils"


    /**
     * 从字节数组获取 Bitmap
     */
    fun getBitmap(byteArray: ByteArray): Bitmap {
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
    }


    /**
     * 水平翻转 Bitmap
     */
    fun flipHorizontal(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 旋转 Bitmap。
     *
     * 坐标约定为左上原点，正角度顺时针，与 RAW 和 HDRNet 的输出坐标一致。
     *
     * 旋转角度为 0 时直接返回原图，避免额外创建 Bitmap。
     */
    fun rotate(bitmap: Bitmap, rotationDegrees: Float): Bitmap {
        val normalizedDegrees = ((rotationDegrees % 360) + 360) % 360
        if (normalizedDegrees == 0f) {
            return bitmap
        }

        val matrix = Matrix().apply { postRotate(normalizedDegrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return rotated
    }

    fun Bitmap.toByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        this.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * 计算经过旋转和裁切后的图像尺寸
     *
     * @param width 原始宽度
     * @param height 原始高度
     * @param aspectRatio 目标宽高比
     * @param cropRegion 裁切区域 (可选)
     * @param rotation
     */
    fun calculateProcessedRect(
        width: Int,
        height: Int,
        aspectRatio: AspectRatio?,
        cropRegion: Rect?,
        rotation: Int = 0
    ): Rect {
        val bitmapBounds = Rect(0, 0, width, height)

        // 1. 统一方向并取交集
        val currentIsLandscape = width >= height
        val safeRegion = if (cropRegion != null && !cropRegion.isEmpty) {
            val regionIsLandscape = cropRegion.width() >= cropRegion.height()
            val alignedRegion = if (regionIsLandscape != currentIsLandscape) {
                // 轴方向不一致，进行坐标转置 (Transpose)
                Rect(cropRegion.top, cropRegion.left, cropRegion.bottom, cropRegion.right)
            } else {
                Rect(cropRegion)
            }
            // 与原图边界取交集
            if (!alignedRegion.intersect(bitmapBounds)) {
                bitmapBounds
            } else {
                alignedRegion
            }
        } else {
            bitmapBounds
        }

        // 2. 确定目标比例
        val cropIsLandscape = safeRegion.width() >= safeRegion.height()
        val targetRatio = aspectRatio?.getValue(cropIsLandscape) ?: (safeRegion.width().toFloat() / safeRegion.height().toFloat())

        // 3. 在安全区域 (safeRegion) 内按照目标比例进行最终裁切
        val baseWidth = safeRegion.width()
        val baseHeight = safeRegion.height()
        val srcRatio = baseWidth.toFloat() / baseHeight.toFloat()

        var finalW: Float
        var finalH: Float

        if (srcRatio > targetRatio) {
            // 安全区太宽 -> 缩减宽度
            finalH = baseHeight.toFloat()
            finalW = baseHeight * targetRatio
        } else {
            // 安全区太瘦 -> 缩减高度
            finalW = baseWidth.toFloat()
            finalH = baseWidth / targetRatio
        }

        // 4. 在安全区域内居中计算最终坐标
        val x = (safeRegion.left + (baseWidth - finalW) / 2f).toInt().coerceAtLeast(0)
        val y = (safeRegion.top + (baseHeight - finalH) / 2f).toInt().coerceAtLeast(0)
        val finalWInt = alignDownToEven(finalW.toInt().coerceAtMost(width - x))
        val finalHInt = alignDownToEven(finalH.toInt().coerceAtMost(height - y))

        // 5. 适配旋转角度
        val isSwapped = rotation == 90 || rotation == 270
        return if (isSwapped) {
            Rect(y, x, y + finalHInt, x + finalWInt)
        } else {
            Rect(x, y, x + finalWInt, y + finalHInt)
        }
    }

    private fun alignDownToEven(value: Int): Int {
        if (value <= 1) return value
        return value and 1.inv()
    }
}
