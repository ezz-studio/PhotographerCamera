# 测试基线报告 — PhotographerCamera（Phase A · 05）

- 执行日期：2026-10-15
- 执行方式：只读运行，未修改任何测试或源码文件
- 项目根：`C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera`

## ① 运行环境

| 项 | 值 |
|---|---|
| Python | 3.13.14（venv: `C:/Users/70898/.workbuddy/binaries/python/envs/default/Scripts/python.exe`） |
| numpy | 2.5.2 |
| pillow | 12.3.0 |
| scipy | 1.18.1（基线执行期间安装到上述 venv；安装前缺失） |
| pytest | 9.1.1（基线执行期间安装到上述 venv） |
| opencv | 未安装（当前测试套件无需） |
| scikit-image | 未安装（当前测试套件无需） |

说明：任务指定的首选 Python（`versions/3.13.12/python.exe`）仅有 pip、无 numpy 等依赖；按约束全部依赖只安装到指定 venv `envs/default`，并用该 venv 的 Python 运行全部测试。安装清单：`pytest`、`scipy`。未做任何全局安装。

运行命令（均为后台执行，日志留存于项目根）：

```
python -m pytest tests/ -v --no-header          → _audit_pytest_run.log（装 scipy 前）
python -m pytest tests/ -v --no-header          → _audit_pytest_run2.log（装 scipy 后，最终结果）
python tests/run_tests.py                       → _audit_run_tests.log
python tools/static_cross_check.py              → _audit_static_cross_check.log
python tests/test_studio_smoke.py               → _audit_studio_smoke.log
```

## ② 逐文件结果表（最终状态 = 安装 scipy 后）

pytest 全量：**收集 17 项 → 14 PASS / 3 FAIL / 0 ERROR / 0 SKIP**（耗时约 16s）

| 测试文件 | 用例数 | 结果 | 明细 |
|---|---|---|---|
| tests/test_profile_schema.py | 3 | 全 PASS | test_default_profile_valid / test_out_of_range_rejected / test_missing_required_rejected |
| tests/test_dataset_loader.py | 1 | 全 PASS | test_discover_and_read |
| tests/test_dataset.py | 2 | 全 PASS | test_analyzer_writes_metadata_and_manifest / test_cleaner_classifies_problem_images |
| tests/test_renderer.py | 4 | 全 PASS | test_identity_profile_is_nearly_passthrough / test_default_film_curve_bounds / test_exposure_bias_brightens / test_vignette_darkens_corners |
| tests/test_offline_pipeline.py | 1 | PASS（装 scipy 前 FAIL） | test_offline_builder_closes_loop |
| tests/test_style.py | 1 | PASS（装 scipy 前 FAIL） | test_style_analysis_emits_all_phase_files |
| tests/test_shader_equivalence.py | 4 | 2 PASS / 3 FAIL | 见 ③ |
| tests/test_studio_smoke.py | 0（pytest 用例） | 脚本单独运行 FAIL | 仅有 `main()`，无 `test_*` 函数，pytest 收集 0 项；由 run_tests.py 以子进程运行 |
| tests/run_tests.py（入口） | — | FAIL | 在同一 shader 用例处中断（见 ③/④） |
| tools/static_cross_check.py（入口调用） | — | ERROR | FileNotFoundError，见 ④ |

## ③ 失败详情（3 项，均在 tests/test_shader_equivalence.py）

共同特征：CPU 参考实现 `profile_renderer.render` 与 GLSL 参考实现 `glsl_reference.render_deterministic` 输出存在大幅数值偏差，远超测试阈值 `TOL = 1e-05`。属真实代码级不一致，与依赖缺失无关（安装 scipy 后依旧失败）。

1. `tests/test_shader_equivalence.py:77` — `test_glsl_matches_cpu_reference`
   - `AssertionError: iter 0: max abs diff 2.41e-01 exceeds 1e-05`（30 组随机 profile/图像的 CPU vs GPU 对比，第 0 组即超差 4 个数量级）
