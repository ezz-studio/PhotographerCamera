# Phase B 修复报告 — Profile 参数传递、状态快照与重复应用（基线 1.7.6 / c4523dc）

- 日期：2026-10-15 · 阶段纪律：每项改动附测试，无未经验证的改动
- 对应总方案：Phase B「修复已证实的 Profile 参数传递、状态快照和重复应用问题」
- 上游报告：`docs/PHASE_A_audit_report.md`（问题清单 PA-01~PA-14）

---

## 一、修改文件清单

| # | 文件 | 改动 | 对应问题 |
|---|---|---|---|
| 1 | `tools/glsl_reference.py` | `render_deterministic` 链序对齐 Android 权威链（tone 移至 film 后、film 移至 shadow 后 tone 前）；修正 docstring | PA-06 |
| 2 | `tools/static_cross_check.py` | 检查 2/3 重写：ProfileRenderer.kt（已删）→ 现行活链路 uniform 查找 vs GLSL 声明交叉校验；声明收集扩全 Kotlin 树 | PA-10 |
| 3 | `tests/test_studio_smoke.py` | 新增 `_make_plain_dataset()`（未调色验证集），`/api/generate` 传 `validation_dir` | PA-10 |
| 4 | `android/.../core/photon/color/CaptureProfileSnapshot.kt` | **新建**：不可变快照数据类 + `CaptureProfileStore` 单例桥 | PA-04 |
| 5 | `android/.../photon/viewmodel/CameraViewModel.kt` | `captureSnapshotSeq` + `createCaptureProfileSnapshot()` + `capture()` 快门入口创建快照 | PA-04 |
| 6 | `android/.../photon/lut/LutImageProcessor.kt` | 7 处 Film/Lens 单例消费点改「快照优先、空则回退单例」 | PA-04 |
| 7 | `android/.../photon/lut/BakedLutExporter.kt` | 2 处导出入口 `CaptureProfileStore.clear()`（非拍摄路径回退活参数） | PA-04 |
| 8 | `docs/PROFILE_参数对照表_Studio_vs_Android.md` | Phase B 修正章节：死字段结论修正、HSL 单位差声明、新通道 D、跨引擎语义差异表 | PA-05/07/13/14 |

未改动：`tools/profile_renderer.py`（Android 活链路即其链序，CPU 参考为正确侧）；预览链 `LutRenderer`（预览继续跟随活参数，设计如此）。

## 二、问题证据与实现说明

### PA-06 — CPU/GLSL 参考实现链序分歧（已修复 ✅）

- **证据**：Phase A 诊断（`_audit_phase_a/06_pa06_diagnosis.md`）：`glsl_reference.render_deterministic` 把 tone curve 提前到 matrix 后、film curve 挪到全链末尾，系 legacy `mainchain.frag` v0.2.x 旧序残留；Android 权威链（effect.frag v0.3.0 + `LutImageProcessor.kt:1102` 注释 "shadow → film curve → tone curve"）与 `profile_renderer` 一致。逐阶段数学等价（同输入 max|diff| ≤ 5.96e-08），仅顺序不同；受控实验证实必须同时修正两处。
- **实现**：只改 glsl_reference 一侧重排为 `… → highlight → shadow → film → tone → HSL → vignette`。
- **过程教训（已记录）**：整文件 Write 重写时在 `_hsv2rgb` 的 `i==5` 分支引入笔误（`v,p,q`→`t,p,q`），导致失败数从 3 升到 5；经 git diff 逐行复核捕获并修复。后续同类改动一律用精准 Edit + diff 复核。

### PA-10 — 测试基础设施（已修复 ✅）

- **static_cross_check.py**：原检查 2/3 引用 0.9.0 已删除的 `core/gpu/ProfileRenderer.kt`，运行即 FileNotFoundError。重写为面向现行链路的 uniform 交叉校验：从 58 个活链路 Kotlin 文件提取 134 个 `glGetUniformLocation` 字面量，与全树 GLSL 字符串声明（613 个）比对，抓「取了 location 但无声明」的 silent no-op uniform（1.7.4/1.7.5 预览黑屏同类 bug 的静态预防）。
- **test_studio_smoke.py**：`build_profile.build()` 强制要求 `validation_root`（产品原则：风格 = T ÷ S，未调色侧是测量基准），smoke 未提供导致 generate 失败。补建 8 张未调色合成图并通过 `validation_dir` 传入。

