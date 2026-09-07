# BUG_FIX_ANALYSIS_0.9.11

版本 0.9.11 (41) ｜ 两条线：**变焦修复** + **画质修复（对齐上游 JPGmax 管线）**

---

# 一、变焦（用户报告 2 点）

## 点 1：无法调用 1x 以下的镜头，变焦无效

### Upstream 状态机
- 档位/settle 路径：`findOptimalLens(targetZoom)`（v2/CameraViewModel 4935-4956，候选 = 非前置非微距中 `displayIntrinsicZoomRatio <= targetZoom + 0.01`，取最大 intrinsic，并列优先当前相机）→ 不同相机则 `switchToLensAndSetZoomRatio(cameraId, ratio)`（3356-3367：syncVendor → switchToCameraId → setZoomRatioForCamera → reopenCamera(preserveVideoRecording=true)）
- 连续拖拽/捏合热路径：纯 `setZoomRatio`，**绝不切镜头**

### 当前项目状态
- 上游方法链在 fork 中**全部留存**（switchToLensAndSetZoomRatio:3191、findOptimalLens:4832、isVideoLensLocked:4867、setZoomRatioForCamera:3300、syncVendorCaptureSettingsToController:3322、reopenCamera:3207、Camera2Controller.switchToCameraId:5177），音量键 handleVolumeZoom 已在用（4714-4718）
- 但 UI settle（CameraScreen:319）只调 `pvm.setZoomRatio(snap)`——**settle 路径无跨镜头路由**（0.8.3 为修乒乓把跨镜头从整条链移除）

### Diff
- 日志铁证：`vm.in ratio=0.622 cur=2 dispIntrinsic=1.0 global=[0.62,5.98]` → `vm.out ctrl=0.622` ✓ → `ctrl requested=0.622 open=2 physOut=null range=[1.00,10.00] clamped=1.000` ✗

### Root Cause
用户设备 HAL 无逻辑多摄路由（各相机独立 zoomRatioRange），0.6x 下发给当前相机（id=2，range=[1.00,10.00]）被 range 下限钳死在 1.0。正确架构 = 上游语义：拖拽不切镜头（防乒乓），仅在 settle 档位切换时跨镜头路由。

### 最小修复
1. `CameraViewModel.settleZoomRatio(ratio)` 新增（照搬 handleVolumeZoom 4708-4719 路由语义：custom stop → setZoomRatio；findOptimalLens 不同相机 → switchToLensAndSetZoomRatio；否则 setZoomRatio）
2. `CameraScreen.settleZoomStop` 改调 `pvm.settleZoomRatio(snap)`；拖拽/捏合热路径不动

---

## 点 2：取景框缩放动画和范围不对

用户规格：0.6-1 预览框内图像变化、取景框最大化保持不动；1 以上预览框内图像不变、取景框缩放，与实际拍摄范围一致。

### Root Cause（三处实锤）
1. `zoomState` 跟随 `state.zoomRatio` = **控制器本地倍率**（ratio/dispIntrinsic 后被相机 range 钳制，Camera2Controller 5827/5853），而 ZoomRotor 的 min/max 标尺是全局**显示倍率** 0.62-5.98 → 长焦上滑块与取景框全部错位
2. 跨镜头时 `switchToCameraId` 先置 1f 再设钳后值 → 取景框两次硬跳，且无动画
3. `eqBase` 用 18..40 过滤拒掉超广角（~16mm）；`focalMm = eqBase × zoomState` 跨镜头后错误（长焦 52×3=156 而非 78）

### 最小修复
1. `zoomState` 源改 `pvm.zoomRatioByMain`（mutableFloatStateOf，显示倍率，与全局标尺同基准）
2. 公式（含焦段与倍率）：`f = if (z > 1.001) (dispIntrinsic / z).coerceAtMost(1f) else 1f`——预览 GL 在 >1x 钳到当前相机 1x（= dispIntrinsic 倍视野），实际拍摄为 z 倍视野；coerce 处理长焦上 z<intrinsic 的瞬态
3. `animateFloatAsState` + 无过冲 spring（DampingRatioNoBully / StiffnessMedium）平滑跟手与跨镜头过渡
4. `eqBase`：后置固定主摄焦段基准（等效焦距 = 主摄焦段 × 显示倍率），前置用前置自身焦段

---

# 二、画质（用户报告 3 点）：色彩断层 / 12MP 仅 1.4MB / 多帧融合重影

固定机位同场景，上游 APP 12MP 4.1MB、无断层；本 APP 23:30 拍摄 12MP 1.4MB、色彩断层、融合重影。

## Upstream 状态机（JPGmax / YUV burst）
5 帧 YUV → 金字塔 LK 对齐（rg16f-sparse2 对齐域 + 可滤波当前金字塔）→ 2x2 块一次性解码（**逐 RGB 采样先 clamp 再平均**，guide 与 alignment 共享同一组 3x3 低通）→ GPU compute 全局对齐（GlesSpatialGlobalAlignment，±64 直方图 / peak≥10）→ rejection → 加法 RBF 时域累计（RGBA16F）→ 后处理。