2. `tests/test_shader_equivalence.py:92` — `test_non_square_and_extreme_images`
   - `AssertionError: assert 0.12013716995716095 < 1e-05`（47×83 非方形图 + 饱和/黑边补丁，触发 vignette uv 与 clamp 路径）
3. `tests/test_shader_equivalence.py:165` — `test_demo_profile_equivalence`
   - `AssertionError: assert 0.053523242473602295 < 1e-05`（profiles/demo/profile_final.json，已 neutralize 模糊类图层，真实 vignette 仍激活）

关键错误信息（前 20 行以内）已完整保留于 `_audit_pytest_run2.log` 第 23–91 行。

## ④ 未运行 / 入口级失败项及原因

1. `tools/static_cross_check.py`（Android 静态交叉校验）— **ERROR，无法运行**：
   `FileNotFoundError: android/app/src/main/java/com/photographercamera/core/gpu/ProfileRenderer.kt` 不存在。本工作区 android/app 下有 401 个 .kt 文件，但预期路径的 `core/gpu/ProfileRenderer.kt` 缺失（工作区搬迁后源码树结构变化所致），非依赖问题，装包装不上。
2. `tests/test_studio_smoke.py`（Studio HTTP 冒烟，~1min）— **FAIL**：服务端 health/controls/browse/thumb/img 均 200 正常；`POST /api/generate` 之后任务失败：
   `ValueError: 未提供「未调色普通照片」目录（validation_root）`（`tools/build_profile.py:86`）。冒烟脚本未传 validation_root，而当前 build_profile 强制要求。属测试与现行代码行为不匹配，非环境问题。
3. `tests/run_tests.py`（完整入口）— **FAIL**：按 Phase 0→25 顺序执行，前 10 个阶段函数全部通过（含装 scipy 后恢复的 offline/style），在第 42 行 `t_eq.test_glsl_matches_cpu_reference()` 抛出与 ③.1 相同的 AssertionError 后中断；因此入口中的 static_cross_check 与 studio smoke 两个后续步骤未执行（已按上述 1/2 单独补跑记录）。

## ⑤ 依赖修复记录（安装前 → 安装后）

| 用例 | 装 scipy 前 | 装 scipy 后 |
|---|---|---|
| test_offline_pipeline.py::test_offline_builder_closes_loop | FAIL（`ModuleNotFoundError: No module named 'scipy'`，`tools/style_analyzer.py:470` 懒加载 `from scipy.ndimage import laplace`） | PASS |
| test_style.py::test_style_analysis_emits_all_phase_files | FAIL（同上） | PASS |
| test_shader_equivalence.py ×3 | FAIL | FAIL（不变） |

结论：2 个失败为纯环境缺失（scipy 懒加载），装上即过；3 个 shader 失败为真实缺陷。requirements.txt 中 scipy 标注为"懒加载、必显式列出"，venv 未预装属环境配置缺口。

## ⑥ 结论

- **基线不健康**。pytest 套件 14/17 通过；剩余 3 个失败全部集中在 `test_shader_equivalence.py`，是 CPU 参考渲染与 GLSL 参考实现的真实数值分歧（最大偏差 0.24，阈值 1e-05），非环境问题，需修复 `profile_renderer` 或 `glsl_reference` 两侧的实现一致性。
- 环境侧基线在补齐 `pytest + scipy` 后健康：profile schema / dataset loader / dataset / renderer / offline pipeline / style 六个文件全部通过。
- 未运行项：`static_cross_check.py` 因本工作区缺少 `android/.../core/gpu/ProfileRenderer.kt` 无法运行（仓库内容缺失，非测试失败）；`test_studio_smoke.py` 已单独运行但在 generate 步骤失败（validation_root 强制校验）。
- 汇总：**总数 17，通过 14，失败 3，错误 0，跳过 0**（另有入口级 2 项附加步骤：static_cross_check ERROR、studio smoke FAIL）。
