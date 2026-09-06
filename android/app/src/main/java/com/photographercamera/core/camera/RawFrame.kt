package com.photographercamera.core.camera

import com.photographercamera.core.gpu.RawCalibration
import java.nio.ByteBuffer

/**
 * One in-memory RAW_SENSOR capture: the untouched Bayer plane plus everything
 * needed to develop it on the GPU (see ProfileRenderer.renderRawChain).
 *
 * @param width      Bayer width in pixels
 * @param height     Bayer height in pixels
 * @param rowStride  bytes per row (can exceed width*2 - row padding)
 * @param bayer      raw little-endian UNSIGNED_SHORT samples
 * @param rotDeg     CW degrees to rotate the buffer upright (0/90/180/270)
 * @param calib      sensor calibration from Camera2 characteristics
 * @param asShotGains  per-shot COLOR_CORRECTION_GAINS from the HAL's 3A
 *                     (TotalCaptureResult via camera2 interop), float[3]=R,G,B
 *                     with G normalized to 1.0; null when unavailable. When
 *                     present it replaces the gray-world estimate as WB.
 */
class RawFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val bayer: ByteBuffer,
    val rotDeg: Int,
    val calib: RawCalibration,
    val asShotGains: FloatArray? = null,
)
