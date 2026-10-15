# 拍摄调用链核实报告 · Phase A-01（只读审计）

- 审计基准：HEAD = `c4523dc`（feat(jpeg): JPEG MAX 对齐 RAW MAX 软件降噪）
- 项目根：`C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera`
- Android 源码根：`.../android/app/src/main/java/com/photographercamera`（下称 `SRC`）
- 对比基线：`docs/AUDIT_060_architecture_compliance.md`（0.7.2 旧基线）
- 审计性质：**只读**。未创建/修改/删除任何被审计文件；唯一写操作是本报告。
- 方法：Grep 定位类名/函数名 → Read 逐段读码。所有结论带 `file:line` 证据。

---

## ① 引擎实例化验证

### 1.1 CameraViewModel ↔ Camera2Controller（新架构成立）

| 项目 | 证据（file:line） | 说明 |
|---|---|---|
| 类声明 | `photon/camera/Camera2Controller.kt:87` `class Camera2Controller(private val context: Context)` | 9290 行，Camera2 直连引擎 |
| **字段声明** | `photon/viewmodel/CameraViewModel.kt:508` `private val cameraController = Camera2Controller(application)` | VM 私有持有，唯一实例化点 |
| 实例化点唯一性 | 全项目 Grep `Camera2Controller(` 仅 2 命中：类自身声明（Camera2Controller.kt:87）+ VM（CameraViewModel.kt:508） | 无第二处实例化、无反射构造（全项目 `Class.forName` 仅 DeviceUtil.kt:22 / SystemPropUtil.kt:10，与相机无关） |
| 状态暴露 | `CameraViewModel.kt:531` `val state: StateFlow<CameraState> = cameraController.state` | VM 直接透传 controller StateFlow |
| 附属句柄 | `CameraViewModel.kt:532-535` `livePhotoRecorder` / `realtimeStabilizationCoordinator` get 到 controller 字段 | — |
| 生命周期-初始化 | `CameraViewModel.kt:1793` `init {` → `:1868` `cameraController.initialize()`；`:1870` collect state；`:1887-2188` 全部回调接线 | init 块内完成 |
| 生命周期-开相机 | `CameraViewModel.kt:2641` `fun openCamera(surfaceTexture)` → `:2663` `cameraController.openCamera(...)`；`:2670` `prepareCamera()` → `:2671` `cameraController.prepareCameras()` | — |
| 生命周期-关相机 | `CameraViewModel.kt:2688` `fun closeCamera(...)` → `:2701` `cameraController.closeCamera(expectedSurfaceTexture = ...)` | — |
| 生命周期-释放 | `CameraViewModel.kt:6671` `override fun onCleared()` → `:6677` `cameraController.release()` | ViewModel 销毁即释放引擎 |
| 回调方向 | controller→VM：`onImageCaptured`（Camera2Controller.kt:465，调用点 `:9036-9038`）、`onMultiFrameSequenceFinished`（`:474`）、`onHdrBracketCaptureFailed`（`:466`）、`onCameraError`（`:676`）、`onVideoSaved`、`onPlayShutterSound`、`onLivePhotoVideoCaptured` | 见 ④ 关系图 |

**结论（已确认事实）**：CameraViewModel 实例化、持有并全权管理 Camera2Controller 生命周期（initialize/openCamera/closeCamera/release），拍摄入口 `capture()` 亦委托 controller。0.7.2 旧报告「Camera2Controller 零实例化」**已作废**。

### 1.2 CameraScreen.kt 如何与 CameraViewModel 交互

| 交互方式 | 证据（file:line） |
|---|---|
| ViewModel 获取 | `ui/screens/CameraScreen.kt:184` `val pvm: CameraViewModel = viewModel()`（import 见 `:123`） |
| StateFlow 订阅 | `:185` `val state by pvm.state.collectAsState()`；`:186` isInitialized；`:187` currentLutId；`:188` currentRecipeParams；`:190-191` calibrationOffset（Flow collectAsState）；`:243` flashMode；`:252` state.useRaw 回流；`:304` zoomRatioByMain；`:306` rawCapable = state.isRawSupported |
| 开相机链 | `:196-198` `LaunchedEffect(isCameraInitialized)` → `pvm.prepareCamera()`；`:199-203` → `pvm.openCamera(st)`（SurfaceTexture 接线） |
| 快门 | `:602-608` `scope.launch { pvm.capture() }`（注释 `:597-598`：「全链路拍照由 CameraViewModel.capture() 内部完成，UI 仅触发快门动画」） |
| 完成/失败事件 | `:613-617` `pvm.captureFailedEvent.collect`；`:621-626` `pvm.imageSavedEvent.collect` → 停止转圈 + 闪屏动画 |
| 生命周期 | `:715-736` ON_RESUME 补开 `pvm.openCamera(it)`（`:726`）；onDispose `pvm.closeCamera()`（`:734`，注释「photon VM owns the Camera2Controller lifecycle」） |
| 其他方法调用 | `:272` setUseLivePhoto；`:274-295` 冷启动设置灌入；`:636` setZoomRatio；`:712` reapplyPreviewLut；`:1076` 引擎拍摄偏好；`:1275` photonVm=pvm 传入子面板 |
| 音量键 | `:631-642` VolumeKeyBus.onCapture = { doCapture() }；onZoomStep → pvm.setZoomRatio |

