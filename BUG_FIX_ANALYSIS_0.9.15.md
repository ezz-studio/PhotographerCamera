# 0.9.15 修复分析：JPEG max / raw max 成片偏小（真机日志铁证）

## 0. 上游状态机（PhotonCamera / 本 fork 实际行为）
快门回调 `CameraViewModel.onImageCaptured` 决策树：
```
if (hdrBracketCapturing)        → HDR 包围
else if (burstCapturing)        → 连续连拍（用户口中的"实时连拍"，本 fork 未启用）
else if (isMultiFrameEnabled)   → 累积帧满 → processStacking → saveStackedPhoto
                                    ├ RAW_SENSOR → saveRawStackedPhoto  (raw max)
                                    └ YUV       → saveYuvStackedPhoto   (JPEG max)
else                            → saveImage → saveYuvPhoto / saveRawPhoto（单帧兜底）
```

`isMultiFrameEnabled = multiFrameOutputScale != null && (!useRaw || isRawSupported)`
`resolveJpgMaxActive(useRaw, useLivePhoto) = !useRaw && !useLivePhoto`（**RAW 关 + 非实况照片即 JPEG max**）
`resolveMultiFrameOutputScale`：`useRawMax`→scale；`useJpgMax`→1f；否则→`null`（掉单帧）

**结论：路由已正确**——"RAW 关→JPEG max、RAW 开→raw max"在代码里已实现（实况照片开启时会临时让 JPEG max 失效，属预期）。

## 1. Profile 参与点
- `saveYuvStackedPhoto` / `saveRawStackedPhoto` 收尾都调 `exportPhoto(bitmap, ...)`。
- `exportPhoto` 内 `photoProcessor.processBitmap` 叠加 profile LUT（3D LUT），**即"JPEG 编码前应用 profile"**，与走A意图一致。改直传位图后该 LUT 层仍生效。

## 2. 真机取证（session s1788831113752，OPPO PLG110，0.9.14）
`exportPhoto JPEG result` 实测（同质量95、近同分辨率）：

| 捕获 | 路径 | output | input | 字节 |
|---|---|---|---|---|
| YUV 单帧 | `saveYuvPhoto` | 2880×3840 | 2880×3840 | **2,886,713** |
| YUV 单帧 | `saveYuvPhoto` | 2880×3840 | 2880×3840 | 2,177,654 |
| YUV 堆叠 | `saveYuvStackedPhoto` | 2880×3840 | **null×null** | **1,008,682** |
| RAW 堆叠 | `saveRawStackedPhoto` | 3072×4096 | 3072×4096 | 1,025,016 |

同一分辨率、同一质量95，堆叠 YUV 仅 1.0MB，单帧却 2.88MB → **堆叠路径丢细节**。

## 3. Diff / Root Cause
`GalleryManager.saveYuvStackedPhoto`（2950 附近）旧代码：
```kotlin
val exportBitmap = if (hasHighQualityPhoto(context, photoId)) null else result
exportPhoto(... exportBitmap ...)   // 传 null
```
- `writeInternalOriginalPhoto` 先把 `result` 存成有损 HEIC；随后 `hasHighQualityPhoto=true` → 传 `null`。
- `exportPhoto` 收到 `null` 后不走直传位图分支，改为从磁盘 HEIC 中间图 `photoProcessor.process` 重新处理 → **二次有损压缩 + 丢细节** → 体积塌到 ~1MB。
- 对照：单帧 `saveYuvPhoto` 直传 `bokehBitmap`（`input=2880x3840`，2.88MB）；RAW 堆叠 `saveRawStackedPhoto` 直传 `bitmap`（`input=3072x4096`，无此 bug）。**仅 YUV 堆叠此处分支不对称**。

**Root Cause**：`saveYuvStackedPhoto` 在有高质量内部原图时错误地把 `null` 传给 `exportPhoto`，触发有损 HEIC 重处理往返，导致 JPEG max 成片过度压缩。

## 4. 最小修复方案（0.9.15）
`saveYuvStackedPhoto` 始终把 `MultiFrameStacker.processBurst` 的直出内存位图 `result` 传给 `exportPhoto`，删除 `hasHighQualityPhoto`→null 分支。
- 与单帧 `saveYuvPhoto`、RAW 堆叠 `saveRawStackedPhoto` 行为一致；
- `result` 已是 tonemapped SDR，经 `exportPhoto` 叠加 profile LUT，符合走A；
- 预期：JPEG max 从 ~1.0MB 回到 ~2.88MB（对齐单帧、接近上游 1.6MB）。

## 5. 残留 / 待办（不猜不改）
- **RAW max（1.0MB vs 上游 3.2MB）**：`saveRawStackedPhoto` 已正确直传位图，其偏小源于 RAW 融合/去噪链路（`MultiFrameStacker.processBurstRaw` / `RawDemosaicProcessor` / `GlesMgcRawFusion`）本身过度平滑或输出分辨率偏低，**非本次 export 往返 bug**。需单独下钻降噪/分辨率，不在本轮猜测修改。
- 实况照片开启时 JPEG max 会被 `resolveJpgMaxActive` 临时关闭（预期行为），若用户希望实况下也保留 max 需另议。
