# PhotographerCamera Profile 参数映射审计报告

- 审计对象：PhotographerCamera，git HEAD = `c4523dc`（feat(jpeg): JPEG MAX 对齐 RAW MAX 软件降噪）
- 审计性质：只读审计，未修改任何源文件；本报告是唯一产出。
- Schema 真源：`android/app/src/main/java/com/photographercamera/core/profile/Profile.kt`（下称 **Profile.kt**）
- 映射器：`android/app/src/main/java/com/photographercamera/core/photon/color/ProfileToRecipeMapper.kt`（下称 **Mapper**）
- 桌面参考：`tools/profile_renderer.py`（下称 **Desktop**）、`tools/glsl_reference.py`（下称 **GlslRef**）

路径图例（下表 file:line 均相对以下绝对根）：
- `A/` = `C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera/android/app/src/main/java/com/photographercamera/`
- `T/` = `C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera/tools/`
- 例：`A/core/photon/color/ProfileToRecipeMapper.kt:84`。

## 0. 链路总览（先读这个再看表）

```
ProfileLoader.getProfile(id)                         A/core/profile/ProfileLoader.kt:95
  └─ CameraScreen.applyAdjustments()                 A/ui/screens/CameraScreen.kt:490-562
       ├─ Mapper.map(profile)                        Mapper:73-146
       │    ├─ recipe: ColorRecipeParams（core 副本）→ toJson → LutManager.saveColorRecipeParams("profile:<name>")
       │    │       A/photon/lut/LutManager.kt:60-160（DataStore 持久化，含 grading* 字段 :137-138）
       │    ├─ residual → LensParamsStore.current   A/core/photon/lens/LensParams.kt:45-48
       │    └─ residual → FilmParamsStore.current   A/core/photon/color/FilmParamsStore.kt:63-64
       └─ pvm.setLut("profile:<name>")
预览（每帧）：LutRenderer.onDrawFrame
  lensStage(LensParamsStore) → drawColorPass(PreviewColorShader) → HDF/Bloom/SoftLight/Clarity 后处理 → FilmGrainGl 后处理
  A/photon/lut/LutRenderer.kt:1488-1514（lens）、898-1001（color pass uniforms）、1240（grain 后处理）
成片（每张）：CameraViewModel.saveImage → ContentRepository.imageProcessor（LutImageProcessor）
  → PhotoProcessor.applyLutStack / LutImageProcessor.applyLut
  A/photon/viewmodel/CameraViewModel.kt:4267、A/photon/gallery/PhotoProcessor.kt:317/714/792/803
  → LutImageProcessor.applyLutStack:673-730（lens stage）→ applyLut（SHADER_BODY 主链 :3970-4235）
  → 后处理：clarity(:1193) → sharpen(:1198) → bloom(:1203) → FilmGrainGl(:1206-1219) → 16F 读回(:1222+)
视频录像：VideoLutEffect（Media3）+ RealtimeVideoRenderer
  A/photon/lut/VideoLutEffect.kt:783-890、A/photon/lut/RealtimeVideoRenderer.kt:425-599
相册重渲染/导出：GalleryManager（lutId + colorRecipeParams 来自 metadata/preferences）
  A/photon/gallery/GalleryManager.kt:276-298、1477-1495（video effects）
```

三个"旁路单例"（recipe/JSON 桥表达不了的参数）：**FilmParamsStore**（film_curve 端点、grain 粒度、原色矩阵、shadow.saturation、halation 强度/半径/阈值/暖度、vignette 半径/羽化/中心、bloom 阈值/半径、sharpen 半径）与 **LensParamsStore**（lens 光学五参数）。二者是进程级 @Volatile 单例，按下发时刻的"当前选中 profile"写入，不按 lutId 持久化（FilmParamsStore.kt:9-12、LensParams.kt:39-44 自述为临时桥接）。

---

## ① 总表（Profile.kt 全字段）

映射精度图例：**精确**=两端同公式；**近似**=同方向不同数学/色彩空间；**仅桌面**=桌面实现 Android 未接；**仅Android**=Android 实现桌面未接；**未实现**=两端都不消费（死参数）；**已接线**=Android 活链路消费（桌面语义不同则注明）。

### 1.1 元数据字段（不参与 GPU）

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| version | 无（序列化 only） | — | — | — | — | int | 1 | 未实现（元数据） | 无 |
| schema_version | 无 | — | — | — | — | int | 1 | 未实现（元数据） | 无 |
| name | 无（注册表 key） | ProfileLoader.registry | A/core/profile/ProfileLoader.kt:89/95 | — | — | string | "Untitled" | 未实现（元数据） | 无 |
| author | 无 | — | — | — | — | string | "" | 未实现（元数据） | 无 |
| description | 无 | — | — | — | — | string | "" | 未实现（元数据） | 无 |
| created_at | 无 | — | — | — | — | string | "" | 未实现（元数据） | 无 |
| validation_status | 无（Profile.kt:11 注释明确"never read by the GPU"） | — | — | — | — | enum | "pending" | 未实现（元数据） | 无 |
| display.name | 无 | — | UI 预设列表 | — | — | string | "" | 未实现（仅 UI） | 无 |
| display.intro | 无 | — | UI 预设列表 | — | — | string | "" | 未实现（仅 UI） | 无 |
| display.icon | 无 | ProfileLoader.iconPath / persistIconDecl | A/core/profile/ProfileLoader.kt:103-112、258-268 | — | — | 扩展名 | "" | 未实现（仅 UI） | 无 |
| conditions（Map<String,Profile>） | mergeConditions | 仅 dead 路径调用 | A/core/profile/Conditions.kt:16-35；唯一调用点 A/core/profile/ProfileLoader.kt:117（toGpuParams） | 无（活链路 getProfile 不合并 scenario） | 无 | — | {} | 未实现（活链路未接线） | 无 |

### 1.2 exposure / white_balance

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| exposure.bias | Mapper:80（直传） | recipe.exposure | uExposure：LutRenderer.kt:901；LutImageProcessor.kt:1019 → applyExposureInLinearSpace（PreviewColorShaderModules.kt:22-27，exp2） | PreviewColorShader.kt:265-268（另 :146-149 供阴影/高光 base 层预曝光） | SHADER_BODY:4001-4004（另 :3851-3857） | EV（-2..+2） | 0.0 | 精确（Desktop T/profile_renderer.py:130-133 同为 2^bias） | 低：EV 另被 CameraScreen.kt:509 加 adjEv 快捷面板偏移（单一写入点）；RAW 链另有 rawExposureCompensation（CameraViewModel.kt:5427-5429），与 profile EV 不同通道 |
| white_balance.temperature_bias | Mapper:82（直传） | recipe.temperature | uTemperature：LutRenderer.kt:904；LIP:1022 → PreviewColorShader.kt:286-287（r+=t·0.1,b-=t·0.1）；SHADER_BODY:4023-4024 | 同上 | 同上 | ±1 归一（正=暖） | 0.0 | 近似：Desktop 为乘性增益（T/profile_renderer.py:141-142，×1±0.2），Android 为加性通道偏移，同向不同数学 | 无 |
| white_balance.tint_bias | Mapper:83（直传） | recipe.tint | uTint：LutRenderer.kt:905；LIP:1023 → PreviewColorShader.kt:288（g-=tint·0.05）；SHADER_BODY:4025 | 同上 | 同上 | ±1 归一（正=品红） | 0.0 | 近似：Desktop 乘性（T/profile_renderer.py:144-146），Android 加性 | 无 |

