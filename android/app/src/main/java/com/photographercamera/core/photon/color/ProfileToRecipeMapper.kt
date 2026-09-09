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

import com.photographercamera.core.photon.lens.LensParams
import com.photographercamera.core.profile.ColorMatrix
import com.photographercamera.core.profile.Exposure
import com.photographercamera.core.profile.HighlightRolloff
import com.photographercamera.core.profile.Hsl
import com.photographercamera.core.profile.PhotographerProfile
import com.photographercamera.core.profile.Shadow
import com.photographercamera.core.profile.ToneCurve
import com.photographercamera.core.profile.WhiteBalance

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
    /** halation.amount 原值（recipe.halation 经 toJson 桥会被上游强制抹零）。 */
    val halationAmount: Float,
    /** shadow.saturation 原值（0..2，阴影区饱和度乘数；recipe 无对应字段）。 */
    val shadowSaturation: Float,
    /** lens 光学阶段参数（distortion/falloff/vignette/bloom/flare；CA 走 recipe）。 */
    val lens: com.photographercamera.core.photon.lens.LensParams =
        com.photographercamera.core.photon.lens.LensParams.ZERO,
)

data class RecipeMapping(val recipe: ColorRecipeParams, val residual: ResidualParams)

object ProfileToRecipeMapper {

    /**
     * stylefit v3 判定：profile 自带 3D 风格 LUT（color_layer=="lut" 且 payload 有数据）。
     * 语义与桌面 tools/profile_renderer.py render() 一致——3D LUT 是唯一风格色阶段，
     * 存在时必须旁路整条参数化色链（exposure/wb/matrix/highlight/shadow/tone_curve/hsl），
     * 否则双重加风格（LUT+参数链叠加）导致严重过调/偏色。
     */
    fun isLutProfile(p: PhotographerProfile): Boolean =
        p.colorLayer == "lut" && !p.colorLut?.data.isNullOrBlank()

    /**
     * LUT 分支用的"色链中性化"副本：参数化色层全部回 schema 默认（=identity），
     * 空间层（grain/bloom/halation/vignette/sharpen/lens/film_curve）原样保留，
     * map() 主体无需感知该分支。
     */
    private fun neutralizeColorLayers(p: PhotographerProfile): PhotographerProfile = p.copy(
        exposure = Exposure(),
        whiteBalance = WhiteBalance(),
        colorMatrix = ColorMatrix(),
        highlightRolloff = HighlightRolloff(),
        shadow = Shadow(),
        toneCurve = ToneCurve(),
        hsl = Hsl(),
    )

    /**
     * PhotographerProfile → ColorRecipeParams(+ResidualParams)。
     * 每个字段的单位换算见行内注释；默认值对默认值，identity 对 identity。
     */
    fun map(p: PhotographerProfile): RecipeMapping {
        val effective = if (isLutProfile(p)) neutralizeColorLayers(p) else p
        // ColorRecipeParams 是全 val data class：用 copy 链逐步覆盖（不可变风格）。
        var r = ColorRecipeParams()

        r = r.copy(
            // EV 直接对应（双方都是 -2..+2 EV）
            exposure = effective.exposure.bias,
            // 双方都为 ±1 归一化，正=暖 / 正=品红（schema description 已确认）
            temperature = effective.whiteBalance.temperatureBias,
            tint = effective.whiteBalance.tintBias,
            highlights = effective.highlightRolloff.strength, // 0..1 → -1..+1 区间的正向压缩
            shadows = effective.shadow.compression,           // 0..1 压暗方向
            // 影调：black_point(0..0.2) 提升黑位 → toe 负方向（提黑）; 用 /0.2 归一
            toneToe = -effective.shadow.blackPoint / 0.2f,
            // 高光滚降 threshold(0..1) 越低越早起肩 → shoulder 正向塑形
            toneShoulder = effective.highlightRolloff.strength * (1f - effective.highlightRolloff.threshold),
            // 中间调反差：shadow.contrast (1=中性) → pivot 轻度联动（保守 1/10）
            tonePivot = (effective.shadow.contrast - 1f) * 0.1f,
            contrast = 1f, // 我方 contrast 语义在 shadow.contrast/toneCurve 中，recipe contrast 保持中性
            // lens.vignette（光学暗角）与 style vignette.amount 独立（HANDOFF_android_lens）：
            // 光学暗角走 LensStage（Stage 0，色彩链之前），此处只保留风格化暗角。
            vignette = effective.vignette.amount,
            chromaticAberration = effective.lens.chromaticAberration,
            // 颗粒（银盐质感）走原生 applyDensityFilmGrain 通路（simplex 银盐颗粒，
            // 与原生滤镜同一套），保持 1:1 接线上游。下方 noise 才是「刺眼噪点」元凶。
            filmGrain = effective.grain.amount,
            // 0.10.x 修复（用户指令：去掉 profile 里面的噪点）：profile 不再驱动独立的
            // uNoise 纯随机通道。该通道每帧重随机、带彩色、观感刺眼，且原生滤镜的
            // noise 恒为 0 —— 收敛到原生行为后，profile 与原生滤镜一致只有银盐颗粒，
            // 不再叠加传感器式噪点 / 色彩断层。profile 自身的 noise.luma/chroma 仅用于
            // 桌面端分析参考，不再下发到实时渲染通道。
            noise = 0f,
            halation = effective.halation.amount,
            bloom = effective.bloom.amount,
            sharpness = effective.sharpen.amount,
        )

        r = mapHsl(effective.hsl, r)
        r = mapShadowTint(effective, r)
        r = r.copy(masterCurvePoints = flattenCurve(effective.toneCurve.points))

        val residual = ResidualParams(
            colorMatrix3x3 = effective.colorMatrix.matrix3x3,
            grainSize = effective.grain.size,
            grainDensity = effective.grain.density,
            halationRadius = effective.halation.radius,
            halationThreshold = effective.halation.threshold,
            halationWarmth = effective.halation.warmth,
            vignetteRadius = effective.vignette.radius,
            vignetteFeather = effective.vignette.feather,
            vignetteCenter = effective.vignette.center,
            sharpenRadius = effective.sharpen.radius,
            bloomRadius = effective.bloom.radius,
            bloomThreshold = effective.bloom.threshold,
            shadowTint = effective.shadow.tint,
            // 0.10.x / 1.3.1 修复（用户指令：胶片人像等「抬黑」profile 显形噪点）：
            // shadow_floor 把 0..N 区间整体抬到 N 的「黑位抬升」，会把传感器读出噪声
            // 暴露在暗部（尤其 RAW_MAX）。原生 identity profile 此值为 0（noise 埋在纯黑），
            // 故对超过引擎中性基准 8 的值做硬上限 10，压制过度抬黑带来的显形噪点，
            // 同时保留轻微胶片抬黑感。recipe 的 shadow.blackPoint 抬黑由 toneToe 单独处理。
            filmCurveShadowFloor = minOf(effective.filmCurve.shadowFloor, 10f),
            filmCurveHighlightCeiling = effective.filmCurve.highlightCeiling,
            halationAmount = effective.halation.amount,
            shadowSaturation = effective.shadow.saturation,
            lens = LensParams.fromProfile(effective.lens),
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

    /** Profile 全默认（identity）→ 可跳过调色链直接输出。自带 3D LUT 的 profile 永非 identity。 */
    fun isIdentity(p: PhotographerProfile): Boolean {
        if (isLutProfile(p)) return false
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
        halationAmount = 0f,
        shadowSaturation = 1f,
        lens = LensParams.ZERO,
    )
}
