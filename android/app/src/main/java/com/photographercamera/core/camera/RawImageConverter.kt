/**
 * RawImageConverter — minimal RAW_SENSOR (RAW16 Bayer) -> ARGB_8888 pipeline for
 * still capture. This replaces the camera ISP's tonemapped JPEG with a
 * linear, profile-controlled path so the GPU LUT chain + film curve operate on
 * clean sensor data (the "RAW -> LUT -> dynamic highlight/shadow" flow):
 *
 *   1. black-level subtraction (per CFA channel, from SensorInfo.BlackLevelPattern)
 *   2. white-balance gains (SENSOR_NEUTRAL_COLOR_POINT, reciprocal convention)
 *   3. normalize by SENSOR_INFO_WHITE_LEVEL
 *   4. bilinear demosaic (single pass, neighbor reads from the raw buffer —
 *      memory-light: raw ShortArray + packed output IntArray only)
 *   5. sensor->XYZ via SENSOR_COLOR_TRANSFORM1/2 (interpolated by color
 *      temperature), then XYZ->sRGB
 *   6. gamma 2.2 encode
 *
 * Everything is computed per-pixel with float math; no GPU involvement here —
 * the result Bitmap is handed to the GPU chain (ProfileRenderer.renderBitmap).
 */
package com.photographercamera.core.camera

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.media.Image
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object RawImageConverter {

    /** Immutable calibration snapshot taken from CameraCharacteristics at open time. */
    class Setup(
        val blackLevel: FloatArray,   // 4 entries in pattern order [offset(0,0),(1,0),(0,1),(1,1)]
        val whiteLevel: Int,
        val cfa: Int,                 // SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
        val ccm1: FloatArray?,        // row-major 3x3, sensor RGB -> XYZ (illuminant 1)
        val ccm2: FloatArray?,        // row-major 3x3, sensor RGB -> XYZ (illuminant 2)
        val cct1: Float,
        val cct2: Float,
    )

    /** Standard illuminant CCTs used to interpolate the two color transforms. */
    private fun illuminantCct(code: Int): Float = when (code) {
        1 -> 2856f   // A
        2 -> 4874f   // B
        3 -> 6800f   // C
        4, 8 -> 5003f  // D50
        5, 9 -> 5503f  // D55
        6, 10 -> 6504f // D65
        7, 11 -> 7504f // D75
        else -> -1f
    }

    fun setupFrom(chars: CameraCharacteristics): Setup {
        val blp = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val bl = if (blp != null) {
            val a = IntArray(4)
            blp.copyTo(a, 0)
            FloatArray(4) { a[it].toFloat() }
        } else FloatArray(4) { 64f }

        val white = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        fun ccmOf(key: CameraCharacteristics.Key<ColorSpaceTransform?>): FloatArray? {
            val cst = chars.get(key) ?: return null
            // 9 rationals, each as a num/den int pair (row-major)
            val r = IntArray(18)
            cst.copyElements(r, 0)
            // Rationals come as num/den pairs in row-major order
            val out = FloatArray(9)
            for (i in 0 until 9) {
                val num = r[i * 2]
                val den = r[i * 2 + 1]
                out[i] = if (den == 0) 0f else num.toFloat() / den
            }
            return out
        }
        val ccm1 = ccmOf(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        val ccm2 = ccmOf(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        val il1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 4
        // SENSOR_REFERENCE_ILLUMINANT2 is TYPE_BYTE in the Camera2 metadata spec
        val il2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 6
        return Setup(bl, white, cfa, ccm1, ccm2, illuminantCct(il1), illuminantCct(il2))
    }

    /**
     * Convert a RAW16 [Image] to an ARGB_8888 Bitmap. [neutralColorPoint] is the
     * reciprocal WB gain triple from the capture result (1/gain per channel);
     * [appliedCcm] (when reported) is the AWB-applied sensor->linear-sRGB matrix
     * and takes precedence over the static SENSOR_COLOR_TRANSFORM interpolation.
     */
    fun rawToBitmap(
        img: Image,
        setup: Setup,
        neutralColorPoint: FloatArray?,
        appliedCcm: FloatArray?,
    ): Bitmap {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val rowStride = plane.rowStride
        val buf = plane.buffer
        val paddedW = rowStride / 2
        // copy into a compact ShortArray once (little-endian RAW16)
        val raw = ShortArray(paddedW * h)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(raw)

        // WB gains: SENSOR_NEUTRAL_COLOR_POINT stores 1/gain per channel.
        val gR = 1f / (neutralColorPoint?.get(0)?.takeIf { it > 0f } ?: 1f)
        val gG = 1f / (neutralColorPoint?.get(1)?.takeIf { it > 0f } ?: 1f)
        val gB = 1f / (neutralColorPoint?.get(2)?.takeIf { it > 0f } ?: 1f)
        val gains = floatArrayOf(gR, gG, gB)

        // CFA map: (x%2, y%2) -> channel (0=R, 1=G, 2=B)
        val map = when (setup.cfa) {
            1 -> intArrayOf(1, 0, 2, 1) // GRBG
            2 -> intArrayOf(1, 2, 0, 1) // GBRG
            3 -> intArrayOf(2, 1, 1, 0) // BGGR
            else -> intArrayOf(0, 1, 1, 2) // RGGB
        }
        // black level per channel (average the two green entries)
        val bl = floatArrayOf(
            setup.blackLevel[0],
            (setup.blackLevel[1] + setup.blackLevel[2]) / 2f,
            setup.blackLevel[3],
        )
        val range = (setup.whiteLevel - min(bl[0], min(bl[1], bl[2]))).coerceAtLeast(1f)

        // Combined matrix:
        //  - appliedCcm (COLOR_CORRECTION_TRANSFORM) ALREADY maps WB'd sensor RGB
        //    -> linear sRGB, so it is used as-is;
        //  - the static SENSOR_COLOR_TRANSFORM1/2 map sensor RGB -> XYZ, so they
        //    must be composed with the XYZ -> sRGB (D65) matrix.
        val xyz2srgb = floatArrayOf(
            3.2404542f, -1.5371385f, -0.4985314f,
            -0.9692660f, 1.8760108f, 0.0415560f,
            0.0556434f, -0.2040259f, 1.0572252f,
        )
        val m: FloatArray = if (appliedCcm != null) {
            appliedCcm
        } else {
            val base = if (setup.ccm1 != null && setup.ccm2 != null && setup.cct1 > 0f &&
                setup.cct2 > 0f && setup.cct2 != setup.cct1
            ) {
                // no per-frame CCT available; split the difference between illuminants
                val w = 0.5f
                FloatArray(9) { i -> setup.ccm1[i] * (1f - w) + setup.ccm2[i] * w }
            } else {
                setup.ccm2 ?: setup.ccm1 ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            }
            FloatArray(9).also { out ->
                for (r in 0 until 3) for (c in 0 until 3) {
                    var acc = 0f
                    for (k in 0 until 3) acc += xyz2srgb[r * 3 + k] * base[k * 3 + c]
                    out[r * 3 + c] = acc
                }
            }
        }

        val out = IntArray(w * h)
        val invGamma = 1f / 2.2f
        val ctx = Ctx(raw, paddedW, map, bl, gains, range, w, h)
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                // calibrated linear value of site (x,y) for channel ch (0..2)
                val site = map[(y and 1) * 2 + (x and 1)]
                val r: Float
                val g: Float
                val b: Float
                if (site == 1) {
                    // green site: which row-neighbour channel decides horizontal avg
                    val leftCh = map[(y and 1) * 2 + ((x - 1) and 1)]
                    g = ctx.cal(x, y)
                    if (leftCh == 0) { // R row: R horizontal, B vertical
                        r = (ctx.cal(max(0, x - 1), y) + ctx.cal(min(w - 1, x + 1), y)) / 2f
                        b = (ctx.cal(x, max(0, y - 1)) + ctx.cal(x, min(h - 1, y + 1))) / 2f
                    } else {           // B row: B horizontal, R vertical
                        b = (ctx.cal(max(0, x - 1), y) + ctx.cal(min(w - 1, x + 1), y)) / 2f
                        r = (ctx.cal(x, max(0, y - 1)) + ctx.cal(x, min(h - 1, y + 1))) / 2f
                    }
                } else if (site == 0) {
                    // red site
                    r = ctx.cal(x, y)
                    g = (ctx.cal(max(0, x - 1), y) + ctx.cal(min(w - 1, x + 1), y) +
                        ctx.cal(x, max(0, y - 1)) + ctx.cal(x, min(h - 1, y + 1))) / 4f
                    b = (ctx.cal(max(0, x - 1), max(0, y - 1)) + ctx.cal(min(w - 1, x + 1), max(0, y - 1)) +
                        ctx.cal(max(0, x - 1), min(h - 1, y + 1)) + ctx.cal(min(w - 1, x + 1), min(h - 1, y + 1))) / 4f
                } else {
                    // blue site
                    b = ctx.cal(x, y)
                    g = (ctx.cal(max(0, x - 1), y) + ctx.cal(min(w - 1, x + 1), y) +
                        ctx.cal(x, max(0, y - 1)) + ctx.cal(x, min(h - 1, y + 1))) / 4f
                    r = (ctx.cal(max(0, x - 1), max(0, y - 1)) + ctx.cal(min(w - 1, x + 1), max(0, y - 1)) +
                        ctx.cal(max(0, x - 1), min(h - 1, y + 1)) + ctx.cal(min(w - 1, x + 1), min(h - 1, y + 1))) / 4f
                }

                // CCM (linear sensor -> linear sRGB)
                val lr = m[0] * r + m[1] * g + m[2] * b
                val lg = m[3] * r + m[4] * g + m[5] * b
                val lb = m[6] * r + m[7] * g + m[8] * b
                // gamma encode
                val sr = (max(0f, lr).pow(invGamma) * 255f).toInt().coerceIn(0, 255)
                val sg = (max(0f, lg).pow(invGamma) * 255f).toInt().coerceIn(0, 255)
                val sb = (max(0f, lb).pow(invGamma) * 255f).toInt().coerceIn(0, 255)
                out[o++] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(out, 0, w, 0, 0, w, h)
        }
    }

    /** Per-capture read-only state shared by the hot loop (avoids per-pixel closures). */
    private class Ctx(
        val raw: ShortArray,
        val paddedW: Int,
        val map: IntArray,
        val bl: FloatArray,
        val gains: FloatArray,
        val range: Float,
        val w: Int,
        val h: Int,
    ) {
        fun cal(cx: Int, cy: Int): Float {
            val ch = map[(cy and 1) * 2 + (cx and 1)]
            val v = max(0f, (raw[cy * paddedW + cx].toInt() and 0xFFFF) - bl[ch])
            return v / range * gains[ch]
        }
    }
}