**结论（已确认事实）**：CameraScreen 不含任何拍摄逻辑，纯 StateFlow 驱动 + 方法委托；相机生命周期归 CameraViewModel/Camera2Controller。

### 1.3 旧 core/camera/ CameraX CameraEngine 现状

| 检查面 | 结果 | 证据 |
|---|---|---|
| 文件存在性 | **目录已不存在**。`core/` 下仅剩 debug/device/gpu/photon/profile/storage/update/util | `ls SRC/core` 实证；Glob `**/CameraEngine.kt` 零命中 |
| 物理删除 | commit `4c463ab`（0.9.0(30)）删除旧 CameraEngine 全套 5 文件（CameraEngine.kt 1949 行 + ImageYuvConverter/RawImageConverter/RawFrame/StillFrame） | 见 `_audit_phase_a/02_legacy_code.md:47-64`（git 考古） |
| 实例化点 | **零**。全项目 Grep `CameraEngine` 无任何代码实例化/引用 | Grep 结果仅见下方注释与文档 |
| 全部现存引用及性质 | ① `ui/screens/CameraScreen.kt:246` 注释（「CameraEngine.setRawIspEnabled」历史说明）；② `ui/screens/AppSettingsScreen.kt:21` 注释「CameraEngine 参数删除（旧引擎退役）」、`:102`「所有开关直连 photon 引擎（CameraEngine 已退役）」、`:140`「LensRef 旧桥已随 CameraEngine 退役」；③ `android/app/build.gradle.kts:38` 注释；④ `assets/shaders/raw_isp.frag:131` 注释「See CameraEngine capture docs」；⑤ 其余全部在 `docs/*.md`、`PhotographerCamera-0.x-report.md`、`交接卡.json`（历史文档） | 逐条 Grep content 命中核对 |
| 旧三管线函数 | `shootBitmap/shootYuv/shootRawIsp` 全项目（android/）**零命中** | Grep 实证 |
| 测试面 | `android/app/src` 下**只有 main 源集**，无 test/androidTest，不存在测试引用 | `ls android/app/src` 实证 |
| 死文件面 | `core/camera/` 目录整体不存在，无死文件残留 | `ls SRC/core` 实证 |

**结论（已确认事实）**：旧 CameraX CameraEngine 已被物理删除，当前既无实例化点也无死文件，仅存注释级、文档级引用。0.7.2 旧报告「旧 CameraEngine 仍是拍摄主力」**已作废**。当前拍摄主力 = photon Camera2Controller（见 1.1）。

---

## ② 六条拍摄路径逐条链路表

路径总入口：`CameraScreen.kt:604 pvm.capture()` → `CameraViewModel.kt:3062 fun capture()` → `Camera2Controller.kt:7454 fun capture()`。
多帧开关判定链：`CameraViewModel.kt:406-409 resolveJpgMaxActive`（`!useRaw && !useLivePhoto`）→ `:411-422 resolveMultiFrameOutputScale`（RAWmax→用户缩放；JPGmax→1f；否则 null）→ `:2298 cameraController.setMultiFrameOutputScale(...)` → `CameraState.kt:440-445 isMultiFrameEnabled / requiresMultiFrameCaptureSequence`、`:459-469 activeMultiFrameCount`。
采集格式判定：`Camera2Controller.kt:2434 effectivelyUseRaw = requestedRawCaptureEnabled && isRawSupported` → `:2451-2457 captureFormat`（RAW_SENSOR / YCBCR_P010 / YUV_420_888）→ `:2505-2510 ImageReader.newInstance`。

### 路径 a · 普通拍照（JPEG 单帧）

