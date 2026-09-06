/*
 * PhotographerCamera 自有 glue 层 —— lens 光学阶段参数。
 *
 * 对应桌面端 tools/profile_renderer.py 的 apply_lens()（Stage 0，光学先于一切
 * 风格化）。schema `lens.*` 六参数中 chromatic_aberration 已由 photon 配方的
 * 空间效应承接（PreviewColorShader uChromaticAberration，径向分通道），其余
 * 五项由 LensStageGl 在 photon 颜色链之前执行。
 */
package com.photographercamera.core.photon.lens

import com.photographercamera.core.profile.Lens

/** lens 光学阶段参数（0 = 该项直通；全 0 = 整个阶段跳过，渲染逐像素不变）。 */
data class LensParams(
    val distortion: Float = 0f,
    val sharpnessFalloff: Float = 0f,
    val vignette: Float = 0f,
    val bloom: Float = 0f,
    val flare: Float = 0f,
) {
    val isZero: Boolean
        get() = distortion == 0f && sharpnessFalloff == 0f &&
            vignette == 0f && bloom == 0f && flare == 0f

    companion object {
        val ZERO = LensParams()

        /** profile.lens（schema camelCase 映射）→ LensParams。CA 不在此处（走配方）。 */
        fun fromProfile(lens: Lens): LensParams = LensParams(
            distortion = lens.distortion,
            sharpnessFalloff = lens.sharpnessFalloff,
            vignette = lens.vignette,
            bloom = lens.bloom,
            flare = lens.flare,
        )
    }
}

/**
 * 当前生效的 lens 参数。profile 注入（CameraScreen.applyAdjustments）时写入；
 * 预览（LutRenderer）与成片（LutImageProcessor）每帧/每张读取。
 * 单例桥接避免改动 photon 移植树的调用面——与 ColorRecipeParams 走
 * DataStore 的正式通道并行，实机验证后可升级为按 lutId 持久化。
 */
object LensParamsStore {
    @Volatile
    var current: LensParams = LensParams.ZERO
}
