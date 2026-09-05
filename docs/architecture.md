# 系统架构 — Architecture

## 1. 总览

```
摄影师 JPG/HEIC dataset
        │
        ▼
 Dataset Analyzer ──► Dataset Cleaner ──► Style Feature Engine
        │                                      │
        │                                      ▼
        │                           AI Profile Generator
        │                                      │
        │                                      ▼
        │                           Profile Optimizer ──► Loss Function
        │                                      │
        │                                      ▼
        │                           Profile Validator (独立 Test Set)
        │                                      │
        │                                      ▼
        │                           PhotographerProfile JSON (.json + schema)
        │                                      │
        └────────── import/export ────────────┘
                                               │
                                               ▼  (打包进 android/assets/profiles)
 Android Camera2/CameraX ──► GPU Rendering Pipeline ──► JPEG/HEIF output
```

## 2. 离线数据流（PC / Server）

1. 摄影师成片
2. EXIF / 图像数据读取（尺寸、色彩空间、ICC、压缩质量、ISO/快门/光圈/焦距/机型/镜头/时间）
3. 数据清洗（去重、相似、低分辨率、压缩、过曝/欠曝、黑白、异常色偏、截图、拼图）
4. RGB / HSV / HSL / Lab / Tone / Texture 特征分析
5. AI 根据统计特征生成初始 Profile（结构化 JSON，参数受范围约束）
6. 传统参数优化器拟合 Profile（可微/非可微，依据 Loss）
7. 独立测试集验证（训练 70% / 验证 15% / 测试 15%）
8. 导出 PhotographerProfile

> 原则：AI 输出不可全信，必须用程序化图像模拟 + 目标数据拟合做二次优化（见 Phase 19）。

## 3. Android 运行时数据流

Camera2/CameraX 取帧 → Camera Frame 转 GPU Texture → 加载 PhotographerProfile → 逐层：Exposure → White Balance → Color Matrix → Tone Curve → Highlight Roll-off → Shadow Response → HSL Mapping → Sharpen → Bloom → Halation → Grain → Noise → Vignette → 显示 Preview → 高分辨率 Capture → JPEG/HEIF Export。

## 4. 模块边界

| 边界 | 规则 |
| --- | --- |
| 离线 / 在线 | AI 与重计算只在离线；Android 仅做 Camera、解析、传统处理、Shader |
| 参数 / 代码 | Profile 参数不进 GLSL；Shader 经 Uniform/Texture/UBO 接收 |
| Preview / Capture | 同一核心成像逻辑，保证视觉一致 |
| 稳定风格 / 单张特效 | 跨大量照片稳定的特征才进 Profile；单张特殊后期不进 |

## 5. Agent 与模块映射

| Agent | 主要对应 Phase |
| --- | --- |
| DatasetAgent | 1 数据集、2 清洗、Phase 1/2 |
| StyleResearchAgent | 3–13 统计/色彩/色调/曝光/纹理分析 |
| ProfileAgent | 14 Schema、15 AI 生成、序列化、参数约束 |
| OptimizationAgent | 16 优化、17 Loss、18 校验、回归 |
| ShaderAgent | 25 GLSL 模块、24/33 GPU Renderer |
| AndroidCameraAgent | 23 Camera Engine、26 Loader、27/28 Preview/Capture |
| QAAgent | 29 性能、32 QA、33 Release、回归 |

## 6. 开源参考（按优先级）

- **P0**：Jetpack Camera App、Android Camera Samples、android-gpuimage、Filmulator、RawTherapee
- **P1**：GPUImage(iOS)、darktable、OpenColorIO
- **P2**：Camera2Raw、LibRaw

参考仅用于研究与架构借鉴，不要求第一版直接依赖 RAW 相关库（无 RAW 输入）。

## 7. 计算摄影相机开源项目调研与吸收（2026-09-05）

依据《开源计算摄影相机项目调研与开发路线》：目标是 **RAW Computational Camera + Data-driven
Camera Recipe Engine**，不是"套 LUT 滤镜相机"。五大推荐仓库逐一研读，吸收点与落地状态如下。

### 7.1 目标架构 → 本项目模块映射

| 调研文档模块 | 本项目对应 | 状态 |
| --- | --- | --- |
| camera/ Camera2Controller + LensController | `core/camera/CameraEngine.kt`（逻辑相机 + CONTROL_ZOOM_RATIO） | ✅ 已落地 |
| camera/ RAWCapture | `CameraEngine` still reader（JPEG 现行；RAW/YUV 代码保留待标定） | ✅/🔜 |
| raw/ RawParser·BayerProcessor·Demosaic | `core/camera/RawImageConverter.kt`、`ImageYuvConverter.kt` | 🔜 待真机标定 |
| isp/ Exposure·WB·ToneCurve·HighlightRecovery | PC 端 profile 生成 + GPU `mainchain`（tone/curve/highlight/shadow） | ✅ |
| color/ HSL·LUT3D·Vibrance | GPU `mainchain` HSL + `tone/hsl LUT`（三方可拍一致性） | ✅ |
| effects/ Bloom·Halation·Grain·Vignette | GPU `bloom`/`halation`/`grain`/`vignette`（u_vwin 窗口化） | ✅ |
| render/ GPUBackend·RenderGraph·Preview/CaptureRenderer | `core/gpu/ProfileRenderer.kt`（15 Pass 链，预览/成片共用） | ✅ |
| recipe/ CameraRecipe·Parser·Validator | `core/profile/`（Profile JSON + schema + Validation.kt） | ✅ |
| ai/ StyleAnalyzer·RecipeGenerator | PC 端 Studio（dataset → profile 拟合管线） | ✅ |
| output/ JPEGEncoder·EXIF | `core/storage/CaptureSaver.kt`（MediaStore）+ JPEG EXIF 直立 | ✅ |

