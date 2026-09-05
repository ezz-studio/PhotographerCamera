# 阶段3审计报告 — film_curve 一致性缺口修复（2026-09-04 20:38）

依据 `PhotographerCamera/交接卡.json` 执行协议：阶段1仅检查 → 用户批准 → 阶段2修复 → 阶段3本报告。

## 1. 改动文件清单（8 个）

| 文件 | 改动 |
|---|---|
| `profiles/schema/photographer_profile.schema.json` | 新增 `film_curve` 字段定义：`shadow_floor` [0,64] 默认 8、`highlight_ceiling` [191,255] 默认 248（0-255 显示级），含语义描述 |
| `tools/profile_renderer.py` | 新增 `apply_film_curve()`（C1 连续 soft-knee，精确复刻 `film_curve.frag`；含 filmEnabled 早退与 GpuParams 相同的 lo/hi coerce）；`render()` 末尾（vignette 后）调用 |
| `tools/glsl_reference.py` | 新增 `pc_film_curve()`（同上，NumPy 向量化）；`render_deterministic()` 末尾调用 |
| `tests/test_shader_equivalence.py` | `_rand_profile` 加入随机 film_curve（含禁用路径）；新增 `test_film_curve_boundaries`（5 组 floor/ceil 边界 + 纯黑/纯白输入 + GPU↔CPU 对拍） |
| `tests/test_renderer.py` | 恒等测试显式禁用 film_curve（其余链保持恒等断言）；新增 `test_default_film_curve_bounds`（默认 profile 输出 ∈ [8/255, 248/255]） |
| `tools/static_cross_check.py` | profile 检查列表由硬编码 `demo.json/photographer_a.json` 改为动态枚举 `assets/profiles/*.json` |
| `android/.../core/profile/Validation.kt` | 新增 2 项范围校验：`film_curve.shadow_floor` [0,64]、`film_curve.highlight_ceiling` [191,255] |
| `tools/profile_schema.py` | `default_profile()` 增加 `film_curve: {shadow_floor: 8, highlight_ceiling: 248}` |

## 2. AGENTS.md §2 强制规则逐条对照

| 规则 | 结论 |
|---|---|
| 1 按阶段执行 | ✓ 遵循交接卡三阶段协议 |
| 3 模块可单独测试 | ✓ film_curve 新增独立单元测试与边界测试 |
| 4 参数有定义/单位/范围/默认 | ✓ schema 补齐后 film_curve 完整具备 |
| 5 参数不硬编码 GLSL | ✓ 未改任何 GLSL；CPU 参考从 profile JSON 取参 |
| 6 Uniform/Texture 注入 | ✓ 未改注入路径（u_film 注入保持原样） |
| 7 预览=Capture 一致 | ✓ 未改 pass 顺序（film_curve 仍在链末） |
| 10 独立验证集 | ✓ 未触碰验证逻辑 |
| 12 禁 AI Runtime | ✓ 无任何 AI 依赖引入 |

**违反项：无。**

## 3. GPU/CPU 一致性测试结论

- `tests/run_tests.py`：**ALL PHOTOGRAPHERCAMERA TESTS PASS**
  - JSON keys 64 ↔ Profile.kt @SerialName 全覆盖；
  - Uniforms 29/29 一一对应；
  - Validation.kt 39 项范围校验 / 35 项与 37 个 schema bounds 精确匹配；
  - `ALL STATIC CROSS-CHECKS PASS`（原 2 项 FAIL 已消除）。
- `tests/test_shader_equivalence.py`：**PASS**（30 组随机 profile + 5 组 film 边界，容差 1e-5）。

## 4. 构建结果

- **BUILD EXIT=0**（`BUILD SUCCESSFUL in 32s`），日志 `PhotographerCamera/gradle_build.log`。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`（已更新）。

## 5. 验证结论

- 模拟器 emulator-5556：`install -r` Success → 启动 PID 2940 存活，logcat 无 FATAL / AndroidRuntime 异常 / GL_INVALID。
- Validation.kt 改动仅为新增范围校验（默认值 8/248 合法），运行时行为不变。
- film 边界的 CPU 端等价验证已落地（`test_default_film_curve_bounds`）；**真机亮/暗场景像素统计仍待真机**。

## 6. 遗留风险与下一步建议（沿袭交接卡，均需真机）

1. 真机验证 RAW_SENSOR 直出（设备 cap 需含 RAW 能力；模拟器自动回退 YUV_420_888）。
2. 真机亮/暗场景像素统计：最亮通道 ≤ 248、最暗通道 ≥ 8（CPU 等价测试已补，实机仍需确认）。
3. 点按测光区在 sensorOrientation 90/180/270 下的坐标映射（横竖屏 + 前/后摄）。
4. 语义说明：CPU `render()` 默认启用 film_curve(8/248) 与 Android GpuParams 默认行为一致（filmEnabled 默认 true）；需要纯恒等渲染的场景须显式设 `shadow_floor=0, highlight_ceiling=255`。
