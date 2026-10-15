# 04 · 图像格式与精度审计（RGBA_F16 高精度链与中间量化）

- 审计对象：PhotographerCamera，HEAD = `c4523dc6b0f00af8aae218b41b1f5ae4ecb2cd4d`
- 源码根：`android/app/src/main/java/com/photographercamera`
- 审计方式：只读（Grep 定位 → Read 逐段读）。本文件为唯一写操作。
- 路径简写（下文 `LIP` = `photon/lut/LutImageProcessor.kt`，`LR` = `photon/lut/LutRenderer.kt`，`RDP` = `photon/raw/RawDemosaicProcessor.kt`，`LSG` = `core/photon/lens/LensStageGl.kt`，`PP` = `photon/gallery/PhotoProcessor.kt`，`FG` = `photon/lut/FilmGrainGl.kt`，`CPP` = `photon/lut/ColorPassProgram.kt`，`GRG` = `photon/hdr/GpuReferenceGainmapProducer.kt`，`OBP` = `photon/processor/OglBokehProcessor.kt`，`YUV` = `photon/processor/GlesYuvStacker.kt`）。

---

## ① 主链流程图（文字版，每阶段标注格式）

### A. 成片端 RAW / DNG 高精度主链（唯一真正的 16F 链）

```
DNG/RAW 文件
  → RDP.uploadRawTextureRegion()            纹理 = R16UI/RGB16UI/RGBA16UI（整数 Bayer/LinearRaw 原始域）
                                              RDP.kt:4365-4374（internalFormat），4406-4416（UNSIGNED_SHORT 上传）
  → RDP demosaic/WB/exposure/tone 全 GPU 链   中间纹理/FBO = RGBA16F（RDP.kt:4833、6243、6273、6328、6445、6481、6529、6584、6625 等）
  → RDP.readPixels(finalW, finalH, workingColorSpace)
        PBO glReadPixels(RGBA, GL_HALF_FLOAT)  RDP.kt:8665；降级直读 RDP.kt:8707
        → Bitmap(RGBA_F16, colorSpace=SRGB)    RDP.kt:8673-8680 / 8711-8718
        = RawHdrRenderResult.sdrBitmap         RawHdrRenderResult.kt:12；workingColorSpace=SRGB（RDP.kt:1687-1688）
     （HDR 参考图平行输出：RGBA_F16 + LINEAR_EXTENDED_SRGB，RDP.kt:4018-4020/3928）
  → PP.prepareUltraHdrSourceFromRawResult()   PP.kt:262-355
      ├─ 可选 Bokeh（先于 LUT 栈）            OBP：F16 输入→RGBA16F FBO→HALF_FLOAT 读回→RGBA_F16（OBP.kt:109、228/355/409/465、533-549）
      └─ applyLutStackWithLuminanceGain()     PP.kt:317 → LIP.kt:735-769 → applyLutStack(bitmap) LIP.kt:673-728
          ├─【Stage 0 · 条件触发】applyLensStage()   LIP.kt:616-671（LensParamsStore.current 非零时，LIP.kt:684）
          │    上传 GLUtils.texImage2D            LIP.kt:630
          │    LSG 内部 8 个 RT 全部 GL_RGBA8     LSG.kt:198(Rt.ensure)、265(makeRt)；warp/extract/blur/streak/composite 全 8-bit
          │    读回 glReadPixels(UNSIGNED_BYTE)   LIP.kt:658
          │    → Bitmap(ARGB_8888)（无 colorSpace）LIP.kt:664
          │    ⇒ F16 输入在此被降为 8-bit；返回后 LIP.kt:694/548 working16F=false
          │    ⇒ 【P0 量化瓶颈：位于整条高精度链最前端，LUT/配方全部随后按 8-bit 运行】
          ├─ baseline 层 applyLut(working16F=true, output16F=true)   LIP.kt:697-704
          │    主 FBO = RGBA16F                 LIP.kt:551→1386-1389（8-bit 分支 1391-1394）
          │    F16 直传 GL_RGBA16F/HALF_FLOAT   LIP.kt:1487-1504
          │    色度降噪 FBO = RGBA16F           LIP.kt:1849
          │    NLM 降噪 compute = RGBA16F       LIP.kt:2490/2570
          │    HDF/SoftLight/Halation 1/4 = RGBA16F  LIP.kt:2643 / 2682 / 2945
          │    主调色 shader pass → 主 16F FBO   LIP.kt:980-985
          │    后处理（条件激活，均为 8-bit 出口）：
          │      clarity 金字塔 RGBA16F / 输出 RGBA8   LIP.kt:2733 / 2748
          │      sharpen  FBO RGBA8                  LIP.kt:2065-2075
          │      bloom mip RGBA16F / 输出 RGBA8       LIP.kt:3203 / 3140
          │      filmGrain RGBA8                     FG.kt:168
          │    中间层读回：末级 8-bit 纹理直通回主 16F FBO（精度不恢复）
          │      renderPassthroughToMainFbo           LIP.kt:2304-2342（调用点 1231）
          │      → glReadPixels(RGBA, HALF_FLOAT)     LIP.kt:1244-1247
          │      → Bitmap(RGBA_F16, inputColorSpace)  LIP.kt:1253-1261
          │    ⇒【P1 条件量化：任一后处理激活时中间层实为 8-bit（代码注释已承认，LIP.kt:1224-1227）】
          └─ creative 层 applyLut(output16F=false)   LIP.kt:705-711
               链路终点唯一一次 8-bit 编码（TPDF 抖动）
                 renderFinalEncodePass → GL_RGBA8 FBO  LIP.kt:2175-2268（FBO 2207-2210）
                 glReadPixels(UNSIGNED_BYTE)            LIP.kt:1293-1296
                 → Bitmap(ARGB_8888, inputColorSpace)   LIP.kt:1302-1304
```

