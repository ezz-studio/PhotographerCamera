package com.photographercamera.photon.lut

import com.photographercamera.photon.color.TransferCurve
import com.photographercamera.photon.model.ColorRecipeParams
import kotlin.math.abs

internal enum class PreviewColorTextureSource {
    EXTERNAL_OES,
    TEXTURE_2D,
}

internal data class PreviewColorShaderVariant(
    val textureSource: PreviewColorTextureSource,
    val includeExtendedLutCurves: Boolean,
    val includeOklchDensity: Boolean,
    val includeLchMixer: Boolean,
    val includePreLogFilmGrain: Boolean,
    val includeLutMask: Boolean = false,
    val includeJpegInputToneCurve: Boolean = false,
    val includeSpatialRecipeEffects: Boolean = false,
) {
    /**
     * 最小可用变体：关掉所有可选特性（它们各自引入额外函数/纹理依赖，是编译失败的主要来源），
     * 只保留与输入契约相关的项（纹理来源、JPEG 输入曲线、空间效果开关）。
     * 供 ColorPassProgram 的降级兜底使用：宁可少几项风格，也不要让取景预览全黑。
     */
    fun minimalFallback(): PreviewColorShaderVariant = copy(
        includeExtendedLutCurves = false,
        includeOklchDensity = false,
        includeLchMixer = false,
        includePreLogFilmGrain = false,
        includeLutMask = false,
    )

    companion object {
        fun forPass(
            textureSource: PreviewColorTextureSource,
            params: ColorRecipeParams,
            lutConfig: LutConfig?,
            lutEnabled: Boolean,
            videoLogEnabled: Boolean,
        ): PreviewColorShaderVariant {
            val lutCurve = lutConfig?.curve ?: TransferCurve.SRGB
            return PreviewColorShaderVariant(
                textureSource = textureSource,
                includeExtendedLutCurves = videoLogEnabled ||
                    (lutEnabled && lutCurve.shaderId !in SIMPLE_LUT_CURVES),
                includeOklchDensity = abs(params.color) > EPSILON,
                includeLchMixer = params.hasLchAdjustments(),
                // A custom Log recording has no display LUT, so bake grain before encoding.
                includePreLogFilmGrain = videoLogEnabled &&
                    !lutEnabled &&
                    params.filmGrain > EPSILON,
            )
        }

        private val SIMPLE_LUT_CURVES = setOf(TransferCurve.SRGB.shaderId, TransferCurve.LINEAR.shaderId)
        private const val EPSILON = 0.001f
    }
}

private fun ColorRecipeParams.hasLchAdjustments(): Boolean {
    return abs(skinHue) > 0.001f ||
        abs(skinChroma) > 0.001f ||
        abs(skinLightness) > 0.001f ||
        abs(redHue) > 0.001f ||
        abs(redChroma) > 0.001f ||
        abs(redLightness) > 0.001f ||
        abs(orangeHue) > 0.001f ||
        abs(orangeChroma) > 0.001f ||
        abs(orangeLightness) > 0.001f ||
        abs(yellowHue) > 0.001f ||
        abs(yellowChroma) > 0.001f ||
        abs(yellowLightness) > 0.001f ||
        abs(greenHue) > 0.001f ||
        abs(greenChroma) > 0.001f ||
        abs(greenLightness) > 0.001f ||
        abs(cyanHue) > 0.001f ||
        abs(cyanChroma) > 0.001f ||
        abs(cyanLightness) > 0.001f ||
        abs(blueHue) > 0.001f ||
        abs(blueChroma) > 0.001f ||
        abs(blueLightness) > 0.001f ||
        abs(purpleHue) > 0.001f ||
        abs(purpleChroma) > 0.001f ||
        abs(purpleLightness) > 0.001f ||
        abs(magentaHue) > 0.001f ||
        abs(magentaChroma) > 0.001f ||
        abs(magentaLightness) > 0.001f
}
