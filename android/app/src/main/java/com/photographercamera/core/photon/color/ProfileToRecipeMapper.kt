/*
 * ProfileToRecipeMapper — 0.5.0 新增（自研胶水，非移植代码）。
 *
 * 把我方 PhotographerProfile（schema: profiles/schema/photographer_profile.schema.json）
 * 映射到移植自 PhotonCamera 的 ColorRecipeParams + ResidualParams。
 *
 * 设计原则：
 *  - Profile 是唯一事实来源；ColorRecipe 只是被复用的"执行引擎"。
 *  - 换算公式集中在这里，桌面端 tools/profile_renderer.py 与 Android 预览
 *    内核共享同一套语义（见 docs/REFACTOR_050_photon_integration.md 第 3 节）。
 */
package com.photographercamera.core.photon.color

import com.photographercamera.core.profile.Hsl
import com.photographercamera.core.profile.PhotographerProfile

/** ColorRecipeParams 覆盖不了的参数，由我方保留 pass / shader 扩展 uniform 消费。 */
data class ResidualParams(
    val colorMatrix3x3: List<List<Float>>,
    val grainSize: Float,
    val grainDensity: Float,
    val halationRadius: Float,
    val halationThreshold: Float,
    val halationWarmth: Float,
    val vignetteRadius: Float,
    val vignetteFeather: Float,
    val vignetteCenter: List<Float>,
    val sharpenRadius: Float,
    val bloomRadius: Float,
    val bloomThreshold: Float,
    val shadowTint: List<Float>,
    val filmCurveShadowFloor: Float,
    val filmCurveHighlightCeiling: Float,
)

data class RecipeMapping(val recipe: ColorRecipeParams, val residual: ResidualParams)

object ProfileToRecipeMapper {

    /**
     * PhotographerProfile → ColorRecipeParams(+ResidualParams)。
     * 每个字段的单位换算见行内注释；默认值对默认值，identity 对 identity。
     */
    fun map(p: PhotographerProfile): RecipeMapping {
        // ColorRecipeParams 是全 val data class：用 copy 链逐步覆盖（不可变风格）。
        var r = ColorRecipeParams()

        r = r.copy(
            // EV 直接对应（双方都是 -2..+2 EV）
            exposure = p.exposure.bias,
            // 双方都为 ±1 归一化，正=暖 / 正=品红（schema description 已确认）
            temperature = p.whiteBalance.temperatureBias,
            tint = p.whiteBalance.tintBias,
            highlights = p.highlightRolloff.strength, // 0..1 → -1..+1 区间的正向压缩
            shadows = p.shadow.compression,           // 0..1 压暗方向
            // 影调：black_point(0..0.2) 提升黑位 → toe 负方向（提黑）; 用 /0.2 归一
            toneToe = -p.shadow.blackPoint / 0.2f,
            // 高光滚降 threshold(0..1) 越低越早起肩 → shoulder 正向塑形
            toneShoulder = p.highlightRolloff.strength * (1f - p.highlightRolloff.threshold),
            // 中间调反差：shadow.contrast (1=中性) → pivot 轻度联动（保守 1/10）
            tonePivot = (p.shadow.contrast - 1f) * 0.1f,
            contrast = 1f, // 我方 contrast 语义在 shadow.contrast/toneCurve 中，recipe contrast 保持中性
            vignette = p.vignette.amount.takeIf { it != 0f } ?: p.lens.vignette,
            chromaticAberration = p.lens.chromaticAberration,
            filmGrain = p.grain.amount,
            noise = (p.noise.luma + p.noise.chroma).coerceIn(0f, 1f),
            halation = p.halation.amount,
            bloom = p.bloom.amount,
            sharpness = p.sharpen.amount,
        )

        r = mapHsl(p.hsl, r)
        r = mapShadowTint(p, r)
        r = r.copy(masterCurvePoints = flattenCurve(p.toneCurve.points))

        val residual = ResidualParams(
            colorMatrix3x3 = p.colorMatrix.matrix3x3,
            grainSize = p.grain.size,
            grainDensity = p.grain.density,
            halationRadius = p.halation.radius,
            halationThreshold = p.halation.threshold,
            halationWarmth = p.halation.warmth,
            vignetteRadius = p.vignette.radius,
            vignetteFeather = p.vignette.feather,
            vignetteCenter = p.vignette.center,
            sharpenRadius = p.sharpen.radius,
            bloomRadius = p.bloom.radius,
            bloomThreshold = p.bloom.threshold,
            shadowTint = p.shadow.tint,
            filmCurveShadowFloor = p.filmCurve.shadowFloor,
            filmCurveHighlightCeiling = p.filmCurve.highlightCeiling,
        )

        return RecipeMapping(
            r,
            residual,
        )
    }