### B. 成片端 YUV/HEIC 链（8-bit 设计链）

```
ISP JPEG/YUV → decode(ARGB_8888) → PP.processBitmap()  PP.kt:739-818
  → applyLutStack / applyLutStackWithLuminanceGain      PP.kt:792/803
  → LIP.applyLut：working16F=false（LIP.kt:548）
     主 FBO = GL_RGBA8（LIP.kt:1391-1394），全链 8-bit；后处理 FBO 亦 8-bit
     读回 UNSIGNED_BYTE → ARGB_8888（LIP.kt:1336-1349）
  → Jpeg444ExportEncoder：非 ARGB_8888 先 copy（Jpeg444ExportEncoder.kt:35-41）
```

### C. 预览链（LutRenderer，GLSurfaceView）

```
相机 OES/2D 纹理
  →【条件】LensStageGl.process()          LR.kt:1496-1514（内部 RT 8-bit，LSG.kt:265；无读回，仅纹理级量化）
  → 预览 color pass → 主 FBO = RGBA16F   LR.kt:755/785
     HDF/Halation/SoftLight 1/4 = RGBA16F  LR.kt:1623/1651/1693
     clarity 输出 RGBA8                     LR.kt:2127
     后处理 scratch RGBA8                   LR.kt:2035
  → 屏显：默认帧缓冲 8-bit（显示用途，预期）
  → 预览抽帧 capture：capture FBO = GL_RGBA8（LR.kt:2543）
      glReadPixels(UNSIGNED_BYTE, PBO)      LR.kt:3102
      → Bitmap(ARGB_8888) + Y 翻转          LR.kt:3113-3121（最终输出用途）
  → 测光：metering FBO RGBA8 + UNSIGNED_BYTE 读回  LR.kt:2500/3203
  → 峰值对焦 FBO RGBA8                        LR.kt:4002
```

### D. 周边链（格式均正确对应其阶段）

