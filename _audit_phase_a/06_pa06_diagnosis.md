# PA06 — PhotographerCamera 桌面参考渲染链 CPU vs GLSL 数值分歧根因诊断

- 诊断对象：`tools/profile_renderer.py`（CPU 参考，`render`）vs `tools/glsl_reference.py`（GLSL 参考，`render_deterministic`）
- 失败基线：`tests/test_shader_equivalence.py` 三例（TOL=1e-05）：`:77` 2.41e-01、`:92` 1.20e-01、`:165` 5.35e-02
- 结论速览：**分歧根因是 `render_deterministic` 的阶段顺序与 Android 活链路（effect.frag 统一引擎）不一致——tone curve 被提前到 highlight/shadow 之前、film curve 被挪到全链末尾。两侧逐阶段数学完全等价（同输入最大差 5.96e-08），仅顺序不同。修正 glsl_reference 的顺序后三例全部落到 6e-08 ~ 4.2e-07，低于 TOL。**
- 诊断方式：只读。未修改任何现有文件；唯一新建为 `_diag_pa06/diag_order.py`（诊断脚本）与本报告。

---

## ① 对比方法还原（tests/test_shader_equivalence.py）

| 用例 | 输入 | 对比调用 | 归一化 / neutralize |
|---|---|---|---|
| `:67 test_glsl_matches_cpu_reference` | rng(123) 30 组：`_rand_profile` + 64×64 随机图 | `profile_renderer.render(img, prof)` vs `glsl_reference.render_deterministic(img, prof)` | 两侧输入均为 [0,1) float32 随机图，无归一化差异；profile 中 blur/procedural 层（sharpen/bloom/halation/grain/noise）已置 0 |
| `:81 test_non_square_and_extreme_images` | rng(2026) 6 组：47×83 非方形图 + 上/下 4 行纯黑/纯白 + 左 3 列纯红补丁 | 同上 | 同上；意图触发 vignette uv 与 clamp 路径 |
| `:95 test_film_curve_boundaries` | 5 组 floor/ceiling 边界，恒等链 | 同上 | 恒等参数；**该用例实际通过**（film 边界两侧一致） |
| `:128 test_identity_passthrough` | rng(1) 随机图，全恒等 profile | 同上 | **通过** |
| `:146 test_demo_profile_equivalence` | `profiles/demo/profile_final.json`，neutralize 模糊类层（sharpen/bloom/halation/grain=0，noise=0），保留真实 vignette(amount=0.6227)；rng(7) 5× 96×128 | 同上 | demo profile **无 `film_curve` 键** → 两侧均走默认值 floor=8.0 / ceiling=248.0（film 曲线在两侧都激活） |

判定：`max|cpu - gpu| < 1e-05`（float32 同数学的舍入量级）。

复现值（本机 venv，cv2 缺失）：

| 场景 | Phase A 报告值 | 本机复现值 |
|---|---|---|
| test1 iter0 | 2.41e-01 | **2.405461e-01** |
| test2 iter0 | 0.12013716995716095 | **1.201372e-01** |
| test3 iter0 | 0.053523242473602295 | **5.352324e-02**（逐迭代：5.35e-02 / 1.42e-01 / 1.30e-01 / 1.24e-01 / 3.01e-01） |

test3 iter0 与报告值逐位一致 → Phase A  pytest 环境同样没有 cv2（`profile_renderer.cv2 is None`，`apply_lens` 整体 no-op），**分歧 100% 来自链序，与 lens 阶段无关**。

## ② 两侧阶段对照表

Android 权威链（`android/app/src/main/assets/shaders/effect.frag` 头部 "UNIFIED IMAGE ENGINE chain - user-defined order (v0.2.5)" 与 main() 352-406 行）：

```
1 exposure → 2 white balance → 3 color matrix → 4 highlight rolloff → 5 shadow
→ 6 film curve → 7 tone curve → 8 HSL → 9 3D LUT → 10 vignette
→ 11 bloom → 12 halation → 13 grain+noise → 14 sharpen(always last)
```

| # | Android effect.frag（权威） | profile_renderer.render (502-555) | glsl_reference.render_deterministic (217-247) |
|---|---|---|---|
| 0 | —（预览链无 lens） | `apply_lens`（517；cv2 缺失时 no-op） | **无 lens 阶段** |
| 1 | exposure | exposure（528） | exposure（220） |
| 2 | white balance | WB（530） | WB（222） |
| 3 | color matrix | matrix（531） | matrix（223） |
| 4 | **highlight rolloff** | highlight（533） | **tone curve（224-226）** ⚠️ 提前 |
| 5 | **shadow** | shadow（535） | highlight（228） |
| 6 | **film curve** | film（537-539） | shadow（230） |
| 7 | **tone curve** | tone（540） | HSL（231） |
| 8 | HSL | HSL（541） | vignette（233-240） |
| 9 | 3D LUT（仅 color_layer=lut） | LUT（523-527，位置不同，见④注） | 无 |
| 10 | vignette | vignette（543） | **film curve（242-246）** ⚠️ 挪到末尾 |
| 11-14 | bloom / halation / grain+noise / sharpen | 同（544-554） | 不含（确定性子集，设计如此） |

