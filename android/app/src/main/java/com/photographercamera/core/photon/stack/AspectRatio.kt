/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.camera/CameraState.kt 中的 AspectRatio 类
 * Licensed under the Apache License, Version 2.0.
 *
 * 0.5.0 移植精简：只保留 BitmapUtils.calculateProcessedRect 需要的
 * getValue(isLandscape) 语义。用 Float 比例构造（如 3/4 竖拍 4:3）。
 */
package com.photographercamera.core.photon.stack

/**
 * 画面比例（堆栈输出裁切用）。
 */
class AspectRatio private constructor(
    val name: String,
    val widthRatio: Int,
    val heightRatio: Int,
) {
    override fun equals(other: Any?): Boolean {
        return other is AspectRatio && name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return name
    }

    fun getValue(isLandscape: Boolean): Float {
        return if (isLandscape) {
            widthRatio.toFloat() / heightRatio
        } else {
            heightRatio.toFloat() / widthRatio
        }
    }

    companion object {
        fun of(widthRatio: Int, heightRatio: Int): AspectRatio {
            return AspectRatio("${widthRatio}:${heightRatio}", widthRatio, heightRatio)
        }
    }
}
