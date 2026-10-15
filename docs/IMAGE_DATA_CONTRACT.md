# 图像数据契约（IMAGE_DATA_CONTRACT）

> 版本：v1.0（Phase C 成文，2026-10-15） · 基线：main = `c4523dc` + Phase C 改动
> 依据：`_audit_phase_a/04_format_precision.md`（只读审计事实源）
> 本文档是 Android 成像链的**数据契约**（需求 3.5/3.6/3.7）。任何新模块接入成像链
> 前必须声明自己满足的契约条目；违反契约的改动不得合入。

---

## 0. 总则

1. **唯一量化点原则**：高精度中间处理尽可能保留高精度，量化只允许发生在
   「最终输出边界」（终点 8-bit 编码 + TPDF 抖动）或该链路的设计入口
   （YUV/HEIC 8-bit 链的输入本就是 ISP 8-bit 编码数据）。
2. **格式即契约**：每个 Pass 的输入/输出必须满足本文对应链路表格中的
   宽高、像素格式、色彩空间、传递函数、动态范围、Alpha、方向约定。
3. **RAW Bayer ≠ 普通 RGB**：Bayer/LinearRaw 数据只走独立整数纹理路径
   （`RawDemosaicProcessor.kt:4365-4374` 上传，`769-777` 物化），
   有 CFA pattern 校验（`RDP.kt:1698-1701`），禁止当作普通 RGB 处理。
4. **ISP 处理过的 JPEG/YUV ≠ 线性传感器数据**：YUV/HEIC 链按编码 sRGB
   处理（8-bit 设计链），禁止按线性域解释。
5. **色彩空间 tag 必须随 Bitmap 传递**：创建 Bitmap 时必须显式传
   `colorSpace`；丢失 tag 视为契约违反（Phase C 已修复 LensStage 出口）。

---

## 1. 链路契约表

### 1.1 RAW / DNG 成片链（唯一 16F 主链）

| 阶段 | 输入 | 输出 | 色彩空间/传递函数 | 动态范围 | 证据 |
|---|---|---|---|---|---|
| RAW 上传 | DNG/RAW | 纹理 R16UI/RGB16UI/RGBA16UI（整数 Bayer/LinearRaw 原始域） | 线性传感器域 | 传感器原生 | RDP.kt:4365-4374/4406-4416 |
| demosaic/WB/exposure/tone | 整数纹理 | RGBA16F 中间纹理/FBO | 编码 sRGB（tone 曲线后） | [0,1] 编码域 | RDP.kt:4833-6625 |
| 引擎交接（readPixels） | RGBA16F FBO | `Bitmap(RGBA_F16, SRGB)` | SRGB tag，编码域 | [0,1] | RDP.kt:8665/8673-8680/8711-8718 |
| **LensStage（Stage 0）** | `Bitmap(RGBA_F16)` | `Bitmap(RGBA_F16, 同输入 tag)` | 编码域（光学效应乘法/阈值按编码域标定） | [0,1] | **Phase C 修复**：LSG 16F RT + HALF_FLOAT 读回；LIP.kt applyLensStage |
| baseline 层（applyLut） | RGBA_F16 | RGBA_F16（output16F=true） | 编码域 | [0,1] | LIP.kt:551/1386-1389/1487-1504 |
| creative 层（applyLut） | RGBA_F16 | ARGB_8888（终点唯一量化 + TPDF） | `inputColorSpace` | [0,1] | LIP.kt:2175-2268/1293-1304 |

**Phase C 变更（PA-01 修复）**：LensStage 从 8-bit 读回（`LIP.kt:658/664` 旧）改为
半精度全程——输入 F16 时 DirectBuffer 直传 `GL_RGBA16F/HALF_FLOAT`、LensStageGl
内部 8 个 RT 全部 `GL_RGBA16F`（`halfFloat=true`）、读回 `GL_HALF_FLOAT`、
出口 `Bitmap(RGBA_F16)` 且 `colorSpace` 随输入传递。**非零 lens 参数的 profile
不再把 16F 主链降级为 8-bit。**

### 1.2 YUV / HEIC 成片链（8-bit 设计链）

| 阶段 | 输入 | 输出 | 说明 |
|---|---|---|---|
| 解码 | ISP JPEG/YUV | ARGB_8888 | 输入即 ISP 已编码数据 |
| applyLutStack | ARGB_8888 | ARGB_8888（working16F=false，主 FBO GL_RGBA8） | 全链 8-bit 为该链设计 |
| LensStage | ARGB_8888 | ARGB_8888 | Phase C 后按 `input.config != RGBA_F16` 走 8-bit 分支（原路径不变） |
| 导出 | ARGB_8888 | JPEG/HEIC | Jpeg444ExportEncoder |

### 1.3 预览链（LutRenderer）

| 阶段 | 格式 | 说明 |
|---|---|---|
| LensStageGl.process() | 纹理级，RT 8-bit（默认 `halfFloat=false`） | 无 CPU 读回；Phase C 未改预览行为 |
| color pass 主 FBO | RGBA16F | LR.kt:755/785 |
| 抽帧 capture | GL_RGBA8 FBO → ARGB_8888 + Y 翻转 | 最终输出用途（LR.kt:2543/3102/3113-3121） |
| 测光 / 峰值对焦 | RGBA8 | 统计用途 |

### 1.4 周边链（格式与阶段正确对应）

