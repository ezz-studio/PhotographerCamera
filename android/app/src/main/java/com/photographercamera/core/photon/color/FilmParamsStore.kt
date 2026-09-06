/*
 * FilmParamsStore — 0.9.1 新增（自研胶水，与 LensParamsStore 同模式）。
 *
 * ColorRecipeParams 的 JSON 桥（CameraScreen: toJson→fromJson）会把 halation
 * 强制抹零（上游 ColorRecipeParams.toJson 的兼容设计），且 film_curve 端点、
 * grain 颗粒度、相机原色矩阵、shadow.saturation 在 recipe/JSON 通道里没有
 * 对应字段——这些 profile 参数经本单例下发，预览（LutRenderer）与成片
 * （LutImageProcessor）读取。
 *
 * 单例桥接避免改动 photon 移植树的调用面——与 LensParamsStore 一致，
 * 实机验证后可升级为按 lutId 持久化。
 */
package com.photographercamera.core.photon.color

object FilmParamsStore {

    /** 全默认值（未注入 profile / profile 卸载后的回退），halation 链保持关闭。 */
    data class FilmParams(
        val halationStrength: Float = 0f,
        val halationRadius: Float = 1f,
        val halationThreshold: Float = 0.9f,
        val halationWarmth: Float = 1f,
        val grainSize: Float = 1f,
        val grainDensity: Float = 1f,
        val shadowSaturation: Float = 1f,
        val filmCurveShadowFloor: Float = 8f,
        val filmCurveHighlightCeiling: Float = 248f,
        val colorMatrix3x3: List<List<Float>> = listOf(
            listOf(1f, 0f, 0f),
            listOf(0f, 1f, 0f),
            listOf(0f, 0f, 1f),
        ),
    ) {
        val isIdentity: Boolean
            get() = halationStrength <= 0f && grainSize == 1f && grainDensity == 1f &&
                shadowSaturation == 1f && filmCurveShadowFloor == 8f &&
                filmCurveHighlightCeiling == 248f && colorMatrix3x3 == IDENTITY_MATRIX
    }

    @Volatile
    var current: FilmParams = FilmParams()

    /** profile 卸载 / 切到非 profile lut 时回默认，防残留毒化。 */
    fun reset() {
        current = FilmParams()
    }

    private val IDENTITY_MATRIX = listOf(
        listOf(1f, 0f, 0f),
        listOf(0f, 1f, 0f),
        listOf(0f, 0f, 1f),
    )
}