| 阶段 | 函数 | file:line | 输入 | 输出 |
|---|---|---|---|---|
| 入口 | CameraScreen doCapture | CameraScreen.kt:602-608 | 快门事件 | — |
| VM 入口 | CameraViewModel.capture() | CameraViewModel.kt:3062 / :3144 | —（非 VIDEO、无 timer） | 委托 controller |
| 采集入口 | Camera2Controller.capture() | Camera2Controller.kt:7454 | — | isCapturing=true（:7506） |
| 分支判定 | requiresMultiFrameCaptureSequence=false | CameraState.kt:444-445 | multiFrameOutputScale==null | 走单帧 |
| 对焦/预闪 | continueCaptureAfterFocusPreparation → performCapture | Camera2Controller.kt:7393 / :8183 | baseExposureResult | 单 STILL_CAPTURE 请求（:8213-8302） |
| 帧交付 | onImageAvailable → SafeImage → onImageCaptured | Camera2Controller.kt:2511 / :9036-9038（SafeImage: photon/model/SafeImage.kt:7） | Image（YUV_420_888/YCBCR_P010 或 RAW_SENSOR） | (SafeImage, CaptureInfo, Characteristics, CaptureResult, CapturedFrameMetadata) |
| VM 分派 | onImageCaptured 单帧 else 分支 | CameraViewModel.kt:1985-1999 | 同上 | saveImage |
| 元数据/Profile 构建 | saveImage → MediaMetadata | CameraViewModel.kt:5355 / :5433-5502 | userPrefs + captureInfo | MediaMetadata（lutId、colorRecipeParams、sharpening、noiseReduction、chromaNoiseReduction、raw* 全套） |
| Profile 数值解析 | resolveCaptureSharpening / resolveCaptureDenoiseStrengths | CameraViewModel.kt:127-141 / :150-181 | isRawCapture=false | 锐化 0f；降噪 1.7.6 起非 RAW 也生效（:154-179） |
| 建档 | GalleryManager.preparePhoto | GalleryManager.kt:5519（定义 :1803） | metadata、captureResult、缩略图 | photoId + 落盘 metadata |
| 保存 | savePhoto → saveYuvPhoto | GalleryManager.kt:5547（定义 :2601）/ :2624（定义 :2000） | SafeImage(YUV/P010) | Bitmap |
| 核心处理 | YuvProcessor.processAndToBitmap | GalleryManager.kt:2029 | YUV_420_888 | ARGB Bitmap |
| 虚化/HDR 源 | renderAndSaveBokehPhoto → photoProcessor.prepareUltraHdrSourceFromProcessedSdr → gainmapProducer.build | GalleryManager.kt:2048 / :2050 / :2060 | Bitmap | GainmapSourceSet / GainmapResult |
| LUT/Profile 施加 | PhotoProcessor.processBitmap → LutImageProcessor.applyLutStack | PhotoProcessor.kt:739-818（applyLutStack :803）；LutImageProcessor.kt:448 | Bitmap + baseline/creative 层 | 处理后 Bitmap |
| 导出 | exportPhoto → exportBitmapToMediaStore | GalleryManager.kt:2079（定义 :1241）/ :1610 | Bitmap | MediaStore JPEG（mime image/jpeg :1533；可选 HEIC/JPEG444 :1271-1280） |
| 错误回退 | ① characteristics null → emit captureFailedEvent + return（CameraViewModel.kt:5512-5517）；② preparePhoto null → emit + return（:5528-5533）；③ 保存协程 try/catch → emit（:5572-5577）；④ 外层 catch → emit（:5579-5582） | — | — | UI 转圈复位 |

注：`useRaw=true 且 useRawMax=false` 时本路径退化为**单帧 RAW**：captureFormat=RAW_SENSOR（Camera2Controller.kt:2451-2452），savePhoto 走 saveRawPhoto（GalleryManager.kt:2639-2659），链为 copyRawSensorImageToContiguousBuffer（:2218）→ prepareRawDngProfile（:2242）→ persistDng 写 DNG（:2272-2276）→ renderRawDngPhotoOutputs（:3779）→ RawDemosaicProcessor.processForHdrSources（:3804）→ 导出 JPEG。输入 RAW_SENSOR(ByteBuffer)，输出 DNG + JPEG。

### 路径 b · JPEG MAX（YUV 多帧 stacking）

| 阶段 | 函数 | file:line | 输入 | 输出 |
|---|---|---|---|---|
| 条件 | resolveJpgMaxActive（!useRaw && !useLivePhoto） | CameraViewModel.kt:406-409 | prefs | true → multiFrameOutputScale=1f（:420） |
| 采集分支 | requiresMultiFrameCaptureSequence=true → prepareMultiFrameFocusForCapture → continueCaptureAfterFocusPreparation → performCapture | Camera2Controller.kt:7513-7557 / :7393 / :8183 | — | burst：`List(frameCount){baseRequest}`（:8304-8312） |
| 帧数 | activeMultiFrameCount = normalizeDenoiseFrameCount(jpgMultiFrameDenoiseFrameCount) | CameraState.kt:459-463 | 默认帧数 | N |
| 帧交付 | onImageAvailable → onImageCaptured（多帧分支） | Camera2Controller.kt:2534 / :1887 | N × YUV_420_888/P010 | — |
| 收帧缓存 | pendingRawStackFrames.add(rawStackFrame(...)) | CameraViewModel.kt:1895-1909（rawStackFrame 定义 :5733） | SafeImage + CaptureResult | RawStackFrame 列表 |
| 曝光规划 | RawmaxExposurePlanner.plan | CameraViewModel.kt:1920-1924（定义 photon/processor/RawmaxExposurePlanner.kt） | exposureProduct + role | normal/auxiliary 分组；非 RAW 时剔除 auxiliary（:1957-1969） |
| 处理入口 | processStacking | CameraViewModel.kt:5858（调用 :1975） | List<RawStackFrame> | isRawStack=false（:5876） |
| Profile 构建 | MediaMetadata（captureMode="jpg_max"） | CameraViewModel.kt:5967-6037（:6036 captureMode） | userPrefs | sharpening=0f（:5878-5881）；降噪 editableLuma/Chroma（:5886-5887，1.7.6 对齐） |
| 建档 | GalleryManager.preparePhoto | GalleryManager.kt:6060（定义 :1803） | metadata | photoId |
| 保存分派 | saveStackedPhoto → saveYuvStackedPhoto | GalleryManager.kt:6090（定义 :4138）/ :4168（定义 :2901） | N × SafeImage(YUV) | — |
| 核心堆叠 | MultiFrameStacker.processBurst → GlesYuvStacker | GalleryManager.kt:2922；MultiFrameStacker.kt:185-229（GlesYuvStacker 构造 :212） | YUV_420_888 N 帧 | ARGB Bitmap（:2936 previewBitmap） |
| LUT/Profile | exportPhoto → prepareUltraHdrSource → processBitmap → applyLutStack | GalleryManager.kt:2953 / :1308；PhotoProcessor.kt:803 | Bitmap | 处理后 Bitmap |
| 导出 | exportPhoto | GalleryManager.kt:1241 | Bitmap | JPEG/HEIC |
| 错误回退 | ① handleMultiFrameSequenceFinished：部分帧→跳过曝光规划直接 processStacking 出片（CameraViewModel.kt:5830-5854）；零帧→forceResetCaptureState + captureFailedEvent（:5814-5828）；② characteristics null → 关全部帧 + emit（:6053-6058）；③ preparePhoto null → 关帧 + emit（:6071-6078）；④ 异常 → 关帧 + emit（:6119-6126） | — | — | 保证成片或干净失败 |

