/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.model/SafeImage.kt
 * Licensed under the Apache License, Version 2.0.
 *
 * 0.5.0 移植解耦：去掉 Camera2Controller 构造依赖（上游用于拍照流程的
 * 释放计数）。我方管线里 proxy 生命周期由 CameraPreviewView 统一收尾，
 * close() 只关 Image 本身。
 */
package com.photographercamera.core.photon.stack

import android.media.Image
import java.util.concurrent.atomic.AtomicBoolean

class SafeImage(val image: Image) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val width: Int
        get() = image.width
    val height: Int
        get() = image.height
    val format: Int
        get() = image.format
    val planes: Array<Image.Plane>
        get() = image.planes
    val timestamp: Long
        get() = image.timestamp

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            image.close()
        }
    }
}
