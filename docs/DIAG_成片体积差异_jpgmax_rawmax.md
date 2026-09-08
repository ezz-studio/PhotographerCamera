# 成片体积差异诊断：JPEG max / RAW max（对比上游 bjzhou/PhotonCamera）

日期：2026-09-08
现象（用户反馈，同场景）：

| 模式 | 本 fork | 上游 APP | 差距 |
|---|---|---|---|
| jpg max | 3.4 MB | 5.4 MB | 1.59× |
| raw max | 2.0 MB | 5.7 MB | 2.85× |

附带反常现象：本 fork 的 **raw max(2.0MB) 竟比 jpg max(3.4MB) 还小**；上游则是 raw max(5.7) > jpg max(5.4)。

---

## 0. 方法论（可复现）

上游源码通过 GitHub API 拉取（代理 `127.0.0.1:7990`），包名归一化后与 fork 做逐行 diff：

- `tools/_fetch_full.py <相对 com/hinnka/mycamera/ 的路径>` → 存为 `tools/_up_<类名>.txt`
- `tools/_diff_all_up.py` → 自动把 `tools/_up_*.txt` 与 fork 同名文件归一化 diff，输出**完整差异地图**与 `_up_diff_hunks.txt`
- `tools/_diff_raw_engine.py` → 专门针对 raw/合成引擎的定位 diff

归一化规则：`com.hinnka.mycamera` ↔ `com.photographercamera.photon`。

---

## 1. 链路（两条都经过 processStacking）

```
CameraViewModel.processStacking(frames, ...)
      └─ GalleryManager.saveStackedPhoto(...)        // 按首帧 format 分发
             ├─ YUV_420_888 / YCBCR_P010 / NV21  → saveYuvStackedPhoto()   ← jpg max（RAW 关闭）
             └─ RAW_SENSOR / RAW10 / RAW12        → saveRawStackedPhoto()   ← raw max（RAW 打开）
```

---

## 2. jpg max（3.4MB vs 5.4MB）

### Upstream 状态机
`saveYuvStackedPhoto` 先落盘全质量中间图 `original.heic`（`INTERNAL_HEIC_QUALITY = 100`），随后：

```kotlin
// 上游 GalleryManager.saveYuvStackedPhoto (up line 3123)
val exportBitmap = if (hasHighQualityPhoto(context, photoId)) { null } else { result }
exportPhoto(context, photoId, exportBitmap, ...)
```

传 `null` → `exportPhoto` 走 `prepareUltraHdrSource` 重载：重新加载 `original.heic`、叠加 profile LUT、整图重编码。在**上游**这条链路产出 5.4MB。

### 当前项目状态（0.9.15 起）
0.9.15 已把该分支**删掉**，改为始终直传内存位图 `result`（当前 3.4MB）。

### Diff / 关键实测（真机 `exportPhoto JPEG result`，同质量 95）

| 路径 | output | input | 字节 |
|---|---|---|---|
| `saveYuvPhoto`（单帧，直传位图） | 2880×3840 | 2880×3840 | 2,886,713 |
| `saveYuvStackedPhoto`（**传 null**） | 2880×3840 | null | **1,008,682** |
| `saveRawStackedPhoto`（直传位图） | 3072×4096 | 3072×4096 | 1,025,016 |

### Root Cause（修正结论）
**上游的 `null` 分支本身不是解药，而是 fork 上被证伪的陷阱。**

- 单纯的"上游有 null 分支、fork 没有"并不能解释差距：fork 原样带该分支时实测只有 **1.0MB**，比现在直传位图的 3.4MB **更差**。
- 因此 0.9.15 的"始终直传 result"是**有效的规避**，不是误改。
- 真正的差距在 fork 的 `exportPhoto(bitmap = null)` 重载/重编码链路（`prepareUltraHdrSource` 重新加载 `original.heic` 再处理）相对上游**严重退化**：上游该链路出 5.4MB，fork 只有 1.0MB。

### 本次决策：不恢复 null 分支（已回滚误改）
本轮一度按"对齐上游"恢复了 `hasHighQualityPhoto → null` 分支，经查证会让 jpg max 从 **3.4MB 回退到 ~1.0MB**，属回归，**已回滚**，并在代码内留注释锁定：

```
GalleryManager.kt saveYuvStackedPhoto：exportPhoto(..., result, ...)
```

### 要追平 5.4MB 的正确下一步
修复 fork 的 `exportPhoto(null)` 重载链路（而非恢复 null 分支）：
1. 对比 fork 与上游 `exportPhoto` 内 `bitmap == null` 分支（`prepareUltraHdrSource` 构造）
2. 对比 `PhotoProcessor.prepareUltraHdrSource` 从 `original.heic` 重建位图的分辨率/色彩/量化路径
3. 定位 fork 上导致输出降到 1MB 的降采样/重编码环节

---