### 1.3 color_matrix

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| color_matrix.matrix_3x3 | Mapper:116（原样入 residual） | FilmParamsStore.colorMatrix3x3（CameraScreen.kt:545 注入） | composeWithProfileColorMatrix（FilmParamsStore.kt:81-109，P@C 行主序复合、列主序输出）→ uPrimaryCalibrationMatrix：LutRenderer.kt:991-1000；LIP:976-978/1097 → applyPrimaryCalibration（PreviewColorShaderModules.kt:279-285，`M*v` 列向量，线性光域） | PreviewColorShader.kt:302-303（曝光/影调/WB 之后，LCH/grading 之前） | SHADER_BODY:4040-4041 | 3×3 行主序（JSON） | 单位阵 | 精确（布局一致，见专项 3）；近似（作用域：Desktop 在显示域、Android 在线性光域，FilmParamsStore.kt:78-79 自述二阶差异） | 中：视频录像路径**不复合** profile 矩阵（VideoLutEffect.kt:966、RealtimeVideoRenderer.kt:596-599 直接用 CameraRawCalibrationMatrix.build）→ 录像与拍照色不一致；BakedLutExporter 烘焙会连同矩阵一起烧进导出 cube（BakedLutExporter.kt:31-35） |
| color_matrix.input_gamut | 无 | — | — | — | — | string | "sRGB" | 未实现（死参数） | 无 |
| color_matrix.output_gamut | 无 | — | — | — | — | string | "sRGB" | 未实现（死参数） | 无 |

### 1.4 hsl（7 区 × 3 子字段，共 21 行）

映射函数：Mapper.mapHsl Mapper:155-182（red:160-162 / orange:163-165 / yellow:166-168 / green:169-171 / cyan:172-174 / blue:175-177 / purple:178-180）；chroma = saturation-1、lightness = lightness-1（偏移语义）。skin/magenta 两区无 profile 对应项，恒 0（Mapper:156-158）。

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| hsl.red.hue_shift | Mapper:160 | recipe.redHue | uLchHueAdjustments[1]（ColorRecipeGl.kt:25-35、61-70；LutRenderer.kt:994-999；LIP:1091-1096）→ LCH_MIXER hueShift×radians(20°)（PreviewColorShaderModules.kt:224） | PreviewColorShader.kt:305-306（applyLchColorMixer） | SHADER_BODY:4043-4044 | 度（schema ±30，Validation.kt:54） | 0.0 | 近似：Desktop 按 /360 换算（T/profile_renderer.py:243、T/glsl_reference.py:135/177），Android 1.0→20°（radians(20)），量级差 ~18×（见专项 4）；且色相空间不同（Desktop HSV 硬分区 vs Android Oklab LCH 8 带加权） | 无 |
| hsl.red.saturation | Mapper:161 | recipe.redChroma | uLchChromaAdjustments[1] → LCH_MIXER:225/247（chromaScale 累加） | 同上 | 同上 | 倍率 1=中性的偏移语义（sat-1） | 1.0 | 近似：Desktop 为 HSV s×sat_f（T/profile_renderer.py:241/245），Android 为 Oklab chroma 乘性缩放 | 无 |
| hsl.red.lightness | Mapper:162 | recipe.redLightness | uLchLightnessAdjustments[1] → LCH_MIXER:226/248（L 偏移 ×0.15） | 同上 | 同上 | 倍率（light-1 偏移） | 1.0 | 近似：Desktop 为 HSV v×light_f，Android 为 Oklab L 加性偏移 | 无 |
| hsl.orange.hue_shift | Mapper:163 | recipe.orangeHue | uLchHueAdjustments[2] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似（同 red.hue_shift） | 无 |
| hsl.orange.saturation | Mapper:164 | recipe.orangeChroma | uLchChromaAdjustments[2] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.orange.lightness | Mapper:165 | recipe.orangeLightness | uLchLightnessAdjustments[2] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.yellow.hue_shift | Mapper:166 | recipe.yellowHue | uLchHueAdjustments[3] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似 | 无 |
| hsl.yellow.saturation | Mapper:167 | recipe.yellowChroma | uLchChromaAdjustments[3] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.yellow.lightness | Mapper:168 | recipe.yellowLightness | uLchLightnessAdjustments[3] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.green.hue_shift | Mapper:169 | recipe.greenHue | uLchHueAdjustments[4] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似 | 无 |
| hsl.green.saturation | Mapper:170 | recipe.greenChroma | uLchChromaAdjustments[4] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.green.lightness | Mapper:171 | recipe.greenLightness | uLchLightnessAdjustments[4] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.cyan.hue_shift | Mapper:172 | recipe.cyanHue | uLchHueAdjustments[5] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似 | 无 |
| hsl.cyan.saturation | Mapper:173 | recipe.cyanChroma | uLchChromaAdjustments[5] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.cyan.lightness | Mapper:174 | recipe.cyanLightness | uLchLightnessAdjustments[5] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.blue.hue_shift | Mapper:175 | recipe.blueHue | uLchHueAdjustments[6] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似 | 无 |
| hsl.blue.saturation | Mapper:176 | recipe.blueChroma | uLchChromaAdjustments[6] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.blue.lightness | Mapper:177 | recipe.blueLightness | uLchLightnessAdjustments[6] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.purple.hue_shift | Mapper:178 | recipe.purpleHue | uLchHueAdjustments[7] → LCH_MIXER:224 | 同上 | 同上 | 度 | 0.0 | 近似 | 无 |
| hsl.purple.saturation | Mapper:179 | recipe.purpleChroma | uLchChromaAdjustments[7] → LCH_MIXER:225 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |
| hsl.purple.lightness | Mapper:180 | recipe.purpleLightness | uLchLightnessAdjustments[7] → LCH_MIXER:226 | 同上 | 同上 | 偏移 | 1.0 | 近似 | 无 |

