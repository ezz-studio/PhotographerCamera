# Phase F 报告 — Profile 体系收尾（PA-05 / PA-07 / PA-09 / PA-13·14）

- 基线版本：`APP_VERSION_NAME = 1.8.0`（`android/app/build.gradle.kts`）
- 范围：Profile 参数语义治理收尾，对应 Phase A 审计条目 PA-05、PA-07、PA-09、PA-13/PA-14。
- 纪律：真机（PLG110）验证通过前**不推 GitHub、不 R2 发布**；本次仅本地改码 + 本地提交 + 门禁验证。

---

## 0. 重要更正（PA-05 前提交点被推翻）

接续前的 `PHASE_A_audit_report` / `PROFILE_参数对照表` 中关于 PA-05 的结论
（"Studio 按度解释 `/360.0`，Android 按 `radians(20.0)` 即 1.0=20°，两端相差约 18 倍，非等价"）
**经逐行核对 Android 渲染链后判定为误读**。正确事实如下：

Android 对 profile 的 `hue_shift` 字段存在**两条独立消费路径**：

1. **HSL-LUT 阶段（profile `hue_shift` 字段的真正渲染路径）**
   - `assets/shaders/hsl.glsl:19`：`float hue_shift = pl.b / 360.0;`
   - `assets/shaders/effect.frag:283`：`float hue_shift = pl.b / 360.0;`
   - 烘焙侧 `core/gpu/GpuParams.kt:154-169`：`bakeHslLut` 把 `c.hueShift` 存为 LUT 的 B 通道（度）。
   - **这与 Studio 两个 CPU 参考完全一致**：
     - `tools/profile_renderer.py:243`：`params.get("hue_shift", 0) / 360.0`
     - `tools/glsl_reference.py:181`：`pl[..., 2] / 360.0`
   - 即：**profile `hue_shift` 字段，Studio 与 Android HSL-LUT 阶段逐字节等价，均为「度」**。

2. **LCH 调色阶段（独立模块，非 `hue_shift` 字段路径）**
   - `PreviewColorShaderModules.kt:224` / `VideoLutEffect.kt:395`：
     `hueShift += uLchHueAdjustments[i + 1] * weight * radians(20.0);`
   - 该 uniform 由 `ProfileToRecipeMapper.mapHsl`（`ProfileToRecipeMapper.kt:155-181`）
     把**同一个** `hsl.<hue>.hueShift` 映射到 `redHue/orangeHue/…/magentaHue` 喂入，
     是一个 **9 段色相带加权 + 肤色 + 色度门控**的重型 OKLCH 调色。
   - 缩放 `×radians(20.0)` 是该**独立调色阶段**的内部约定，**并不能用「全局 ×20°」在 Studio 复现**
     （它是 per-pixel 带权重 + 色度门控的旋转，等价于 blur/grain 之类的 GPU 近似层，由真机验证）。

**结论**：PA-05 的"两端非等价 / 18 倍差异"是混淆了上述两条路径导致的误判。
真实情况是 HSL-LUT 路径两端已一致，**Studio 代码无需改动**。原计划的"把 Studio 对齐为 ×20°"
若执行，反而会**破坏** Studio 与真实 HSL-LUT 着色器的等价性（引入 bug）。因此本次 PA-05 **不做 hue 数学改动**。

---

## 1. PA-05 — 处置与固化

| 项 | 动作 |
|---|---|
| `tools/profile_renderer.py:243` | 保留 `/360.0`；新增契约注释，明确"度"语义、点名 LCH 调色为独立阶段、禁止误改为 ×20° |
| `tools/glsl_reference.py:181` | 同上，新增契约注释 |
| `profiles/schema/photographer_profile.schema.json` `hue_shift` | 新增 `description`：单位=度，`/360.0` 与 Android HSL-LUT 一致，点名 LCH 阶段不使用此字段 |
| `docs/PROFILE_参数对照表_Studio_vs_Android.md` §2 | 整段重写：作废"18 倍不匹配"旧结论，给出两条路径的准确说明 |
| `tests/test_hue_shift_units.py`（新增） | 回归测试锁定契约（见 §4） |

