package com.photographercamera.core.camera

import android.graphics.Bitmap
import android.media.Image

/**
 * Convert a Camera2 YUV_420_888 Image to an ARGB_8888 Bitmap without going
 * through JPEG. This keeps the still-capture pipeline in a linear-ish sensor
 * space until the GPU LUT chain applies the look, avoiding the camera ISP's
 * second JPEG tonemapping/quantization pass.
 *
 * Handles both planar (pixelStride == 1) and semiplanar/interleaved
 * (pixelStride == 2) YUV layouts by sampling the U/V planes independently.
 */
object ImageYuvConverter {
    fun yuvToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val width = image.width
        val height = image.height
        val argb = IntArray(width * height)

        // Bounds-guarded reads: some devices report row strides / buffer capacities
        // whose tail padding does not match the ideal layout; a raw get() there is an
        // IndexOutOfBoundsException on real hardware. Clamp instead of crashing.
        val yLimit = yBuf.limit()
        val uLimit = uBuf.limit()
        val vLimit = vBuf.limit()
        fun u8(buf: java.nio.ByteBuffer, limit: Int, idx: Int): Int =
            buf.get(if (idx < limit) idx else limit - 1).toInt() and 0xff

        var outIdx = 0
        for (j in 0 until height) {
            val yRowOffset = j * yRowStride
            val uvRow = j shr 1
            val uRowOffset = uvRow * uRowStride
            val vRowOffset = uvRow * vRowStride
            for (i in 0 until width) {
                val y = u8(yBuf, yLimit, yRowOffset + i)
                val uvCol = (i shr 1) * uPixelStride
                val u = u8(uBuf, uLimit, uRowOffset + uvCol) - 128
                val v = u8(vBuf, vLimit, vRowOffset + uvCol * vPixelStride / uPixelStride) - 128

                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                argb[outIdx++] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(argb, 0, width, 0, 0, width, height)
        }
    }
}