### 1.5 tone_curve / highlight_rolloff / shadow

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| tone_curve.points | Mapper:113 + flattenCurve Mapper:206-218（identity 两点→null） | recipe.masterCurvePoints | 曲线纹理：LutRenderer.setRecipeParams:3804-3818 → CurveUtils.buildCurveTextureBuffer（CurveUtils.kt:83-110）→ uCurveTexture/uCurveEnabled：LutRenderer.kt:1003-1006；LIP:1121-1124 | PreviewColorShader.kt:379-385（色彩配方之后、HDF/LUT 之前） | SHADER_BODY:4144-4150（同位置） | 0-1 归一（Validation.kt:62-63） | [[0,0],[1,1]] | 精确（Desktop 同为 0-1 与 texel-center LUT，T/profile_renderer.py:116-177）；插值器不同：Android 单调三次 Hermite（CurveUtils.kt:28-50）vs Desktop 线性 interp → 近似于控制点密集场景 | 中：film_curve 与 tone_curve 被复合进同一张 LUT（master(channel(film(x)))，CurveUtils.kt:97-99）各一次；BakedLutExporter 烘焙时整链烧入 cube（:31-35）；RAW 双层链 baseline→creative 会连续跑两个 recipe（LIP:695-711） |
| highlight_rolloff.threshold | Mapper:89（仅作 shoulder 系数：strength×(1-threshold)） | recipe.toneShoulder（间接） | uToneShoulder：LutRenderer.kt:915；LIP:1028 → applyToneCurveToLuma shoulder 分支（PreviewColorShader.kt:199-201/280；SHADER_BODY:3889-3894/4015） | 预览 :280 | 成片 :4015 | 0-1 | 0.8 | 近似：Desktop 直接进 rolloff 公式的膝位（T/profile_renderer.py:185），Android 仅缩放 toneShoulder；strength=0 时 threshold 完全无效（两端同样早退，Desktop :181-182） | 无 |
| highlight_rolloff.strength | **Mapper:84（→recipe.highlights）与 Mapper:89（→recipe.toneShoulder）双重映射** | recipe.highlights + recipe.toneShoulder | ① uHighlights：LutRenderer.kt:909-913（ShadowsHighlightsShader.bindUniformLocations）；LIP:1026 → applyShadowsHighlights（ShadowsHighlightsShader.kt:196-217）② uToneShoulder：LutRenderer.kt:915；LIP:1028 → applyToneCurve（PreviewColorShader.kt:191-225；SHADER_BODY:3884-3922） | 预览：先 :270（overlay）后 :280（tone curve）——同一像素顺序施加两次不同变换 | 成片：先 :4007 后 :4015——同上 | 0-1 | 0.0 | 近似（ Desktop 单一 soft-knee 压缩公式 T/profile_renderer.py:180-187） | **高：双重映射，见专项 1** |
| highlight_rolloff.saturation | 无（Mapper 未映射） | 仅 dead GpuParams.highlightSaturation（GpuParams.kt:42/90） | 无 glUniform 消费 | 无 | 无 | 倍率 0-2 | 1.0 | 未实现（死参数；Desktop 同样收了参数不用，T/profile_renderer.py:180 签名有 saturation 但函数体未使用） | 无 |
| shadow.black_point | Mapper:87（toneToe = -bp/0.2） | recipe.toneToe | uToneToe：LutRenderer.kt:914；LIP:1027 → applyToneCurveToLuma toe 分支（gamma>1 压黑，PreviewColorShader.kt:196-198/280；SHADER_BODY:3889-3891/4015） | 预览 :280 | 成片 :4015 | 0-0.2 显示级 | 0.0 | 近似：Desktop (out-bp)/(1-bp) 硬压缩（T/profile_renderer.py:192-193），Android 负 toe gamma 曲线，同向（压黑）不同形状；Mapper:86 注释"提黑"指黑位上移=压黑，方向与桌面一致 | 无 |
| shadow.compression | Mapper:85（直传） | recipe.shadows | uShadows：LutRenderer.kt:909-913；LIP:1026 → applyShadowsHighlights 阴影 overlay（ShadowsHighlightsShader.kt:212-214） | 预览 :270 | 成片 :4007 | 0-1 | 0.0 | 近似：Desktop 向 0.5 提亮+阴影 mask（T/profile_renderer.py:194-197），Android Lab 域 overlay 凸组合，同向 | 无 |
| shadow.tint | Mapper:112 + mapShadowTint Mapper:188-203（RGB→主色相+amount） | recipe.gradingShadowHue / gradingShadowAmount | uGradingHues/uGradingAmounts：LutRenderer.kt:968-979；LIP:1065-1076 → applyThreeWayColorGrading（ThreeWayColorGradingShader.GLSL；PreviewColorShader.kt:308-316；SHADER_BODY:4046-4056） | 预览 :308-316 | 成片 :4046-4056 | RGB 偏移 -1..1/通道（内部转 0-1 hue + amount） | [0,0,0] | 仅Android：Desktop apply_shadow 收了 tint 参数但**从未使用**（T/profile_renderer.py:190-201、T/glsl_reference.py:94-105）→ Android 独有接线 | 无 |
| shadow.saturation | Mapper:138（原值入 residual） | FilmParamsStore.shadowSaturation（CameraScreen.kt:542 注入） | **无任何 glUniform 消费**（ColorPassLocations 无此 location，ColorPassProgram.kt:6-66；预览/成片 shader 均无 uniform） | 无 | 无 | 倍率 0-2 | 1.0 | 未实现（Android 死参数；Desktop 已实现 T/profile_renderer.py:198-200） | 无 |
| shadow.contrast | Mapper:91（tonePivot=(c-1)×0.1，保守 1/10 联动） | recipe.tonePivot | uTonePivot：LutRenderer.kt:916；LIP:1029 → applyToneCurveToLuma pivotPoint（PreviewColorShader.kt:193/280；SHADER_BODY:3886/4015） | 预览 :280 | 成片 :4015 | 倍率 1=中性（0-2） | 1.0 | 仅Android：Desktop apply_shadow 收了 contrast 参数但**从未使用**（T/profile_renderer.py:190-201） | 无 |

### 1.6 lens（6 字段）

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| lens.vignette | LensParams.fromProfile（A/core/photon/lens/LensParams.kt:29-35，Mapper:139） | LensParams.vignette → LensParamsStore（CameraScreen.kt:529） | LensStageGl uVignette（LensStageGl.kt:346；公式 :91-94，1-v·0.75·(r-0.3)^1.5 乘法暗角） | LutRenderer.kt:1496-1514（颜色链之前 Stage 0） | LIP:684-685 → applyLensStage:616-671 | 0-1 | 0.0 | 精确（忠实移植 Desktop apply_lens，LensStageGl.kt:1-16；T/profile_renderer.py:442-448） | 低：与 style vignette.amount 是两个独立暗角（Mapper:93-95 注释明确），同时非零会叠加（设计如此） |
| lens.chromatic_aberration | Mapper:96（走 recipe 空间效应） | recipe.chromaticAberration | uChromaticAberration：LIP:1158；PreviewColorShader.kt:243-257（径向分通道偏移，pow(dist,1.5)·ca·0.08） | 预览采样端 :243-257 | 成片 SHADER_BODY:3982-3993 | 0-1 | 0.0 | 近似（Desktop cv2.remap 分通道放大 T/profile_renderer.py:416-427；Android 径向偏移，系数不同） | 无 |
| lens.sharpness_falloff | LensParams.fromProfile | LensParams.sharpnessFalloff | LensStageGl uFalloff + uBlurTexel（LensStageGl.kt:345-351；9-tap 高斯混合 :67-89） | 同上 | 同上 | 0-1 | 0.0 | 精确（移植 Desktop T/profile_renderer.py:429-440，权重/半径公式一致） | 无 |
| lens.distortion | LensParams.fromProfile | LensParams.distortion | LensStageGl uDistortion（LensStageGl.kt:344；f=1+0.35k·r² :63-64） | 同上 | 同上 | -1..1 | 0.0 | 精确（T/profile_renderer.py:405-414） | 无 |
| lens.bloom | LensParams.fromProfile | LensParams.bloom | LensStageGl uBloomAmt（LensStageGl.kt:417；增益 0.8·lb :171-173，阈值固定 0.82 :109） | 同上 | 同上 | 0-1 | 0.0 | 精确（T/profile_renderer.py:450-458） | 低：与 style bloom.amount 是两个独立 bloom（光学 0.82 阈值 vs 风格 bloom.threshold） |
| lens.flare | LensParams.fromProfile | LensParams.flare | LensStageGl uFlareAmt（LensStageGl.kt:418；暖辉光+冷条纹 :174-179） | 同上 | 同上 | 0-1 | 0.0 | 精确（T/profile_renderer.py:460-472） | 无 |

