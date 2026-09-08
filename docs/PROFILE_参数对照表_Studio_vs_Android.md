# Profile 参数对照表（Studio 桌面端 ↔ Android 成像路径）

> 生成于 0.9.17 轮（2026-09-08）。权威 Schema：`profiles/schema/photographer_profile.schema.json`。
> 桌面端参考实现：`tools/profile_renderer.py`（Studio 软件 = 本仓库 desktop/studio 链路生成的 profile JSON，
> 例：`profiles/studio/光月定大青蛙.json`）。
> Android 消费端：预览 = `LutRenderer`（GL 实时链），成片 = `LutImageProcessor`（导出 JPEG 前）。

## 三条注入通道

| 通道 | 载体 | 注入点 | 消费点 |
|---|---|---|---|
| A. Recipe | `ColorRecipeParams`（lutId=`profile:<名>` 存 LutManager DataStore） | `CameraScreen.applyAdjustments` → `ProfileToRecipeMapper.map` | 预览 `PreviewColorShader` + 成片内嵌 recipe shader |
| B. Film 单例 | `FilmParamsStore.current` | 同上（内存单例） | `LutRenderer` + `LutImageProcessor` 的 film/halation/grain/bloom/曲线 pass |
| C. Lens 单例 | `LensParamsStore.current` | 同上 | `LutRenderer` LensStage（预览 L1462）+ `LutImageProcessor` LensStage（成片 L595-665） |

## 逐参数对照