**结论：profile_renderer 与 Android 活链路逐项对齐；glsl_reference 在第 4/10 两个位置偏离。**

权威性佐证：
- `android/.../photon/lut/LutImageProcessor.kt:1102` 注释原文：*"pc_film_curve（= 桌面端 apply_film_curve，链序 shadow → film curve → tone curve）"* —— Android 成片链路显式声明链序并显式等同于桌面 `apply_film_curve` 的位置。
- `profile_renderer.py:507-511` docstring：*"Chain order synced with effect.frag v0.3.0 (Unified Image Engine, the user-defined authoritative order)"*。
- glsl_reference 的 "tone 在 highlight 前" 顺序与**遗留** `shaders/mainchain.frag`（桌面及 android assets 各一份，头部注明合并链，无 film curve）和 `docs/rendering_pipeline.md §1`（Phase 25 旧序，无 film curve）一致——是 v0.2.x 旧序的残留。
- glsl_reference.py:241 注释 *"film curve LAST (same position as ProfileRenderer's film pass)"* **与事实不符**：ProfileRenderer 的 film 在 tone 之前（537-539 vs 540），不在末尾。
- `core/profile/Profile.kt:131` 与 `core/profile/Validation.kt:107` 中 "LAST stage / LAST chain pass" 字样是统一引擎改版前的陈旧文档，已被 effect.frag 代码位置（第 6 站）取代；以代码为准。

## ③ 二分定位过程与第一个分歧阶段

诊断脚本：`_diag_pa06/diag_order.py`（只调用两侧模块自身函数，仅在本地改变调用顺序，无任何文件修改）。

**步骤 1 — 排除逐阶段公式分歧（同输入喂两侧同阶段函数）：**

| 阶段 | 同输入 max\|diff\| |
|---|---|
| exposure / wb / matrix / tone / highlight / shadow / film / vignette | 0.000e+00 |
| hsl | 5.960e-08（float32 舍入） |

→ 所有阶段的**数学公式完全等价**（HSL LUT 烘焙、矩阵转置约定、tone LUT texel-center、film 软膝公式均无差），排除"矩阵行列布局 / HSL 单位 / uv 坐标系"类嫌疑。

**步骤 2 — 只改顺序的受控实验（test1 iter0，floor=2.434 / ceiling=255）：**

| 链序 | max\|diff\| vs CPU |
|---|---|
| glsl as-is（tone@4, film@9） | 2.405461e-01 |
| **仅把 tone 移到 Android位（film 仍末尾）** | 2.405461e-01 |
| **仅把 film 移到 Android位（tone 仍提前）** | 2.401078e-01 |
| **两者都移到 Android 位** | **5.960464e-08** |

→ 单一移动都不足，必须同时修正两处；修正后即低于 TOL。

**步骤 3 — 累积分歧定位（两侧各自按自己的链序并行推进，按阶段数对齐比较）：**

```
after stage 1 (exposure  vs exposure ): 0.000e+00
after stage 2 (wb        vs wb       ): 0.000e+00
after stage 3 (matrix    vs matrix   ): 0.000e+00
after stage 4 (tone      vs highlight): 8.520e-02   ← 第一个显著偏差
after stage 5 (highlight vs shadow   ): 1.146e-01
after stage 6 (shadow    vs film     ): 9.176e-02
after stage 7 (hsl       vs tone     ): 3.128e-01
after stage 8 (vignette  vs hsl      ): 4.626e-01
after stage 9 (film      vs vignette ): 2.405e-01   ← 最终偏差
```

**第一个产生显著偏差的阶段：第 4 站。** 对同一 post-matrix 图像，CPU 下一步施加 `apply_highlight_rolloff`（阈值压缩，强非线性），GLSL 下一步施加 `pc_tone_curve`（1D LUT 重映射）——两者都是非线性阶段且不可交换，单步即引入 8.52e-02 偏差（占最终偏差的 35%），后续 highlight/shadow/film/tone 的相对错位继续放大，经 HSL（第 7 站峰值 3.13e-01）与 vignette 乘法后收敛到 2.41e-01。

**步骤 4 — 排除 vignette uv 嫌疑（test2 47×83 同输入直接对比两侧 vignette）：**

`apply_vignette` vs `pc_vignette` 同输入 max|diff| = **0.000e+00**。两侧 uv 完全一致：texel center `((x+0.5)/w, (y+0.5)/h)`、y 自顶行、非方形图按各自边长归一化、center 直接相减。**vignette 的 uv 坐标系/非方形归一化不是分歧源**（test2 的 1.20e-01 与 test1 同源，均为链序）；test2 补丁（纯黑/纯白/纯红）只是放大了错位阶段间的差异。

## ④ 根因结论

**`tools/glsl_reference.py:217-247` `render_deterministic` 的阶段顺序落后于 Android 统一引擎（effect.frag v0.3.0）：**