### 路径 c · RAW MAX（MGC Spatial GPU stack → DNG）

| 阶段 | 函数 | file:line | 输入 | 输出 |
|---|---|---|---|---|
| 条件 | useRaw && useRawMax → multiFrameOutputScale = rawMaxOutputScale（默认超分缩放） | CameraViewModel.kt:2088-2089 / :416-419 | prefs | isRawMaxEnabled（CameraState.kt:453-454） |
| 帧数/曝光计划 | activeMultiFrameCount = normalizeHdrPlusFrameCount(hdrPlusFrameCount, bracket) | CameraState.kt:464-467 | HDR+ 帧数 | N（含 short/long） |
| 采集分支 | performCapture burst + HDR+ bracket 计划 | Camera2Controller.kt:8304-8358 | — | normal × baseRequest + short/long 请求（:8317-8351，tag=MultiFrameCaptureRole :8307） |
| 帧交付/收帧 | onImageCaptured 多帧分支 → rawStackFrame（role 按 request tag） | Camera2Controller.kt:2534；CameraViewModel.kt:1903 / :5775-5782 | N × RAW_SENSOR | RawStackFrame（NORMAL/HIGHLIGHT_SHORT/SHADOW_LONG） |
| 曝光规划 | RawmaxExposurePlanner.plan（enableHdrFusion=isHdrPlusBracketExposureEnabled） | CameraViewModel.kt:1917-1924 | 帧列表 | 分组 + 剔除（:1931-1933） |
| 处理入口 | processStacking（isRawStack=true） | CameraViewModel.kt:5858 / :5876 | RAW 帧 | rawMaxMode（:5915-5917 SPATIAL/SABRE 用户可配）、rawSpatialOutputMode（:5918-5927） |
| Profile 构建 | MediaMetadata | CameraViewModel.kt:5967-6037 | userPrefs | 宽高按 superResScale 缩放（:6013-6014）；rawDcpId/hncs/spectral/toneMapping 等 |
| 建档 | GalleryManager.preparePhoto | GalleryManager.kt:6060 | metadata | photoId |
| 保存分派 | saveStackedPhoto → saveRawStackedPhoto | GalleryManager.kt:6090 / :4186（定义 :2971） | RAW 帧 + rawStackFrames | — |
| RAW 元数据 | RawMetadata.create + noiseProfileSelection + black/white/CFA 模式解析 | GalleryManager.kt:3027-3088 | characteristics + captureResult | rawMetadata |
| **核心堆叠** | RawDemosaicProcessor.getInstance().runStackingOnGlContext { MultiFrameStacker.processBurstRaw } | GalleryManager.kt:3130-3156（runStackingOnGlContext 定义 RawDemosaicProcessor.kt:628；processBurstRaw 定义 MultiFrameStacker.kt:282-357） | List<RawStackFrame> + CFA/BL/WL/WB/噪声模型 | RawStackResult |
| MGC 融合 | GlesMgcRawFusion.processFrames → GlesMgcRawSpatialStacker（SABRE 时 GlesMgcRawSabreProcessor） | MultiFrameStacker.kt:337-356；GlesMgcRawFusion.kt:41 / :205（:113 SABRE） | RAW 帧 | fusedBayerBuffer + gpuLinearRgbSource（RGBA16F :3149） |
| 默认降噪 | RawDemosaicProcessor.processMgcSpatialGpuLinearRgb | GalleryManager.kt:3235-3260 | GPU LinearRGB + 降噪强度（:3250-3258 由 metadata.rawDenoiseValue/rawChromaDenoiseValue 驱动） | 降噪后 GPU 源 |
| DNG 持久化 | RawProcessor.prepareRawDngProfile → trySaveStackedRawDng | GalleryManager.kt:3357-3380 / :3400（trySaveStackedRawDng 定义 :3956） | GPU LinearRGB | **DNG（LINEAR_RAW_RGB，:3329）** |
| 出片渲染 | renderRawDngPhotoOutputs → RawDemosaicProcessor.processForHdrSources | GalleryManager.kt:3779 / :3804（定义 RawDemosaicProcessor.kt:2046） | DNG 路径 | SDR Bitmap + HDR 参考 |
| LUT/Profile | PhotoProcessor.prepareUltraHdrSourceFromRawResult → applyLutStackWithLuminanceGain / processBitmap → applyLutStack | PhotoProcessor.kt:262-355 / :792 / :803 | Bitmap | 处理后 Bitmap + 亮度增益图 |
| 导出 | exportPhoto | GalleryManager.kt:1241 | Bitmap | JPEG/HEIC（exportDngWithRawExport 控制附带 DNG，CameraViewModel.kt:5564） |
| 错误回退 | ① rawStackResult null → return（:3158）；② 噪声模型不完整 → abort（:3227-3234）；③ processMgcSpatialGpuLinearRgb null → abort（:3261-3268）；④ prepareRawDngProfile null → return（:3381-3384）；⑤ 外层 catch + captureFailedEvent（CameraViewModel.kt:6128-6131）；⑥ 缺帧收敛同路径 b（:5794-5856） | — | — | — |