## 3. raw max（2.0MB vs 5.7MB）：链路已逐项对齐上游，未发现代码分歧

对 raw max 做了**全链路**比对，fork 与上游在 raw 路径上**参数与引擎完全一致**：

### 3.1 引擎（逐字节级）
| 文件 | 上游行数 | fork 行数 | 结论 |
|---|---|---|---|
| `GlesMgcRawSpatialStacker.kt` | 9921 | 9921 | **字节级一致（仅包名）** |
| `MgcFullResolutionDenoise.kt` | 781 | 781 | **字节级一致** |
| `RawDemosaicProcessor.kt` | 8947 | 8948 | 5 行差异 = 本地 profile 高光/阴影特性 |
| `MultiFrameStacker.kt` | 373 | 370 | 5 行差异 = 缺 `supportsBracketExposure` 属性 |

### 3.2 参数（全部一致）
- **降噪**：`RAW_MAX_LUMA/CHROMA_STRENGTH = 1.0f`；`ChromaDenoiseDefaults.forRawCapture()`、`resolveNoiseReduction()` 一致
- **锐化**：`RawSharpeningDefaults`（DEFAULT 0.4 / ALGORITHM_SCALE 2）一致
- **超分/输出倍率**：`processStacking` 的 `useSuperRes` / `superResScale`（含 `?.let(normalizeOutputScale) ?: MIN_OUTPUT_SCALE` 兜底）与上游**逐行一致**
- **`multiFrameOutputScale` 接线**：`resolveMultiFrameOutputScale()` 与上游**逐行一致**；`rawMaxOutputScale` 默认 `1f`
- **质量**：`photoQuality.firstOrNull() ?: 95` 一致
- **帧数**：`BURST_CAPTURE_BATCH_SIZE = 8`、`MAX_FRAME_COUNT = 20`、HDR+ 帧数算法一致
- **DNG 导出**：`exportDngWithRawExport` 默认 **false**（两边一致）→ raw max 成片都是 JPEG

### 3.3 保存函数
`saveRawStackedPhoto` fork(2971–3760) vs 上游(3147–4086)：DNG profile 准备、`processDngBufferForHdrSources`、`materializeAndPersistDng`、延迟 DNG 写入、`writeFinalJpeg` / `exportPhoto` 全链路一致。

### 3.4 结论
**raw max 不存在能解释 2.85× 体积差的代码分歧。** 剩余两个本地 delta 都不是体积因素：
1. `RawDemosaicProcessor` 的 profile 高光/阴影注入 —— **用户明确要求的特性**（0.9.8），不应移除，只改影调不改分辨率/质量
2. `MultiFrameStacker` 缺 `supportsBracketExposure` —— 只影响包围曝光能力开关

### 3.5 更可能的非代码因素（建议复核）
1. **测量口径**：2.0MB 与 5.7MB 未必同类文件（DNG vs JPEG）。两边 `exportDngWithRawExport` 默认 false，若一侧开了 DNG 体积天然差数倍
2. **测量基准**：需用同一构建、同场景、同 ISO/光照复测
3. **场景熵值**：高质量编码体积对画面细节量极敏感，不同光照/ISO 可成倍差异

---

## 4. 完整差异地图（fork vs 上游）

```
Camera2Controller.kt            844 changed lines   （本地特性：live photo / 手电 / 看门狗 / AF 触发）
CameraViewModel.kt              809 changed lines   （本地修复：0.9.2 帧泄漏、0.9.10 视频串行等）
GalleryManager.kt               230 changed lines
UserPreferencesRepository.kt    127 changed lines
MultiFrameStacker.kt              5 changed lines   （缺 supportsBracketExposure）
RawDemosaicProcessor.kt           5 changed lines   （profile 高光/阴影注入，本地特性）
MultiFrameConfig.kt               4 changed lines   （DEFAULT_HDR_PLUS_FRAME_COUNT=5，用户指令，不改）
GlesMgcRawSpatialStacker.kt     IDENTICAL
MgcFullResolutionDenoise.kt     IDENTICAL
```

> `Camera2Controller`/`CameraViewModel`/`GalleryManager` 的大头差异是本地**功能新增与 bug 修复**（帧泄漏、LIVE 视频串行化、失败态复位等），与成片体积无关，不应回退。

---

## 5. 本轮实际改动

- `android/app/build.gradle.kts`：`APP_VERSION_NAME 0.9.15→0.9.16`、`APP_VERSION_CODE 45→46`（纯修复轮 patch+1）
- `GalleryManager.kt saveYuvStackedPhoto`：保持 0.9.15 的"直传 result"写法，新增**防回退注释**（记录 null 分支实测 1.0MB 的证据）
- 新增诊断工具（不参与编译）：
  - `tools/_diff_all_up.py`（全量差异地图）
  - `tools/_diff_raw_engine.py`（raw 引擎定位 diff）