### PA-04 — 拍摄期间 Profile 参数竞争（已实现核心闭环 ✅）

- **证据**：Phase A 03 报告：Film/Lens 参数经进程级可变单例下发，异步多帧处理与导出期间用户切 profile 会读到另一份参数；相册重渲染存在跨 profile 毒化路径。
- **实现**（需求 2.7，最小侵入、沿用既有单例桥模式）：
  1. `CaptureProfileSnapshot(profileId, recipe, film, lens, version, createdAt)` 不可变数据类；
  2. `CameraViewModel.capture()` 快门入口同步创建（recipe 取 VM 内 `currentRecipeParams`，与预览/视频同源；零竞态，无异步窗口）；
  3. `CaptureProfileStore.current` 单例桥下达成片链：`LutImageProcessor` 7 处消费点改「快照优先」；
  4. 预览 `LutRenderer` 与 `BakedLutExporter`（导出烘焙）显式不读/清快照，行为与改动前一致。

### PA-05/07/13/14 — 死字段与语义差异（文档化处置 ✅）

- 10 个死字段逐个给出决策（见对照表 Phase B 章节）：`shadow.saturation`/`halation.radius/threshold/warmth` = 已注入未消费（Phase C/E 接线候选）；`color_adaptive`/`gamut`/`color_lut.source/strength` = 元数据/预留，标记不接线。
- HSL hue_shift 单位差（桌面按度 vs Android 1.0=20°，约 18 倍）按需求 2.5/6.2 标记为**近似映射、双端非等价**；决策不改 Android 单位（会静默改变存量 profile 语义），统一动作放 Phase F 的 Profile Builder 导出侧。
- highlightRolloff.strength 双重映射 = 有意设计（PA-13），保留并文档化，禁止删任何一侧。
- shadow.tint = Android 独有转换（桌面未消费 tint），已注明。

## 三、测试命令与真实结果

| 测试 | 命令 | 结果 |
|---|---|---|
| Python 全量 | `pytest tests/ -v`（venv Python 3.13.14） | **17/17 PASS**（含此前 3 失败的 shader equivalence，现 5/5 全过） |
| 静态交叉校验 | `python tools/static_cross_check.py` | **ALL PASS**（134 uniform 查找全部有声明；JSON keys 73；Validation 40 项） |
| Studio 冒烟 | `python tests/test_studio_smoke.py` | **PASS**（health→browse→generate→preview→validate→save→export→batch 全端点 200，25s） |
| Kotlin 编译 | `gradle compileDebugKotlin` | **BUILD SUCCESSFUL**（1m08s；首次失败 1 处：VM 简名缺 import，改全限定名后通过） |
| GLSL 静态校验 | — | 本阶段未改着色器，不适用 |

## 四、未完成事项（如实报告，不设「下一版」表述）

1. **B-3 元数据记录未做**：`MediaMetadata` 尚未记录 `profileId/version`（需求 2.7 的「元数据记录」一环）。RAW 路径元数据结构已存在，JPEG 路径需扩字段——排期 Phase C 与导出链改造一并做（避免本阶段动导出签名扩大风险面）。
2. **B-3 真机验证未做**：需 PLG110 实测「快门后立刻切 profile，成片参数 = 快门时快照」。
3. **halation.radius/threshold/warmth、shadow.saturation 接线**：需改 shader，排期 Phase C/E。
4. **HSL hue_shift 单位统一**：排期 Phase F（Profile Builder 导出侧）。

## 五、Phase C 准入条件

1. 测试门禁保持绿（pytest 17/17 + static_cross_check PASS + 编译通过）——已满足并作为 C 阶段每次改动的回归基线。
2. PA-01（P0）LensStage 8-bit 读回修复方案设计先行：F16 路径或量化点后移，验收图 = 非零 lens profile 固定场景实拍对比。
3. B-3 真机验证通过后再合入发布分支（当前改动已编译验证，真机通过即可随下一发布交付）。
4. 数据契约（需求 3.5）成文作为 Phase C 各格式改动的依据。
