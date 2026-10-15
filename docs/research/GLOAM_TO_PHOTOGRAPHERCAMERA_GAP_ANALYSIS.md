# Gloam → PhotographerCamera 差距分析（Phase 2）

- 日期：2026-10-15
- 上游研究：`docs/research/GLOAM_CAMERA_RESEARCH.md`（按 A/B/C/D 证据分级）
- 本仓现状基线：Phase A 只读审计（`docs/PHASE_A_audit_report.md`）+ Phase B/C/D/E/F 代码治理
- 证据等级：**A**=官方明确 / **B**=样片观察 / **C**=技术推断 / **D**=未证实

---

## 一、总览：Gloam 公开能力 vs 本仓现状

| 维度 | Gloam 公开表现（证据） | PhotographerCamera 现状 | 差距判定 |
|------|------------------------|--------------------------|----------|
| 拍摄链路 | RAW 拍摄 → 色彩映射 → 影调 → 颗粒 → JPG（A，App Store） | 已落地 JPEG MAX / RAW MAX 双路径 + Profile 管线（B/C/E/F 治理） | ✅ 同构，已具备 |
| 非破坏性重调（"数字底片"） | 照片保留 RAW 信息，可后期重调（A） | `CaptureProfileSnapshot` 快门冻结参数代 + `MediaMetadata` 记录（Phase B/C） | ✅ 思路同构；本仓以"参数代快照"而非"RAW 容器留存"实现 |
| 颗粒（hero 特性） | 胶片颗粒为卖点，按画幅/ISO 感知（B/C） | `FilmGrainGl` F16 化 + 光照分布颗粒（Phase E-1）+ 回归测试 | ✅ 已具备；真机尺度待验 |
| Halation / 光晕 | 高亮光晕、柔和高光（B） | `RealtimeVideoRenderer` + 预览端 HDF 红晕一致性（Phase E-3）+ 死字段接线（Phase E-2） | ✅ 已具备；默认强度极低 |
| Bloom | 高亮扩散（B） | `renderLdrBloom` 16F 免混合双分支（Phase E-1） | ✅ 已具备 |
| 胶片曲线 / 影调 | 胶片风格曲线、柔和高光滚降（A/B） | `ShadowsHighlightsShader` + film_curve + tone_curve 三端共用（Phase B-1 链序对齐） | ✅ 已具备 |
| HSL / 色彩变换 | 色彩风格化（A） | HSL-LUT 阶段两端等价（Phase F 校正） | ✅ 已具备 |
| 预览↔成片一致 | 编辑即所见（A） | 预览/成片共用同一 GLSL 核心（Phase A/B/C 治理） | ✅ 已具备；录像 halation 已对齐（Phase E-3） |
| 多风格/多机型致敬 | 内置胶片模拟滤镜（A） | Profile 体系 + 7 内置档（native/VINTAGE/FRESH/CLASSIC/CINEMA/ACROS/MONO） | ✅ 已具备 |
| 设备稳定性 | iOS 单平台 | Android 多 GPU（Mali/Adreno）回退路径 + GLSL 静态校验门禁 | ✅ 已具备；真机矩阵待扩 |
| 16-bit 中间精度 | RAW 全程高精度（C） | RGBA_F16 主链贯通（Phase C-1 LensStage 16F + Phase E-1 后处理 16F） | ✅ 已具备（残余 2 处 8-bit 出口已文档化） |
| 自动场景分析 | 自动识别场景调参（C，推断） | 无（本仓为显式 Profile 驱动） | ⚪ 设计差异，非缺陷 |
| 社交分享/云端成片库 | 一键分享（A） | 无内置分享；远程日志/更新服务（106.53.7.242） | ⚪ 产品边界外 |
| 视频胶片模拟 | 视频也可加胶片感（A） | `RealtimeVideoRenderer` 已含 halation/bloom 对齐（Phase E-3） | ✅ 已具备基础 |

---

## 二、已具备且经治理确认的能力（无需新建）

1. **双模式互斥（JPEG MAX / RAW MAX / Live）**：代码路径确认（Phase A 12.4），非 RAW 不依赖 RAW 落回链。
2. **Profile 参数真实生效**：从 JSON → `Profile.kt` → `ProfileToRecipeMapper` → GPU uniform/纹理 → 成片，已闭环（Phase B/F）。
3. **高光/阴影/胶片曲线可控**：三端共用同一 GLSL，链序已对齐（Phase B-1）。
4. **Grain / Halation / Bloom / Vignette / Sharpen**：实现已存在并修复（Phase E）。
5. **预览=成片核心数学**：同 GLSL 注入、同参数链（Phase A/B/C）。

