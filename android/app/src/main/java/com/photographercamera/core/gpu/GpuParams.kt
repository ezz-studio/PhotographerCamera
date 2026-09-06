/**
 * GpuParams - flattens a [PhotographerProfile] into the exact uniform / texture
 * layout consumed by the GLSL chain (shaders, the *.frag files). This is the single bridge
 * between the data model and the renderer; the renderer never hard-codes a
 * photographer's look.
 *
 * LUT conventions (MUST match tools/glsl_reference.py and the shaders):
 *   - Tone LUT : TONE_LUT_SIZE texels, baked at TEXEL CENTRES, GL_LINEAR.
 *   - HSL LUT  : 7 texels, CATEGORY-indexed in the same order as pc_hue_index()
 *                in shaders/common.glsl, GL_NEAREST.
 *   - Color matrix is uploaded TRANSPOSED: GLSL `m * c` (column-major) then equals
 *     the CPU reference `v @ M^T` (row-vector math). See shaders/color_matrix.glsl.
 *
 * Nothing here depends on a GPU; GpuParams is unit-testable on the JVM.
 */
package com.photographercamera.core.gpu

import com.photographercamera.core.profile.Hsl
import com.photographercamera.core.profile.PhotographerProfile

// Must stay in lock-step with pc_hue_index() in shaders/common.glsl and
// HUE_ORDER in tools/glsl_reference.py.
private val HUE_ORDER = listOf("red", "orange", "yellow", "green", "cyan", "blue", "purple")

const val TONE_LUT_SIZE = 1024
const val HSL_LUT_SIZE = 7

/** Downsample factor for the Bloom/Halation intermediate buffers (see docs/rendering_pipeline.md). */
const val BLOOM_DOWNSCALE = 0.25f

