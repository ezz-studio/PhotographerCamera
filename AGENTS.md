# AGENTS.md — PhotographerCamera 开发规则

> 本文件是项目的"宪法"。任何 Agent（主开发 Agent、子 Agent、协作者）在进入本项目时**必须**先读取并严格遵守。

## 0. 根指令（Root Instruction）

你是 PhotographerCamera 项目的主开发 Agent。严格按照 `phases` 与 `agent_execution_order` 执行：
- 不要跳阶段；
- 不要为了快速完成而把摄影师风格简化成单一 LUT；
- 先完成 PC 端 `Dataset → Style Features → Profile → Optimizer → Validator`，再开发 Android `Camera → GPU Renderer`；
- AI 只存在于离线 Profile Builder，绝不进入 Android App；
- 每完成一个 Phase，运行测试并输出该 Phase 的 deliverables，再进入下一 Phase。

## 1. 项目定义

- **目标**：把摄影师最终成片（JPG/HEIC，无 RAW）分析为可执行的 Photographer Camera Profile，在 Android 端用传统图像处理 + GPU Shader 实时模拟其成像风格。
- **核心定义**：本项目是 Photographer Camera Simulation System。一个摄影师 = 一个可执行 Camera Profile（多成像层），而不是一个 LUT。

## 2. 强制规则（Mandatory）

1. 严格按 Phase 顺序执行，不允许跳过 Profile 验证直接开发 Android UI。
2. 先建立 PC 离线 Profile Pipeline，再迁移到 Android GPU Pipeline。
3. 每一个图像处理模块必须可以单独测试。
4. 每一个 Profile 参数必须有明确的定义、单位、范围和默认值。
5. Profile 参数不能硬编码在 GLSL Shader 中。
6. Android Shader 通过 Uniform / Texture / UBO / 参数缓冲等方式接收 Profile 参数。
7. 预览 Pipeline 与最终 Capture Pipeline 必须保持相同的核心成像逻辑。
8. 任何无法从数据稳定推断出的特征不得强行写入 Profile。
9. 必须区分摄影师稳定风格和单张照片的特殊后期效果。
10. Profile 必须使用独立验证集进行验证。
11. 所有参数优化都必须有可量化的 Loss 或评价指标。
12. Android 端禁止依赖 Python、PyTorch、LLM 或其他 AI Runtime。

## 3. 非目标（Non-Goals，第一阶段）

- 不开发社交功能、云同步、账号系统、商城；
- 不开发 App 内 AI；
- 不追求完整 Lightroom 功能；
- 不使用 Stable Diffusion 等生成式模型直接重绘照片。

## 4. Agent 角色分工

| Agent | 职责 |
| --- | --- |
| DatasetAgent | dataset、EXIF、cleaning、duplicate detection、quality control、metadata |
| StyleResearchAgent | RGB、HSV、HSL、Lab、Histogram、Tone、Color、Highlight、Shadow、Texture |
| ProfileAgent | Profile Schema、AI Profile Generator、Profile serialization、parameter constraints |
| OptimizationAgent | Profile fitting、Loss function、parameter optimization、validation、regression testing |
| ShaderAgent | OpenGL ES、GLSL、Texture Pipeline、Color/Tone/HSL/Grain/Noise/Bloom/Halation/Vignette Shader |
| AndroidCameraAgent | Camera2、CameraX 集成、Preview、Capture、Focus、Exposure、Zoom、Flash、Profile switching |
| QAAgent | visual QA、performance QA、device compatibility、profile regression、export validation |

## 5. 技术栈

- **Profile Builder**：Python（OpenCV、NumPy、Pillow、scikit-image）；AI 可选（PyTorch / LLM API / local LLM），仅离线。
- **Desktop Viewer**：Python + Qt/PySide + OpenCV + NumPy。
- **Android**：Kotlin，Camera2（CameraX 可选高层封装），Jetpack Compose，GPU 用 OpenGL ES 3.0 + GLSL；Vulkan 留作后续性能路线。

## 6. 数据流

### 离线（PC / Server）
摄影师成片 → EXIF/图像数据读取 → 数据清洗 → RGB/HSV/HSL/Lab/Tone/Texture 特征分析 → AI 据统计特征生成初始 Profile → 传统参数优化器拟合 → 独立测试集验证 → 导出 PhotographerProfile。

### Android 运行时
Camera2/CameraX 取帧 → 转 GPU Texture → 加载 PhotographerProfile → Exposure → White Balance → Color Matrix → Tone Curve → Highlight Roll-off → Shadow Response → HSL Mapping → Sharpen → Bloom → Halation → Grain → Noise → Vignette → 显示 Preview → 高分辨率 Capture → JPEG/HEIF 导出。

## 7. 执行顺序（agent_execution_order）

`01_ProjectSkeleton` → `02_DatasetAnalyzer` → `03_DatasetCleaner` → `04_RGBAnalyzer` → `05_HSV_HSL_Analyzer` → `06_LabAnalyzer` → `07_ToneAnalyzer` → `08_ExposureAnalyzer` → `09_ColorAnalyzer` → `10_HighlightAnalyzer` → `11_ShadowAnalyzer` → `12_TextureAnalyzer` → `13_StyleFeatureJSON` → `14_CameraProfileSchema` → `15_AIProfileGenerator` → `16_ProfileOptimizer` → `17_LossFunction` → `18_ProfileValidator` → `19_DesktopProfileViewer` → `20_ExposureModel` … `34_AndroidCamera2` … `55_ReleaseBuild`。

完整 55 步见需求 JSON 的 `agent_execution_order`。

## 8. 最终交付标准（Definition of Done，摘要）

- Profile Builder 可导入 100–1000+ 张 JPG/HEIC，自动分析 EXIF，完成清洗，生成多维度统计，生成并优化 Profile，用独立 Test Set 验证，导出 JSON Profile。
- Desktop Viewer 可加载任意图片/Profile，实时应用，Before/After，直方图，调参并保存。
- Android App 可调用 Camera2/CameraX，实时 Preview，加载并切换 Profile，GPU Shader 实时渲染，高分辨率拍摄，导出 JPEG/HEIF，且不依赖 AI Runtime。
- 质量：Profile 在未见过的 Test Set 上仍稳定；不依赖单一 LUT；Preview 与成片视觉基本一致；新增摄影师只加 Profile 不改核心渲染代码。
