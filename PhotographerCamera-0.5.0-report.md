# PhotographerCamera 0.5.0 版本报告 — PhotonCamera 技术底座重构

> 日期：2026-09-06
> 上游底座：https://github.com/bjzhou/PhotonCamera（Apache-2.0，包名 com.hinnka.mycamera）
> 方案文档：docs/REFACTOR_050_photon_integration.md

## 1. 本轮目标与结果

按用户决策：保留我方 UI/动效/Profile 体系**完全不变**，把采集与处理内核替换为 PhotonCamera 的成熟管线——
多帧 YUV 对齐堆栈降噪 + 分层调色引擎，通过 ProfileToRecipeMapper 接入我方摄影师 Profile，
实现"高光/阴影/中间调/曲线/色彩分层控制"的摄影师风格复刻（不是套 LUT）。

**结果：0.5.0 APK 构建通过（assembleDebug，Kotlin 编译零错误）。**

## 2. 新管线拓扑（成片主通道）

```
CameraEngine（UI/动效不变）
  └─ 多帧连拍：6 张全分辨率 YUV_420_888 顺序采集（pc_burst.txt 可调 2-12，
     pc_burst_off.txt 后门；闪光/RAW 模式自动回退单帧链）
       └─ [移植] PhotonMultiFrameStacker → GlesYuvStacker（4244 行）
            帧间对齐 + 时域合并 + MGC 空间降噪（独立 EGL context，后台线程）
       └─ [移植] LutImageProcessor.applyLut（3743 行，独立 EGL context）
            ColorRecipe 全参数链：曝光/对比/饱和/色温色调/高光/阴影/
            toe-shoulder-pivot 中间调塑形/9 区 LCH/三分区 grading/
            主+RGB 曲线/颗粒/晕影/泛光/HDF 光晕/色散/锐化
            ← ProfileToRecipeMapper（自研）：PhotographerProfile → ColorRecipeParams
       └─ [我方保留] ProfileRenderer.renderPhotonChain（GL 线程）
            FilmCurve 输出一致性终层（film_curve.frag，与其它路径同源数学）
       └─ JPEG/EXIF → CaptureSaver（不变）
```

## 3. 移植清单（零算法改动，仅包名/解耦点适配）

- **core/photon/stack/**（14 文件）：GlesYuvStacker、GlesYuvSpatialShaders、GlesMgcRawSpatialShaders、
  GlesGpuScheduler、MgcSpatialMergeTuning、MgcSpatialNoiseEstimatesLut、MgcSpatialRejectionGeometry、
  MgcSabreRejectionTuning、MgcSpatialOutputMode(随编排)、RawStackRuntimeDebug、SystemPropertiesUtil、
  LargeDirectBuffer、DirectBufferAllocator、PLog、BitmapUtils + 自研 PhotonMultiFrameStacker/
  SafeImage(解耦 Camera2Controller)/AspectRatio(精简)
- **core/photon/color/**（30 文件）：LutImageProcessor、Shaders、ColorRecipeParams、ColorRecipeGl、
  PreviewColorShaderModules、ShadowsHighlightsShader、ThreeWayColorGradingShader、DirectFlashShader、
  BasicToneLut+Shader+GlTextures、SrgbSharpnessShader、ClarityShaders、FilmGrainGl+Shaders、
  CurveUtils、CameraRawCalibrationMatrix、ChromaDenoise*、DenoiseProfile*、RawFullscreenQuad、
  RawGlesProgram、GlUtils、LutConfig、LutShaderMappings、TransferCurve、ColorSpace、
  ColorPaletteMapper/State、BloomLdrSettings、LutRenderLayer(摘出)+ 自研 ProfileToRecipeMapper
- **胶水/接线**（自研）：PhotonStackPipeline、GpuParams.sourceProfile 字段、
  CameraEngine.shootYuvBurst（多帧采集）、StillFrame.Stack、
  CameraScreen/CameraPreviewView 接线（签名级改动，UI/动效零变化）、ProfileRenderer.renderPhotonChain + progFilmCurve

## 4. 关键工程决定

1. **minSdk 保持 26**：GLES3.1 运行时探测（GlesYuvStacker 自带检查），不支持自动回退 0.4.0 单帧链。
2. **不整体移植 Camera2Controller（8798 行）**：与我方 UI/状态机深度耦合，破坏 UI 冻结原则；
   多帧采集用顺序 takePicture 实现（HAL AE 连续收敛，静态场景曝光漂移可忽略）。
3. **双 EGL context 隔离**：堆栈器与调色器各自持有 context，在后台线程运行，
   预览 GL 线程只在 FilmCurve 终层短暂介入。
4. **回退链完整**：多帧任意失败 → 单帧 YUV 链 → JPEG → 预览帧，逐级降级并 RemoteLog。
5. **版本**：0.5.0 (versionCode 18)；UI/动效零改动；0.4.0 更新系统保留。

## 5. 遗留事项

- **桌面端同步**（用户许可的"微小改动"）：tools/profile_renderer.py 当前仍渲染旧 effect 链数学；
  Profile→Recipe 的换算语义已固化在 ProfileToRecipeMapper.kt 注释与 docs/REFACTOR_050 文档，
  待 0.5.0 实机验证通过后按同一映射表同步 Python 端（保证桌面预览=成片）。
- **AE 漂移**：顺序连拍帧间曝光由 HAL 各自收敛，极端场景可能引入轻微亮度阶梯；
  后续可换 Camera2 burst + AE lock（预留 pc_burst.txt 调参通道观察）。
- **HDR 包围曝**：processHdrBurstYuv 已随移植保留（Mertens 融合），待采集端产出包围曝帧后启用。
- **MGC RAW 空间堆栈 / native 降噪**（cpp/mgc_denoise_static、LibRaw）：本轮未移植，
  RAW 实验性功能冻结状态不变。

## 6. 构建产物

- `android/app/build/outputs/apk/debug/PhotographerCamera-0.5.0.apk`（debug 签名，实机验证用）