### 1.7 grain / noise

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| grain.amount | Mapper:99（→recipe.filmGrain；注释 :97-98 说明走原生银盐通路） | recipe.filmGrain | uFilmGrain：LutRenderer.kt:918 → 预览 video-log 模式内嵌 shader（PreviewColorShader.kt:397-408，与后处理互斥 :1141-1144）；常规模式走后处理 FilmGrainGl uAmount（FilmGrainGl.kt:81） | LutRenderer.kt:1240（FilmGrainGl.renderToTexture，LUT 之后） | LIP:1206-1219（bloom 之后） | 0-1 | 0.0 | 近似：Desktop 高斯噪声+亮度 mask（T/profile_renderer.py:321-336）vs Android 密度域 simplex（FilmGrainShaders.kt:94-153），算法不同 | 低：预览内嵌 grain 与 FilmGrainGl 后处理互斥（LutRenderer.kt:1141-1144）；BakedLutExporter 烘焙时清零（:74） |
| grain.size | Mapper:117 → residual.grainSize | FilmParamsStore.grainSize（CameraScreen.kt:539） | uGrainSize：LutRenderer.kt:931-934；FilmGrainGl.kt:85；VideoLutEffect.kt:883-886；RealtimeVideoRenderer.kt:555-559 → FilmGrainShaders.kt:126（grainSizeScale clamp 0.25-4） | 预览 color pass :931-934 + 后处理 FilmGrainGl:85 | 成片 FilmGrainGl:85（经 LIP:1206 后处理） | 倍率（schema 0.5-3） | 1.0 | 已接线（FilmGrainShaders.kt:13-17 注释记录 0.9.x 前为死参数的历史） | 无 |
| grain.density | Mapper:118 → residual.grainDensity | FilmParamsStore.grainDensity（CameraScreen.kt:540） | uGrainDensity：LutRenderer.kt:935-938；FilmGrainGl.kt:86；VideoLutEffect.kt:887-890 → FilmGrainShaders.kt:148（clamp 0.25-4） | 同上 | 同上 | 倍率（0.5-3） | 1.0 | 已接线（同上历史注释） | 无 |
| grain.luma_contrast | Mapper:119 → residual.grainLumaContrast | FilmParamsStore.grainLumaContrast（CameraScreen.kt:541） | uGrainLumaContrast：ColorPassProgram.kt:171（location）；LutRenderer.kt:927-930；FilmGrainGl.kt:84；VideoLutEffect.kt:879-882；RealtimeVideoRenderer.kt:550-551 → FilmGrainShaders.kt:135-138（highlightFloor=mix(1,0.32,lc)） | 预览 :927-930 + FilmGrainGl:84 | 成片 FilmGrainGl:84 | 0-1 | 1.0 | 已接线（疑点复核属实，见专项 6）；仅Android（Desktop 无对应参数，固定 mask） | 无 |
| noise.luma | **Mapper:105 硬编码 noise=0f**（设计边界，注释 :100-104） | recipe.noise=0 | uNoise：LutRenderer.kt:964；LIP:1055（恒 0 → shader 分支 :360-374/:4118-4137 不执行） | 不生效 | 不生效 | 0-1 | 0.0 | 未实现（既定边界，不建议改回；Desktop 仍实现 T/profile_renderer.py:344-354 仅参考） | 无 |
| noise.chroma | **Mapper:105 硬编码 noise=0f** | recipe.noise=0 | 同上 | 不生效 | 不生效 | 0-1 | 0.0 | 未实现（既定边界） | 无 |

### 1.8 halation / bloom

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| halation.amount | Mapper:106（recipe.halation）+ Mapper:137（residual.halationAmount 原值兜底，因 toJson 抹零：A/core/photon/color/ColorRecipe.kt:277） | recipe.halation（JSON 桥后被抹零）+ FilmParamsStore.halationStrength（CameraScreen.kt:535） | effectiveHalation=maxOf(recipe,store)：LutRenderer.kt:1135/1732/2412；LIP:1137/3102 → HDF 复合 uHalation（LutRenderer.kt:2412；SHADER_BODY:4153-4177 screen+mist） | LutRenderer.kt:1135-1136（hdfEnabled 门控 HDF 后处理链） | LIP:1137-1143（uHalation + uHdfTexture） | 0-1 | 0.0 | 近似（Desktop 暖色辉光 T/profile_renderer.py:305-318；Android HDF blur+screen，观感对齐实现不同） | 无 |
| halation.radius | Mapper:120 → residual.halationRadius | FilmParamsStore.halationRadius（CameraScreen.kt:536） | **无消费**（唯一读取者 assets/shaders/effect.frag:73/336 为死 shader，无 Kotlin 引用；HDF 阈值/核半径硬编码） | 无 | 无 | 倍率 0.5-4 | 1.0 | 未实现（死参数；Desktop 已实现 T/profile_renderer.py:310） | 无 |
| halation.threshold | Mapper:121 → residual.halationThreshold | FilmParamsStore.halationThreshold（CameraScreen.kt:537） | **无消费**（LutRenderer.kt:1733 与 LIP:3087 均硬编码 threshold=0.9-h·0.3） | 无 | 无 | 0-1 | 0.9 | 未实现（死参数；Desktop 已实现 T/profile_renderer.py:309） | 无 |
| halation.warmth | Mapper:122 → residual.halationWarmth | FilmParamsStore.halationWarmth（CameraScreen.kt:538） | **无消费**（唯一读取者 effect.frag:74/344 死 shader） | 无 | 无 | 0-2 | 1.0 | 未实现（死参数；Desktop 已实现 T/profile_renderer.py:315-317） | 无 |
| bloom.amount | Mapper:107 | recipe.bloom | Bevy bloom 后处理：LutRenderer.kt:1138/1819-1990（BloomLdrSettings 全链）；LIP:1203 → renderLdrBloom:3262-3320（BEVY_BLOOM_COMPOSITE） | LutRenderer.kt:1819-1990（mip 链） | LIP:3262-3320 | 0-1 | 0.0 | 近似（Desktop 双高斯 T/profile_renderer.py:293-302；Android Bevy mip 泛光） | 低：与 lens.bloom 光学溢光为两个独立 bloom |
| bloom.radius | Mapper:127 → residual.bloomRadius | FilmParamsStore.bloomRadius（CameraScreen.kt:553） | BloomLdrSettings.profileBloomRadiusFactor（BloomLdrSettings.kt:21-25，(r-1)/3+0.5）→ mipAddWeight/compositeMipPosition：LutRenderer.kt:1971/1988-1990；LIP:3295/3308-3310 | 同上 | 同上 | 倍率 0.5-4 | 1.0 | 近似（Desktop ksz=6·radius T/profile_renderer.py:297；Android 转为 mip 选择因子，语义非线性） | 无 |
| bloom.threshold | Mapper:128 → residual.bloomThreshold | FilmParamsStore.bloomThreshold（CameraScreen.kt:552） | BloomLdrSettings.thresholdPrecomputations（BloomLdrSettings.kt:15-18/27-36）：LutRenderer.kt:1932-1943；LIP:3269 | 同上 | 同上 | 0-1 | 0.9 | 精确（桌面同为高光提取阈值 T/profile_renderer.py:296；经 knee 换算后近似 Bevy 语义） | 无 |

