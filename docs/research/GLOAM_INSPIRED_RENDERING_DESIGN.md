# 实际成像管线与技术设计（Phase 11.3）

- 日期：2026-10-15
- 现状基线：Phase A–F 代码治理后
- 关联文档：`docs/rendering_pipeline.md`（内部管线约定）、`docs/IMAGE_DATA_CONTRACT.md`（数据契约 v1.0）、`docs/PHASE_A_audit_report.md`
- 本文件**只描述实际代码**，不把计划写成已实现。

---

## 1. 实际运行架构（已确认，非推测）

通过 Phase A 调用链审计确认，当前运行时真正使用的链路是 **PhotonCamera 移植基座 + 自有 UI + Profile 管线**，核心路径：

```
Camera2 输出 (YUV / RAW)
  ├─ JPEG MAX 路径：YUV/P010 → LutImageProcessor（LUT + 风格层）→ 保存
  └─ RAW MAX 路径：RAW → RawDemosaicProcessor → GlesMgcRawSpatialStacker（Sabre/Spatial 降噪）→ LUT + 风格层 → 保存
预览路径：Camera2 → LutRenderer（复用成片同款 GLSL 风格层）→ 屏幕
录像路径：Camera2 → RealtimeVideoRenderer（PreviewColorShader + 空间效果内联）→ 录制
```

- **唯一真源 Profile 模型**：`android/.../core/profile/Profile.kt`
- **映射层**：`android/.../core/photon/color/ProfileToRecipeMapper.kt`（Profile → recipe params → GPU）
- **快门快照**：`CaptureProfileSnapshot.kt` + `CaptureProfileStore`（Phase B 闭环，防止拍摄期间参数竞争）
- **远端服务**：`android/app/src/main/assets/remote_log_endpoint.txt`（106.53.7.242:18888 日志/版本）

---

## 2. 处理阶段、颜色空间与精度（逐阶段已落实）

| 阶段 | 输入格式 | 输出格式 | 颜色空间 | 传递函数 | 精度 | 消费位置 |
|------|----------|----------|----------|----------|------|----------|
| 相机获取 | YUV_420 / RAW | 纹理 | 相机 native | linear | — | `Camera2Controller` |
| 镜头校正 (LensStage) | RGBA_F16 / RGBA8 | RGBA_F16 / RGBA8 | 保 ColorSpace tag | 不变 | **F16 路径（Phase C-1）** | `LensStageGl.kt` / `LutImageProcessor.applyLensStage` |
| 曝光/白平衡 | 同左 | 同左 | 不变 | 不变 | 同左 | `LutImageProcessor` 主链 GLSL |
| Color Matrix | 同左 | 同左 | 线性 | 不变 | 同左 | `effect.frag` 主链 |
| Tone Curve | 同左 | 同左 | 不变 | 不变 | 同左 | `uCurveTexture` |
| Highlight Roll-off | 同左 | 同左 | 不变 | 不变 | 同左 | 主链 GLSL |
| Shadow Response | 同左 | 同左 | 不变 | 不变 | 同左 | `ShadowsHighlightsShader.kt` |
| HSL Mapping | 同左 | 同左 | 不变 | 不变 | 同左 | `hsl.glsl` / `effect.frag`（**度**，Phase F 校正） |
| Sharpen / Clarity | 同左 | RGBA16F | 不变 | 不变 | **16F（Phase E-1）** | `LutImageProcessor` |
| Bloom | 同左 | RGBA16F | 不变 | 不变 | **16F 免混合（Phase E-1）** | `renderLdrBloom` |
| Halation | 同左 | RGBA16F | 不变 | 不变 | 16F 双 FBO | `RealtimeVideoRenderer` + 预览 composite |
| Grain | 同左 | RGBA16F | 不变 | 不变 | **16F（Phase E-1）** | `FilmGrainGl.kt` |
| Vignette | 同左 | 同左 | 不变 | 不变 | 同左 | 最终乘算 |
| 输出编码 | RGBA16F | JPEG (8-bit) | sRGB 编码 | sRGB | 最终单次量化 + TPDF 抖动 | `renderFinalEncodePass` |