```
YUV 多帧堆栈：accumulator RGBA16F，output/readbackTexture RGBA8，readOutputBitmap UNSIGNED_BYTE→ARGB_8888（YUV.kt:787/789/2578/2555）
LUT 亮度 sidecar：RGBA16F FBO（LIP.kt:810）→ HALF_FLOAT 读回（863-871）→ RGBA_F16 + LINEAR_EXTENDED_SRGB（874-880）
Gainmap：渲染目标 RGBA8（GRG.kt:300），alpha 读回 UNSIGNED_BYTE（GRG.kt:219）——gainmap 最终产物，8-bit 为规范
RAW 堆栈物化：GL_RGBA_INTEGER/UNSIGNED_SHORT（RDP.kt:769-777）——16-bit 整数线性域，正确
RAW AE 预览：UNSIGNED_BYTE 读回（RDP.kt:8490-8498）——仅测光，不影响成片
MGC 降噪：HALF_FLOAT PBO 读回（RDP.kt:1256-1264、5005-5013）——16F 保持
```

---

## ② 读回 / 量化点全表

| # | 位置（file:line） | 阶段 | 格式动作 | 精度影响 | 定级 |
|---|---|---|---|---|---|
| Q1 | LIP.kt:658 + LIP.kt:664 | 成片链 Stage 0（LensStage，位于 LUT/配方之前） | RGBA→UNSIGNED_BYTE 读回 + 建 ARGB_8888（无 colorSpace） | **F16→8-bit，整链降级** | P0 高危 |
| Q2 | LSG.kt:198 / LSG.kt:265 | LensStage 内部全部 8 个 RT | GL_RGBA/UNSIGNED_BYTE 创建 | 首个 warp pass 即量化；composite 还 clamp 到 [0,1]（LSG.kt:180） | P0 高危（与 Q1 同因） |
| Q3 | LIP.kt:2748（clarity 输出）/ 2065-2075（sharpen）/ 3140（bloom 输出）/ FG.kt:168（grain） | 成片链后处理 pass | RGBA8 FBO | 中间层（baseline，output16F=true）激活时任一后处理 → 8-bit 量化后经 passthrough 回 16F（LIP.kt:1231），精度不恢复 | P1 条件瓶颈 |
| Q4 | LIP.kt:1516-1525（asGlUploadCompatibleBitmap） | F16 上传降级路径 | F16→ARGB_8888 隐式量化 | 仅当 F16 native buffer 分配失败触发（LIP.kt:1506 告警后）；正常路径已绕开（1487-1504） | P2 降级路径 |
| Q5 | LIP.kt:1293-1296 + 1302-1304 | 成片链终点 | UNSIGNED_BYTE 读回 8-bit 编码 FBO | 设计内唯一量化，TPDF 抖动（3710-3732） | 预期 |
| Q6 | LIP.kt:1336-1349 | 8-bit 链（YUV/HEIC）读回 | UNSIGNED_BYTE | 该链整体 8-bit 设计 | 预期 |
| Q7 | LIP.kt:1313-1329 | 终点编码失败兜底 | 8-bit FBO 直读 UNSIGNED_BYTE / readMain16FAs8Bit Skia 转换（2386-2387） | 兜底仍出 8-bit 成片；Skia 转换丢失 TPDF 抖动（注释 2361 承认） | P2 容错路径 |
| Q8 | LR.kt:3102 + 3113 | 预览抽帧 | UNSIGNED_BYTE → ARGB_8888 | 预览末端，输出用途 | 预期 |
| Q9 | LR.kt:3203；LR.kt:2500 | 预览测光 | UNSIGNED_BYTE | 统计用途 | 预期 |
| Q10 | YUV.kt:2578 + 2555 | YUV 堆栈输出 | UNSIGNED_BYTE → ARGB_8888 | YUV 链 8-bit 设计 | 预期 |
| Q11 | GRG.kt:219 | Gainmap alpha 读回 | UNSIGNED_BYTE | gainmap 规范 8-bit | 预期 |
| Q12 | RDP.kt:8490-8498 | RAW 传统 AE 预览帧 | UNSIGNED_BYTE | 仅测光预览 | 预期 |
| Q13 | RDP.kt:769-777 | 堆栈 LinearRaw 物化 | RGBA_INTEGER/UNSIGNED_SHORT | 16-bit 整数保持 | 正确 |
| Q14 | LIP.kt:1580-1587 / GlUtils.kt:114/129 | LUT 3D 纹理 | UINT16→GL_RGB16F(FLOAT)；8-bit→GL_RGB8(UNSIGNED_BYTE) | 8-bit LUT 数据源精度由文件决定 | 数据源属性 |
| Q15 | ColorRecipeGl.kt:85-95 | 曲线纹理 | RGBA8 256×1 | 曲线 256 级量化 | 数据源属性 |
| Q16 | Jpeg444ExportEncoder.kt:35-41 | 最终导出 | 非 ARGB_8888 → copy(ARGB_8888) | 终点量化 | 预期 |
| Q17 | BakedLutExporter.kt:31-37/53-58/91/117 | LUT 烘焙 | 恒等 CLUT/Hald 用 ARGB_8888 进出 | LUT 数据本身 8-bit | 预期 |