### 1.9 vignette / sharpen

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| vignette.amount | Mapper:95 | recipe.vignette | uVignette：LutRenderer.kt:939；LIP:1030 → PreviewColorShader.kt:341-358（uVignetteStyle=1 走桌面公式 :344-347）；SHADER_BODY:4091-4116（:4095-4098 桌面公式） | 预览 :341-358 | 成片 :4091-4116 | 0-1 | 0.0 | 精确（style=1 公式与 Desktop apply_vignette T/profile_renderer.py:357-372 逐项一致：dist/(radius·0.7071)、mask=1-amount·clamp((dist-(1-feather))/feather)） | 低：与 lens.vignette 光学暗角独立叠加（设计如此） |
| vignette.radius | Mapper:123 → residual.vignetteRadius | FilmParamsStore.vignetteRadius（CameraScreen.kt:548） | uVignetteRadius：LutRenderer.kt:948-951（coerce 0.1-1）；LIP:1039-1042 | 预览 :344 | 成片 :4095 | 0-1（dist 归一半径） | 1.0 | 精确（同桌面） | 无 |
| vignette.feather | Mapper:124 → residual.vignetteFeather | FilmParamsStore.vignetteFeather（CameraScreen.kt:549） | uVignetteFeather：LutRenderer.kt:952-955；LIP:1043-1046 | 预览 :345-346 | 成片 :4096-4097 | 0-1 | 0.5 | 精确（同桌面） | 无 |
| vignette.center | Mapper:125 → residual.vignetteCenter | FilmParamsStore.vignetteCenterX/Y（CameraScreen.kt:550-551） | uVignetteCenter：LutRenderer.kt:956-960；LIP:1047-1051 | 预览 :344 | 成片 :4095 | uv 0-1×2（y 自顶行） | [0.5,0.5] | 精确（uv 约定与桌面一致，T/profile_renderer.py:357-363） | 无 |
| sharpen.amount | Mapper:108 | recipe.sharpness | uSharpening：LutRenderer.kt:917 → PreviewColorShader.kt:451-478（sRGB 域最终锐化）；后处理 SrgbSharpnessShader：LIP:1198-1199 → renderLutSharpenPass:2122-2143 | 预览 :451-478 | 成片 :1198-1199（后处理） | 0-1（shader 内 ×2） | 0.0 | 近似（Desktop USM 高斯 T/profile_renderer.py:282-290；Android 4 邻域亮度差） | 低：RAW 基线锐化是另一通道（CameraViewModel.resolveCaptureSharpening:127-141，profile 激活时关闭基线 :136） |
| sharpen.radius | Mapper:126 → residual.sharpenRadius | FilmParamsStore.sharpenRadius（CameraScreen.kt:547） | SrgbSharpnessShader uRadius：LIP:2142-2143（profileActive 门控） | 预览不使用 radius（PreviewColorShader.kt:451-478 无 radius 项）→ **预览未接线** | 成片 :2142-2143 | 倍率 0.5-3 | 1.0 | 仅Android（成片）：Desktop 已实现（T/profile_renderer.py:285 sigma=0.6·radius·relScale）；预览端缺失 | 无 |

### 1.10 film_curve / color_layer / color_adaptive / color_lut

| 源字段 | 映射函数(file:line) | 目标参数 | 消费 Shader/处理器(file:line,uniform) | 预览应用位置 | 成片应用位置 | 单位 | 默认值 | 映射精度 | 重复应用风险 |
|---|---|---|---|---|---|---|---|---|---|
| film_curve.shadow_floor | Mapper:135（**minOf(floor,10f) 硬上限**，注释 :130-134） | FilmParamsStore.filmCurveShadowFloor（CameraScreen.kt:543） | CurveUtils.buildFilmCurveLut（CurveUtils.kt:122-142，lo=floor/255 coerce[0,0.4]）→ 复合进曲线 LUT 输入侧（CurveUtils.kt:92-99）→ uCurveTexture：LutRenderer.kt:3800-3803/3813-3818；LIP:1106-1119 | 预览曲线纹理 :379-385 | 成片曲线纹理 :4144-4150 | 0-255 显示级（schema 0-64） | 8.0 | 精确（公式与 Desktop apply_film_curve T/profile_renderer.py:476-499 一致）；差异：Android 有 ≤10 硬上限（防抬黑显噪点）、profileActive 门控（FilmParamsStore.kt:35-40）；视频路径不应用 | 中：与 tone_curve 复合同一 LUT 各一次；GlslRef 与 Desktop 链序不一致（见专项 2） |
| film_curve.highlight_ceiling | Mapper:136（直传） | FilmParamsStore.filmCurveHighlightCeiling（CameraScreen.kt:544） | 同上（hi=ceil/255 coerce[0.6,1]，CurveUtils.kt:125） | 同上 | 同上 | 0-255 显示级（schema 191-255） | 248.0 | 精确（同桌面） | 中（同上一行） |
| color_layer | Mapper.isLutProfile:62-63（判定）；LutManager.loadProfileLut:301-321（加载门控 :305） | 3D LUT 纹理配置（"profile:<name>"） | sampler3D uLutTexture：PreviewColorShader.kt:416-433；SHADER_BODY:4187-4213（P3 输入输出变换 :4191-4212） | 预览 :416-433（色彩链末端） | 成片 :4187-4213 | 字符串开关 | null | 已接线：与 Desktop render 语义一致——LUT 承载风格、参数化链作为 LUT 之上的微调层原样下发（Mapper:56-67；T/profile_renderer.py:518-528） | 中：BakedLutExporter 把整链烧进导出 cube（:31-38），若再与原 recipe 同时使用会双施（用户侧导出流程） |
| color_adaptive | 无 | — | — | — | — | bool | null | 未实现（死字段）：全仓无任何渲染消费；stylefit 只写 false（T/stylefit/fit.py:678）；Desktop 自适应走 color_lut_bank/color_lut_router 原始 dict 键（T/profile_renderer.py:89-104），该二键不在 Profile.kt 模型内 | 无 |
| color_lut.size / dtype / scale / data / order | 无（不经 Mapper；LutManager.buildProfileLutConfig:323-361） | LutConfig（uint8 3D 纹理，r_fastest 直传） | 同上（uLutTexture） | 预览 :416-433 | 成片 :4187-4213 | int/string/float | 33/"uint8"/255/"r_fastest"/"" | 已接线（dtype≠uint8 或 size∉[2,65] 拒绝 :324-332；scale≠255 防御性预除 :345-353） | 无 |
| color_lut.source / color_lut.strength | 无（Profile.kt:162 注释自述"仅作元数据"） | — | — | — | — | string/float | ""/1.0 | 未实现（死元数据）：Android 不读（LutManager 只用 size/dtype/scale/data）；Desktop apply_color_lut 也不读 strength（T/profile_renderer.py:107-110）→ 两端均忽略 LUT 强度 | 无 |

---

## ② 专项核实结论（7 项）

### 专项 1：highlightRolloff.strength 是否双重映射 → **是，确认为双重映射且双重生效**

两处映射 code：
- 映射 A（高光 overlay）：Mapper:84 `highlights = effective.highlightRolloff.strength`
  - 预览消费：LutRenderer.kt:909-913（`ShadowsHighlightsShader.bindUniformLocations(highlights=params.highlights, ...)`）→ uniform `uHighlights` → `applyShadowsHighlights`（ShadowsHighlightsShader.kt:196-217；预览调用点 PreviewColorShader.kt:270）
  - 成片消费：LIP:1026（`ShadowsHighlightsShader.bindUniforms(program, highlights, shadows)`）→ `uHighlights` → SHADER_BODY:4007
- 映射 B（影调肩部）：Mapper:89 `toneShoulder = strength * (1 - threshold)`
  - 预览消费：LutRenderer.kt:915（`uToneShoulder`）→ `applyToneCurve`（PreviewColorShader.kt:280；函数 :191-225）
  - 成片消费：LIP:1028（`uToneShoulder`）→ SHADER_BODY:4015（函数 :3884-3922）

判定：**同一像素被应用两次，但两次是不同的数学变换，且顺序固定**。预览链：exposure(:265) → applyShadowsHighlights(:270) → contrast(:275) → applyToneCurve(:280)；成片链：exposure(:4001) → applyShadowsHighlights(:4007) → contrast(:4011) → applyToneCurve(:4015)。即 strength 先经 Lab 域凸组合 overlay 压高光，再经 luma 域 gamma 重塑肩部（默认 threshold=0.8 时 B 的系数为 strength×0.2）。二者叠加但非同函数重复执行；注释（Mapper:84、88-89）表明这是有意的"双通道塑形"，非 bug。但因为两个 uniform 由同一字段驱动，调 strength 的实际响应 ≈ 两个变换的复合，预览/成片响应一致（两端链序相同），无预览成片分歧。

