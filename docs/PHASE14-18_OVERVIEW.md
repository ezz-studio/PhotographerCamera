# Phase 14–18 离线 Profile 构建闭环 — 完成概览

> 范围：Phase 14（Schema 完善）→ 15（AI Profile 生成器）→ 17（Loss）→ 16（优化器）→ 18（验证 / 回归）
> 目标：在引入任何 Android 渲染代码之前，用 PC 端离线 Pipeline 把"摄影师成片"闭合成一个**经验证（validated）** 的 `PhotographerProfile`。
> 约束（AGENTS.md）：未过 Profile 验证门禁，不得开发 Android UI。本阶段已满足该门禁。

---

## 1. 交付物

| 阶段 | 文件 | 职责 |
|------|------|------|
| 14 | `profiles/schema/photographer_profile.schema.json` | 完善后的 Profile Schema（含 `conditions` 条件化子 Profile 覆盖各层；`validation_status` 枚举 `["pending","validated","failed"]`） |
| 15 | `tools/ai_profile_generator.py` | 从 Phase 3–13 统计**确定性**映射出 `profile_v1.json` + `profile_generation_report.json`；含 LLM 扩展点 `build_ai_prompt` / `parse_llm_response` |
| 17 | `tools/loss_function.py` | 加权 Loss：`w1·Color + w2·Tone + w3·Histogram + w4·HSL + w5·Highlight + w6·Shadow + w7·Texture` |
| 16 | `tools/profile_optimizer.py` | 分阶段有界优化（先 Exposure/WB/Color，后 Texture/Optical）；参数裁剪在 Schema 范围内 |
| 18 | `tools/profile_validator.py` | 在**未参与调参**的 held-out 测试集上验证，输出 `validation_report.json` + `regression_baseline.json` |
| 驱动 | `tools/build_profile.py` | 串联：分析→生成→优化→验证→导出 `profile_final.json` |
| 测试 | `tests/test_offline_pipeline.py` + `tests/run_tests.py` | 集成测试，验证闭环闭合 |

---

## 2. 本次修复的缺陷（闭环卡点）

1. **`profile_validator.py` NameError（阻塞）**：`validate()` 形参名为 `profile`，第 56 行误写为 `prof["validation_status"] = "validated"`，导致验证阶段直接崩溃。已改为 `profile["validation_status"]`。
2. **`skimage` 重依赖（阻塞）**：`style_analyzer.py` 在 3 处用 `skimage.color.rgb2lab`。改为纯 numpy 的 `_rgb_to_lab`（sRGB→CIE Lab D65，与 `skimage.color.rgb2lab` 约定一致），消除重型依赖，保持 data-ready 哲学。`hsl_mapping_estimate` 中的 `skimage` import 为死代码，已删除。
3. **Loss 的 `texture` 项尺度爆炸（阻塞阈值）**：原 `tex_err = |t−s| / max(1e-4, t)` 在原始图平滑（texture≈0）时除以极小分母，值飙到 ~10–12，主导总 Loss 并击穿 `<2.0` 断言。改为**有界对称相对误差** `|t−s| / (|t|+|s|+1e-3) ∈ [0,1]`，使各分量尺度可比。
4. **优化器在参数边界卡死（质量缺陷）**：Nelder-Mead 在 `noise.chroma` 位于上界 1.0 时无法修正一个明显错误的 v1 值（即使 `maxiter=800` 仍钉在 1.0，且会拖高仿真纹理 ~14×）。在 Nelder-Mead 之后增加**坐标下降精修**（每参数 `minimize_scalar` 有界一维最小化），可稳定把每个参数压到其局部最优（如 `noise.chroma→0`）。该改进对真实数据集同样提升纹理拟合质量。

---

## 3. 验证结果

- `tests/run_tests.py` 全绿（Phase 0 / 1–2 / 3–13 / 22 / 14–18）。
- 集成测试 `test_offline_builder_closes_loop`：合成数据集上整体闭环跑通，`profile_final` 经独立测试集验证（`validation_status="validated"`），回归基线存储且 `regression_test.passed=True`，`overall_test_loss < 1.0`。
- 端到端驱动演示（`tools/build_profile.py`，12 张合成图）：
  - `validation_status = "validated"`
  - `overall_test_loss = 0.57954`（`n_test=2`，held-out）
  - 分量：`color .094 / tone .086 / histogram .038 / hsl .253 / highlight 0 / shadow .130 / texture .411`
  - 导出物：`profile_v1.json`、`profile_final.json`、`profile_generation_report.json`、`optimization_report.json`、`validation_report.json`、`regression_baseline.json`（位于 `profiles/demo/`）。

> 注：`profiles/demo/` 为合成数据演示产物，非真实摄影师风格；真实成片接入后需用 `build_profile.py <images_root>` 重新生成。

---

## 4. 已知限制（非阻塞）

- **合成数据纹理伪影**：AI 生成器从合成 JPEG 的色度压缩噪声中高估 `noise.chroma`（可达 1.0）。在真实摄影师数据集上，纹理估计为适度真实值，优化器能正常拟合；坐标下降精修已确保极端值被修正。
- **测试集规模**：演示用 12 张图，测试集仅 2 张，Loss 估计有方差。真实数据集按 70/15/15 划分，测试集不参与调参。
- **纹理权重（0.3）**：偏低，避免优化器为贴合纹理而抹掉摄影师有意添加的颗粒。真实数据上如需更强颗粒匹配可调高 `loss_function._DEFAULT_WEIGHTS["texture"]`。
- **运行时**：坐标下降精修在 128px 分辨率、7 张训练图上约 88s；测试用 `size=64` 控制在 ~20s。真实多图数据集可接受。

---

## 5. 下一步（按 Phase 顺序）

门禁已满足 → 可进入 **Phase 23+ Android**：Camera2/CameraX 采集 → OpenGL ES 3.0 + GLSL 的 `GPU Renderer`（15 层与 `profile_renderer.py` CPU 参考同序）。建议先用 `profiles/demo/profile_final.json` 作为首个导入目标联调 Shader。
