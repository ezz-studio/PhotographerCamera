# 测试报告（Phase 11.5）

- 日期：2026-10-15
- 版本：1.8.1（APP_VERSION_CODE = 81，构建于本会话）
- 环境：Windows + Git Bash；Python 3.13.14 venv（`C:/Users/70898/.workbuddy/binaries/python/envs/default`）；Gradle 9.6.0；JDK 17
- 纪律：真机（PLG110）不在手，**真机测试项明确标注未执行**，不伪造

---

## 1. 测试执行总表

| 测试项目 | 执行结果 | 证据 | 未通过原因 |
|---|---|---|---|
| 项目构建（assembleDebug） | ✅ BUILD SUCCESSFUL | `/tmp/build_phase.log`（见 §5） | — |
| 单元测试（pytest） | ✅ **22 passed** | `pytest tests/ -q` 实跑 | — |
| Profile / Schema 测试 | ✅ 包含于 pytest | 同上 | — |
| Shader 等价性测试 | ✅ 包含于 pytest（5/5） | `tests/test_shader_equivalence.py` | — |
| hue_shift 单位回归 | ✅ 新增，包含于 pytest | `tests/test_hue_shift_units.py` | — |
| 静态交叉校验 | ✅ **ALL STATIC CROSS-CHECKS PASS** | `tools/static_cross_check.py`（59 活链文件 / 138 uniform 查找 / 620 GLSL 声明 / 40 范围校验 / 36-38 schema 匹配） | — |
| GLSL 符号校验 | ✅ **无条件路径符号全部已定义** | `tools/check_glsl_symbols.py --entry preview`（519 片段，23439 字符模板） | — |
| Studio 冒烟测试 | ✅ PASS（历史基线） | `tests/test_studio_smoke.py`（Phase B 加验证集） | — |
| 真机测试（PLG110） | ⛔ 未执行 | 无设备 | 环境无 Android 真机 |
| 视觉回归测试 | ⛔ 未执行 | 需固定输入图 + 真机/模拟器对比 | 环境无设备 |

---

## 2. 单元测试明细（实跑 22 passed）

执行命令：`pytest tests/ -q` → `22 passed in 13.82s`

覆盖：
- `test_shader_equivalence.py`：CPU 参考（profile_renderer）与 GLSL 参考（glsl_reference）逐阶段数学等价，随机 `hue_shift`(−20..20) 驱动，5 用例。
- `test_hue_shift_units.py`（**新增，Phase F**）：锁定 `hue_shift` = **度**契约（红像素 +30° 落 30°，**显式排除 ×20°=240° 误改**）；区间外像素不受影响；`profile_renderer.apply_hsl` 与 `glsl_reference.pc_hsl` 对任意随机 `hue_shift` 完全一致；`hue_shift=0` 恒等。
- 其余：Profile 解析 / Schema 默认与边界 / studio 冒烟端点。

---

## 3. 静态交叉校验（`tools/static_cross_check.py`）

- 活链路 Kotlin 文件：59
- uniform 查找：138，GLSL 声明：620 → 0 误报（抓"取了 location 但无声明"的 silent no-op）
- Validation.kt 范围校验：40，schema 匹配 36/38（2 个为数组元素无逐元素边界，正常）
- Shader asset references：0（着色器以 Kotlin 字符串内联，非独立 asset）
- 结果：**ALL STATIC CROSS-CHECKS PASS**

---

## 4. GLSL 符号校验（`tools/check_glsl_symbols.py --entry preview`）

- 收集 519 个 GLSL 片段；preview 模板 23439 字符
- 仅某一变体分支出现（通常无害）：`applyDensityFilmGrain`
- 结果：**✓ 无条件路径上的符号全部已定义**（防止运行时 GLSL 编译失败→黑屏）

---

## 5. 构建（assembleDebug）— 本会话执行

- 命令：`gradle assembleDebug`（Gradle 9.6.0，JDK 17，构建目录 `android/app/build2`）
- 前置：临时注释全局 `~/.gradle/gradle.properties` 中已下线的 7990 代理（4 行），构建后复原
- 产出：`android/app/build2/outputs/apk/debug/PhotographerCamera-1.8.1.apk`
- 结果：**BUILD SUCCESSFUL（EXIT=0）**
- 日志：`/tmp/build_phase.log`

> 注：因 7990 代理已离线，构建前必须临时注释代理（腾讯云镜像直连可达），否则依赖下载被拒导致假失败。

---

## 6. 真机验证（明确未执行）

Phase D 协议（`docs/PHASE_D_protocol.md`）要求 PLG110 真机执行：
- 三种模式（JPEG MAX / RAW MAX / Live）固定场景对比
- PA-01 V1/V2/V3（非零 lens profile + RAW 出口 F16、HALF_FLOAT 读回合法性、Room 39→40 迁移）
- 颗粒尺度随分辨率归一、多 GPU 帧率

**上述全部未执行**：当前环境无 Android 真机。按用户纪律，未验证前不推 GitHub、仅本地打包 + R2 发布。

---

## 7. 结论

- 可在本环境执行的全部测试（构建 / 单元 / 等价 / 静态 / GLSL）**真实通过，无伪造**。
- 真机测试与视觉回归**如实标注未执行**，待 PLG110 真机按 Phase D 协议回填。
- 版本 1.8.1 已构建并产出 APK，按纪律通过 R2 发布（真机安装通道），GitHub 推送等用户明确"推"。