### 专项 2：shadow/tone_curve/film_curve/color_matrix 叠加关系

应用顺序（Android 活链路，预览=成片逐段同序）：
1. lens 光学阶段（LensStageGl，全部颜色链之前）：LutRenderer.kt:1488-1514 / LIP:680-687
2. CA 采样偏移 → exposure → **applyShadowsHighlights（shadow.compression 唯一入口）** → contrast → **applyToneCurve（shadow.blackPoint→toe、shadow.contrast→pivot、highlight.strength→shoulder 的唯一入口）** → basicToneLUT → WB → saturation → vibrance → **applyPrimaryCalibration（color_matrix 唯一入口）** → LCH mixer(HSL) → three-way grading(shadow.tint) → fade/bleach/flash/vignette/noise → **曲线纹理（film_curve+tone_curve 复合，各一次）** → HDF/redHalation → LUT → softLight → dither；后处理 clarity→sharpen→bloom→grain
3. Desktop 权威链序（T/profile_renderer.py:516-554）：lens → [LUT→film] → exposure → WB → matrix → highlight → shadow → [film(无 LUT 时)] → tone → HSL → vignette → bloom → halation → grain → noise → sharpen(最后)

同一曲线被应用多次的核查结论：
- **tone_curve**：每帧/每张恰好一次（曲线 LUT，预览 :379-385 / 成片 :4144-4150）。不存在 Recipe/FilmParamsStore/自有 shader/导出流程中的重复施加——**除非**走 BakedLutExporter 导出（:31-38 用 LutImageProcessor 跑完整链烧进 .cube，导出物本身包含曲线；这是"烘焙"而非"重复应用"，但若把导出 cube 当 LUT 导入且原 recipe 仍激活则会双施）。
- **film_curve**：恰好一次，且与 tone_curve 复合为 master(channel(film(x)))（CurveUtils.kt:92-99、144-146），由 profileActive 门控（LutRenderer.kt:3800；LIP:1107-1108）。**视频录像路径不应用 film_curve**（VideoLutEffect.kt:1072 调 buildCurveTextureBuffer 时不传 film 端点）。桌面两工具链序互相矛盾：Desktop render 把 film 放在 tone 之前（:537-540），GlslRef.render_deterministic 把 film 放最后（:241-246）——以 Desktop render + Android 复合顺序为准，GlslRef 该处与实现不符。
- **color_matrix**：恰好一次（applyPrimaryCalibration，预览 :302 / 成片 :4040）。**视频录像路径不应用**（VideoLutEffect.kt:966、RealtimeVideoRenderer.kt:596-599 用未复合的校准矩阵）。BakedLutExporter 烘焙会包含矩阵。
- **shadow.blackPoint/compression/contrast**：各一次（toe/shadows overlay/pivot，见专项 1 链序）；无第二入口。
- RAW 双层链注意：baseline 层与 creative 层是两个完整 recipe 的顺序应用（LIP:695-711），若两层都来自 profile 类 LUT，参数化链会跑两遍（设计内的双层修正，非同一 profile 重复）。

### 专项 3：RGB 矩阵行列布局两端一致性 → **一致（均为 JSON 行主序 + 行向量数学）**

- Desktop：`flat = rgb.reshape(-1,3) @ M.T`（T/profile_renderer.py:150-155）
- GlslRef：`c.reshape(-1,3) @ M.T`，注释明确"GLSL uploads the transpose; GLSL m*c == c @ M.T"（T/glsl_reference.py:56-59）
- Android 活链路：FilmParamsStore.composeWithProfileColorMatrix 把 profile 矩阵按行主序取元素（:86-88），与校准矩阵做 `R = P @ C` 行主序复合（:94-102），输出列主序 `out[col*3+row]`（:104-108）；shader 端 `uPrimaryCalibrationMatrix * v` 列向量施加（PreviewColorShaderModules.kt:282）⇔ 桌面 `v @ M.T`。
- Android 遗留链路：GpuParams.kt:74-79 同样 transpose（`flat[c*3+r]=v`）供 `m*c`——但该链路已死（见专项 6/③）。
结论：JSON 侧一律行主序；GPU 侧一律列主序上传；两端数学等价。**唯一实质差异是作用域**：桌面在显示域（sRGB 0-1 clip 后）施加，Android 在线性光域施加（PRIMARY_CALIBRATION 先 srgbToLinear）——FilmParamsStore.kt:78-79 已自述该二阶差异。

### 专项 4：HSL/曲线/EV/饱和度/抬黑/高光压缩的两端数学含义差异

| 参数 | Desktop/GlslRef 含义 | Android 活链路含义 | 判定 |
|---|---|---|---|
| hsl.*.hue_shift | 度，/360 换环（T/profile_renderer.py:243；T/glsl_reference.py:135/177）；schema 范围 ±30 度（Validation.kt:54） | 归一因子，1.0 → radians(20°)≈20°（PreviewColorShaderModules.kt:224） | **量级错位**：同一 JSON 值 Android 旋转量约为桌面的 18 倍（hue_shift=1：桌面 1° vs Android 20°；=30：桌面 30° vs Android 600°取模）；且色相空间不同（HSV 硬分区 vs Oklab LCH 8 带加权+skin 带） |
| hsl.*.saturation | HSV S 乘性（s×sat_f） | Oklab chroma 乘性缩放（chromaScale+=(sat-1)，:225/247） | 近似，不同色彩空间 |
| hsl.*.lightness | HSV V 乘性（v×light_f） | Oklab L 加性偏移（×0.15，:226/248） | 近似 |
| tone_curve 坐标 | 0-1（T/profile_renderer.py:175-177） | 0-1（Mapper:206-218；CurveUtils.kt:37） | 一致；插值：桌面线性 vs Android 单调三次 Hermite（CurveUtils.kt:7/28-50）→ 控制点稀疏时近似 |
| film_curve 坐标 | 0-255 显示级，/255 后 coerce（T/profile_renderer.py:488-489） | 0-255 显示级，/255 后 coerce（CurveUtils.kt:124-125） | 一致；Android 额外 ≤10 抬黑上限（Mapper:135）与 profileActive 门控 |
| exposure EV | ×2^bias（T/profile_renderer.py:133） | exp2（PreviewColorShaderModules.kt:24） | 一致 |
| 白平衡 | 乘性增益（×1±0.2 / ±0.1） | 加性通道偏移（±0.1 / ∓0.05） | 近似，数学不同 |
| 色彩饱和度（全局） | 无全局 saturation 入口（profile 无该字段；recipe.saturation 恒 1，Mapper 不映射） | 同上（uSaturation 恒 1，shader 分支跳过） | 一致（均不消费） |
| 暗部抬黑（shadow_floor） | soft-knee 抬升到 lo（T/profile_renderer.py:492-499） | 同一公式 LUT 化（CurveUtils.kt:128-141） | 精确（除 ≤10 上限） |
| shadow.black_point | (out-bp)/(1-bp) 压黑（T/profile_renderer.py:192-193） | toneToe=-bp/0.2 → toe gamma>1 压黑（PreviewColorShader.kt:196-198） | 近似，同向 |
| shadow.compression | 向 0.5 提亮+阴影 mask（T/profile_renderer.py:194-197） | Lab overlay 凸组合提亮（ShadowsHighlightsShader.kt:212-214） | 近似，同向 |
| 高光压缩 | 单一 soft-knee 压缩公式（T/profile_renderer.py:180-187） | highlights overlay + toneShoulder gamma 双通道（专项 1） | 近似 |
| vignette | dist/(radius·0.7071)，mask=1-amount·clamp((dist-(1-feather))/feather)（T/profile_renderer.py:370-372） | 同一公式（PreviewColorShader.kt:344-347；SHADER_BODY:4095-4098） | 精确 |
| grain | 高斯噪声+亮度 mask（T/profile_renderer.py:321-336） | 密度域 simplex 银盐（FilmGrainShaders.kt:94-153） | 近似，算法不同；luma_contrast 仅 Android 有参数化入口 |

