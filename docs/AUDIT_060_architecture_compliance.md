# 架构合规与死代码审计报告（0.7.2 基线）

审计时间：2026-09-06 15:30 · 范围：android/app 全部 Kotlin（约 130,118 行）+ assets
方法：文件级符号引用分析（全项目调用图）+ 指导手册逐条比对

---

## 任务 1：架构合规判定 —— ❌ 尚不符合，处于过渡态

### ✅ 已符合项
| 指导手册要求 | 现状 | 证据 |
|---|---|---|
| UI 设置保留 | AppSettingsScreen（309 行扁平结构，待重构） | ui/screens/ |
| 取景框缩放动效 | pinch 缩放 + animateFloatAsState 平滑收敛 | CameraScreen.kt:582,640 |
| 相机配置文件控制成像风格 | 6 款 JSON 胶片预设 → ProfileRenderer（highlight rolloff → shadow → HSL → 曲线，全参数化无硬编码） | assets/profiles/*.json, core/gpu/ProfileRenderer.kt |
| 高光/阴影分区 | ProfileRenderer 含 toLinearAnchor(highlightThreshold)/shadowBlackPoint 分区处理 | ProfileRenderer.kt:448-459 |
| 在线更新 | UpdateChecker 接 R2 链路（0.7.2 已实发） | CameraScreen.kt:1197-1212 |
| 实时调试日志 | DebugLog + RemoteLog + DebugConnectDialog 全链存活 | core/debug/ |
| 单 Activity、Manifest 干净 | 仅 1 个 activity，无残留组件 | AndroidManifest.xml |

### ❌ 不符合项（按严重度排序）
1. **"新旧结合"仍在（核心问题）**：拍摄主力仍是旧 CameraX `CameraEngine`（1,678 行，shootBitmap/shootYuv/shootRawIsp 三条旧管线）；移植的上游引擎 `Camera2Controller`（8,799 行）**零实例化**——全项目唯一"引用"是一行注释（CameraEngine.kt:1460）。
2. **"可导入相机配置文件"未实现**：全项目无任何文件选择器（GetContent/OpenDocument）用于导入外部 .cube/.xmp；移植的 `CustomImportManager`（998 行，支持 .cube/.xmp/.png→.plut）是**死代码**；目前只有 6 款内置 JSON 预设。
3. **设置菜单未按指导手册重构**：仍是旧扁平结构（上轮 settings-port 代理被 429 阻断，未动工）。
4. **RAW 回退链残留自研 shader**：0.7.2 RAW 主链已接上游 `RawDemosaicProcessor` ✅，但失败回退仍走自研 raw_isp.frag（红图隐患残留路径）。
5. **photon 栈利用率极低**：295 个移植模块仅 16 个被引用，且其中 `LutConfig`/`EyeFocusPreviewFrame` 等引用来自死文件（见下）。

---

## 任务 2：死代码/残留代码清单

### A. 死文件（整文件无外部引用）——40 个，共 7,144 行
**确认可删（无规划价值，950 行）**：
- `core/camera/ImageYuvConverter.kt`（70）、`core/camera/RawImageConverter.kt`（233，旧 RAW 解码器，已被 RawDemosaicProcessor 取代）
- `ui/preview/CameraPreviewGL.kt`（336）+ `ui/preview/CameraGLSurfaceView.kt`——**预览双实现死分支**（二者互相引用成孤岛，活链是 CameraScreen→CameraPreviewView）

**待引擎切换后处置（保留观察，约 4,300 行）**：
- photon 栈内死文件：DngBlackLevelPatcher/DngCfaPatternPatcher/DngWhiteLevelPatcher（813，上游 DNG 修正，引擎切换后随 Camera2Controller 使用）、RawStackFrameRegistrationEstimator（533）、PreviewEyeFocusProcessor（614）、DngPhotonLocalToneMapper（837）、FrameTemplateParser（449）等
- `core/photon/gallery/Jpeg444ExportEncoder.kt`（362）、`core/photon/hdr/UltraHdrWriter.kt`（38）等上游配套

**按新功能规划保留（暂死，将接线）**：
- `core/photon/livephoto/MotionPhotoWriter.kt`（401，Live Photo）、`core/photon/video/VideoAudioInput.kt`（116）、`core/photon/camera/HighResolutionHelper.kt`（382）

### B. 未引用 shader 资产——16 个（早期实验残留）
bloom.frag/.glsl、blur.frag、exposure.glsl、extract_blur.frag、grain.glsl、grain_noise.frag、halation.frag/.glsl、highlight.glsl、hsl.glsl、mainchain.frag、noise.glsl、shadow.glsl、sharpen.glsl、tone_curve.glsl —— 全部打进 APK 但零加载点。

### C. 类级残留（文件存活但类未用，误报已剔除后确认项）
- `CameraEngine.LensRef`、`UserPreferencesRepository.UserPreferences`、`RawStackTuningProfile` 系列——随宿主文件处置

### D. 引擎切换后的必删项（本轮不动）
- 旧 CameraX CameraEngine 三管线 + raw_isp.frag 回退 + pc_* 应急后门文件机制（pc_yuv_off/pc_burst_off/pc_raw_isp_off.txt）

---

## 处置计划（17:16 配额恢复后执行）
1. **引擎切换轮**：CameraScreen 直连 Camera2Controller 最小调用面 → 退役 CameraEngine + raw_isp.frag + 死分支 ui/preview + 16 个 shader 一次性清理并编译验证
2. **导入功能轮**：CustomImportManager 接入设置页文件选择器（实现"可导入配置文件"）
3. **设置菜单轮**：按指导手册分组重构（保留项功能与上游一致）