### 路径 d · HDR bracket（JPEG MAX HDR 合成）

| 阶段 | 函数 | file:line | 输入 | 输出 |
|---|---|---|---|---|
| 条件 | isJpgMaxHdrEnabled = isJpgMaxEnabled && useJpgMaxHdrComposition | CameraState.kt:450-451；setter Camera2Controller.kt:7124 | 用户开关 | true |
| 分支判定 | shouldUseJpgMaxHdrCapture | Camera2Controller.kt:8153-8161（调用 :8192） | state | true → performHdrBracketCapture |
| 采集 | performHdrBracketCapture | Camera2Controller.kt:7581-7587（调用 :8200） | zeroEvFrameCount（:8204 resolveHdrBracketZeroEvFrameCount） | EV offsets（:7594 buildHdrBracketEvOffsets）→ captureBurst（:7640）；hdrBracketCapturing=true（:7596-7599） |
| 帧交付/收帧 | onImageCaptured → handleHdrBracketFrameCaptured | CameraViewModel.kt:1888-1889 / :6135（判定 :1888 `state.value.hdrBracketCapturing`） | 3+ 帧 YUV/P010（±EV） | hdrBracketImages（:6155） |
| 收齐 | onHdrBracketFramesCollected → processHdrBracket | CameraViewModel.kt:6159-6183（onHdrBracketFramesCollected :6172 → Camera2Controller.kt:7101） | 帧 + CaptureResults | — |
| 排序 | orderHdrBracketFramesByTimestamp | CameraViewModel.kt:6196 / :6292-6322 | 帧列表 | 按 SENSOR_TIMESTAMP 排序 |
| Profile 构建 | buildPhotoMetadata（captureMode="jpg_max"，sharpening=0f，降噪 1.7.6 对齐） | CameraViewModel.kt:6225-6233（定义 :2884）；降噪 :6204-6213 | captureInfo | MediaMetadata |
| 建档 | GalleryManager.preparePhoto | GalleryManager.kt:6235 | metadata | photoId |
| 融合 | composeHdrBracketPhoto → buildYuvHdrFrameSelection → processHdrBracketYuvFrames → MultiFrameStacker.processHdrBurstYuv → GlesYuvStacker.processHdr | CameraViewModel.kt:6251；GalleryManager.kt:2718 / :2758 / :2853-2873；MultiFrameStacker.kt:232-279（GlesYuvStacker.processHdr :261） | N 帧 + exposureProducts + 角色（ZERO/HIGH/LOW_EV :2875-2892） | 融合 Bitmap |
| 保存 | GalleryManager.saveBitmapPhoto → writeFinalJpeg + exportPhoto | GalleryManager.kt:6263（定义 :2668）/ :2686 / :2701 | Bitmap | JPEG/HEIC |
| 错误回退 | ① <3 帧 → 关图 + null（GalleryManager.kt:2728-2732）；② 帧序不匹配 expectedFrameCount → return + 关图（CameraViewModel.kt:6201 / :6285-6288）；③ fusedBitmap null → return@launch（:6261）；④ 采集失败 onHdrBracketCaptureFailed → resetHdrBracketCapture(closeImages=true) + captureFailedEvent（CameraViewModel.kt:2040-2044；controller 侧 :7675 / :7688） | — | — | — |

### 路径 e · Live Photo

