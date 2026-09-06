package com.photographercamera.core.photon.camera

import android.media.Image
import java.util.concurrent.atomic.AtomicBoolean

class SafeImage(val image: Image, private val camera2Controller: Camera2Controller? = null) : AutoCloseable {
// 0.7.0: controller 改可空——0.5.0 PhotonStackPipeline 独立构造 SafeImage（无控制器记账），close() 里判空即可
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
            camera2Controller?.onImageRelease()
        }
    }
}
