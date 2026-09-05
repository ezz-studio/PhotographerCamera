# Profile 规范 — Profile Spec

## 1. 设计原则

PhotographerProfile 是一个**纯数据 JSON**，描述一位摄影师的多层成像参数。Android 渲染代码只读它，新增摄影师只新增 JSON。

- 每个参数具备：`type`、`unit`、`range [min, max]`、`default`、语义说明。
- 参数不硬编码进 Shader；Shader 通过 Uniform / Texture / UBO 接收。
- 使用语义化版本 `version`（整数，从 1 起）+ `schema_version`。

## 2. 顶层结构

```json
{
  "version": 1,
  "schema_version": 1,
  "name": "Photographer A",
  "author": "...",
  "description": "...",
  "created_at": "2026-09-03",
  "validation_status": "pending",
  "exposure":        { "bias": 0.0 },
  "white_balance":   { "temperature_bias": 0.0, "tint_bias": 0.0 },
  "color_matrix":    { "matrix_3x3": [[1,0,0],[0,1,0],[0,0,1]], "input_gamut": "sRGB", "output_gamut": "sRGB" },
  "hsl":             { "red":{}, "orange":{}, "yellow":{}, "green":{}, "cyan":{}, "blue":{}, "purple":{} },
  "tone_curve":      { "points": [ [0.0,0.0], [1.0,1.0] ] },
  "highlight_rolloff": { "threshold": 0.8, "strength": 0.0, "saturation": 1.0 },
  "shadow":          { "black_point": 0.0, "compression": 0.0, "tint": [0,0,0], "saturation": 1.0, "contrast": 1.0 },
  "lens":            { "vignette": 0.0, "chromatic_aberration": 0.0, "sharpness_falloff": 0.0, "distortion": 0.0, "bloom": 0.0, "flare": 0.0 },
  "grain":           { "amount": 0.0, "size": 1.0, "density": 1.0 },
  "noise":           { "luma": 0.0, "chroma": 0.0 },
  "halation":        { "amount": 0.0, "radius": 1.0, "threshold": 0.9, "warmth": 1.0 },
  "bloom":           { "amount": 0.0, "radius": 1.0, "threshold": 0.9 },
  "vignette":        { "amount": 0.0, "radius": 1.0, "feather": 0.5, "center": [0.5,0.5] },
  "sharpen":         { "amount": 0.0, "radius": 1.0 }
}
```

## 3. 各层参数定义（类型 / 单位 / 范围 / 默认）