| 阶段 | 函数 | file:line | 输入 | 输出 |
|---|---|---|---|---|
| 开关 | setUseLivePhoto → livePhotoRecorder.startRecording | CameraScreen.kt:272；CameraViewModel.kt:4550；Camera2Controller.kt:9081-9088 | 用户开关 | 环形录制启动 |
| 入口 | CameraViewModel.capture() → setCapturingLivePhoto(true) + snapshotLivePhoto() + cameraController.capture() | CameraViewModel.kt:3136-3144；Camera2Controller.kt:9119-9121 | — | 单帧采集（Live Photo 与多帧互斥：resolveJpgMaxActive=false :406-409 → multiFrameOutputScale=null） |
| 视频录制 | LivePhotoRecorder（MediaCodec H.264+AAC 环形缓冲） | photon/livephoto/LivePhotoRecorder.kt:30-53（MIME :38-45） | 预览帧 + 音频 | 压缩样本环 |
| 帧处理 | saveImage（同路径 a） | CameraViewModel.kt:5355 | 单帧 YUV/P010 或 RAW | Bitmap/DNG |
| 视频落盘 | recordLivePhotoVideo → livePhotoRecorder.recordVideo → GalleryManager.saveVideo | CameraViewModel.kt:5504-5509；Camera2Controller.kt:9129-9138；GalleryManager.kt:5545（定义 :1925） | image.timestamp/1000 | video.mp4 + presentationTimestampUs 元数据（GalleryManager.kt:1942-1950） |
| Motion Photo 合成 | exportPhoto → MotionPhotoWriter.write | GalleryManager.kt:1511-1526（import :32） | JPEG + video.mp4 + TS | Motion Photo JPEG；**失败回退普通 JPEG**（:1523-1526） |
| LUT/Profile | 同路径 a（saveImage metadata → processBitmap → applyLutStack） | CameraViewModel.kt:5433；PhotoProcessor.kt:803 | Bitmap | 处理后 Bitmap |
| 错误回退 | ① 视频超时 withTimeoutOrNull(LIVE_PHOTO_VIDEO_TIMEOUT_MS) → 无视频继续（GalleryManager.kt:1936-1941）；② MotionPhotoWriter 失败 → 纯 JPEG（:1523-1526）；③ saveImage 全套 captureFailedEvent（:5512/5528/5572/5579） | — | — | — |

### 路径 f · 设备不支持 RAW 时的回退路径

| 阶段 | 函数 | file:line | 输入/条件 | 输出 |
|---|---|---|---|---|
| 能力判定 | isRawOutputSupported（CameraUtils.getRawCaptureSize != null） | Camera2Controller.kt:3430-3432（赋值 :2323） | characteristics | isRawSupported |
| 运行时降级 | 物理流 RAW profile 失败 → isRawSupported=false | Camera2Controller.kt:3402-3410（判定入口 :2458-2472） | 失败 key | state.isRawSupported=false |
| 开相机回退 | effectivelyUseRaw = requestedRawCaptureEnabled && isRawSupported → rawCaptureSize=null → captureFormat=YUV_420_888/P010 | Camera2Controller.kt:2434 / :2442-2446 / :2451-2457 | 不支持 RAW | ImageReader 走 YUV/P010 |
| 多帧回退 | isMultiFrameEnabled = multiFrameOutputScale != null && (!useRaw \|\| isRawSupported) | CameraState.kt:440-441 | RAWmax 请求但不支持 | false → 单帧 |
| VM 侧同步 | effectiveUseRaw = it.useRaw；与 state 不一致则 setUseRaw（controller 内 restartCameraForCaptureOutputMismatch 重配会话） | CameraViewModel.kt:2083 / :2101-2103；Camera2Controller.kt:5125-5143 | prefs | 引擎状态对齐 |
| UI 呈现 | rawCapable = state.isRawSupported（RAW 键显隐）；state.useRaw 回流 | CameraScreen.kt:306 / :252 | 引擎状态 | 顶栏显示 |
| 最终落点 | 单帧 YUV → saveImage → saveYuvPhoto → exportPhoto → JPEG | 同路径 a | — | JPEG（**回退到路径 a**） |
| 错误回退 | 物理流失败时同时把 outputPhysicalCameraId 置空回退逻辑流（Camera2Controller.kt:2458-2472）；rawCaptureSize=null 后 P010/YUV 选择（:2453-2456） | — | — | — |

备注（代码推断，需真机验证）：运行时（非开相机时）翻转 `isRawSupported=false` 后，控制器不会主动重建会话（`restartCameraForCaptureOutputMismatch` 仅由 setUseRaw/setUseP010 触发，:5138/:9100），当前会话仍按打开时配置出帧，直到下次重开相机才完全生效。

---

## ③ 模块调用关系图（文字版）

