# 画质根因分析 0.9.14（RAW/JPEG 体积偏小 + LIVE 封面差于视频静帧）

> 本轮目标：定位「JPEG max 600KB（上游 1.6MB）、RAW max 1.1MB（上游 3.2MB）、LIVE 视频静帧远好于封面」的根因。
> 方法：用 `_diff_engine.py`（包名归一化）对共享管线做全量 diff，并对关键文件逐行比对上游 HEAD。

## 1. Upstream 状态（bjzhou/PhotonCamera HEAD）
- RAW/YUV 成片管线全部在 `processor/`：`GlesMgcRawSpatialStacker.kt`、`GlesMgcRawFusion.kt`、`GlesYuvStacker.kt` 等。
- 上游**没有** `raw/` 包、`YuvProcessor`、`MultiFrameStacker`、`RawDemosaicProcessor`、`RawProcessor`、`SuperResolutionDngWriter`。
- 上游偏好默认值已核对：`rawMaxNoiseReduction = RawDenoiseDefaults.RAW_MAX_LUMA_STRENGTH = 1.0f`（与 fork 完全一致）。

## 2. 当前项目状态（PhotographerCamera）
- `processor/` 已在 **0.9.11 / 0.9.13** 整体对齐上游（GlesYuvStacker / GlesMgcRawSpatialStacker / GlesMgcRawSabreShaders / CalibratedRawNoiseProfile 等）。
- 但成片保存路径**完全走自研引擎**：
  - RAW：`RawDemosaicProcessor.kt`（~8800 行，自研）← `PhotoProcessor.processDng` 调用。
  - JPEG：`YuvProcessor.kt`（自研，上游不存在）← `utils/`。
  - 多帧堆叠：`MultiFrameStacker.kt`（自研，上游不存在）。
- `fork/raw/` 共 **95 个 .kt 文件**，全部上游无对应。

## 3. Profile 参与点（绝不能破坏）
- 成片 LUT 上色在 `LutImageProcessor.applyLutStack(baselineLayer, creativeLayer, noiseReductionValue=0f)`，`PhotoProcessor.processDng` 调用。
- 共享渲染文件与上游的 diff **100% 是 profile 接线**，不是质量 bug：
  - `LutImageProcessor.kt`（111 行 diff）= LensStageGl 镜头光学阶段 + FilmParamsStore 的 profile 色彩矩阵/胶片曲线/halation。
  - `Shaders.kt`（392 行 diff）= 自研计算光圈/虚化着色器（U2NetP 显著性 mask、深度上采样）。
  - `LutRenderer.kt`（87）、`gallery/GalleryManager.kt`（227）、`OglBokehProcessor.kt`（211）等 = 虚化/UI/自定义特性。
- **结论：盲目照搬上游这些文件会删除用户的 profile 系统与虚化功能，绝对不能动。**

## 4. Diff 全景（lut/gallery/processor/utils）
- 真实差异文件（上游存在且内容不同）：`LutImageProcessor`(profile)、`LutRenderer`、`GalleryManager`、`CurveUtils`、`LutManager`、`VideoLutEffect`、`FilmGrainShaders`(profile 颗粒)、`MultiFrameStacker`、`RawStackRuntimeDebug`。
- 上游缺失（fork 自研，无法对齐）：`raw/` 全部 95 文件、`YuvProcessor`、`MultiFrameStacker`、`RawDemosaicProcessor`。
- `utils/` 仅 `PLog`(17)/`DeviceUtil`(4) 微小差异——**`YuvProcessor` 不在差异列表 = 上游根本没有该文件 = 自研**。

## 5. Root Cause（核心结论）
**画质回归 100% 位于 fork 自研的 RAW/JPEG 引擎（raw/RawDemosaicProcessor 95 文件 + YuvProcessor + MultiFrameStacker），上游完全没有对应代码。**

证据链：
- 「LIVE 视频静帧 >> 封面」：视频走 `HardwareLutVideoRenderer`（复用 LutRenderer 顶点着色器，实时 LUT），封面走 `RawDemosaicProcessor → applyLutStack`。LUT/Profile 通道是**同一条且已正确接线**（diff 仅为 profile 功能），所以差异在 LUT **之前**的成片基底——即自研 `RawDemosaicProcessor` 的输出比相机实时 YUV 更平滑。
- 降噪默认值上下游都是 MAX(1.0)，**已排除**默认降噪差异。
- `processor/` 已对齐，但 `RawDemosaicProcessor` 并不调用对齐后的 `GlesMgcRawSpatialStacker`（它用 processor 底层原语自建堆叠），故 0.9.13 的对齐**未进入 RAW 保存路径**。
- 分辨率已核实：1x 下 `exportPhoto output=3072x4096`（全 12.58MP），无缩放；体积偏小纯属内容被过度平滑（高频被抹平 → JPEG 更小）。

## 6. 最小修复方案（需你定夺，因涉及架构）
`processor/` 已无可抄；共享渲染文件动不得（profile）。真正能拿到上游画质的只有两条路：

**(A) 让成片保存改走已对齐的上游 `processor/` 管线**（推荐，符合"对齐上游"语义）
- RAW：`processDng` 改为调用对齐后的 `GlesMgcRawSpatialStacker` + `GlesMgcRawFusion` 做堆叠/融合，而非 `RawDemosaicProcessor` 自建堆叠。
- JPEG：`YuvProcessor` 改为调用对齐后的 `GlesYuvStacker`（0.9.11 已对齐）。
- Profile LUT 在 `applyLutStack` 下游独立施加，**不受影响**。
- 风险：自研引擎内可能还含 profile 色调映射（AgX/Spektrafilm/Hncs 等），需确认这些是在 `RawDemosaicProcessor` 内还是 `applyLutStack` 之后；若在前者，改走 processor/ 会丢失自研色调，需把色调逻辑迁移到 LUT/recipe 层。

**(B) 调试自研引擎的过度平滑**
- 需逐行理解 `RawDemosaicProcessor`（8800 行）+ `YuvProcessor` 的 demosaic/tonemap/降噪参数，定位抹平高频的具体 pass。
- 你已明确「不要自己写代码、不要猜测」——此路本质是在改自研代码，与你指令冲突，故不采用。

## 7. 本次未改动代码的原因
你设定的工作流要求「确认根因后再修改」；且「对齐上游」在画质关键路径上**无上游代码可抄**（fork 自研引擎）。盲改 8800 行自研引擎既违反「不要自己写代码」，也会破坏 profile 系统。故先交付根因，请你在 (A)/(B) 间确认方向。

## 8. 已验证/已交付
- 0.9.11：对齐 YUV 管线（JPEG max 崩退修复的前置）。
- 0.9.12：修 JPEG max 闪退（补上游 GLES JNI）。
- 0.9.13：对齐 RAW 融合栈（GlesMgcRawSpatialStacker 等）。
- 工具：`_diff_engine.py`（包名归一化全量 diff）、`_push_via_api.py`（git-https 不通时经 API 推送）。