### 7.2 各仓库吸收点（研读结论 → 已落地/路线）

**MotionCam**（f0enix/motioncam — RAW/Camera2/GPU 底座）
- 「GPU 简化管线预览 = 成片准确预览」原则 → 预览与成片共用同一 GLSL 链（已落地）。
- 欠曝单帧高光恢复、exposure fusion tonemapping、光流多帧降噪 → 第四阶段路线（多帧 RAW/HDR）。
- Halide 代码生成思路 → 记录；CPU 参考（tools/glsl_reference.py）与 GPU 对拍已覆盖等价性。

**PhotonCamera**（bjzhou/PhotonCamera — 算法/色彩 Recipe）
- 「风格是一整套参数而非一个 LUT」→ Profile JSON 已含 tone/curve/HSL/LUT/grain/bloom/halation/vignette（已落地）。
- 深度 Recipe 参数（fade、bleach bypass、dispersion）→ recipe 扩展候选（路线）。
- **Phantom 模式**：系统相机成片 + 自家 LUT 引擎，绕开第三方 API 画质问题 → 画质兜底备选路线。

**DAZZ Retro Camera**（ganjmeng/dazz-retro-camera — JSON Camera Definition + GPU Pass）
- 20 Pass 严格光学顺序（锐化→色差→WB→黑白场→高光/阴影→对比→饱和→Bloom→Halation→高光滚落→曲线→颗粒→暗角）→ 与本项目链序对照校准（已落地）。
- **previewPolicy / exportPolicy**：预览降级省帧率、成片全质量 → 60fps 优化（uniform 缓存/LUT 按需上传）体现该原则；成片专属 Pass 为路线。
- 防特效双重叠加（`!gpuProcessed` 守卫）→ 暗角只作用于取景框（u_vwin）+ 成片单一管线，无叠加路径。
- GPU 失败自动降级 → 成片渐进降采样重试（已落地）。

**ZoomBox Camera**（Indukto/zoom-box — MVP/UX，与本项目最直接同构）
- **Zoom Box 取景框**：框随数字变焦缩放，框内即成片 → **产品需求保留项**。落地为：预览请求钉在
  广角端（`previewZoom`），拍照请求单独应用 `CONTROL_ZOOM_RATIO`（同会话、无重建、无切镜），
  内嵌框 f = previewZoom/zoom 随焦段缩放（弹簧动效已优化）。
- JSON look profile（assets 资产化，"asset change, not code change"）→ ProfileLoader 已是此模式。
- 单一参数快照驱动多渲染后端 + GPU 失败回退 CPU → GpuParams + 渐进降级（已落地）。
- 程序化胶片瑕疵（灰尘/划痕/漏光，免贴图）、highlight roll-off（filmic shoulder）→ 成片专属 Pass 路线。
- RAW(DNG) 与 JPEG 并行采集 → 第二阶段路线。

**CinemaCamera**（OpenMotionFX/OpenCamera — RAW/GPU 底层）
- 传感器自动探测（含隐藏物理 ID，剔除 depth-only）→ refreshLenses 已实现同款评分选择（已落地）。
- GLES3 GPU debayer、CinemaDNG → 第四阶段路线。
- 真机验证文化（每次提交带实测数据）→ DebugLog（Downloads 全链路打点：LENS/SESSION/SHOT/SAVE/CRASH）已落地。

### 7.3 变焦架构决策记录（2026-09-05）

- **逻辑相机 + 原生变焦**：只 open 逻辑多相机（或最优单相机），绝不自管物理镜头切换
  （切镜 = 会话重建 = 真机黑预览/拍摄慢的根因）。
- **Zoom Box 语义**：预览钉广角（`CONTROL_ZOOM_RATIO = zoomRange.lower`），拍照请求按用户变焦
  单次下发（HAL 内部切换物理镜头 + 裁切），内嵌取景框 `f = previewZoom / zoom` 纯数据推导。
- **坐标系**（AOSP 文档确认）：zoomRatio 生效时 AE/AF 区域坐标系 = 变焦后视野映射到整个
  activeArray —— 预览钉广角时点击坐标直接 × activeArray；zoom≠1 时禁 FREEFORM cropRegion。
- 成片 = ISP JPEG（硬件 demosaic/WB，快且全尺寸正确）+ 同一 GPU 风格链；自研 RAW/YUV 转换
  在真机产生绿噪点（逐机型标定缺失），代码保留待第四阶段按 MotionCam 思路重做。