### OPEN QUESTION（遗留，不在 Phase F 范围，待真机裁决）
Android 同一 `hue_shift` 值会**同时**驱动 HSL-LUT（`/360.0`，细微）与 LCH 调色（`×20°`，重型）
两道阶段。当前所有内置 profile（`native/VINTAGE/FRESH/CLASSIC/CINEMA/ACROS/MONO`）的 `hue_shift`
**全部为 0**，两道阶段都为零贡献，故现网两端（含桌面预览）完全一致。
若未来启用非零 `hue_shift`，Android 会出现 **HSL-LUT + LCH 的叠加旋转**，与桌面（仅 HSL-LUT）产生差异。
是否应让二者互斥或仅保留其一，需在 PLG110 真机评估后由用户决定——**不在本次清理范围，也未改 Android**。

---

## 2. PA-07 — 残余死字段处置

清单与处置（均遵循原设计边界：**文档标记，不接线**）：

| 字段 | 状态 | 处置 |
|---|---|---|
| `highlight_rolloff.saturation` | Android 实时通道无 uniform 消费端；仅桌面 `apply_shadow` 消费，双端不等价 | 已在 schema 加 `description` 标注 inert（PA-07） |
| `color_matrix.input_gamut` | 双端均无消费 | 已在 schema 加 `description` 标注预留/元数据 |
| `color_matrix.output_gamut` | 双端均无消费 | 已在 schema 加 `description` 标注预留/元数据 |
| `color_adaptive` | 不在 schema 内（out-of-schema 预留字段） | 文档说明，不接线 |
| `color_lut.source` / `color_lut.strength` | 不在 schema 内（元数据：`strength` 已烘进 LUT data，运行时 100% 采样即正确；`source` 无消费） | 文档说明，非缺陷 |

> 注：schema 仅对存在的字段加 `description`，**未改动任何 `minimum`/`maximum`**，故 `static_cross_check`
> 的 Validation.kt 范围校验不受影响（实测 36/38 匹配，2 个为数组元素无逐元素边界，正常）。

---

## 3. PA-09 — Studio 链序矛盾（已解，确认）

`tools/glsl_reference.py::render_deterministic`（L222-259）的确定性链序已在 Phase B-1 与 Android
统一引擎对齐：
`exposure → WB → matrix → highlight → shadow → film curve → tone curve → HSL → vignette`
（含 L233-240 的详细注释说明旧 `mainchain.frag v0.2.x` 顺序为何被废弃）。本次仅确认，**无代码改动**。

---

## 4. 验证门禁结果

| 门禁 | 命令 | 结果 |
|---|---|---|
| 单元测试（含新增） | `pytest tests/ -q` | **22 passed** |
| 静态交叉校验 | `python tools/static_cross_check.py` | **ALL STATIC CROSS-CHECKS PASS** |
| GLSL 符号校验 | `python tools/check_glsl_symbols.py --entry preview` | （着色器无改动，沿用 Phase E 结论 ✓） |

新增回归测试 `tests/test_hue_shift_units.py` 覆盖：
- `hue_shift = +30°` 时红像素落在 30°（**显式排除** `30×20°=240°` 的误改）；
- 已知原色（红/绿/蓝，含负向环绕）旋转量精确等于 `输入 + 偏移`；
- 区间外像素不受影响；
- `profile_renderer.apply_hsl` 与 `glsl_reference.pc_hsl`（经 `bake_hsl_lut`）对任意随机 `hue_shift` 完全一致（锁定双参考等价，防未来误改 ×20°）；
- `hue_shift=0` 为恒等变换。

---

## 5. 改动文件清单

- `tools/profile_renderer.py`（注释，+9 行，数学不变）
- `tools/glsl_reference.py`（注释，+6 行，数学不变）
- `profiles/schema/photographer_profile.schema.json`（3 处 `description` 增注）
- `docs/PROFILE_参数对照表_Studio_vs_Android.md`（§2 整段重写）
- `tests/test_hue_shift_units.py`（新增）
- `docs/PHASE_F_report.md`（本报告）

---

## 6. 风险与下一步

1. **不推 GitHub / 不 R2**：遵守既定纪律，待 PLG110 真机验证 1.8.0 后，由用户明确"推"再执行
   `tools/_push_via_api.py <sha> main`，并视情况 `_r2put.py`/`_r2upload.py` 发布。
2. **PA-05 OPEN QUESTION**：非零 `hue_shift` 在 Android 的 HSL-LUT+LCH 叠加旋转需在真机评估，
   决定是否让两阶段互斥；本次未改 Android。
3. **PA-07 死字段**：维持"文档标记、不接线"；若后续确需启用（如 `highlight_rolloff.saturation` 接 Android uniform），
   属独立工程，需单独立项 + 真机验证。
