# PhotographerCamera

将摄影师的大量最终成片（JPG/HEIC，无 RAW）分析为可执行的 **Photographer Camera Profile**，并在 Android 相机 App 中用传统图像处理算法 + GPU Shader 实时模拟该摄影师的成像风格。

本项目**不是 LUT Filter App**，而是 **Photographer Camera Simulation System**：每个摄影师由 Exposure / White Balance / Color Matrix / HSL Mapping / Tone Curve / Highlight Roll-off / Shadow Response / Lens / Grain / Noise / Halation / Bloom / Vignette / Sharpen 等多个成像层共同定义。

## 关键约束

- 输入数据没有 RAW，主要使用摄影师最终 JPG/HEIC 成片。
- Android App 不运行 AI 模型；AI 仅用于 PC/服务器端离线分析、生成与优化 Profile。
- 不得把摄影师风格实现为单一 LUT；不得使用生成式 AI 重绘用户照片。
- 摄影师 Profile 必须可独立导入、导出与版本管理。
- 新增摄影师原则上不修改 Android 渲染代码，只新增 Profile。

## 仓库结构

| 目录 | 职责 |
| --- | --- |
| `docs/` | 产品规格、架构、Profile 规范、渲染管线、开发规范 |
| `dataset/` | `accepted/`、`rejected/`、`review/` 三级清洗结果 + 元数据 |
| `tools/` | PC 离线 Profile Builder：数据集读取/清洗、特征分析、AI 生成、优化、校验 |
| `profiles/` | Profile JSON 与 JSON Schema（`profiles/schema/`） |
| `renderer/` | GPU 渲染管线（Pass 管理、FBO）概念与接口 |
| `shaders/` | GLSL Shader 模块（每个成像层一个） |
| `android/` | Android Camera2/CameraX + GPU Renderer + UI |
| `tests/` | 冒烟测试与回归测试 |

## 阶段状态

| Phase | 名称 | 状态 |
| --- | --- | --- |
| 0 | 项目规范与工程骨架 | ✅ 进行中 |
| 1–22 | PC 离线 Pipeline（数据集→特征→Profile→优化→校验→桌面查看器） | ⏳ 待数据就绪 |
| 23–33 | Android Camera Engine / GPU Renderer / Shaders / 性能 / QA / Release | ⏳ 未开始 |

严格按 Phase 顺序执行，不允许跳过 Profile 验证直接开发 Android UI。详见 `AGENTS.md`。

## 快速开始（Phase 0 骨架）

```bash
# 使用管理版 Python 建 venv
python -m venv .venv
.venv/Scripts/activate      # Windows
pip install -r requirements.txt

# 运行冒烟测试，验证骨架可运行
python tests/run_tests.py

# 生成身份 Profile 样例
python tools/profile_schema.py default "Photographer A" > profiles/photographer_a.json
python tools/profile_schema.py validate profiles/photographer_a.json
```

数据集就绪后：

```bash
python tools/dataset_loader.py scan <dataset_root> --out dataset/dataset_metadata.json
```