**中间 8-bit 量化瓶颈计数：1 个链头高危（Q1/Q2，条件触发：profile 含非零 lens 参数）+ 1 个条件性中间层量化（Q3，后处理激活时）+ 1 个降级路径（Q4，buffer 分配失败时）。**

---

## ③ ColorSpace 一致性分析

**一致的部分（确认事实）：**
- RAW 引擎 sdrBitmap：RGBA_F16 + SRGB tag（RDP.kt:8673-8680、8711-8718；workingColorSpace=SRGB，RDP.kt:1687-1688）。值域为编码 sRGB（tone 曲线后）。
- LIP 主链：`renderColorSpace = bitmap.colorSpace ?: SRGB`（LIP.kt:579）；终点/兜底 Bitmap 均以 `inputColorSpace` 创建（LIP.kt:1302、1348、2377）；uInputColorSpace uniform 仅 0/1（SRGB / DISPLAY_P3，LIP.kt:1010-1011），shader 内按编码域运算、仅 HSL/LCH/exposure 等局部显式 srgbToLinear/linearToSrgb（LIP.kt:3864、3878、4205、4210）。
- LUT 亮度 sidecar 与 HDR 参考图：LINEAR_EXTENDED_SRGB（LIP.kt:879；RDP.kt:4018-4020），与 PP.kt:347 `HdrBufferEncoding.LINEAR_SRGB` 语义一致——线性数据按线性解释，正确。
- OglBokehProcessor 以 ColorSpace tag 判定线性输入（OBP.kt:110-111）——全项目少见的显式色彩契约用法，正确。
- GpuReferenceGainmapProducer：F16 半精度直传（GRG.kt:248-252、267-274）；ICC 由导出端按 colorSpace 生成（Jpeg444ExportEncoder.kt:42-50）。
- 预览链：主 FBO 16F，送显前由 GL 驱动/默认帧缓冲处理；抽帧产物 ARGB_8888 未显式设 colorSpace（LR.kt:3113，默认 sRGB，与屏显链一致）。

**不一致 / 风险点：**
1. **LensStage 出口丢失 ColorSpace tag**：LIP.kt:664 `Bitmap.createBitmap(w, h, ARGB_8888)` 未传 colorSpace，恒为 sRGB。当前调用方（RAW sdrBitmap）tag 恰为 SRGB 故无实际漂移；但若输入为 P3/线性 F16，此处静默重解释为 sRGB。**代码推断 + 契约缺口。**
2. **LensStage 无色彩空间概念**：LSG 为纯光学效应（畸变/暗角/bloom/flare，乘法与阈值提取），对线性/编码域不做区分；bloom 阈值 0.82、flare 阈值 0.9（LSG.kt:109-111）是按编码域标定的，若链序前移接到线性数据会失真。当前链序（编码 sRGB 域）与桌面参考一致。
3. **方向约定隐式**：成片链读回不翻转（LIP.kt:1351-1355 翻转代码被注释），靠 texCoordBuffer 翻转采样补偿（LIP.kt:3509-3519）；预览抽帧显式翻转（LR.kt:3118-3121）；LensStage 读回同样不翻转（LIP.kt:622 注释）。全链自洽但无文档化契约。
4. **未发现"把线性数据按 sRGB 解释（或相反）"的实际路径**：8-bit 链输入是 ISP 已编码的 JPEG/YUV，shader 按编码域处理；F16 链输入是 tone 后的编码 sRGB。bayer 原始数据走独立整数纹理路径（RDP.kt:4365-4374），未被当普通 RGB。