**唯一量化点原则**（`docs/IMAGE_DATA_CONTRACT.md`）：高精度（RGBA_F16）贯穿需要精度的处理阶段，仅最终输出边界做一次 8-bit 量化 + TPDF 抖动。已知残余 2 处周边 8-bit 出口（Q3/Q4）已文档化，非阻塞。

---

## 3. 颜色空间与传递函数约定

- **工作空间**：主链在线性光（linear）下执行色调/色彩变换，最终输出统一做 sRGB 编码。
- **LUT 颜色空间**：3D LUT 为风格唯一来源（`ProfileToRecipeMapper.map()` → `LutManager.saveColorRecipeParams`），参数链（LUT→film→exposure→wb→matrix→highlight→shadow→tone→hsl）三端（预览/成片/桌面）一致。
- **HSL 阶段**：在 GLSL 内做 RGB→XYZ→（色相旋转）→XYZ→RGB，`hue_shift` 按**度**（`pl.b / 360.0`），与 Studio 参考逐字节等价（Phase F 校正，原"18 倍"为误读）。
- **LCH 调色**：独立重型 OKLCH 调色阶段（`uLchHueAdjustments`，9 段色相带加权 + 肤色 + 色度门控），**非** `hue_shift` 字段路径。

---

## 4. 精度治理结论（P0/P1）

- **PA-01（P0）已修复**：16F 主链最前端的 LensStage 8-bit 读回瓶颈消除（Phase C-1），非零 lens profile 下 RAW 成片不再降级为 8-bit。
- **PA-02（P1）已修复**：后处理 clarity/sharpen/bloom/grain 改为 RGBA16F，避免 banding 与高光细节丢失（Phase E-1）。
- **未做全分辨率 F16 读回风险**：`asGlUploadCompatibleBitmap` 失败时的隐式降级已有告警（Phase C-5），需真机验证（V3）。

---

## 5. 预览↔成片↔录像一致性

- **预览=成片核心数学**：同一套 GLSL 风格层（LUT + HSL + 高光/阴影 + 空间效果），参数链三端共用。
- **录像对齐（Phase E-3）**：`RealtimeVideoRenderer` 内联 HDF 光晕 + 红晕，复用预览端共享 shader 的 `includeSpatialRecipeEffects` 块，与预览同源同算法；`hdfEnabled` 默认 false（母版 halation.amount≈0.0037）保证零成本。
- **分辨率归一**：Grain/Bloom/Halation 按分辨率尺度归一，避免放大后质感失真（需真机尺度验证）。

---

## 6. 性能与设备兼容

- **资源复用**：Shader program / FBO / 纹理按阶段复用，不在每帧重建（Phase A 复查）。
- **GPU 回退**：不支持目标 GPU 格式（RGBA16F/HALF_FLOAT）时，`LensStageGl` 与 `FilmGrainGl` 均有 8-bit no-op 回退 + 告警（Phase C-1/E-1）。
- **GLSL 静态校验门禁**：`tools/check_glsl_symbols.py` 在改着色器后必须过（Kotlin 编译不校验 GLSL，运行时编译失败会黑屏）。
- **未量化指标**：1080p≥30FPS 等帧率目标需真机测量（无设备在手，未测）。

---

## 7. 设计取舍（对照 Gloam 借鉴点）

| Gloam 借鉴点 | 本仓落地方式 | 说明 |
|--------------|--------------|------|
| RAW→色彩映射→影调→颗粒→JPG | JPEG MAX / RAW MAX 双路径 + Profile 管线 | 同构，已治理 |
| 数字底片（非破坏性重调） | `CaptureProfileSnapshot` 参数代快照 + `MediaMetadata` 记录 | 以参数代而非 RAW 容器实现 |
| 颗粒为 hero、按画幅感知 | `FilmGrainGl` F16 + 光照分布 | 真机尺度待验 |
| 柔和高光/Halation | `ShadowsHighlightsShader` + HDF 红晕 | 已实现 |

**结论**：实际成像管线已具备 Gloam 公开可借鉴的全部核心能力，且与设计文档 `rendering_pipeline.md` 一致；剩余为精度尾差（已文档化）与真机验证（设备不在手）。