```
[CameraScreen.kt]  (ui/screens，纯 StateFlow + 方法委托)
  │  collectAsState: state/isInitialized/currentLutId/...      (CameraScreen.kt:185-191)
  │  方法调用: capture()/openCamera()/closeCamera()/prepareCamera()/
  │            setUseLivePhoto()/setZoomRatio()/reapplyPreviewLut()   (:604 :202 :734 :197 :272 :636 :712)
  │  事件流: imageSavedEvent / captureFailedEvent             (:613-626)
  ▼
[CameraViewModel.kt]  (photon/viewmodel，唯一引擎持有者)
  │  private val cameraController = Camera2Controller(application)   (:508)
  │  val state = cameraController.state                             (:531)
  │  生命周期: initialize() :1868 / openCamera() :2663 / closeCamera() :2701 / release() :6677
  │  拍摄: capture() :3062 → snapshotLivePhoto() :3142 / cameraController.capture() :3127/3144
  │  参数: setUseRaw :2102 / setMultiFrameOutputScale :2105/2298 / setHdrPlusFrameCount :4498 / ...
  │  回调注册: onImageCaptured :1887 / onMultiFrameSequenceFinished :2004 /
  │            onCameraError :2018 / onHdrBracketCaptureFailed :2040 / onVideoSaved :2007
  ▼
[Camera2Controller.kt]  (photon/camera，Camera2 采集引擎)
  │  ImageReader: RAW_SENSOR / YCBCR_P010 / YUV_420_888            (:2451-2457, :2505)
  │  capture() :7454 → performCapture :8183 → 单帧 / burst / performHdrBracketCapture :7581
  │  帧回调 onImageCaptured(SafeImage, CaptureInfo, Characteristics, CaptureResult, CapturedFrameMetadata)  (:465, 调用 :9038)
  │  LivePhotoRecorder(:389) / recordLivePhotoVideo :9129 / snapshotLivePhoto :9119
  ▼ (帧数据)
[CameraViewModel 处理层]
  ├─ 单帧 ──────────► saveImage :5355
  ├─ 多帧(JPEGmax/RAWmax) ─► RawmaxExposurePlanner.plan :1920 ─► processStacking :5858
  ├─ HDR bracket ───► handleHdrBracketFrameCaptured :6135 ─► processHdrBracket :6187
  └─ 缺帧兜底 ──────► handleMultiFrameSequenceFinished :5794
  ▼
[GalleryManager.kt]  (photon/gallery，保存编排)
  ├─ preparePhoto :1803（建档+元数据）
  ├─ saveVideo :1925（Live Photo video.mp4 + TS）
  ├─ savePhoto :2601 ─┬─ YUV/P010 ─► saveYuvPhoto :2000（YuvProcessor.processAndToBitmap :2029）
  │                   └─ RAW ─────► saveRawPhoto :2118（DNG 落盘 :2276 + renderRawDngPhotoOutputs :3779）
  ├─ saveStackedPhoto :4138 ─┬─ YUV ─► saveYuvStackedPhoto :2901 ─► MultiFrameStacker.processBurst
  │                         └─ RAW ─► saveRawStackedPhoto :2971 ─► RawDemosaicProcessor.runStackingOnGlContext :3132
  │                                                                  └─ MultiFrameStacker.processBurstRaw :3133
  │                                                                       └─ GlesMgcRawFusion :337 ─┬─ GlesMgcRawSpatialStacker
  │                                                                                                  └─ GlesMgcRawSabreProcessor(SABRE)
  │                                                                       └─ processMgcSpatialGpuLinearRgb :3235（默认降噪）
  │                                                                       └─ trySaveStackedRawDng :3400 → DNG
  ├─ composeHdrBracketPhoto :2718 ─► processHdrBracketYuvFrames :2853 ─► MultiFrameStacker.processHdrBurstYuv :2861
  │                                                                    └─ GlesYuvStacker.processHdr
  ├─ saveBitmapPhoto :2668（writeFinalJpeg :2686）
  ├─ exportPhoto :1241 ─┬─ prepareUltraHdrSource :1308（PhotoProcessor）
  │                    ├─ MotionPhotoWriter.write :1511（Live Photo 合成，失败回退纯 JPEG）
  │                    └─ exportBitmapToMediaStore :1610 → MediaStore（JPEG/HEIC/JPEG444）
  └─ recordVideoCapture :4847（普通视频）
  ▼
[PhotoProcessor.kt]  (photon/gallery，LUT/边框/虚化编排)
  ├─ processBitmap :739 / processDng :637
  ├─ RawDemosaicProcessor.getInstance().process :659 / .processForHdrSources :575
  └─ LutImageProcessor.applyLutStack :714/:803 / applyLutStackWithLuminanceGain :317/:792
  ▼
[RawDemosaicProcessor.kt]  (photon/raw，单例，GLES demosaic/HDR 渲染)  getInstance :305
[LutImageProcessor.kt]     (photon/lut，GLES LUT/配方/降噪)            applyLutStack :448/:673
```

### 图像格式流转总表

| 环节 | 格式 | 证据 |
|---|---|---|
| HAL 采集 | RAW_SENSOR / YCBCR_P010 / YUV_420_888 | Camera2Controller.kt:2451-2457 |
| 帧包装 | SafeImage(Image) | photon/model/SafeImage.kt:7；交付 Camera2Controller.kt:9038 |
| RAW → DNG | ByteBuffer（contiguous）→ DNG | GalleryManager.kt:2218 / :2276（单帧）、:3400（堆叠，LINEAR_RAW_RGB） |
| RAW 堆叠中间态 | GPU LinearRGB（RGBA16F）+ fusedBayerBuffer | GalleryManager.kt:3149-3160 |
| YUV → 显示/成片 | ARGB_8888 Bitmap | GalleryManager.kt:2029（YuvProcessor）、MultiFrameStacker.kt:221（GlesYuvStacker） |
| LUT/配方处理 | Bitmap(ARGB) → Bitmap | LutImageProcessor.kt:448；PhotoProcessor.kt:803 |
| HDR | GainmapSourceSet + GainmapResult（Ultra HDR JPEG） | GalleryManager.kt:2050-2061 |
| 最终输出 | JPEG（image/jpeg）/ HEIC / JPEG444 / DNG / Motion Photo JPEG | GalleryManager.kt:1533、:1271-1280、:2276 |

---

## ④ 三级结论分级汇总

### 已确认事实（有代码证据）

