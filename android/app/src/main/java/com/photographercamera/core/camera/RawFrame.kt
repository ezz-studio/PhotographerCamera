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
 */
class RawFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val bayer: ByteBuffer,
    val rotDeg: Int,
    val calib: RawCalibration,
)
