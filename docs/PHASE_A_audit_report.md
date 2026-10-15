# Phase A 只读审计 · 主报告与问题清单

- 日期：2026-10-15 · 基线：main HEAD = `c4523dc`（1.7.6）
- 阶段纪律：本阶段零运行代码改动（唯一写操作 = 5 份审计报告 + 本文档 + AUDIT_060 重写）
- 子报告（`_audit_phase_a/`）：`01_callchain.md`（287 行）/ `02_legacy_code.md`（231）/ `03_profile_param_map.md`（321）/ `04_format_precision.md`（214）/ `05_test_baseline.md`（84）

---

## 一、Phase A 交付清单（对照总原则第十一条）

| 要求产出 | 状态 | 位置 |
|---|---|---|
| 真实调用链报告 | ✅ | 01_callchain.md（六条路径逐段 + file:line + 三级分级） |
| Profile 参数映射表 | ✅ | 03_profile_param_map.md（~85 字段全量 + 7 项专项核实） |
| 图像格式路径表 | ✅ | 04_format_precision.md（17 个读回/量化点 Q1–Q17） |
| 旧代码依赖表 | ✅ | 02_legacy_code.md（LIVE/DEAD/UNVERIFIED 判定 + 证据） |
| 当前测试基线 | ✅ | 05_test_baseline.md（17 用例实跑：14 过 / 3 败） |
| 问题清单 | ✅ | 本文档第二节 |

## 二、问题清单（按严重度排序，均已带证据；分派 Phase B–G）

### P0（阻塞后续阶段，必须最先处理）

| 编号 | 问题 | 证据 | 分派 |
|---|---|---|---|
| **PA-01** | **LensStage 8-bit 读回位于 F16 主链最前端**：applyLensStage 在 Stage 0（先于一切 LUT/recipe 层）用 `glReadPixels(GL_UNSIGNED_BYTE)` + `ARGB_8888` 读回；profile lens 参数非零时整条 16F 链降级为 8-bit。LensStageGl 内部 8 个 RT 全为 8-bit | LutImageProcessor.kt:658/664、:683-687（Stage 0 顺序）、LensStageGl:198/265 | **Phase C** 头号修复：提供 F16 路径或将量化推迟到最终输出边界 |
| **PA-06** | **3 个 shader equivalence 测试失败**：CPU 参考（profile_renderer）与 GLSL 参考（glsl_reference）输出偏差 0.24 / 0.12 / 0.05，阈值 1e-05。属真实代码缺陷，非环境问题 | 05_test_baseline.md ③（test_shader_equivalence.py:77/92/165） | **Phase F 前必须修复**，否则后续改动无回归网 |

### P1（影响画质/正确性，Phase B–C 处理）

| 编号 | 问题 | 证据 | 分派 |
|---|---|---|---|
| PA-02 | 后处理层（clarity/sharpen/bloom/grain）FBO 均为 RGBA8，中间层交接实为 8-bit（条件瓶颈，效果开启即触发） | 04 报告 Q 表（LIP.kt:2748/2068/3140、FilmGrain:168） | Phase C |
| PA-03 | 异常回退多为**静默 passthrough**（init 失败、LensStage 异常、initShaderProgram 未检查），调用方无法感知 Profile 未应用，违反需求 3.8 | LIP.kt:524/667-670/334 | Phase C（回退必须记录原因） |
| PA-04 | **拍摄期间参数竞争**：FilmParamsStore/LensParamsStore 为进程级可变单例，currentRecipeParams/currentLutId 可变；异步多帧处理与导出期间可能读到另一份 Profile；相册重渲染存在跨 profile 毒化 | 03 报告（消费链均为 current 单例读取） | **Phase B**：按需求 2.7 建 CaptureProfileSnapshot，快门时创建并贯通多帧/元数据/渲染/保存 |
| PA-05 | **HSL hue_shift 单位语义差 ~18 倍**：桌面按度（/360），Android 1.0 → 20°；同 JSON 值两端旋转量差约 18 倍。WB 乘性 vs 加性、HSV vs Oklab 亦不同 | 03 报告专项 4（PreviewColorShaderModules.kt:224 vs profile_renderer.py） | Phase B/F（统一单位或文档化近似映射，禁止标记为等价） |
| PA-10 | 测试基础设施损坏：static_cross_check.py 引用已删 ProfileRenderer.kt 直接崩溃；test_studio_smoke 的 validation_root 与现行 build_profile 不匹配 | 05 报告 ④ | Phase B 顺手修复（否则测试门禁不全） |

