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

---

## Phase B 修正（2026-10-15，基线 1.7.6 / c4523dc）

以下条目依据 Phase A 只读审计（`_audit_phase_a/03_profile_param_map.md`，逐字段带 file:line）修正，
**修正旧表结论，以代码为准**：

### 1. 旧表结论修正

| 参数 | 旧表结论 | Phase A 实证 | 新结论 |
|---|---|---|---|
| `shadow.saturation` | ✅ 全图饱和 | 仅注入 `FilmParamsStore.shadowSaturation`，**全仓无任何 uniform 消费端** | ⬜ **Android 死参数**（桌面端 `apply_shadow` 正常消费；双端不等价，勿标 ✅） |
| `noise.luma/chroma` | ⚠️ 合并语义 | `ProfileToRecipeMapper.kt:105` 硬编码 `noise = 0f`（0.10.x 用户指令） | ⛔ **Android 实时通道不消费**（既定设计边界：桌面仅分析参考；严禁重新映射成任意 Noise 参数） |
| `highlight_rolloff.strength` | ✅ 双重挂载 | `Mapper:84→recipe.highlights`（uHighlights）+ `Mapper:89→toneShoulder`（uToneShoulder），同一像素顺序施加两种非线性变换，**注释明示为有意双通道塑形** | ✅ **有意设计，保留**（PA-13；非 bug，禁止删任何一侧） |
| `shadow.contrast` | ⬜ 桌面未消费 | Android `tonePivot=(c-1)×0.1` 弱联动生效 | ✅ Android 近似联动（桌面确为死参数） |
| `halation.radius/threshold/warmth` | ✅ 已注入 | 已注入 FilmParamsStore，但渲染链未找到对应 uniform 消费 | ⚠️ **已注入未消费**（Phase C/E 接线候选；当前改动这些值不影响成像） |
| `color_matrix.input_gamut/output_gamut` | ⬜ 双端死 | 双端均无消费 | ⬜ 双端死参数（文档标记，不接线） |
| `color_adaptive` | 未列 | 全链无消费 | ⬜ Android 死字段（预留） |
| `color_lut.source/strength` | 未列 | strength=烘焙元数据（已烘进 data，运行时 100% 采样即正确）；source 无消费 | ⬜ 元数据字段（非缺陷） |

### 2. HSL hue_shift 单位语义（PA-05，需求 2.5/6.2）—— 2026 复核修正

**结论：profile 的 `hue_shift` 字段在两端（Studio ↔ Android HSL-LUT 阶段）语义一致，均为「度」（`/360.0`），
并非此前误判的 18–20 倍差异。此前结论已作废。**

- 桌面 `profile_renderer.apply_hsl`（`tools/profile_renderer.py`）：`hue_shift / 360.0` —— 按**度**解释。
- Android HSL-LUT 阶段：`assets/shaders/hsl.glsl:19` 与 `effect.frag:283` 均为 `pl.b / 360.0`，**同样按度**，
  与桌面逐字节等价（CPU 参考测试 `tests/test_shader_equivalence.py` 覆盖）。
- **此前「18 倍不匹配」的误判来源**：Android 另有一条**独立的 OKLCH 调色阶段**
  （`uLchHueAdjustments * radians(20.0)`，见 `PreviewColorShaderModules.kt:224` / `VideoLutEffect.kt:395`），
  其 9 段 hue/chroma/lightness 偏移由 `ProfileToRecipeMapper.mapHsl` 把同一 `hsl.<hue>.hueShift`
  映射到 `redHue/orangeHue/…/magentaHue` 喂入。该阶段是**独立的、按色相带加权 + 肤色/色度门控的重型调色**，
  缩放为 `×radians(20.0)`，**并非 `hue_shift` 字段的 HSL-LUT 渲染路径**，也不能用简单的「全局 ×20°」
  在 CPU 参考里复现（它是 per-pixel 带权重 + 色度门控的旋转）。
- **处置决策（不变）**：HSL-LUT 路径两端已一致，**Studio 代码无需改动**（已加注释固化契约，见
  `profile_renderer.py` / `glsl_reference.py` 的 `/360.0` 处）。LCH 调色阶段属独立模块，
  由真机（PLG110）验证，不在 CPU 参考建模范围（等价于 blur/grain 等 GPU 近似层）。
- **遗留 OPEN QUESTION（待用户/真机裁决，不在 Phase F 范围）**：Android 同一 `hue_shift` 值会**同时**驱动
  HSL-LUT（`/360.0`，细微）与 LCH 调色（`×20°`，重型）两道阶段。当前所有内置 profile 的 `hue_shift=0`，
  两道阶段都为零贡献，故现网两端一致；若未来用到非零 `hue_shift`，Android 会出现 HSL-LUT + LCH 的
  **叠加旋转**，与桌面（仅 HSL-LUT）产生差异。是否应让二者互斥或仅保留其一，需真机评估后决定。

### 3. 新增注入通道 D：CaptureProfileStore（Phase B，需求 2.7）

快门触发时 `CameraViewModel.createCaptureProfileSnapshot()` 冻结当前生效的
Profile ID + Recipe + Film + Lens + 版本号为不可变快照（`CaptureProfileSnapshot`），
经 `CaptureProfileStore.current` 下达成片渲染链：`LutImageProcessor` 的 Film/Lens 消费点
改为「快照优先、快照为空回退单例」。预览（`LutRenderer`）与相册重渲染不读快照，继续跟随活参数。
异步多帧处理/RAW 显影/最终保存期间用户切 profile 或调参，不再污染进行中的照片。

### 4. 跨引擎语义差异备忘（需求 2.5）

| 语义 | 桌面 | Android | 差异 |
|---|---|---|---|
| RGB 矩阵 | 行主序 + 行向量数学（rgb@M.T） | 列主序上传 + `M*v` | 数学等价（Phase A 实证同输入 diff=0）；**应用空间不同**：桌面显示域、Android 线性光域（二阶差异） |
| WB | 加性偏移 | 乘性增益 | 不等价（小偏差下近似） |
| HSL 分区 | HSV 7 区 | LCH 9 区（magenta 混入 purple/blue） | 近似 |
| 曲线坐标 | 0-1 | 0-1（tone）/ 0-255 显示级（film） | 单位不同，已按通道区分 |
| vignette 公式 | 线性 ramp + radius×0.7071 | smoothstep + uVwin 窗口（profile 未激活时） | 不等价（Phase A 实证三测试不受影响） |
| LUT 链位 | 链首（post-LUT 调参语义） | HSL 之后（effect.frag 第 9 站） | LUT profile 两端链序不同（Phase A 遗留议题，需真机 A/B） |
