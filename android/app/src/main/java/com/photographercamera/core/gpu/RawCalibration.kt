package com.photographercamera.core.gpu

/**
 * Static per-camera RAW calibration from Camera2 characteristics - everything
 * the GPU RAW ISP shader needs to turn Bayer data into display-ready sRGB.
 * Queried once at bind time (core/camera/CameraEngine) and passed with every
 * RAW frame.
 *
 * @param cfaOffset  position of the R site inside the 2x2 quad:
 *                   RGGB(0,0) GRBG(1,0) GBRG(0,1) BGGR(1,1)
 * @param blackLevel per-quad-position black levels, index = dy*2+dx
 * @param whiteLevel sensor white level
 * @param wbGains    per-channel gains (R,G,B), green normalized to 1.0
 * @param ccm        3x3 camera-rgb -> sRGB matrix, row-major
 */
data class RawCalibration(
    val cfaOffset: IntArray,
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    val wbGains: FloatArray,
    val ccm: FloatArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        /** Neutral fallback so a partially-reported camera still renders. */
        fun fallback(): RawCalibration = RawCalibration(
            cfaOffset = intArrayOf(0, 0),
            blackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            whiteLevel = 1023f,
            wbGains = floatArrayOf(1f, 1f, 1f),
            ccm = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )
    }
}