### 专项 5：noise.luma/chroma 边界 → **核实属实，如实记录（既定设计边界，不建议改回）**

- Mapper:105 `noise = 0f`，注释 :100-104 明确：profile 不再驱动独立 uNoise 随机通道（每帧重随机、带彩色、观感刺眼），收敛到原生行为；profile 自身 noise 仅桌面分析参考。
- 后果链：recipe.noise 恒 0 → LutRenderer.kt:964 / LIP:1055 上传 0 → shader 噪声分支不执行（PreviewColorShader.kt:360-374；SHADER_BODY:4118-4137）。
- Desktop 仍实现 apply_noise（T/profile_renderer.py:344-354），仅作 PC 参考。
- 遗留 GpuParams.noiseVec（GpuParams.kt:56/109-114，含 normNoise /100 归一）随整条 GpuParams 链路死亡，无渲染消费。
- 相邻事实（commit c4523dc）：JPEG 成片软件降噪已接入，但走**用户偏好**通道而非 profile：CameraViewModel.resolveCaptureDenoiseStrengths:150-193（!isRawCapture 分支 1.7.6 起同样给 editable/baked 强度），经 metadata.noiseReduction 施加（CameraViewModel.kt:5379-5384、5443-5444）。与 profile noise 无关。

### 专项 6：已知发现复核

1. **shadow.saturation 死参数——复核属实**。Mapper:138 → residual.shadowSaturation → FilmParamsStore.shadowSaturation（FilmParamsStore.kt:27，CameraScreen.kt:542 注入）后断链：全仓无 `uShadowSaturation` 类 glUniform（ColorPassLocations 无该 location：ColorPassProgram.kt:6-66；预览/成片/视频 shader 均无声明）。仅 dead GpuParams.kt:45/93 有同名字段。桌面已实现（T/profile_renderer.py:198-200）。
2. **grain.luma_contrast 已接线——复核属实**。uniform 声明 FilmGrainShaders.kt:12；使用 FilmGrainShaders.kt:135-138（highlightFloor=mix(1.0,0.32,grainLc)）；location ColorPassProgram.kt:171；上传 LutRenderer.kt:927-930（预览 color pass）、FilmGrainGl.kt:84（预览/成片 grain 后处理）、VideoLutEffect.kt:879-882、RealtimeVideoRenderer.kt:550-551。同族 grain.size/grain.density 也已接线（FilmGrainShaders.kt:18-19/126/148；FilmGrainGl.kt:85-86）。
3. **JPEG 降噪 c4523dc 接入——复核属实**。CameraViewModel.kt:150-193（resolveCaptureDenoiseStrengths），调用点 :5379、:5882、:6207、:6384。
4. **shadow.tint → gradingShadowHue/Amount 真实消费——复核属实**。映射 Mapper:188-203（RGB→HSV 主色相 + amount 均值，hue/360 归一）；上传 LutImageProcessor.kt:1065-1076（uGradingHues/uGradingAmounts）与 LutRenderer.kt:968-979；消费 applyThreeWayColorGrading（预览 PreviewColorShader.kt:308-316；成片 SHADER_BODY:4046-4056）。注意：Desktop apply_shadow 的 tint 参数从未使用（T/profile_renderer.py:190-201）→ 该映射为 Android 独有。
5. **附带复核**：halation.radius/threshold/warmth 三个字段同样已注入 FilmParamsStore（CameraScreen.kt:536-538）但无活消费（HDF 阈值硬编码 0.9-h·0.3：LutRenderer.kt:1733、LIP:3087；唯一读取者 assets/shaders/effect.frag:72-74/336-344 是无 Kotlin 引用的死 shader，FilmGrainShaders.kt:13-14 有同样的历史记录）。

### 专项 7：color_layer="lut" 与 color_adaptive 的实际行为；highlight_rolloff.threshold/highlightSaturation 消费端

- **color_layer="lut"**：判定 Mapper.isLutProfile:62-63（colorLayer=="lut" 且 payload.data 非空）。加载门控 LutManager.loadProfileLut:305 → buildProfileLutConfig:323-361（uint8/2-65 尺寸/scale 防御）→ sampler3D 采样（预览 PreviewColorShader.kt:416-433；成片 SHADER_BODY:4187-4213）。语义：LUT 存在时**不** neutralize 参数化链（Mapper:65-67 路线 A：exposure/wb/matrix/highlight/shadow/tone/hsl 作为 LUT 之上的微调层原样下发），与 Desktop render（T/profile_renderer.py:518-528）一致。附带事实：Desktop 另有 neutralize_lut_params / bake_params_into_lut（:579-688）用于把参数烧进 LUT，Android 活链路不用（仅 BakedLutExporter 有等价烘焙）。color_lut.strength 两端均忽略（专项 1.10）。
- **color_adaptive**：Profile.kt:206 字段存在，**全仓零渲染消费**（Android/Desktop 渲染链均无引用；stylefit 只写 false：T/stylefit/fit.py:678）。Desktop 的自适应 LUT 银行由 color_lut_bank/color_lut_router 两个原始 dict 键驱动（T/profile_renderer.py:89-104），该二键不在 Profile.kt 序列化模型内 → color_adaptive 是未接线的死字段。
- **highlight_rolloff.threshold 消费端**：唯一入口是 Mapper:89 的 toneShoulder 系数（strength×(1-threshold)）→ uToneShoulder（LutRenderer.kt:915；LIP:1028）→ applyToneCurve 肩部。strength=0（默认）时完全无效（Desktop 同样早退：T/profile_renderer.py:181-182）。无其他消费。
- **highlight_rolloff.saturation 消费端**：无。Mapper 未映射；唯一载体是 dead GpuParams.highlightSaturation（GpuParams.kt:42/90/218）；Desktop 也是收了参数不用（T/profile_renderer.py:180-187 签名有 saturation、函数体未引用；T/glsl_reference.py:85-91 同）。

---

## ③ 死参数 / 未接线清单