@Suppress("MemberVisibilityCanBePrivate", "unused")
class GpuParams private constructor(
    // main chain
    val exposure: Float,
    val wbTemp: Float,
    val wbTint: Float,
    val colorMatrixGL: FloatArray, // 9 floats, column-major (transposed)
    val toneLut: FloatArray, // TONE_LUT_SIZE
    val hslLut: FloatArray, // 7 * 3 (sat, light, hueShiftDeg)
    val highlightThreshold: Float,
    val highlightStrength: Float,
    val highlightSaturation: Float,
    val shadowBlackPoint: Float,
    val shadowCompression: Float,
    val shadowSaturation: Float,
    // passes
    val sharpenAmount: Float,
    val sharpenRadius: Float,
    val bloomAmount: Float,
    val bloomRadius: Float, // already in low-res texel space
    val bloomThreshold: Float,
    val halationAmount: Float,
    val halationRadius: Float,
    val halationThreshold: Float,
    val halationWarmth: Float,
    val grainVec: FloatArray, // amount, size, density, _unused
    val noiseVec: FloatArray, // luma, chroma, aspect, _unused
    val vignetteAmount: Float,
    val vignetteRadius: Float,
    val vignetteFeather: Float,
    val vignetteCenter: FloatArray, // (x, y) in image space (y down)
    // film curve (output consistency): shadow floor / highlight ceiling in 0..1
    val filmFloor: Float,
    val filmCeil: Float,
    val filmEnabled: Boolean,
    /** 0.5.0: 源 Profile 引用（PhotonCamera recipe 管线映射用；flatten 后仍可回溯）。 */
    val sourceProfile: PhotographerProfile? = null,
) {
    companion object {
        fun from(profile: PhotographerProfile, aspect: Float = 1.0f): GpuParams {
            val m = profile.colorMatrix.matrix3x3
            // CPU reference uses row-vector math v @ M^T. GLSL mat3 is column-major
            // and computes m * c. Uploading M^T makes m_gl * c == M^T * c == v @ M^T.
            val flat = FloatArray(9)
            for (r in 0..2) for (c in 0..2) {
                val row = m.getOrElse(r) { emptyList() }
                val v = row.getOrElse(c) { 0f }
                flat[c * 3 + r] = v // transpose: index = c*3 + r (column-major)
            }

            return GpuParams(
                exposure = profile.exposure.bias,
                wbTemp = profile.whiteBalance.temperatureBias,
                wbTint = profile.whiteBalance.tintBias,
                colorMatrixGL = flat,
                toneLut = bakeToneLut(profile.toneCurve.points),
                hslLut = bakeHslLut(profile.hsl),
                highlightThreshold = profile.highlightRolloff.threshold,
                highlightStrength = profile.highlightRolloff.strength,
                highlightSaturation = profile.highlightRolloff.saturation,
                shadowBlackPoint = profile.shadow.blackPoint,
                shadowCompression = profile.shadow.compression,
                shadowSaturation = profile.shadow.saturation,
                sharpenAmount = profile.sharpen.amount,
                sharpenRadius = profile.sharpen.radius,
                bloomAmount = profile.bloom.amount,
                bloomRadius = profile.bloom.radius * BLOOM_DOWNSCALE,
                bloomThreshold = profile.bloom.threshold,
                halationAmount = profile.halation.amount,
                halationRadius = profile.halation.radius * BLOOM_DOWNSCALE,
                halationThreshold = profile.halation.threshold,
                halationWarmth = profile.halation.warmth,
                grainVec = floatArrayOf(
                    profile.grain.amount,
                    profile.grain.size,
                    profile.grain.density,
                    0f,
                ),
                noiseVec = floatArrayOf(
                    normNoise(profile.noise.luma),
                    normNoise(profile.noise.chroma),
                    aspect,
                    0f,
                ),
                vignetteAmount = profile.vignette.amount,
                vignetteRadius = profile.vignette.radius,
                vignetteFeather = profile.vignette.feather,
                vignetteCenter = profile.vignette.center.toFloatArrayOr(0.5f, 0.5f),
                filmFloor = (profile.filmCurve.shadowFloor / 255f).coerceIn(0f, 0.4f),
                filmCeil = (profile.filmCurve.highlightCeiling / 255f).coerceIn(0.6f, 1f),
                filmEnabled = profile.filmCurve.shadowFloor > 0f || profile.filmCurve.highlightCeiling < 255f,
                sourceProfile = profile,
            )
        }

        /** Bake control points at texel centres so a GL_LINEAR fetch at u=c is the curve. */
        fun bakeToneLut(points: List<List<Float>>): FloatArray {
            if (points.size < 2) return identityToneLut()
            val xs = FloatArray(points.size) { points[it][0] }
            val ys = FloatArray(points.size) { points[it][1] }
            sortPairs(xs, ys)
            val out = FloatArray(TONE_LUT_SIZE)
            for (i in 0 until TONE_LUT_SIZE) {
                val u = (i + 0.5f) / TONE_LUT_SIZE
                out[i] = piecewiseLinear(xs, ys, u.coerceIn(0f, 1f))
            }
            return out
        }

        private fun identityToneLut(): FloatArray =
            FloatArray(TONE_LUT_SIZE) { (it + 0.5f) / TONE_LUT_SIZE }

        /**
         * Analyzer-measured noise arrives on a 0-100-ish scale (e.g. a
         * recompressed source JPEG measured chroma_noise=20.83); the shader
         * expects ~[0,1]. Passing the raw value once amplified chroma noise to
         * ±40% of channel range and destroyed every saved still. Normalize
         * anything above 1 by /100 and hard-cap both channels so even a broken
         * profile can only ever inject subtle film-like noise.
         */
        private fun normNoise(v: Float): Float =
            (if (v > 1f) v / 100f else v).coerceIn(0f, 0.5f)

        /** 7 texels, one per hue category, in HUE_ORDER. R=sat, G=light, B=hueShift(deg). */
        fun bakeHslLut(hsl: Hsl): FloatArray {
            val out = FloatArray(HSL_LUT_SIZE * 3)
            for ((k, key) in HUE_ORDER.withIndex()) {
                val c = when (key) {
                    "red" -> hsl.red
                    "orange" -> hsl.orange
                    "yellow" -> hsl.yellow
                    "green" -> hsl.green
                    "cyan" -> hsl.cyan
                    "blue" -> hsl.blue
                    else -> hsl.purple
                }
                out[k * 3 + 0] = c.saturation
                out[k * 3 + 1] = c.lightness
                out[k * 3 + 2] = c.hueShift
            }
            return out
        }

        private fun piecewiseLinear(xs: FloatArray, ys: FloatArray, x: Float): Float {
            if (x <= xs[0]) return ys[0]
            if (x >= xs[xs.lastIndex]) return ys[ys.lastIndex]
            for (i in 0 until xs.lastIndex) {
                if (x >= xs[i] && x <= xs[i + 1]) {
                    val t = if (xs[i + 1] == xs[i]) 0f else (x - xs[i]) / (xs[i + 1] - xs[i])
                    return ys[i] + t * (ys[i + 1] - ys[i])
                }
            }
            return ys[ys.lastIndex]
        }

        private fun sortPairs(xs: FloatArray, ys: FloatArray) {
            val n = xs.size
            for (i in 0 until n - 1) {
                for (j in 0 until n - 1 - i) {
                    if (xs[j] > xs[j + 1]) {
                        val tx = xs[j]; xs[j] = xs[j + 1]; xs[j + 1] = tx
                        val ty = ys[j]; ys[j] = ys[j + 1]; ys[j + 1] = ty
                    }
                }
            }
        }
    }

    /** Return a copy with optional live overrides (EV / WB / Grain) for QuickControls.
     *  * [ev]/[wbTemp]/[wbTint] are absolute offsets in the profile's own units.
     *  * [grain] is a MULTIPLIER on the profile's own grain amount (1.0 = unchanged),
     *    so each preset keeps its distinct grain character (MONO 400 coarse vs
     *    FRESH 200 fine) instead of every preset receiving the same grain. */
    fun withAdjustments(
        ev: Float? = null,
        wbTemp: Float? = null,
        wbTint: Float? = null,
        grain: Float? = null,
    ): GpuParams = GpuParams(
        exposure = ev ?: this.exposure,
        wbTemp = wbTemp ?: this.wbTemp,
        wbTint = wbTint ?: this.wbTint,
        colorMatrixGL = this.colorMatrixGL,
        toneLut = this.toneLut,
        hslLut = this.hslLut,
        highlightThreshold = this.highlightThreshold,
        highlightStrength = this.highlightStrength,
        highlightSaturation = this.highlightSaturation,
        shadowBlackPoint = this.shadowBlackPoint,
        shadowCompression = this.shadowCompression,
        shadowSaturation = this.shadowSaturation,
        sharpenAmount = this.sharpenAmount,
        sharpenRadius = this.sharpenRadius,
        bloomAmount = this.bloomAmount,
        bloomRadius = this.bloomRadius,
        bloomThreshold = this.bloomThreshold,
        halationAmount = this.halationAmount,
        halationRadius = this.halationRadius,
        halationThreshold = this.halationThreshold,
        halationWarmth = this.halationWarmth,
        grainVec = if (grain != null) floatArrayOf(this.grainVec[0] * grain, this.grainVec[1], this.grainVec[2], this.grainVec[3]) else this.grainVec,
        noiseVec = this.noiseVec,
        vignetteAmount = this.vignetteAmount,
        vignetteRadius = this.vignetteRadius,
        vignetteFeather = this.vignetteFeather,
        vignetteCenter = this.vignetteCenter,
        filmFloor = this.filmFloor,
        filmCeil = this.filmCeil,
        filmEnabled = this.filmEnabled,
    )
}

private fun List<Float>.toFloatArrayOr(a: Float, b: Float): FloatArray {
    return when {
        size >= 2 -> floatArrayOf(this[0], this[1])
        size == 1 -> floatArrayOf(this[0], b)
        else -> floatArrayOf(a, b)
    }
}