---

## ④ 异常回退行为表

| 异常 | 位置 | 回退行为 | 是否静默 / 调用方是否误以为 Profile 已应用 |
|---|---|---|---|
| EGL/init 失败 | LIP.kt:522-525（applyLut）、368-372（ShortBuffer 变体返空白图） | `return bitmap` 原图直返 | **静默**。调用方拿到"已处理"结果，实为原图 |
| 主 shader 编译失败 | LIP.kt:334（initShaderProgram 返回值未检查）、3384-3399 | shaderProgram=0 但 isInitialized=true；后续 performRender 以 program 0 绘制（无效），读回未初始化 FBO | **静默劣化**（仅 PLog.e 编译日志） |
| FBO 创建失败（主/sharpen/grain/bloom/clarity） | LIP.kt:1412-1415（仅记日志继续）、2094-2098/2224-2228/3149-3155/2788-2798（释放并返 false） | 主 FBO：继续用不完整 FBO；其余：跳过该 pass | 主 FBO 失败会产出垃圾画面；跳过错 pass 至少成片可见 |
| LensStage 抛异常（含 shader 编译失败 LSG.kt:232/247） | LIP.kt:667-670（catch）+ 683-687（runCatching） | passthrough 原输入 | **静默**：lens 光学效果未应用，无异常、无返回值差异 |
| 16F 中间层读回 glError | LIP.kt:1263-1264 | 落 8-bit 终点编码 | 成片正常；中间层精度丢失（有日志） |
| 终点编码失败 | LIP.kt:1282-1287（重试一次）→ 1313-1329 | readMain16FAs8Bit（HALF_FLOAT+Skia）或 8-bit FBO 直读 | 成片正常；兜底路径注释承认花片/陈旧内存史（1263-1276） |
| readMain16FAs8Bit glError | LIP.kt:2381-2385 | 输出**空白** bitmap，仅 PLog.e | 静默出白图 |
| 读回 buffer OOM | LIP.kt:1448-1462（抛 OutOfMemoryError） | 无 catch，冒泡出协程 | 调用链崩溃/失败，非静默 |
| 中间 Bitmap 创建 OOM | LIP.kt:1252-1259（catch Throwable→无 colorSpace 的 F16）；RDP.kt:4031-4034/4460-4463/8681-8683（return null） | LIP：退化到 SRGB tag；RDP：null 上浮 | 退化路径有色空间漂移风险；null 路径需调用方处理 |
| 协程取消 | LIP.kt:521/552/556/560/566/584/588/592（ensureActive）；performRender 内无检查点 | 取消后不再产生新帧 | performRender（非 suspend）中途不可取消，需等读回完成 |
| 预览 shader 编译失败（含 minimalFallback） | CPP.kt:72-103、PreviewColorShaderVariant.kt:27-33 | 完整变体失败→最小变体（关 LCH/OKLCH/grain/LUT mask，部分风格不生效，画面可见）；再失败→null，LR.kt:1522 `?: return` 放弃该帧绘制 | **半静默**：风格缺失有日志；帧放弃绘制有日志（1.7.4 预览黑屏根因即此，注释 CPP.kt:85-88） |
| GPU 设备不支持 compute | LIP.kt:1652-1659、3449-3463 | 降级 passthrough program，跳过硬核降噪 | 静默跳过降噪 |
| Bokeh 失败 | OBP.kt:577-579 | return null → 调用方跳过 bokeh | 调用方可见 null |

---

## ⑤ 数据契约缺口清单