    /**
     * 我方 7 区 HSL（hue_shift/saturation/lightness，中性=0/1/1）→
     * recipe 9 区 LCH 偏移（hue/chroma/lightness，中性=0/0/0）。
     * - hue_shift 直接对应 hue 偏移；
     * - saturation 1→chroma 0，>1 增色、<1 减色（线性 ×1）；
     * - lightness 1→lightness 0（偏移语义）。
     */
    private fun mapHsl(hsl: Hsl, out: ColorRecipeParams): ColorRecipeParams {
        // recipe 顺序: skin/red/orange/yellow/green/cyan/blue/purple/magenta
        // 我方没有 skin(肤色) 与 magenta 独立项，留 0；magenta 混入 purple/blue 由
        // schema 层吸收（我方 7 区与 recipe 8+1 区的子集关系）。
        return out.copy(
            redHue = hsl.red.hueShift,
            redChroma = hsl.red.saturation - 1f,
            redLightness = hsl.red.lightness - 1f,
            orangeHue = hsl.orange.hueShift,
            orangeChroma = hsl.orange.saturation - 1f,
            orangeLightness = hsl.orange.lightness - 1f,
            yellowHue = hsl.yellow.hueShift,
            yellowChroma = hsl.yellow.saturation - 1f,
            yellowLightness = hsl.yellow.lightness - 1f,
            greenHue = hsl.green.hueShift,
            greenChroma = hsl.green.saturation - 1f,
            greenLightness = hsl.green.lightness - 1f,
            cyanHue = hsl.cyan.hueShift,
            cyanChroma = hsl.cyan.saturation - 1f,
            cyanLightness = hsl.cyan.lightness - 1f,
            blueHue = hsl.blue.hueShift,
            blueChroma = hsl.blue.saturation - 1f,
            blueLightness = hsl.blue.lightness - 1f,
            purpleHue = hsl.purple.hueShift,
            purpleChroma = hsl.purple.saturation - 1f,
            purpleLightness = hsl.purple.lightness - 1f,
        )
    }

    /**
     * shadow.tint（RGB 偏移，-1..1/通道）→ 三分区调色的阴影 hue/amount：
     * 转成主色调色相 + 强度。中性全零 → amount 0。
     */
    private fun mapShadowTint(p: PhotographerProfile, out: ColorRecipeParams): ColorRecipeParams {
        val t = p.shadow.tint
        if (t.size < 3 || (t[0] == 0f && t[1] == 0f && t[2] == 0f)) return out
        // RGB→Hue：取最大通道主导色相（0..360），amount 用通道绝对幅度的均值
        val maxc = maxOf(t[0], t[1], t[2])
        val minc = minOf(t[0], t[1], t[2])
        val amount = ((kotlin.math.abs(t[0]) + kotlin.math.abs(t[1]) + kotlin.math.abs(t[2])) / 3f)
            .coerceIn(0f, 1f)
        if (maxc == minc) return out // 纯灰偏移，无色相
        val hue = when (maxc) {
            t[0] -> 60f * (((t[1] - t[2]) / (maxc - minc)) % 6f)
            t[1] -> 60f * (((t[2] - t[0]) / (maxc - minc)) + 2f)
            else -> 60f * (((t[0] - t[1]) / (maxc - minc)) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return out.copy(gradingShadowHue = hue / 360f, gradingShadowAmount = amount)
    }

    /** [[x,y],...] → floatArray [x0,y0,x1,y1,...]，recipe 曲线纹理格式。 */
    private fun flattenCurve(points: List<List<Float>>): FloatArray? {
        if (points.size < 2) return null
        val isIdentity = points.size == 2 &&
            points[0][0] == 0f && points[0][1] == 0f &&
            points[1][0] == 1f && points[1][1] == 1f
        if (isIdentity) return null
        val arr = FloatArray(points.size * 2)
        points.forEachIndexed { i, pt ->
            arr[i * 2] = pt[0]
            arr[i * 2 + 1] = pt[1]
        }
        return arr
    }

    /** Profile 全默认（identity）→ 可跳过调色链直接输出。 */
    fun isIdentity(p: PhotographerProfile): Boolean {
        val m = map(p)
        return m.recipe.isDefault() && isIdentityCurve(m.recipe.masterCurvePoints) &&
            m.residual == identityResidual()
    }

    private fun isIdentityCurve(pts: FloatArray?): Boolean = pts == null

    private fun identityResidual(): ResidualParams = ResidualParams(
        colorMatrix3x3 = listOf(listOf(1f, 0f, 0f), listOf(0f, 1f, 0f), listOf(0f, 0f, 1f)),
        grainSize = 1f, grainDensity = 1f,
        halationRadius = 1f, halationThreshold = 0.9f, halationWarmth = 1f,
        vignetteRadius = 1f, vignetteFeather = 0.5f, vignetteCenter = listOf(0.5f, 0.5f),
        sharpenRadius = 1f, bloomRadius = 1f, bloomThreshold = 0.9f,
        shadowTint = listOf(0f, 0f, 0f),
        filmCurveShadowFloor = 8f, filmCurveHighlightCeiling = 248f,
    )
}