---

## 三、已实现但有缺陷 → 已在本次治理修复

| 缺陷（Phase A 编号） | 修复阶段 | 现状 |
|----------------------|----------|------|
| PA-01 16F 主链最前端 LensStage 8-bit 读回 | Phase C-1 | ✅ F16 路径贯通，量化点 3→2 |
| PA-02 后处理层 RGBA8 中间量化 | Phase E-1 | ✅ clarity/sharpen/bloom/grain 改 RGBA16F |
| PA-04 拍摄期间参数竞争 | Phase B（CaptureProfileSnapshot） | ✅ 快门冻结快照，成片优先读快照 |
| PA-06 CPU/GLSL 链序分歧 | Phase B-1 | ✅ glsl_reference 重排对齐，测试门禁恢复 |
| PA-08 录像缺失 halation | Phase E-3 | ✅ 录像端内联 HDF/红晕，与预览同源 |
| PA-10 测试基础设施损坏 | Phase B | ✅ static_cross_check 重写 + studio smoke 补验证集 |
| PA-05 HSL 单位误判 | Phase F | ✅ 澄清为误读，Studio 与 Android HSL-LUT 等价，契约固化 |
| PA-07 残余死字段 | Phase B/F | ✅ 死字段清单 + schema 标注 inert/reserved |
| PA-09 Studio 链序矛盾 | Phase B-1 | ✅ 已解，确认无改 |

---

## 四、未实现 / 待决策能力（如实列出，不分派"下一版"敷衍）

1. **自动场景识别调参**：Gloam 的自动化方向（C 推断），本仓为显式 Profile 驱动，属产品定位差异，**非缺陷**。若要做，需独立立项（语义分割 + 风格推荐），不并入当前管线。
2. **内置社交分享 / 云端成片库**：Gloam 的 A 类能力，超出本仓"相机+Profile"边界；远程日志/更新服务已独立存在。
3. **非零 `hue_shift` 的 Android HSL-LUT+LCH 叠加旋转**（Phase F OPEN QUESTION）：内置 profile 全 0，现网一致；启用前需真机裁决是否让两阶段互斥。
4. **残余 2 处 8-bit 出口**（Phase C 04 报告 Q3/Q4）：clarity/sharpen 等周边链 8-bit 出口已文档化（`docs/IMAGE_DATA_CONTRACT.md`），属已知精度尾差，非阻塞。

---

## 五、需真机 / 实验验证（当前环境无 PLG110 设备，明确未执行）

| 验证项 | 来源 | 阻塞原因 |
|--------|------|----------|
| PA-01 V1：非零 lens profile + RAW 成片出口为 F16 | `docs/IMAGE_DATA_CONTRACT.md` §7 | 无设备 |
| PA-01 V2：16F RT + HALF_FLOAT 读回在 Mali/Adreno 合法性 | 同上 | 无设备 |
| Phase B B-3：快门后切 profile 成片参数=快照 | Phase B 报告 | 无设备 |
| Phase E：预览/成片 halation 一致性、后处理 F16 无 banding | `docs/PHASE_D_protocol.md` | 无设备 |
| 颗粒尺度随分辨率归一 | 渲染管线 §3 | 无设备 |
| 多 GPU 帧率（1080p≥30FPS） | 渲染管线 §6 | 无设备 |

---

## 六、对应代码位置与建议

- **拍摄链路 / 双模式**：`android/.../photon/Camera2Controller.kt`、`CameraViewModel.kt`、`RawEngineTonePass.kt` / `LutImageProcessor.kt`（JPEG MAX）/`GlesMgcRawSpatialStacker.kt`（RAW MAX）。
- **Profile 参数链**：`android/.../core/profile/Profile.kt` → `ProfileToRecipeMapper.kt` → `LutImageProcessor.kt` / `PreviewColorShaderModules.kt`。
- **风格效果**：`android/.../photon/lut/` 下 `FilmGrainGl.kt`、`Shaders.kt`（bloom/halation 合成）、`ShadowsHighlightsShader.kt`。
- **16F 精度**：`LensStageGl.kt`（C-1）、`LutImageProcessor.kt`（E-1）。
- **测试门禁**：`tests/`（pytest）、`tools/static_cross_check.py`、`tools/check_glsl_symbols.py`。

**结论**：Gloam 公开可借鉴的成像能力（RAW→色彩映射→影调→颗粒→JPG、数字底片式非破坏性、颗粒为 hero）**本仓均已具备并经本次治理强化**，无需要颠覆性新建。剩余缺口为产品边界差异（自动识别/分享）与真机验证（设备不在手），均如实标注。
