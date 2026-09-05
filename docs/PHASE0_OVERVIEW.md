# Phase 0 交付概览 — 项目规范与工程骨架

**日期**：2026-09-03
**项目**：PhotographerCamera v0.1.0
**范围**：按需求 JSON 的 Phase 0 执行（工程骨架 + 规范文档）。数据集暂无，故 `tools/` 写成数据就绪（data-ready）形态。

## 已交付内容

### 1. 工程骨架（目录）
```
PhotographerCamera/
├── docs/            product_spec / architecture / profile_spec / rendering_pipeline / development_conventions
├── dataset/         accepted / rejected / review（+ .gitkeep 说明）
├── tools/           profile_schema.py（纯 Python 校验器+默认生成器）、dataset_loader.py（数据就绪扫描）
├── profiles/schema/ photographer_profile.schema.json（Phase 0 草案，含类型/范围/默认值/单位）
├── renderer/ shaders/ android/   README 占位（Phase 24/25/23 实现）
├── tests/           test_profile_schema.py / test_dataset_loader.py / run_tests.py
├── AGENTS.md        项目最高约束（强制规则、非目标、Agent 分工、执行顺序）
├── README.md pyproject.toml requirements.txt .gitignore
└── profiles/photographer_a.json  身份 Profile 示例（已通过校验）
```

### 2. 可运行性验证（已实跑通过）
- `python tests/run_tests.py` → ALL PHASE 0 SMOKE TESTS PASS
  - 身份 Profile 校验通过；越界参数被拒；缺失必填被拒
  - 合成 JPG 的 discover / read_image / read_exif / generate_id / build_metadata 全部通过
- `python tools/profile_schema.py default "Photographer A" > profiles/photographer_a.json` → 校验 OK
- `python tools/dataset_loader.py <dir> --out meta.json` → 扫描 2 张示例图，输出 metadata（HEIC supported 因未装 pillow-heif 为 False，预期内）

### 3. 环境
- 管理版 Python 3.13.12 建 `.venv`，安装 numpy + pillow（Phase 0 最小依赖）。
- OpenCV / scikit-image / pillow-heif 列入 `requirements.txt`（Phase 1+ 启用），未强制安装。

## 关键设计决策
- **Schema 单一来源**：`profiles/schema/photographer_profile.schema.json` 为结构权威；`tools/profile_schema.py` 内实现轻量 JSON Schema 子集校验（不依赖 jsonschema），保证无第三方依赖即可跑校验。
- **数据就绪**：`dataset_loader.py` 接任意 JPG/HEIC 目录即可运行；重型依赖（OpenCV/scikit-image）延迟导入，骨架在无数据、无重依赖时仍可导入与测试。
- **约束贯彻**：参数全部从 Profile 注入（不在 GLSL 硬编码）、AI 仅离线、非单一 LUT、Preview/Capture 同逻辑等，已在 AGENTS.md 与 docs 中固化。

## 下一步（待数据集就绪后）
- Phase 1–2：真实成片导入 + 清洗（accepted/rejected/review 三级）
- Phase 3–13：RGB/HSV/HSL/Lab/Tone/Texture 特征分析（需 OpenCV/scikit-image）
- Phase 14–21：Profile Schema 完善、AI 生成、优化器、Loss、验证
- Phase 22：桌面查看器（Qt/PySide）
- Phase 23+：Android Camera2 + GPU Renderer + GLSL

> 严格按 Phase 顺序，未通过 Profile 验证不得开发 Android UI（AGENTS.md 规则 1）。
