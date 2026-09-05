# Phase 1–13 + 22 交付概览 — 离线 Pipeline 与桌面查看器

**日期**：2026-09-03（续 Phase 0）
**范围**：按需求 JSON 推进 Phase 1–2（数据集+清洗）、Phase 3–13（特征分析）、CPU 参考渲染器 + Phase 22（桌面查看器）。真实成片仍无，全部数据就绪并以合成图验证。

## 新增模块（tools/）

| 文件 | Phase | 职责 |
| --- | --- | --- |
| `dataset_analyzer.py` | 1 | 完整 EXIF 读取（ISO/快门/光圈/焦距/机型/镜头/时间/尺寸/色彩空间/ICC/质量代理），输出 `dataset_metadata.json` + `dataset_manifest.json` |
| `dataset_cleaner.py` | 2 | 重复/近重复(aHash)/低分辨率/重压缩/黑/过曝/欠曝/黑白/色偏(→review)/截图/拼图 检测；输出 accepted/rejected/review（可选拷贝）+ `cleaning_report.json` |
| `style_analyzer.py` | 3–13 | 聚合 RGB/HSV/HSL/Lab/Tone/Exposure/ColorModel/ColorMatrix/HSLMapping/Highlight/Shadow/Lens/Texture 统计与初始模型，输出 13 个 JSON |
| `profile_renderer.py` | 22(前置) | CPU 参考渲染器，按 15 层顺序把 Profile 应用到图像（与 Android 同逻辑，供 PC 验证） |
| `desktop_viewer.py` | 22 | matplotlib 查看器：Original/Simulated/Split、RGB+亮度直方图、Tone Curve、参数滑块、保存 Profile、批量渲染+报告 |

## 依赖
- 新增：opencv-python-headless 5.0、scikit-image、pillow-heif、matplotlib（已装入 `.venv`）。
- `scipy`（scikit-image 传递依赖）用于 Laplacian 局部对比度/噪点。

## 验证（已实跑）
- `tests/run_tests.py` 全绿：覆盖 Phase 0（schema/loader）、Phase 1–2（元数据/清洗分类）、Phase 3–13（13 文件产出）、Phase 22 渲染器（identity 近透传 / 曝光偏置提亮 / 暗角压暗边角）。
- 端到端演示（10 张合成图）：analyzer→cleaner→style_analyzer(13 JSON)→renderer batch 全部跑通；`color_statistics.json` 含真实 Lab/hue 统计。
  - 注：演示中 9/10 被清洗拒为"近重复"，因合成图彼此相近（aHash 近重复检测生效）；真实成片不会出现。

## 关键设计决策与边界
- **初始模型=启发式、保守、可被优化器覆盖**：Color Matrix 用 gray-world 对角；HSL saturation 用相对全局均值；Highlight/Sharpen 等 strength 留 0 待 Phase 16–19 优化。符合规则"不把不稳定特征强行写入 Profile"。
- **JPEG 质量不可还原**：仅给代理指标（bytes/pixel + 估算质量），明确标注。
- **暗角/CA 用几何估计**，其余镜头项留 0 待优化；弱启发式（截图/拼图）仅送 review，不误删。
- **桌面查看器用 matplotlib 而非 PySide**：零额外 GUI 依赖、保证可运行；Phase 22 后续可平滑迁移到 Qt/PySide（推荐栈）。
- **CPU 渲染器与 Android 共用同一层顺序**，是 Preview/Capture 一致的 PC 侧参考实现。

## 下一步
- Phase 14 Profile Schema 完善（以已有 `photographer_profile.schema.json` 为准，补充条件化 `conditions`）。
- Phase 15 AI Profile Generator（结构化 JSON 输出 + 解释报告 + 可复现）。
- Phase 16–19 Optimizer + Loss + Validator（用本阶段统计与 CPU 渲染器做拟合与量化 Loss）。
- Phase 23+ Android Camera2 / GPU Renderer / GLSL。

> 严格按 Phase 顺序：未通过 Profile 验证不得开发 Android UI（AGENTS.md 规则 1）。