1. **无成文的数据契约**：`docs/rendering_pipeline.md` 只有概念链序；`docs/HANDOFF_android_lens.md` 完全未提像素格式/精度/色彩空间（Grep 零命中）。宽高、像素格式、色彩空间、传递函数、动态范围、Alpha、方向、处理阶段均无统一契约定义，现状是"各文件注释约定"。
2. **LensStage 交接契约缺失**：注释（LIP.kt:622）自称"与文件内其他链路一致：GLUtils 直传，读回不翻转"，但未定义输入输出格式契约；其 8-bit 出口与 16F 主链的接口不匹配是 Q1 的根因。
3. **ColorSpace tag 非强制**：仅 OglBokehProcessor（OBP.kt:110-111）显式消费 tag；LIP 主链只把 colorSpace 用于终点 Bitmap 创建与 P3 二值判断（LIP.kt:1010），LensStage 出口直接丢弃 tag（LIP.kt:664）。
4. **中间层精度契约未定义**：`output16F` 中间层语义（LIP.kt:1222-1227 注释）承认"任一后处理激活即为 8-bit"，但调用方（applyLutStack，LIP.kt:694/703）无法感知/选择。
5. **Alpha 契约**：LensStage composite 恒输出 a=1.0（LSG.kt:180）；主链终点保留 color.a（LIP.kt:3730）；raw sdrBitmap 的 alpha 语义未定义。
6. **RAW Bayer vs 普通 RGB**：Bayer/LinearRaw 走独立整数纹理路径（RDP.kt:4365-4374、769-777），有 CFA pattern 校验（RDP.kt:1698-1701）；未发现 Bayer 被当普通 RGB 的路径——但该隔离仅靠代码结构维持，无契约文档。
7. **ISP JPEG/YUV vs 线性传感器数据**：YUV/HEIC 链按编码 sRGB 处理（8-bit 设计链），未发现当线性的路径；风险在于若未来把 F16 线性参考图误接入 applyLutStack，working16F 判断只认 config 不认 tag（LIP.kt:548），线性数据会被按编码域调色。
8. **F16 上传依赖**：LIP.kt:1487-1504 自管 half-float 直传（好），但 LensStage 仍走 GLUtils.texImage2D（LIP.kt:630），F16 行为依赖 ROM 的 GLUtils 实现（AOSP 8+ 支持 kRGBA_F16 → RGBA/HALF_FLOAT），未做防御性检查。

---

## ⑥ 三级结论分级

