# Profile JSON 字段手册

适用范围：`profiles/**/*.json`（Studio 生成、App assets/profiles 预设共用）。
字段渲染顺序以 `tools/profile_renderer.py::render()` 为准，Android GPU 链与此同序。

**核心定位**：profile 是**风格层**，不是相机硬件参数。它不改变 ISO / 快门 / 光圈 / 对焦 / 相机 AWB，
而是作用在已成像的画面上（预览流与成片各消费一次），等价于"ISP 之后再叠一层胶片模拟"。
之所以每个字段都能在相机或后期软件里找到对应物，是因为它复刻的正是 ISP 与后期的那套环节。

---

## 元信息（不参与渲染）

| 字段 | 说明 |
|---|---|
| `version` | profile 结构版本（当前 2） |
| `schema_version` | 校验用 JSON Schema 版本 |
| `name` | 内部名 |
| `validation_status` | 验证状态，`validated` 表示通过留出集验证 |
| `display.name` | App 预设列表显示名（存用户原始输入，不能用 safeName） |
| `display.intro` | 预设简介 |
| `display.icon` | 图标扩展名，App 按 `<id>.json` + `<id>.<ext>` 同名配对查找 |

---

## 渲染阶段（按执行顺序）

### 0. `lens.*` — 镜头光学模拟（最先执行）

| 字段 | 范围 | 含义 | 对应相机概念 |
|---|---|---|---|
| `distortion` | [-1, 1] | 径向畸变（+桶形 / −枕形） | 镜头畸变 |
| `chromatic_aberration` | [0, 1] | R 内缩、B 外扩 | 色散（紫边） |
| `sharpness_falloff` | [0, 1] | 边缘锐度衰减 | 镜头分辨率衰减 |
| `vignette` | [0, 1] | 光学自然暗角（乘性，风格前） | 镜头暗角 |
| `bloom` | [0, 1] | 高光周边柔光 | 镜头光晕 |
| `flare` | [0, 1] | 水平拉丝 + 暖核 | 变形宽银幕眩光 |

### 1. `exposure.bias` — 曝光

- 范围 [-2, 2]，公式 `rgb × 2^bias`
- `0.11` ≈ +0.11 EV，整幅亮度 ×1.079
- 对应：相机曝光补偿

### 2. `white_balance.*` — 白平衡

```python
R *= 1 + temperature_bias * 0.2
B *= 1 - temperature_bias * 0.2     # + = 暖
R *= 1 + tint_bias * 0.1
B *= 1 + tint_bias * 0.1
G *= 1 - tint_bias * 0.1            # + = 洋红，− = 绿
```
范围均 [-1, 1]。对应：相机白平衡色温 / 色调微调。

### 3. `color_matrix.matrix_3x3` — 色彩校正矩阵（CCM）

3×3 矩阵右乘 RGB。非对角为 0 时退化为三通道独立增益（对角线即 R/G/B 增益）。
`input_gamut` / `output_gamut` 声明色彩空间（当前实现未用于转换）。

例：`diag(0.882, 0.921, 1.118)` = 红 −11.8%、绿 −7.9%、蓝 +11.8% → 整体偏青蓝。

### 4. `highlight_rolloff.*` — 高光滚降

| 字段 | 范围 | 含义 |
|---|---|---|
| `threshold` | [0, 1] | 起压点，典型 0.8–0.9 |
| `strength` | [0, 1] | 压缩强度 |
| `saturation` | [0, 2] | 高光饱和度（**CPU 参考未实现，仅 Android 生效**） |

对应：高光滑块 / 曲线肩部。

### 5. `shadow.*` — 阴影与黑场

| 字段 | 范围 | 含义 |
|---|---|---|
| `black_point` | [0, 0.2] | 黑场抬升，`(x − bp) / (1 − bp)`；0.0329 ≈ 黑场定在 8.4/255 |
| `compression` | [0, 1] | 暗部向中间灰 0.5 提亮（灰雾） |
| `tint` | 数组 | 暗部染色（**CPU 参考未实现，仅 Android 生效**） |
| `saturation` | [0, 2] | 暗部饱和度 |
| `contrast` | [0, 2] | 暗部反差（**CPU 参考未实现**；Android → `tonePivot = (v−1)×0.1`） |

对应：黑色色阶 + 阴影滑块。

