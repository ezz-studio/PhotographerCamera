# 实施记录（Phase 11.4）— A–F 实际代码改造汇总

- 日期：2026-10-15
- 范围：本会话执行的全部真实代码/测试/文档改动（Phase A 只读审计 + B/C/D 协议 + E/F 改造 + Phase 1/2 研究）
- 纪律：真机（PLG110）验证通过前**不推 GitHub、仅本地提交 + 本地打包 + R2 发布**

---

## 1. Phase A — 只读审计（零运行代码改动）

- 产出 5 份子报告（`docs/_audit_phase_a/`）+ `docs/PHASE_A_audit_report.md` + `AUDIT_060_architecture_compliance.md` 重写。
- 发现并编号 PA-01~PA-14，分派 Phase B–F。
- 测试基线：17 用例实跑 14 过 / 3 败（PA-06 真实缺陷）。

---

## 2. Phase B — Profile 参数传递、状态快照、重复应用（commit 基线 1.7.6→1.8.0 治理）

| 文件 | 改动 | 问题 |
|------|------|------|
| `tools/glsl_reference.py` | `render_deterministic` 链序重排对齐 Android（`…→highlight→shadow→film→tone→HSL→vignette`） | PA-06 |
| `tools/static_cross_check.py` | 重写：134 uniform 查找 vs 613 GLSL 声明交叉校验 | PA-10 |
| `tests/test_studio_smoke.py` | 新增 `_make_plain_dataset()` 未调色验证集 | PA-10 |
| `core/photon/color/CaptureProfileSnapshot.kt` | **新建**不可变快照 + `CaptureProfileStore` | PA-04 |
| `viewmodel/CameraViewModel.kt` | 快门入口 `createCaptureProfileSnapshot()` | PA-04 |
| `photon/lut/LutImageProcessor.kt` | 7 处 Film/Lens 单例消费点改「快照优先」 | PA-04 |
| `photon/lut/BakedLutExporter.kt` | 2 处 `CaptureProfileStore.clear()` | PA-04 |
| `docs/PROFILE_参数对照表_Studio_vs_Android.md` | 死字段/单位差/跨引擎语义章节 | PA-05/07/13/14 |

- 测试：**pytest 17/17 PASS**；static_cross_check ALL PASS；Kotlin 编译 BUILD SUCCESSFUL。
- 教训：整文件重写引入 `_hsv2rgb` `i==5` 笔误（`v,p,q`→`t,p,q`），经 git diff 复核捕获。

---

## 3. Phase C — 中间量化 / 色彩空间 / F16 修复

| 文件 | 改动 | 问题 |
|------|------|------|
| `core/photon/lens/LensStageGl.kt` | Rt/makeRt `half` 模式、`process(halfFloat)`、`releaseRts` | PA-01（P0） |
| `photon/lut/LutImageProcessor.kt` | `applyLensStage` 双模（16F 直传 / 8-bit 回退）+ colorSpace；`initShaderProgram`→Boolean+链接检查；回退日志 | PA-01 / PA-03 / PA-12 |
| `photon/gallery/MediaMetadata.kt` | `profileId`/`profileVersion` 字段 + JSON 解析 | 需求 2.7 |
| `viewmodel/CameraViewModel.kt` | `buildPhotoMetadata` 填快照标识 | 需求 2.7 |
| `photon/gallery/db/GalleryMediaEntity.kt` / `GalleryMediaStore.kt` / `GalleryDatabase.kt` | 两列 + 双向映射 + version 40 + MIGRATION_39_40 | 需求 2.7 |
| `docs/IMAGE_DATA_CONTRACT.md` | 新建数据契约 v1.0（四条链路格式表 + 量化规则 + 异常回退 + V1~V3 验证项） | PA-11 |

- 测试：Kotlin 编译 BUILD SUCCESSFUL（2m34s）；static_cross_check ALL PASS（59 文件/134 uniform/617 声明）；pytest 17 passed。
- 量化点计数：3 → 2（余后处理层 Q3、降级路径 Q4，移交 Phase E）。

---

## 4. Phase D — 真机验证协议（文档，未执行）

- 新建 `docs/PHASE_D_protocol.md`：PLG110 三种模式（JPEG MAX / RAW MAX / Live）固定场景对比表、PA-01 V1/V2/V3 验收图采集法。
- **真机验证未在手设备执行**，结果表为空，待用户回填。

---

## 5. Phase E — 后处理 F16 化 / 死字段接线 / 录像一致性（commit `95c6a34`）

