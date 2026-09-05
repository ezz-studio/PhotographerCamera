# 开发规范 — Development Conventions

## 1. 语言与目录约定

- **Python（Profile Builder / 工具 / 桌面查看器）**：`tools/` 下按职责分子模块；包名小写蛇形。
- **Kotlin（Android）**：`android/` 下按 feature 分包；类名 PascalCase。
- **GLSL（Shader）**：`shaders/` 下每个成像层一个 `.glsl`，文件名即层名。

## 2. Python 代码风格

- 遵循 PEP 8；行宽 100；用 `black` + `isort` 格式化，`flake8` 做 lint。
- 类型注解必写（函数签名 + 关键变量）。
- 数值计算统一用 NumPy；图像用 `float32` 归一化 [0,1] 或 `uint8` 明确标注。
- 所有外部重依赖（OpenCV / scikit-image）**延迟导入**，模块在仅做 Schema 校验等轻量场景时不应因缺包而整体不可用。
- 不把第三方二进制/模型提交进仓库；大文件走 `.gitignore` 或 LFS。

## 3. Kotlin / Android 风格

- 遵循官方 Kotlin 规范；用 `ktlint` / `detekt`。
- Camera 相关只依赖 Camera2（CameraX 仅高层封装），禁止引入 AI / ML 运行时。
- Shader 字符串不内联硬编码摄影师参数；统一从 Profile 注入。

## 4. GLSL 风格

- 每个 Shader 文件顶部注释写明：所属成像层、输入（texture/uniform）、输出、参数范围约束。
- 禁止在 Shader 内写死摄影师数值；参数全部经 uniform/ubo 传入。
- 浮点精度：移动端默认 `mediump`，关键累加用 `highp`。

## 5. 测试规范（强制）

1. **每个图像处理模块必须可单独测试**（规则 3）。
2. Python 模块：`tests/` 下 `test_<module>.py`，可用 `pytest` 或 `python tests/run_tests.py` 运行；纯逻辑（Schema 校验等）不得依赖第三方即可运行。
3. Shader：每个 `.glsl` 需有单元测试（参数边界）+ 视觉回归测试（固定输入图对比像素/指标）。
4. Profile 必须有 **Regression Test**：对固定测试集，参数变更后核心指标（Color/Tone/Texture 误差）不得劣化超阈值。
5. 数据集划分：训练 70% / 验证 15% / 测试 15%；**测试集完全不参与参数调整**。
6. 所有优化必须记录 Loss 曲线（Phase 20）。

## 6. 提交与阶段门禁

- 严格按 Phase 顺序；未完成上一 Phase deliverables 不得进入下一 Phase（尤其：Profile 验证未过不得开发 Android UI）。
- 每个 Phase 结束输出其 deliverables 文件，并在 PR/变更说明中列出。
- `AGENTS.md` 为本项目最高约束，任何冲突以它为准。

## 7. 依赖管理

- Python：`requirements.txt`（运行时）+ 开发依赖分组；使用隔离 venv，不污染用户环境。
- Android：`build.gradle` 依赖最小化，禁止 AI/ML 相关依赖。
- 所有依赖注明用途与优先级（P0/P1/P2），参考 `architecture.md` 开源参考表。