### P2（需修复/文档化，Phase B–F）

| 编号 | 问题 | 证据 | 分派 |
|---|---|---|---|
| PA-07 | **10 个完全死字段**：shadow.saturation、highlight_rolloff.saturation、halation.radius/threshold/warmth、color_adaptive、color_matrix.input_gamut/output_gamut、color_lut.source/strength；外加整条 GpuParams 遗留死链路（toGpuParams 零调用者） | 03 报告死参数清单 | Phase B/F：逐个决策「接线或文档标记未实现」，禁止静默丢参 |
| PA-08 | 视频录像路径不应用 film_curve/color_matrix/halation 强度 → 录像与拍照色不一致 | 03 报告其他发现 | Phase E |
| PA-09 | 桌面两工具链序矛盾：glsl_reference film LAST vs profile_renderer film BEFORE tone | 03 报告 | Phase F |
| PA-11 | LensStage 出口丢失 ColorSpace tag；全链无成文数据契约（8 项缺口：宽高/格式/色彩空间/传递函数/动态范围/Alpha/方向/阶段） | 04 报告 ⑤ | Phase C（按需求 3.5 立契约） |
| PA-12 | F16 上传缓冲失败时 asGlUploadCompatibleBitmap 隐式量化降级，无明确回退测试 | 04 报告 Q17（LIP.kt:1506/1516-1525） | Phase C |
| PA-14 | shadow.tint 为 Android 独有（桌面参考未消费），参数对照表需注明 | 03 报告专项 6 | Phase F（文档） |

### 已核实「无需处理」项（防误改）

- **PA-13**：highlightRolloff.strength 双重映射（recipe.highlights + toneShoulder）经核实为**有意双通道塑形**（注释明示），非 bug；按需求 2.4 保留并文档化，不删任何一侧。
- noise.luma/chroma 实时通道硬编码 0 为**既定设计边界**（0.10.x 用户指令），桌面仅参考；需求 6.3 明确要求保留此语义，严禁重新映射成任意 Noise 参数。
- grain.luma_contrast 已完整接线（FilmGrainShaders.kt:135-138 / FilmGrainGl.kt:84 / ColorPassProgram.kt:171），非死参数。
- JPEG MAX 软件降噪（c4523dc）与成片保存直传 Bitmap 规避为**受保护修复**，需求 4.2/10.2 明令不得回退。

## 三、Phase A 验收对照（总原则第十二条）

| 验收项 | 状态 |
|---|---|
| 12.1 项目可构建 | ✅ 1.7.6 assembleDebug 已过（上一轮），本阶段零代码改动 |
| 12.2 已有测试全部运行并记录 | ✅ 14 过 / 3 败 / 0 错 / 0 跳；3 败为真实缺陷（PA-06），未伪造 |
| 12.3 预览/成片一致风格语义 | 部分成立：LUT 与参数链三端共用一份 GLSL；HSL 单位差（PA-05）与录像缺失（PA-08）为已知缺口 |
| 12.4 普通/JPEG MAX 不依赖 RAW | ✅ 代码确认（无 RAW 走 YUV/P010 落回路径 a），待真机复核（第 4 节） |
| 12.5–12.7 无新增画质问题 / 未删已验证能力 / 关键修改可复现 | ✅ 本阶段只读；引用既有修复均标注防回退 |
| 12.8 新增参数单位/默认值/范围/消费阶段 | 本阶段无新增参数；PA-07 死字段将在 Phase B/F 补齐声明 |
| 12.9 未完成任务 | 无「下一版」表述：全部遗留项已编号分派 Phase B–G 并给出准入条件 |

## 四、Phase B 准入条件

1. PA-01 修复方案已设计（F16 LensStage 路径或量化点后移），并明确验收图（非零 lens profile 固定场景实拍对比）。
2. PA-06 修复前，任何渲染链改动不得合入——先修 CPU/GLSL 一致性，恢复测试门禁。
3. PA-10 测试基础设施修复（static_cross_check 指向新映射器、studio smoke 参数对齐）。
4. PA-04 CaptureProfileSnapshot 设计评审通过（快照字段集 = Profile ID + Recipe + Film + Lens + 处理开关 + 版本标识）。

**下一步**：进入 Phase B（修复已证实的 Profile 参数传递、状态快照和重复应用问题），从 PA-06 + PA-10 + PA-04 开始，每项改动附测试。