| Schema 参数 | 桌面端（profile_renderer.py） | Android 通道 | 换算/语义 | 状态 |
|---|---|---|---|---|
| exposure.bias | 全局 EV | A: recipe.exposure | 直接对应（双端均 ±2EV） | ✅ |
| white_balance.temperature_bias | ±1 正=暖 | A: recipe.temperature | 直接对应 | ✅ |
| white_balance.tint_bias | ±1 正=品红 | A: recipe.tint | 直接对应 | ✅ |
| color_matrix.matrix_3x3 | apply_color_matrix（rgb@M.T） | B: composeWithProfileColorMatrix | 校准先行、profile 后乘 out=P@(C@v) | ✅ |
| color_matrix.input/output_gamut | 未实现 | — | 双端一致死参数 | ⬜ 两端均未消费 |
| hsl.*（7 区 hue/sat/light） | apply_hsl（LCH 分区） | A: 9 区 LCH 偏移 | sat-1→chroma、light-1→lightness；magenta 混入 purple/blue | ✅ |
| tone_curve.points | 曲线 LUT | A: masterCurvePoints | 恒等曲线传 null | ✅ |
| film_curve.shadow_floor | apply_film_curve（soft-knee） | B: filmCurveShadowFloor | 显示级 0-255，C1 连续膝点 | ✅ |
| film_curve.highlight_ceiling | 同上 | B: filmCurveHighlightCeiling | 同上 | ✅ |
| highlight_rolloff.threshold | apply_highlight_rolloff | A: 参与 toneShoulder=strength×(1-threshold) | 近似（桌面在 lum>threshold 区收肩） | ✅ 近似 |
| highlight_rolloff.strength | 同上 | A: recipe.highlights + toneShoulder | 双重挂载 | ✅ |
| highlight_rolloff.saturation | **声明但函数体未用** | — | 双端一致死参数 | ⬜ 两端均未消费 |
| shadow.black_point | 线性重映射 (x-bp)/(1-bp) | A: toneToe = -bp/0.2 | 近似（曲线 toe 方向一致） | ✅ 近似 |
| shadow.compression | 阴影压向 0.5 | A: recipe.shadows | ShadowsHighlightsShader | ✅ 近似 |
| shadow.tint | RGB 偏移 | A: gradingShadowHue/Amount | RGB→主色调相+强度（有损转换） | ✅ 近似 |
| shadow.saturation | lum+(x-lum)×sat | B: shadowSaturation | 全图饱和（在 shadow pass 内） | ✅ |
| shadow.contrast | **声明但函数体未用**（仅 tonePivot 弱联动 ×0.1） | A: tonePivot=(c-1)×0.1 | 双端一致死参数，Android 有弱联动 | ⬜ 桌面未消费 |
| lens.vignette | apply_lens（Stage 0 光学） | C: LensParams.vignette | 光学暗角（色彩链之前） | ✅ |
| lens.chromatic_aberration | apply_lens | A: recipe.chromaticAberration | recipe 通道 | ✅ |
| lens.sharpness_falloff | apply_lens | C: LensParams.sharpnessFalloff | | ✅ |
| lens.distortion | apply_lens | C: LensParams.distortion | | ✅ |
| lens.bloom | apply_lens | C: LensParams.bloom | | ✅ |
| lens.flare | apply_lens | C: LensParams.flare | | ✅ |
| grain.amount | apply_grain | A: recipe.filmGrain | | ✅ |
| grain.size | apply_grain | B: grainSize | | ✅ |
| grain.density | apply_grain | B: grainDensity | | ✅ |
| noise.luma | —（桌面 schema 有、renderer 未实现） | A: recipe.noise=(luma+chroma).coerce(0,1) | 双通道合并 | ⚠️ 合并语义 |
| noise.chroma | 同上 | 同上 | | ⚠️ 合并语义 |
| halation.amount | apply_halation | B: halationStrength（JSON 桥抹零补偿） | | ✅ |
| halation.radius | 同上 | B: halationRadius | | ✅ |
| halation.threshold | 同上 | B: halationThreshold | | ✅ |
| halation.warmth | 同上 | B: halationWarmth | | ✅ |
| bloom.amount | apply_bloom | A: recipe.bloom | mip 管线强度 | ✅ |
| bloom.threshold | apply_bloom(threshold) | **0.9.17 修复**：B: bloomThreshold → BloomLdrSettings 覆盖 | 未注入走上游默认 0.9 | ✅ 0.9.17 |
| bloom.radius | apply_bloom(radius=高斯核) | **0.9.17 修复**：B: bloomRadius → mip 选择因子 | radius 0.5..4 → 0..1 | ✅ 0.9.17 |
| vignette.amount | apply_vignette | A: recipe.vignette（仅 >0 走桌面公式） | | ✅ |
| vignette.radius | apply_vignette(radius) | **0.9.17 修复**：B: vignetteRadius → uVignetteRadius | dist/(radius×0.7071) | ✅ 0.9.17 |
| vignette.feather | apply_vignette(feather) | **0.9.17 修复**：B: vignetteFeather → uVignetteFeather | 过渡带 | ✅ 0.9.17 |
| vignette.center | apply_vignette(center) | **0.9.17 修复**：B: vignetteCenterX/Y → uVignetteCenter | uv 空间，y 自顶行 | ✅ 0.9.17 |
| sharpen.amount | apply_sharpen | A: recipe.sharpness | | ✅ |
| sharpen.radius | apply_sharpen(radius) | **0.9.17 修复**：B: sharpenRadius → SrgbSharpnessShader.uRadius | shader 端早已支持，此前硬编码 1.0 | ✅ 0.9.17 |

## 0.9.17 修复摘要（本轮）

修复前 Android 端死参数：`vignette.radius/feather/center`、`sharpen.radius`、`bloom.radius/threshold`（6 个）。
修复方式（全部走 FilmParamsStore 下发，profile 卸载/切原生时 `reset()` 回默认，非 profile 状态引擎行为与上游逐字节一致）：

1. `sharpenRadius` → `LutImageProcessor` 锐化 pass 的 `uRadius`（成片）
2. `vignette.radius/feather/center` → `LutImageProcessor` 内嵌 shader + `PreviewColorShader` 新增
   `uVignetteStyle/uVignetteRadius/uVignetteFeather/uVignetteCenter`；profile 激活时走桌面
   `apply_vignette` 公式（仅暗角语义），否则保留上游 smoothstep 公式（预览+成片双端同步）
3. `bloom.threshold/radius` → `BloomLdrSettings.thresholdPrecomputations()` 与 mip 选择因子覆盖

## 与 Studio JSON 的取值范围校验

Studio 生成值（例：`光月定大青蛙.json`）全部在 schema 约束内；
Android 端 coerce 保护：sharpenRadius 0.5..3、vignetteRadius 0.1..1、vignetteFeather 0.05..1、
bloomThreshold 0..1、bloomRadius 归一 0..1。