### 6. `film_curve.*` — 输出黑白场定标

- `shadow_floor` [0, 64]、`highlight_ceiling` [191, 255]，单位是 **0–255 显示层级**（不是 0–1）
- 内部 `lo = floor/255` 钳到 [0, 0.4]，`hi = ceiling/255` 钳到 [0.6, 1]
- 膝点 `kt = lo+0.13`、`kh = hi−0.17`，C1 连续软膝
- 作用：同一 profile 下所有成片共享一个黑点白点，保证一致性
- 对应：黑白场吸管

### 7. `tone_curve.points` — 色调曲线

17 个 `[输入, 输出]` 点对，按 1/16 等距分布，GPU 上烘成 1D LUT 线性采样
（CPU 端 `sample_lut1d_linear` 严格模拟 GL_LINEAR + CLAMP_TO_EDGE 采样，见 `tests/test_shader_equivalence.py`）。

- 直线 `[[0,0],[1,1]]` = 无变化
- S 型 = 加反差；起点抬高（如 0.08）= 褪色胶片
- 对应：曲线工具 / 灰度系数

### 8. `hsl.*` — 分区调色

七个分区：`red`(345°–15°) `orange`(15–45) `yellow`(45–70) `green`(70–160)
`cyan`(160–200) `blue`(200–260) `purple`(260–345)。

- `hue_shift`：**度**（内部 /360）
- `saturation`：乘性系数，1 = 不变
- `lightness`：乘性系数，作用在 V（明度）上，1 = 不变

实现走 HSV：先算 H/S/V，按分区掩码套系数，再重建 RGB。
对应：HSL / 混色器面板。

### 9. `vignette.*` — 风格暗角

`amount`[0,1] 强度、`radius`[0,1] 半径、`feather`[0,1] 羽化、`center` 中心。
对应：后期暗角（区别于 `lens.vignette` 的光学自然衰减）。

### 10. `bloom.*` — 高光扩散

`amount`[0,1]、`threshold`[0,1]、`radius`[0.5,4]。对应：柔焦 / 黑柔滤镜。

### 11. `halation.*` — 胶片光晕

`amount`[0,1]、`threshold`[0,1]、`radius`[0.5,4]、`warmth`[0,2]。
高光向外的暖色溢光（红移）。对应：胶片光晕 / 电影机特性。

### 12. `grain.*` + `noise.*` — 颗粒与噪点

| 字段 | 范围 | 含义 |
|---|---|---|
| `grain.amount` | [0, 1] | 强度，**为 0 时整段跳过，`size`/`density` 失效** |
| `grain.size` | [0.5, 3] | 颗粒粗细（越小越高频） |
| `grain.density` | [0.5, 3] | 密度 |
| `noise.luma` | [0, 1] | 亮度噪点 |
| `noise.chroma` | [0, 1] | 彩色噪点 |

颗粒分布是**光照感知**的（引擎里唯一的自适应项）：暗部密、中间调 0.4 底、强高光轻抬，
参数本身恒定、只变逐像素掩码。对应：胶片颗粒 / ISO 高低。

### 13. `sharpen.*` — 锐化（永远最后）

`amount`[0,1]、`radius`[0.5,3]；`rgb + amount × (rgb − GaussianBlur(rgb))`。
半径按分辨率相对缩放（400px 为基准），保证任意尺寸观感一致。对应：机内锐度设置。

---

## 已知一致性缺口

CPU 参考实现（`profile_renderer.py`）未消费以下三个字段，但 Android 端会读：

| 字段 | Android 侧去向 |
|---|---|
| `shadow.tint` | `ProfileToRecipeMapper.shadowTint` |
| `shadow.contrast` | `tonePivot = (contrast − 1) × 0.1` |
| `highlight_rolloff.saturation` | `GpuParams.highlightSaturation` |

后果：这三个字段在 PC 预览里看不到效果，只有真机出片才有。需要补 CPU 侧实现。

## 3D LUT 导出边界

`_profile_color_lut_cube()` 只烘颜色层（曝光 → 白平衡 → 色彩矩阵 → 色调曲线 → 高光滚降 → 阴影 → HSL）。
grain / noise / vignette / bloom / halation / sharpen 是空间相关效果，3D LUT 无法表达，只能由 App 实时渲染。