1. **tone curve 位置错误**（224-226 行）：被放在 color matrix 之后、highlight/shadow 之前（对应 legacy `mainchain.frag` 旧序），而 Android 权威链是 highlight → shadow → film → **tone**（第 7 站）。
2. **film curve 位置错误**（242-246 行）：被放在 vignette 之后作全链末段，而 Android 权威链是 shadow 之后、tone 之前（第 6 站）；其 241 行注释声称 "same position as ProfileRenderer's film pass" 与 profile_renderer.py:537-540 的实际代码矛盾。

tone/highlight/shadow/film 四个非线性阶段互相不可交换，两处错位在每个激活非线性阶段的随机 profile 上都会产生 O(0.1) 偏差——与三个失败用例的量级（0.24 / 0.12 / 0.053~0.30）完全吻合。demo 用例虽无 `film_curve` 键，但默认值 8/248 使 film 曲线在两侧都激活，错位同样发生。

非根因（已排除）：矩阵行列布局（同输入 diff=0）、HSL 单位/烘焙（5.96e-08）、vignette uv 与非方形归一化（diff=0）、浮点尾差（偏差大 4 个数量级）。

遗留风险（本次不触发，建议跟进）：
- glsl_reference 无 lens 阶段而 profile_renderer.render 有 `apply_lens`。本 venv 无 cv2 故 no-op；在装有 cv2 的环境（含 demo profile 的 `lens.chromatic_aberration=0.0748`），修正链序后 test3 仍会因 CPU 侧色差重采样而分歧。建议后续在测试中 neutralize lens，或为 glsl_reference 增加对应阶段。
- Android effect.frag 的 vignette 公式（smoothstep + `u_vwin` 窗口 + radius 直接作阈值）与两侧 Python 的线性 ramp + `radius*0.7071` 不同；三测试不受影响（两侧一致），但桌面 vs Android 视觉 parity 是独立议题。
- LUT profile（`color_layer="lut"`）下 profile_renderer 把 3D LUT 放在链首（post-LUT 调参语义），effect.frag 把 LUT3D 放在 HSL 之后；三测试均不使用 LUT profile，不在本次范围。

## ⑤ 修复建议

**改哪侧：改 `tools/glsl_reference.py`，不动 `tools/profile_renderer.py`。** 依据：Android 活链路（成片走 `LutImageProcessor` + 预览走 `PreviewColorShader`/effect.frag）的语义是 shadow → film → tone；`LutImageProcessor.kt:1102` 显式写下 "链序 shadow → film curve → tone curve" 并等同于桌面 `apply_film_curve` 的既有位置。profile_renderer 已与 Android 对齐，glsl_reference 是沿用 legacy mainchain 旧序的偏离方。桌面应对齐 Android，而非相反。

**具体到函数与行号**（`tools/glsl_reference.py`，`render_deterministic`，217-247 行）：

1. 将 224-226 行（tone curve 块：`points = ...` / `if points ...` / `c = pc_tone_curve(...)`）从 color matrix 之后**移到** shadow（229-230 行）与 film 块之后、HSL（231 行）之前。
2. 将 242-246 行（film 块：`fc = p.get("film_curve", {})` … `c = pc_film_curve(c, floor/255.0, ceil/255.0)`）从函数末尾**移到** shadow 之后、tone curve 之前。
3. 修正 241 行注释为如实描述：film curve 位于 tone curve 之前（Android effect.frag 统一引擎顺序），删除 "same position as ProfileRenderer's film pass" 的错误表述。
4. （建议）在 docstring 补一句链序来源：`exposure → WB → matrix → highlight → shadow → film → tone → HSL → vignette`（effect.frag unified engine v0.3.0）。

**预期效果**（本机实证，见 ③步骤 2/5）：

| 用例 | 现状 | 修正后（重排 glsl_reference 调用序） |
|---|---|---|
| `:77 test_glsl_matches_cpu_reference`（30 组） | 2.41e-01 | 最差 4.172e-07（全 30 组）＜ 1e-05 ✅ |
| `:92 test_non_square_and_extreme_images` | 1.20e-01 | 5.960e-08 ✅ |
| `:165 test_demo_profile_equivalence` | 5.35e-02 ~ 3.01e-01 | 5.960e-08 ✅ |
| `:95 test_film_curve_boundaries` / `:128 test_identity_passthrough` | 已通过 | 恒等链下顺序不敏感，仍通过（回归保护） |

注意：上述"修正后"数值由诊断脚本在本地重排调用顺序实测，未改动仓库文件；实际落地时按上述行号执行等价重排即可。

## ⑥ 临时诊断脚本留存路径

- `PhotographerCamera/_diag_pa06/diag_order.py` —— 复现三例、per-stage 等价证明、顺序受控实验、累积分歧定位、vignette uv 对照。
- 运行方式：`C:/Users/70898/.workbuddy/binaries/python/envs/default/Scripts/python.exe _diag_pa06/diag_order.py`
- 本目录及本报告为本次诊断唯一新建物；未修改、未删除任何现有文件。