### 确认事实（代码直证）
- F1. `working16F` 仅由 `bitmap.config == RGBA_F16` 决定（LIP.kt:548）；RAW 引擎交接的 sdrBitmap 确为 RGBA_F16（RDP.kt:8676/8714、4028），故 RAW/DNG 成片链默认进入 16F 工作空间。
- F2. 主 FBO 在 16F 模式下为 GL_RGBA16F（LIP.kt:1386-1389），8-bit 模式为 GL_RGBA8（1391-1394）；F16 上传为半精度直传（LIP.kt:1496-1500）。
- F3. **applyLensStage 用 `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 读回（LIP.kt:658）并创建 `ARGB_8888` Bitmap（LIP.kt:664）**；调用点在 applyLutStack 的**最前端**（LIP.kt:683-687），先于 baseline/creative 两层；触发条件为 `LensParamsStore.current.isZero == false`（LIP.kt:684，由 CameraScreen.kt:529 按 profile lens 段注入）。
- F4. LensStageGl 全部内部 RT 为 8-bit（LSG.kt:198/265），composite 输出 clamp 到 [0,1]（LSG.kt:180）。
- F5. 中间层（output16F=true）读回前，若 clarity/sharpen/bloom/grain 激活，代码把末级 8-bit 纹理直通回主 16F FBO 再读（LIP.kt:1229-1235、2304-2342）——量化已发生在 8-bit FBO 写入侧。
- F6. 终点唯一 8-bit 编码带 TPDF 抖动（LIP.kt:2175-2268、3710-3732），读回 UNSIGNED_BYTE（1293-1296）。
- F7. 预览链主 FBO 为 RGBA16F（LR.kt:755/785），抽帧末端 8-bit（LR.kt:2543/3102/3113）。
- F8. ColorPassProgram.minimalFallback 降级路径存在（CPP.kt:89-100），失败后调用方 `?: return` 放弃绘制（LR.kt:1522）。
- F9. 多处异常回退为静默 passthrough：applyLut init 失败（LIP.kt:524）、LensStage 异常（LIP.kt:667-670 + 683-687）。
- F10. initShaderProgram 返回值未检查（LIP.kt:334）。

### 代码推断（强推断，未上真机）
- I1. **Q1/Q2 构成链头 8-bit 量化瓶颈**：当 profile 含非零 lens 参数且输入为 RAW F16 时，LensStage 出口即 ARGB_8888，后续 `intermediate16F=false`（LIP.kt:694）、baseline/creative 两层全部按 8-bit 运行——1.3.4 "Option B 16F 工作空间"在 lens 段激活时被完全旁路。此为本次审计最重要的精度结论。
- I2. GLUtils.texImage2D 对 F16 的行为：AOSP 8+ 支持 half-float 上传（compileSdk=36，app/build.gradle:24），但即使上传保精度，LSG 首个 warp pass 即写入 8-bit RT（Q2），结论不变。
- I3. 中间层量化（Q3）：baseline 层带 clarity/sharpen/bloom/grain 时，中间交接实为 8-bit（代码注释 LIP.kt:1224-1227 已承认机制，但仅作为"非法读回"的修复说明，未作为精度缺陷标注）。
- I4. LIP.kt:664 缺 colorSpace：当前唯一 F16→LensStage 调用方（RAW sdrBitmap）tag 为 SRGB，实际无漂移；属潜在契约缺口而非现实 bug。
- I5. performRender 内无协程取消检查点；取消只能发生在 pass 边界，读回中途不可中断（推断自代码结构）。

### 需真机验证
- V1. 在 Mali/Adreno 真机上验证：lens 参数非零 + RAW 成片路径下，最终成片确实不经 16F（可在 applyLut 出口打印 working16F 与 config）。
- V2. 验证 GLUtils.texImage2D 在目标机型上对 RGBA_F16 不抛 IllegalArgumentException（若抛，则 LensStage 静默 passthrough，镜头效果完全丢失——F3 的极端情形）。
- V3. 验证 RGBA16F 渲染 + HALF_FLOAT 读回组合在各目标 GPU 上恒合法（代码注释声称 1.3.4/1.3.5 已实证，需回归确认）。
- V4. 验证终点编码失败兜底（LIP.kt:1313-1329）不再产生花片（1.3.4~1.3.6 多次真机修复史，见注释 1263-1276）。
- V5. 验证 minimalFallback 降级后预览可见性（1.7.4 黑屏史，CPP.kt:85-88）。
- V6. 中间层 8-bit 量化对成片的可见影响（抬黑/曲线场景下与全 16F 的 A/B 对比）。

---

## 附：量化瓶颈汇总

- **P0（高危，条件触发）**：LensStage 读回 8-bit（LIP.kt:658/664）+ LensStageGl 内部 8-bit RT（LSG.kt:198/265）。位于高精度主链最前端，触发后整链降级。触发条件：profile lens 参数非零（CameraScreen.kt:529 → LensParamsStore）。
- **P1（条件瓶颈）**：后处理 pass 8-bit 出口（clarity LIP.kt:2748 / sharpen LIP.kt:2068 / bloom LIP.kt:3140 / grain FG.kt:168）导致中间层交接实为 8-bit。
- **P2（降级路径）**：F16 上传缓冲分配失败 → asGlUploadCompatibleBitmap 隐式量化（LIP.kt:1506/1516-1525）；终点编码失败兜底（LIP.kt:1313-1329）。
- **预期量化（非缺陷）**：终点 TPDF 8-bit 编码（Q5）、8-bit YUV 链（Q6）、预览末端（Q8/Q9）、gainmap/导出（Q11/Q16）。

*报告完。所有结论均基于 HEAD=c4523dc 只读审计；未修改任何源文件。*