- 规模：11 文件 +671/−106。
- E-1 后处理 F16 化（PA-02）：`Shaders.kt` bloom composite `uAddSource` 免混合；`FilmGrainGl.kt`、`LutImageProcessor.kt`（clarity/sharpen/bloom FBO 改 RGBA16F、`renderLdrBloom` 双分支）、`LutRenderer.kt`。
- E-2 死字段接线：`ShadowsHighlightsShader.kt` 加 `uShadowSaturationDelta`；`Shaders.kt`/`LutImageProcessor.kt`/`PreviewColorShader.kt`/`RawAdjustmentPass.kt`/`RawEngineTonePass.kt`/`ColorPassProgram.kt` 透传；HDF 阈值/半径/暖色（`uHalationWarmth`）接线。
- E-3 录像 halation 一致性（PA-08）：`RealtimeVideoRenderer.kt`（+316 行）内联 HDF/红晕模糊，`PreviewColorShader.kt` `includeSpatialRecipeEffects` 扩展，`hdfEnabled` 默认 false 零成本。
- 修复编译错：`byteArrayOf(0,0,0,255)` → `byteArrayOf(0.toByte(),0.toByte(),0.toByte(),255.toByte())`。
- 门禁：pytest 17 passed；static_cross_check ALL PASS；GLSL ✓；compileDebugKotlin EXIT=0。

---

## 6. Phase F — Profile 体系收尾（commit `6fd3872`，+273/−12）

- **PA-05 关键更正**：逐行核对推翻"18 倍不匹配"误判。profile `hue_shift` 字段真实走 HSL-LUT（`hsl.glsl:19`/`effect.frag:283` `pl.b/360.0`=度），与 Studio 参考（`profile_renderer.py:243`/`glsl_reference.py:181` 均 `/360.0`）**逐字节等价**；`radians(20.0)` 属独立 LCH 调色阶段（由 `mapHsl` 喂入 `redHue/…`），非 `hue_shift` 路径。**Studio 代码未改 hue 数学**（原计划"对齐 ×20°"会破坏等价）。
- 固化：两处 `/360.0` 加契约注释 + schema `hue_shift` description + 对照表 §2 整段重写 + 新增 `tests/test_hue_shift_units.py`（锁定"度"契约 + 双参考一致 + 显式排除 ×20° 误改）。
- PA-07：schema 标注 `highlight_rolloff.saturation` / `color_matrix.input_gamut` / `output_gamut` 为 inert/reserved。
- PA-09：确认 Studio 链序已在 B-1 对齐，无改。
- 门禁：**pytest 22 passed**；static_cross_check ALL PASS。
- OPEN QUESTION：非零 `hue_shift` 在 Android 会同时驱动 HSL-LUT + LCH 叠加旋转，与桌面（仅 HSL-LUT）差异；内置 profile 全 0，现网一致；未改 Android，待真机裁决。

---

## 7. Phase 1/2 — Gloam 研究 + 差距分析（commit `24b9690` + 本文件批次）

- `docs/research/GLOAM_CAMERA_RESEARCH.md`：App Store 官方 + 版本史 + 主流科技媒体评测，A/B/C/D 分级，不归属其内部算法。
- `docs/research/GLOAM_TO_PHOTOGRAPHERCAMERA_GAP_ANALYSIS.md`：Gloam 公开能力 vs 本仓现状对照，结论——可借鉴能力均已具备并经治理强化，缺口为产品边界差异 + 真机验证。
- `docs/research/GLOAM_INSPIRED_RENDERING_DESIGN.md`：实际成像管线、颜色空间、逐阶段精度、一致性、性能兼容。
- `docs/research/GLOAM_INSPIRED_TEST_REPORT.md`：测试报告（本批次另写）。

---

## 8. 保留的既有能力（未破坏）

- JPEG MAX / RAW MAX / Live 互斥关系（代码确认）。
- 预览↔成片共用 GLSL 核心（Phase A/B/C/E 治理后维持）。
- Profile 体系（7 内置档 + Import/Export + 远端版本更新）。
- 远端日志/更新服务（106.53.7.242:18888，已部署并端到端验证）。
- JPEG MAX 软件降噪接线、成片保存直传 Bitmap 规避（受保护修复，未回退）。

---

## 9. 已确认未解决 / 待真机裁决

| 项 | 状态 |
|----|------|
| PLG110 真机验证（Phase D） | ⛔ 无设备，未执行 |
| 残余 2 处 8-bit 出口（Q3/Q4） | ⚠️ 已文档化，非阻塞 |
| 非零 `hue_shift` HSL-LUT+LCH 叠加（PA-05 OPEN） | ⚠️ 待真机裁决，未改 Android |
| GitHub 推送 | ⛔ 按纪律等用户明确"推" |