## 当前项目状态（真机日志 s1788794050128）
- `JPGmax denoise burst plan: zeroEvFrames=5 hdr=false`，5 帧全捕获
- `GLES stack format=YUV_420_888 internal=R8/RG8 spatialGuide=2048x1536 flowGrid=256x192`
- 四帧 `global=mode(-64.0,-64.0,n≈1116)` 恒定（边界 bin 聚集）
- `writeFinalJpeg ... quality=95, jpeg444=false, bytes=1432344`；internal HEIC q100 中转无损

## Profile 参与点
融合管线（MultiFrameStacker → GlesYuvStacker）不读 profile/LUT 参数——与"原生滤镜"无因果关系；LUT 在导出链 8bit 位图上应用只会放大已有断层，非根因，本轮不动。

## Diff（fork 的 0.8.0 快照 vs 上游 HEAD）
上游 processor 目录实测对比：
- `GlesYuvStacker.kt` 4282 vs 4275 行，**1219 行 diff**（159 hunk）
- `GlesYuvSpatialShaders.kt` 436 vs 376 行，**248 行 diff**
- fork **缺失** `GlesSpatialGlobalAlignment.kt`、`GlesYuvAlignmentShaders.kt`、`GlesYuvHardwareBufferInput.kt`、`GlesYuvTiming.kt`、`GlesHardwareBufferImage.kt`、`GlesGpuTimerQuery.kt`
- fork 的 `GlesMgcRawSpatialShaders.kt` **缺失** `yuvGrayDownsample` / `yuvGrayDownsample4` / `yuvAlignmentGradientProducts` / `yuvUpsampleAlignment` / `rejectionWithFlowSource` 及其 4 个 builder
- 已对齐（0 差异）：`MgcSpatialMergeTuning`、`MgcSpatialDenoiseModel`、`MgcSpatialNoiseEstimatesLut`、`MgcAlignmentInputScale`、`MgcSpatialOutputExposure`、`DenoiseStrength`、`GlesMgcSpatialRgbChromaPostprocessor`、`GlesComputeWorkGroup`、`GlesGpuScheduler`、`GlesGpuCompletion`、`GlesPixelBufferTransfer`

实质性偏差：
1. **色度解码时序**：上游 `prepareBlocks` 逐 RGB 采样**先 clamp 再平均**（"as the Spatial guide does"，alpha 保留未缩放 Y）；fork 是**先平均 YUV 再转 RGB**、guide 与 alignment 各自独立低通 → 色彩准确度与色度伪影差异
2. **对齐域**：上游 `alignment=rg16f-sparse2`（半浮点对齐域 + 稀疏精细级）；fork 为 R16I Fixed14
3. **金字塔**：上游 `createPyramid(linearSampling = true)`（可滤波当前金字塔，LK 更精确）
4. **全局对齐**：上游 GPU compute（GlesSpatialGlobalAlignment）；fork 为 CPU glReadPixels 版
5. **输入**：上游 HardwareBuffer 零拷贝输入（`inputPreference=hardwarebuffer fallbackUpload=ring2`）
6. **拒绝滤波**：上游对称配对高斯核（paired kernel）；fork 为方向分离式

## Root Cause
fork 的融合管线停留在 0.8.0 移植时的上游旧快照，色度解码/对齐域/金字塔采样/全局对齐/拒绝滤波均与上游 HEAD 存在系统性偏差。错误对齐 → 重影；融合退化为参考帧主导的过度平滑 → 细节丢失（JPEG 体积跌至 1.4MB）→ 大面积平滑渐变叠加 8bit + 4:2:0 → 色彩断层。

## 最小修复（照搬上游，不自行改写）
1. 整体照搬 `GlesYuvStacker.kt`、`GlesYuvSpatialShaders.kt`（上游 HEAD 原文件，仅做包名映射 `com.hinnka.mycamera` → `com.photographercamera.photon`）
2. 新增上游 `GlesSpatialGlobalAlignment.kt`、`GlesYuvAlignmentShaders.kt`、`GlesYuvHardwareBufferInput.kt`、`GlesYuvTiming.kt`、`GlesHardwareBufferImage.kt`、`GlesGpuTimerQuery.kt`
3. 从上游 `GlesMgcRawSpatialShaders.kt` 原样复制 fork 缺失的 9 个成员（4 val + 4 builder + `rejectionWithFlowSource`，共 408 行），**不改动 RAW 路径现有成员**
4. 不动编码参数（photoQuality 95 / jpeg444 默认 false / internal HEIC q100）——体积与断层是融合输出的果，待实机验证

## 验证
- 编译：BUILD SUCCESSFUL
- 待真机：同机位同场景 A/B（重影、体积、断层）；日志看 `global=` 不再恒为 `mode(-64,-64)`，`GLES stack ... alignment=rg16f-sparse2`
