# 画质根因分析 0.9.14（RAW/JPEG 体积偏小 + LIVE 封面差于视频静帧）— 已确认走 A 并修复

> 前一轮（同名文档上半部分）误判"上游无 MultiFrameStacker、成片完全走自研引擎"。本轮经完整调用链核查，**纠正该前提**，并落实用户确认的"走 A"方向。
> 用户原话：「上游本来就有 lut 层，在 JPEG 出现之前应用我的 profile 就行，走 a。」

## 1. Upstream / 已对齐管线实际状态（经调用链核查）
- 实时连拍**已经在用已对齐的上游 `processor/` 管线**：
  - `GalleryManager.saveYuvStackedPhoto` → `MultiFrameStacker.processBurst`（YUV）
  - `GalleryManager.saveRawStackedPhoto` → `MultiFrameStacker.processBurstRaw`（RAW，上游融合栈）
- `processor/` 在 0.9.11/0.9.13 已对齐上游（GlesYuvStacker / GlesMgcRawSpatialStacker / GlesMgcRawFusion / CalibratedRawNoiseProfile 等），且**已进入保存路径**（0.9.14 核查推翻了"未进入"的旧结论）。
- 上游"LUT 层"= 本项目 `LutImageProcessor.applyLutStack`（3D LUT / profile），**已在 `PhotoProcessor` 里于 JPEG 编码前叠加**。用户说的"上游本来就有 lut 层"指的就是这一层，且它已经正确接线。

## 2. 真正决定"成片观感/体积"的开关：渲染引擎档位
- 最终 SDR 色调映射由 `RawDemosaicProcessor` 的 `rawRenderingEngine` 参数选择，枚举 `RawRenderingEngine`：
  - `AdobeCurve`（shaderId=0，"Adobe 曲线 · Camera2 降噪模型"，注释"已参与成像管线"，`RawDemosaicProcessor` 内部默认参数即 `AdobeCurve`）→ **上游中性基线**。
  - `AgX`（shaderId=1，defaultExposureCompensationEv=0.7，电影感胶片）→ **自研重度色调映射**，会压暗肩部、削高频、抹细节，成片被压成小文件。
  - 另含 HncsCcm/HncsLut/Spektrafilm/DarktableSigmoid/DarktableFilmic（均为自研创意档）。
- **捕获链路默认被钉成 `AgX`**（三处一致的 `0.9.9：默认 AgX（用户指令）`）：
  1. `UserPreferencesRepository.kt:117` → `rawRenderingEngine = RawRenderingEngine.AgX`
  2. `CameraViewModel.kt:1058` → `resolveCaptureRawRenderingEngine` 回退 `AgX`
  3. `RawRenderingEngine.kt:71` → `fromPersistedName` 回退 `AgX`

## 3. Profile 参与点（绝不能破坏，本次也未动）
- 成片 LUT 上色在 `LutImageProcessor.applyLutStack(baselineLayer, creativeLayer, noiseReductionValue=0f)`，`PhotoProcessor.processDng` / `exportPhoto` 调用，位于色调映射**之后**、JPEG 编码**之前**。
- 切换基础引擎只改"色调映射基底"，profile 3D LUT 仍在之后叠加 → **profile 风格 100% 保留**（这正是走 A 相对"改走 processor/ 重写管线"更安全之处）。

## 4. Diff / Root Cause（修正）
- **Root Cause**：画质回归不在融合栈（已用上游），而在**最终色调映射档位默认 `AgX`**（自研电影感），叠加把基底压平 → 封面比实时 LIVE 视频（走 `HardwareLutVideoRenderer`，仅叠 profile LUT、基底中性）更糊、文件更小。
- 文件体积偏小（JPEG 600KB vs 1.6MB、RAW 1.1MB vs 3.2MB）= 高频被 AgX 肩部压平 → JPEG 更容易压缩；分辨率已核实无缩放（1x 全 12.58MP）。
- 旧分析称"上游无 MultiFrameStacker、0.13 对齐未进入路径"——**本轮核实为误判**：`saveRawStackedPhoto` 明确调用 `MultiFrameStacker.processBurstRaw`，对齐代码已进入保存路径。

## 5. 最小修复方案（已实施，走 A）
**把捕获默认渲染引擎从 `AgX` 切回上游中性 `AdobeCurve`**，profile LUT 维持 JPEG 前叠加：
- `UserPreferencesRepository.kt:117`：`AgX` → `AdobeCurve`
- `CameraViewModel.kt:1058`：回退 `AgX` → `AdobeCurve`
- `RawRenderingEngine.kt:71`：`fromPersistedName` 回退 `AgX` → `AdobeCurve`

效果：
- 新拍摄成片基底改为上游中性 `AdobeCurve`，观感/体积向上游看齐；封面与 LIVE 视频静帧趋同。
- profile（摄影师 LUT）在 `applyLutStack` 后仍叠加 → 风格不丢。
- 旧照片按各自捕获时存储的引擎回放，不受影响；用户仍可在设置里手动切回 AgX/Spektrafilm 等。

## 6. 版本
- `android/app/build.gradle.kts`：`0.9.13`(43) → `0.9.14`(44)，并编译发布到 R2（每次打包必上传）。

## 7. 遗留 / 待真机验证
- 若用户设备曾在设置里**显式**选过 AgX（被 DataStore 持久化），默认值改动不生效，需在设置里切到"Adobe 曲线"或后续加一次性迁移；多数情况下 AgX 仅为默认未持久化，改默认值即生效。
- RAW 模式下的 DNG 体积（融合 linear raw）不受引擎档位影响；若"RAW 1.1MB"指 DNG，则需另查 `MultiFrameStacker` 输出分辨率/位深（非本次范围）。
- 真机验证点：JPEG/RAW 体积是否向上游靠拢；LIVE 封面是否接近视频静帧；profile 风格是否保留。
