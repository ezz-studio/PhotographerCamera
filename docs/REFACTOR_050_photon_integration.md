# 0.5.0 重构方案 — 基于 PhotonCamera 技术底座

> 上游：https://github.com/bjzhou/PhotonCamera（本地克隆 `../photon-camera`，包名 `com.hinnka.mycamera`，Apache-2.0）
> 目标：保留我方 UI/动效/Profile 体系不变，用 PhotonCamera 的管线技术替换采集与处理内核。

## 1. 勘察结论

### 我方现状（PhotographerCamera）
- 采集：CameraX，`CameraEngine.shootYuvCapture()` 全分辨率 YUV_420_888 直采（单帧）；analysis 流 1.6MP 仅降级用。
- 渲染：`ProfileRenderer` 两 pass 架构（OES→2D 拷贝 + effect 链），YUV/RAW 链走 `GpuFilterChain` ping-pong；Profile 参数经 `GpuParams.kt` 注入 uniform。
- Profile：`core/profile/Profile.kt`（schema 驱动，198 行模型 + JSON schema），含 exposure/wb/color_matrix/hsl/tone_curve/highlight_rolloff/shadow/lens/grain/noise/halation/bloom/vignette/sharpen/film_curve。
- UI 冻结区：`app/MainActivity.kt`、`ui/CameraPreviewView.kt`（接缝签名不变）。

### PhotonCamera 可复用件（已核实 import 闭包）
| 模块 | 文件 | 规模 | 依赖闭包 | 难度 |
| --- | --- | --- | --- | --- |
| YUV 多帧对齐+堆栈+降噪 | `processor/GlesYuvStacker.kt` | 4244 行 | SafeImage、LargeDirectBuffer、PLog（纯 Kotlin/GLES31） | 低-中 |
| 多帧编排 | `processor/MultiFrameStacker.kt`（YUV 部分） | 370 行 | BitmapUtils、MultiFrameConfig、AspectRatio | 低 |
| GPU 调度 | `GlesGpuScheduler/GlesGpuCompletion/GlesPixelBufferTransfer/GlesComputeWorkGroup` | ~800 行 | 自闭合 | 低 |
| 调色引擎 | `model/ColorRecipe.kt` + `lut/ColorRecipeGl.kt` + PreviewColorShader* / ShadowsHighlights / ThreeWay / FilmGrain / CurveUtils / BasicToneLut* / SrgbSharpness / Clarity / CameraRawCalibrationMatrix | ~3-4k 行 | R 字符串（少量） | 中 |
| 静态降噪 native | `cpp/mgc_denoise_static`、LibRaw、libultrahdr | C++ | 不在本轮范围（YUV 链不需要） | 高 |

- `ColorRecipeParams` 参数面是我方 Profile 的**超集**：exposure/contrast/saturation/temperature/tint/highlights/shadows/toneToe/Shoulder/Pivot（中间调塑形）/8 区 HSL/三分区 grading（shadow-midtone-highlight 的 hue+amount+luminance+balance+blending）/master+RGB 曲线/filmGrain/vignette/bloom/halation/chromaticAberration/noise/clarity/sharpness。
- 入口 API：`MultiFrameStacker.processBurstYuv(List<SafeImage>, ...) -> Bitmap`；`GlesYuvStacker(width,height,outputW,outputH,rotation,colorSpace,inputFormat).process(images)`。
- `SafeImage` 构造耦合 `Camera2Controller` → 移植时解耦（只包 `android.media.Image`）。
- Camera2Controller（8798 行）**不整体移植**——与 Compose UI/状态机深度耦合，破坏 UI 冻结原则。

## 2. 新管线拓扑

```
CameraX CameraEngine（UI/动效不变）
  └─ 多帧采集：连续 N 张全分辨率 YUV_420_888（AE 锁定首帧后，Camera2Interop）
       └─ [移植] MultiFrameStacker.processBurstYuv → GlesYuvStacker
            （帧间对齐 + 时域合并 + 空间降噪 + 可选超分）→ 干净 Bitmap
       └─ [移植] 调色链（PortraitColorRenderer，自建编排）
            ColorRecipe pass 链 ← ProfileToRecipeMapper(PhotographerProfile)
            + 我方保留 pass：color_matrix 3x3 → halation 参数化扩展 → film_curve 终层
       └─ JPEG/EXIF 编码 → CaptureSaver（不变）
预览：ProfileRenderer 两 pass 架构不变，effect pass 内核替换为移植的
      recipe shader（WYSIWYG：预览=成片同一套数学）
桌面端：tools/profile_renderer.py 同步 recipe 数学（微小改动），PhotographerStudio 布局不动
```

## 3. Profile → ColorRecipe 映射表（ProfileToRecipeMapper）

| PhotographerProfile | ColorRecipeParams | 换算 |
| --- | --- | --- |
| exposure.bias | exposure | 直接（EV） |
| white_balance.temperature_bias / tint_bias | temperature / tint | 线性归一（±K → ±1） |
| hsl.<band>.hue_shift/saturation/lightness | <band>Hue/Chroma/Lightness（8 区 + primary） | hue_shift→hue、sat-1→chroma、light-1→lightness |
| tone_curve.points | masterCurvePoints | 直接 |
| highlight_rolloff.threshold/strength | gradingHighlight* + toneShoulder | 映射（见代码注释） |
| shadow.black_point/compression/tint | toneToe + gradingShadow{Hue,Amount,Luminance} | tint→gradingShadowHue/Amount |
| lens.vignette / chromatic_aberration / bloom | vignette / chromaticAberration / bloom | 直接 |
| grain.amount/size/density | filmGrain | amount 直接；size/density 走我方扩展 uniform |
| noise.luma/chroma | noise | 直接 |
| halation.amount | halation | radius/threshold/warmth 走扩展 uniform |
| vignette（独立层） | vignette（若 lens.vignette 已用则合并） | feather/center 走扩展 uniform |
| sharpen.amount | sharpness | radius 走扩展 uniform |
| color_matrix.matrix_3x3 | （recipe 无此项） | **保留我方 3x3 pass**（或复用 lut/CameraRawCalibrationMatrix） |
| film_curve | （recipe 无此项） | **保留我方 FilmCurve 终层**（输出一致性签名） |

## 4. 工程决定
- **minSdk 保持 26**；GLES3.1 运行时探测（`ensureGles31` 已内置检查），不支持时回退 0.4.0 单帧链。实机（用户手机）已验证支持 PhotonCamera。
- 移植代码放 `com.photographercamera.core.photon.*`，保留原文件头 Apache-2.0 出处注释；`PLog` → 适配器转发 `DebugLog`。
- 版本 0.5.0（minor+1），UI/动效零改动；0.4.0 更新系统保留。
- 回退开关：Profile JSON 加 `"engine": "photon" | "legacy"`（默认 photon），失败自动回退 legacy 并记录 RemoteLog。

## 5. 分工（并行）
- Agent P：移植 processor 闭包（GlesYuvStacker 等）→ `core/photon/stack/`
- Agent C：移植调色闭包 + ProfileToRecipeMapper → `core/photon/color/`
- 主 Agent：CameraEngine 多帧采集、PhotonCapturePipeline 编排、预览内核替换、构建、桌面端同步、报告