| 链路 | 格式 | 判定 |
|---|---|---|
| YUV 多帧堆栈 | accumulator RGBA16F；输出 RGBA8 → ARGB_8888 | YUV.kt:787/789/2578/2555 |
| LUT 亮度 sidecar | RGBA16F → HALF_FLOAT 读回 → RGBA_F16 + LINEAR_EXTENDED_SRGB | 线性数据按线性解释（LIP.kt:810/863-880） |
| Gainmap | 渲染 RGBA8，alpha 读回 UNSIGNED_BYTE | gainmap 规范 8-bit（GRG.kt:219/300） |
| RAW 堆栈物化 | GL_RGBA_INTEGER/UNSIGNED_SHORT | 16-bit 整数线性域（RDP.kt:769-777） |
| Bokeh | F16 输入→RGBA16F→RGBA_F16；以 ColorSpace tag 判定线性 | OBP.kt:109-111/228-549 |
| RAW AE 预览 | UNSIGNED_BYTE | 仅测光（RDP.kt:8490-8498） |
| MGC 降噪 | HALF_FLOAT PBO | 16F 保持（RDP.kt:1256-1264/5005-5013） |

---

## 2. 色彩空间与传递函数约定

1. **16F 主链的值域是编码 sRGB（tone 曲线后）**，不是线性。`workingColorSpace=SRGB`
   （RDP.kt:1687-1688）。shader 内仅 HSL/LCH/exposure 等局部显式
   `srgbToLinear/linearToSrgb`（LIP.kt:3864/3878/4205/4210）。
2. **LensStage 按编码域标定**：bloom 阈值 0.82、flare 阈值 0.9
   （LSG.kt:109-111）——链序前移到线性数据会失真，禁止。
3. **线性数据必须带 LINEAR tag**：LUT 亮度 sidecar 与 HDR 参考图用
   `LINEAR_EXTENDED_SRGB`（LIP.kt:879；RDP.kt:4018-4020）。
4. **`uInputColorSpace` uniform 仅二值**（0=SRGB / 1=DISPLAY_P3，LIP.kt:1010-1011）；
   P3 输入按编码域处理，不引入线性假设。
5. **Bitmap 创建必须传 colorSpace**（Phase C 起 LensStage 出口强制执行；
   其余入口见 §1 各表证据行）。

## 3. Alpha 语义

- LensStage composite 恒输出 `a=1.0`（LSG.kt:180）——光学阶段不保留 alpha。
- 主链终点保留 `color.a`（LIP.kt:3730）。
- RAW sdrBitmap 的 alpha 当前无消费方；新模块不得假设其有效。

## 4. 方向约定

- 成片链读回**不翻转**，靠 `texCoordBuffer` 翻转采样补偿（LIP.kt:3509-3519）。
- 预览抽帧**显式翻转**（LR.kt:3118-3121）。
- LensStage 读回不翻转（LIP.kt:622 注释，Phase C 保持）。
- 新接入链必须声明遵循上述其一，禁止混用。

## 5. 量化规则

| 量化点 | 位置 | 性质 |
|---|---|---|
| 终点 8-bit 编码 + TPDF | LIP.kt:2175-2268/1293-1296 | **唯一设计内量化** |
| 后处理 8-bit 出口（clarity/sharpen/bloom/grain） | LIP.kt:2748/2065-2075/3140、FG.kt:168 | P1 条件量化（中间层实为 8-bit，代码注释 LIP.kt:1224-1227 承认）——排期 Phase E |
| LensStage ~~8-bit 读回~~ | ~~LIP.kt:658/664~~ | **Phase C 已消除（PA-01）** |
| F16 上传缓冲分配失败降级 | LIP.kt:1506/1516-1525 | P2 降级路径（有告警日志） |
| 终点编码失败兜底 | LIP.kt:1313-1329 | P2 容错（注释承认无 TPDF） |

## 6. 异常回退契约（需求 3.8）

**原则：回退路径必须记录原因，禁止静默返回原图使调用方误以为 Profile 已应用。**

| 异常 | 位置 | 行为 | 日志 |
|---|---|---|---|
| applyLut initialize 失败 | LIP.kt:522-525 | 原图直返 | **Phase C 补** `PLog.w(...profile NOT applied)` |
| 主 shader 链接失败 | LIP.kt:1670-1690 | initialize 返 false（上层按上条处理） | **Phase C 补**链接状态检查 + `PLog.e` |
| 主 FBO 不完整 | LIP.kt:1482-1485 | 继续（既有行为） | `PLog.e`（已有） |
| LensStage 异常 | LIP.kt applyLensStage catch | passthrough 原输入 | `Log.w` + **Phase C 补** `PLog.w`（含 want16F） |
| compute 不支持 | LIP.kt:1721-1729 | 降级 passthrough program | **Phase C 补** `PLog.w` |
| LensStage F16 上传/读回 buffer 分配失败 | LIP.kt applyLensStage | 8-bit 降级 / OOM 返原图 | `PLog.w` / `PLog.e` |

## 7. 待真机验证（Phase C）

- V1（PLG110）：非零 lens profile + RAW 成片 → `applyLensStage` 出口为
  RGBA_F16（可在远程日志确认无 "8-bit fallback" 告警）。
- V2：`GL_RGBA16F` RT + `GL_HALF_FLOAT` 读回组合在 Mali/Adreno 上恒合法
  （与 1.3.4 已实证的 16F 主链同组合，需回归确认）。
- V3：Room 39→40 迁移后旧相册正常打开，`profileId/profileVersion` 为 NULL。

---

*契约维护：任何改动 §1 表格中格式/色彩空间/方向的提交，必须同步更新本文档。*