| 层 | 参数 | 类型 | 单位 | 范围 | 默认 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| exposure | bias | float | EV | [-2, 2] | 0.0 | 整体曝光偏置（无 RAW，基于成片亮度估计） |
| white_balance | temperature_bias | float | 黄↔蓝 | [-1, 1] | 0.0 | 色温偏置（正=暖） |
| white_balance | tint_bias | float | 绿↔品 | [-1, 1] | 0.0 | 色调偏置（正=品红） |
| color_matrix | matrix_3x3 | 3×3 float | 线性 | 元素 [-2,2] | 单位阵 | 全局色彩响应矩阵，需做色域/爆炸约束 |
| hsl.* | hue_shift | float | 度 | [-30,30] | 0 | 分色相 Hue 偏移，避免全局大偏移 |
| hsl.* | saturation | float | × | [0,2] | 1.0 | 分色相饱和度系数 |
| hsl.* | lightness | float | × | [0,2] | 1.0 | 分色相亮度系数 |
| tone_curve | points | [[x,y]…] | 归一化 | x,y∈[0,1] | 单位线 | 分段/样条曲线，不少于 2 点 |
| highlight_rolloff | threshold | float | 归一化 | [0,1] | 0.8 | 高光起始阈值 |
| highlight_rolloff | strength | float | 0–1 | [0,1] | 0.0 | 高光压缩强度 |
| highlight_rolloff | saturation | float | × | [0,2] | 1.0 | 高光饱和保持 |
| shadow | black_point | float | 归一化 | [0,0.2] | 0.0 | 黑位 |
| shadow | compression | float | 0–1 | [0,1] | 0.0 | 阴影压缩 |
| shadow | tint | [r,g,b] | 归一化 | [-1,1] | [0,0,0] | 阴影色偏 |
| shadow | saturation | float | × | [0,2] | 1.0 | 阴影饱和度 |
| shadow | contrast | float | × | [0,2] | 1.0 | 阴影对比 |
| lens | vignette | float | 0–1 | [0,1] | 0.0 | 暗角量 |
| lens | chromatic_aberration | float | 0–1 | [0,1] | 0.0 | 色差 |
| lens | sharpness_falloff | float | 0–1 | [0,1] | 0.0 | 边缘锐度衰减 |
| lens | distortion | float | -1–1 | [-1,1] | 0.0 | 镜头畸变 |
| lens | bloom | float | 0–1 | [0,1] | 0.0 | 镜头泛光（光学） |
| lens | flare | float | 0–1 | [0,1] | 0.0 | 光斑 |
| grain | amount | float | 0–1 | [0,1] | 0.0 | 颗粒强度 |
| grain | size | float | × | [0.5,3] | 1.0 | 颗粒尺寸 |
| grain | density | float | × | [0.5,3] | 1.0 | 颗粒密度 |
| noise | luma | float | 0–1 | [0,1] | 0.0 | 亮度噪点（JPEG 伪影需区分） |
| noise | chroma | float | 0–1 | [0,1] | 0.0 | 色度噪点 |
| halation | amount | float | 0–1 | [0,1] | 0.0 | 光晕强度 |
| halation | radius | float | × | [0.5,4] | 1.0 | 扩散半径 |
| halation | threshold | float | 归一化 | [0,1] | 0.9 | 高光提取阈值 |
| halation | warmth | float | × | [0,2] | 1.0 | 暖/红通道权重 |
| bloom | amount | float | 0–1 | [0,1] | 0.0 | 泛光强度 |
| bloom | radius | float | × | [0.5,4] | 1.0 | 扩散半径 |
| bloom | threshold | float | 归一化 | [0,1] | 0.9 | 亮部阈值 |
| vignette | amount | float | 0–1 | [0,1] | 0.0 | 暗角量 |
| vignette | radius | float | 归一化 | [0,1] | 1.0 | 半径 |
| vignette | feather | float | 0–1 | [0,1] | 0.5 | 羽化 |
| vignette | center | [x,y] | 归一化 | [0,1] | [0.5,0.5] | 中心 |
| sharpen | amount | float | 0–1 | [0,1] | 0.0 | 锐化量 |
| sharpen | radius | float | × | [0.5,3] | 1.0 | 半径 |

> 注释：Phase 0 仅给出**初始 Schema 草案**（`profiles/schema/photographer_profile.schema.json`）。随 Phase 17 完善，所有参数的类型/范围/默认值/单位以 Schema 为权威，并与 `tools/profile_schema.py` 的校验注册表保持同步。

## 4. 版本与兼容

- `version`：该摄影师 Profile 的迭代版本（整数）。
- `schema_version`：Profile 结构版本（整数），Loader 据此做前向/后向兼容。
- 新增参数必须带默认值，删除/重命名参数需升 `schema_version` 并在 Loader 做迁移。

## 5. 导入 / 导出 / 校验

- 离线导出为单个 `.json`；Android 打包进 `assets/profiles/`。
- 每次加载须经 Schema 校验 + 参数范围校验；非法参数拒绝并报告。
- 校验器见 `tools/profile_schema.py`（纯 Python，无第三方依赖即可运行）。

## 6. 条件化 Profile（Phase 31，后续）

同一摄影师可携带 `conditions`（Daylight / Cloudy / Tungsten / Low Light / Flash / Backlight）子参数集，按输入画面亮度/色温选择，保持核心风格不变。本 Phase 0 Schema 预留 `conditions` 字段（可选）。