| # | 字段 | 状态 | 证据 |
|---|---|---|---|
| 1 | shadow.saturation | 死（Android 注入后无 glUniform 消费；桌面已实现） | Mapper:138 → FilmParamsStore.kt:27 / CameraScreen.kt:542；无 uniform（专项 6-1） |
| 2 | highlight_rolloff.saturation | 死（两端均未使用） | Mapper 无映射；Desktop T/profile_renderer.py:180-187 未使用 |
| 3 | halation.radius | 死（注入 FilmParamsStore 后无消费） | Mapper:120；HDF 硬编码 LutRenderer.kt:1733/LIP:3087 |
| 4 | halation.threshold | 死（同上） | Mapper:121 |
| 5 | halation.warmth | 死（同上） | Mapper:122 |
| 6 | color_adaptive | 死字段（全仓零消费） | Profile.kt:206；grep 仅 stylefit 写 false |
| 7 | color_matrix.input_gamut | 死（仅 schema） | Profile.kt:32；无消费 |
| 8 | color_matrix.output_gamut | 死（仅 schema） | Profile.kt:33；无消费 |
| 9 | color_lut.source | 死元数据 | Profile.kt:171；LutManager 不读 |
| 10 | color_lut.strength | 死元数据（两端均忽略 LUT 强度） | Profile.kt:172；T/profile_renderer.py:107-110 不读 |
| 11 | conditions | 活链路未接线（仅 dead toGpuParams 路径合并） | Conditions.kt:16；唯一调用 ProfileLoader.kt:117；CameraScreen.kt:500 不过 scenario |
| 12 | sharpen.radius | 半死（仅成片接线，预览缺失） | LIP:2142-2143 vs 预览无 radius uniform |
| 13 | noise.luma / noise.chroma | 设计边界内"未下发"（非缺陷） | Mapper:105 |
| 14 | film_curve / color_matrix / vignette 半径族 / bloom 阈值半径族 / sharpen.radius / halation 强度 | 视频录像路径整体未应用 | VideoLutEffect.kt:783-890/966/1072；RealtimeVideoRenderer.kt:550-599 |
| 15 | 整条 GpuParams 遗留链路（highlightThreshold/highlightStrength/highlightSaturation/shadowBlackPoint/shadowCompression/shadowSaturation/noiseVec/toneLut/hslLut/filmFloor/filmCeil） | 死代码（toGpuParams 无调用方） | ProfileLoader.kt:115 定义后无调用；assets/shaders/*.frag 旧链无 Kotlin 引用 |

---

## ④ 重复应用风险清单

| # | 风险点 | 性质 | 证据 | 等级 |
|---|---|---|---|---|
| 1 | highlightRolloff.strength → highlights + toneShoulder 双 uniform | 设计内双重塑形（不同变换顺序叠加），非 bug 但调节响应为复合效应 | Mapper:84/89；LutRenderer.kt:909-915；专项 1 | 中（需文档化） |
| 2 | BakedLutExporter 把参数化链（含 tone/film/matrix/halation）烧进导出 .cube | 导出物再与原 recipe 并用时双施（用户侧流程） | BakedLutExporter.kt:31-38、71-86（仅清零空间类效果） | 中 |
| 3 | RAW 双层链 baseline→creative 连续两个完整 recipe | 设计内双层修正；两层均非恒等时链跑两遍 | LIP:695-711 | 低（设计内） |
| 4 | FilmParamsStore/LensParamsStore 为进程级单例，相册重渲染/导出读"当前选中"而非"照片拍摄时"的 profile | 切换 profile 后重渲染旧照片会用新参数毒化 | FilmParamsStore.kt:9-12、63-64；LensParams.kt:39-47；GalleryManager.kt:1477-1495 只带 lutId+recipe，单例旁路参数不重建 | 中 |
| 5 | tone_curve 与 film_curve 复合进同一 LUT | 各一次，无重复；但两端桌面工具链序矛盾（GlslRef film LAST vs Desktop render film BEFORE tone） | CurveUtils.kt:92-99；T/profile_renderer.py:537-540 vs T/glsl_reference.py:241-246 | 低（文档问题） |
| 6 | 预览 grain：内嵌 shader（video-log 模式）与 FilmGrainGl 后处理 | 互斥，无重复 | LutRenderer.kt:1141-1144 | 无 |
| 7 | lens.vignette 与 vignette.amount、lens.bloom 与 bloom.amount 双暗角/双 bloom | 设计内独立两层（不同阈值/公式） | Mapper:93-95；LensStageGl.kt:91-94/171-173 | 低（设计内） |
| 8 | halation.amount 双载体（recipe.halation 被 toJson 抹零后用 FilmParamsStore 补） | maxOf 取大，单次生效 | ColorRecipe.kt:277；LutRenderer.kt:1135；LIP:1137 | 无 |

---

## ⑤ 跨引擎语义差异清单（Android vs Desktop）

1. **HSL hue_shift 单位**：桌面=度（/360）；Android=归一因子（1.0→20°）。同一 JSON 值旋转量差约 18 倍。文件：T/profile_renderer.py:243 / PreviewColorShaderModules.kt:224。
2. **HSL 色彩空间**：桌面 HSV 七分区硬掩码；Android Oklab LCH 八带加权 + skin 带。文件：T/profile_renderer.py:210-268 / PreviewColorShaderModules.kt:186-253。
3. **HSL lightness/saturation 语义**：桌面 HSV V/S 乘性；Android Oklab L 加性偏移 / chroma 乘性。文件：T/profile_renderer.py:245-246 / PreviewColorShaderModules.kt:225-226、247-248。
4. **白平衡数学**：桌面乘性增益；Android 加性通道偏移。文件：T/profile_renderer.py:141-146 / PreviewColorShader.kt:286-288。
5. **color_matrix 作用域**：桌面显示域；Android 线性光域。文件：T/profile_renderer.py:150-155 / FilmParamsStore.kt:78-79、PreviewColorShaderModules.kt:279-285。
6. **film_curve 链序**：Desktop render 在 tone 之前；GlslRef 在最后；Android 与 Desktop render 一致（复合 LUT 输入侧）。文件：T/profile_renderer.py:537-540 / T/glsl_reference.py:241-246 / CurveUtils.kt:92-99。
7. **film_curve.shadow_floor 上限**：Android 硬上限 10（防抬黑显噪点）；桌面无。文件：Mapper:135 / T/profile_renderer.py:488。
8. **shadow.contrast / shadow.tint**：桌面未实现（参数收了不用）；Android 接线（tonePivot / gradingShadowHue）。文件：T/profile_renderer.py:190-201 / Mapper:91、188-203。
9. **shadow.saturation**：桌面实现；Android 死。文件：T/profile_renderer.py:198-200 / 专项 6-1。
10. **highlight_rolloff.saturation**：两端均死。文件：T/profile_renderer.py:180-187 / GpuParams.kt:90（死链路）。
11. **tone_curve 插值**：桌面线性 interp；Android 单调三次 Hermite。文件：T/profile_renderer.py:127 / CurveUtils.kt:28-50。
12. **grain 算法**：桌面高斯+亮度 mask；Android 密度域 simplex；luma_contrast 仅 Android 参数化。文件：T/profile_renderer.py:321-336 / FilmGrainShaders.kt:94-153。
13. **sharpen.radius**：桌面实现；Android 仅成片实现，预览缺失。文件：T/profile_renderer.py:285 / LIP:2142-2143。
14. **vignette 公式**：两端精确一致（style=1 分支）。文件：T/profile_renderer.py:357-372 / PreviewColorShader.kt:344-347、SHADER_BODY:4095-4098。
15. **曝光 EV**：两端一致（2^bias / exp2）。文件：T/profile_renderer.py:133 / PreviewColorShaderModules.kt:24。
16. **视频录像路径**：Android 录像（VideoLutEffect/RealtimeVideoRenderer）不应用 film_curve、color_matrix、halation 强度、vignette 半径族、bloom 阈值半径族、sharpen.radius；桌面无对应录像引擎（N/A）。文件：VideoLutEffect.kt:783-890/966/1072。

---

## 附：审计方法说明

- Grep 定位 → Read 逐段核实；每条结论均带 file:line。
- 活链路判定标准：存在从 CameraScreen.applyAdjustments / LutRenderer / LutImageProcessor / VideoLutEffect 可达的 glUniform 上传与 shader 使用；仅 assets/shaders/*.frag（无 Kotlin 引用）或 GpuParams（toGpuParams 无调用方）可达的判为死代码。
- 未修改任何源文件；本报告为唯一写操作产出。