1. **新架构成立**：CameraViewModel 唯一实例化并持有 Camera2Controller（CameraViewModel.kt:508），全项目无第二处实例化点；initialize/openCamera/closeCamera/release 全在 VM（:1868/:2663/:2701/:6677）。
2. **CameraScreen 已彻底 VM 化**：`pvm: CameraViewModel = viewModel()`（CameraScreen.kt:184），StateFlow 驱动（:185-191），快门只调 `pvm.capture()`（:604），相机生命周期归 VM（:726/:734）。
3. **旧 CameraX CameraEngine 已不存在**：`core/camera/` 目录物理删除（commit 4c463ab），零实例化、零测试引用，现存引用全部是注释/文档（CameraScreen.kt:246、AppSettingsScreen.kt:21/102/140、build.gradle.kts:38、raw_isp.frag:131）。
4. **六条路径全部落在 photon 引擎内**，入口均为 `CameraViewModel.capture()`（:3062）→ `Camera2Controller.capture()`（:7454），格式判定在 Camera2Controller.kt:2451-2457，无一条经过已删的旧管线。
5. **RAW MAX 堆叠链真实存在**：`saveRawStackedPhoto` → `RawDemosaicProcessor.runStackingOnGlContext`（GalleryManager.kt:3132）→ `MultiFrameStacker.processBurstRaw`（MultiFrameStacker.kt:282）→ `GlesMgcRawFusion`（:337）→ `GlesMgcRawSpatialStacker`（GlesMgcRawFusion.kt:205，SABRE 为 :113）→ DNG（:3400）。
6. **JPEG MAX 与 HDR bracket 共用 GLES YUV 堆叠器**：`MultiFrameStacker.processBurst/processHdrBurstYuv` → `GlesYuvStacker`（MultiFrameStacker.kt:212/253-261）。
7. **Live Photo 合成链**：`recordLivePhotoVideo`（Camera2Controller.kt:9129）→ `GalleryManager.saveVideo`（:1925）→ `MotionPhotoWriter.write`（:1511），失败回退纯 JPEG（:1523-1526）。
8. **Profile（LUT/配方/降噪/锐化）应用点明确**：采集参数在 `saveImage`/`processStacking` 构建 MediaMetadata（CameraViewModel.kt:5433/5967），渲染时经 `PhotoProcessor.processBitmap/processDng` → `LutImageProcessor.applyLutStack`（PhotoProcessor.kt:803/714）施加；RAW 链另经 `RawDemosaicProcessor.processForHdrSources`（:3804）与 `processMgcSpatialGpuLinearRgb`（:3235）。
9. **1.7.6 HEAD 提交属实**：`resolveCaptureDenoiseStrengths`（CameraViewModel.kt:150-181）使 JPEG MAX/HDR bracket 与 RAW MAX 共用软件降噪通道（:6204-6213 同步到 bracket）。
10. **错误回退链完整**：所有失败路径统一发 `captureFailedEvent`（CameraViewModel.kt:545/546）驱动 UI 转圈复位；多帧缺帧有收敛/快速失败双分支（:5794-5856）。

### 代码推断（逻辑推断未实测）

1. **RAW 不支持时的实际拍摄格式**：由 `effectivelyUseRaw = requestedRawCaptureEnabled && isRawSupported`（Camera2Controller.kt:2434）推断不支持时自动落 YUV/P010 单帧 → 路径 a；但真机 HAL 对 P010/RAW profile 的实际广告行为需实测。
2. **运行时 RAW profile 失败后的会话不同步**：`isRawSupported=false`（:3408）翻转后不主动重建会话（重启仅由 setUseRaw/setUseP010 触发 :5138/:9100），推断需重开相机才完全生效——需真机验证。
3. **多帧收齐后的曝光规划剔除逻辑**（CameraViewModel.kt:1957-1969）推断非 RAW 时 auxiliary 帧被关闭、只用 normal 帧堆叠——与日志文案一致但未经真机比对。
4. **Live Photo 与 JPEG MAX 互斥**由 `resolveJpgMaxActive`（:406-409）保证，推断互斥语义与上游一致。
5. `handleMultiFrameSequenceFinished` 的 2s 延迟收敛（:5800）在慢机上的时序 adequacy 属推断。

### 需真机验证

1. 六条路径端到端成片率与画质（尤其 JPEG MAX 1.7.6 降噪对齐后的体积/观感，注释已记录 null 重载退化史，GalleryManager.kt:2944-2952）。
2. RAW 不支持设备（或物理流失败设备）上 RAW 开关 UI 显隐与实际出片格式是否一致（路径 f）。
3. Live Photo 的 video.mp4 时序（presentationTimestampUs）与 Motion Photo 在各 OEM（vivo/oppo 分支，:1538-1555）上的合成成功率。
4. HDR bracket 帧序重排（:6292-6322）在真实 HAL 交付乱序时的正确性。
5. 多帧 HAL 拒帧场景下 2s 收敛兜底（:5794-5856）是否成片。
6. `GlesMgcRawSpatialStacker` 与 `GlesMgcRawSabreProcessor` 在目标 GPU 上的兼容性（mergeMethod 用户可配，:5915-5917）。

---

*审计方法：全项目 Grep（类名/函数名/格式常量/资产路径）+ Read 逐段读码 + git 删除考古（4c463ab）。只读完成，未改动任何被审计文件。*
