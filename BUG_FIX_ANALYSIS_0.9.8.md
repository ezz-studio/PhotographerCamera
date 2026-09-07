# PhotographerCamera 0.9.8 — 7 BUG 根因分析与修复方案

> 版本：0.9.7 → **0.9.8**（APP_VERSION_CODE 37 → 38）
> 强制工作流：修改代码前必须先给出「Upstream 状态机 → 模式互斥 → 当前项目状态 → Profile 参与点 → Diff → Root Cause → 最小修复方案」。
> 本文件为该分析链的交付物（代码已按此分析实施）。

---

## 0. Upstream 状态机（PhotonCamera 上游）

PhotonCamera 的拍照链路（`CameraViewModel.capture()` → `Camera2Controller`）核心状态：

1. **模式判定**：进入 `capture()` 时，依据 `useRaw` / `useLivePhoto` / `activeMultiFrameCount` 决定走单帧还是多帧连拍。
2. **多帧连拍**：`captureBurst` 按设定帧数抓取 N 张 RAW/JPEG，由 `GlesYuvStacker` 在 GPU 上做堆叠（融合 / 降噪 / 锐化），完成后进入 `processStacking` → 出图。
3. **看门狗**：`handleMultiFrameSequenceFinished` 是一个 **2s 超时守卫**——当判定“帧已收齐”（`pendingCount==0`）时调用 `forceResetCaptureState` 复位捕获状态。
4. **存储事件**：出图落盘后发 `imageSavedEvent`（`SharedFlow`），UI 据此收尾。

关键不变量：**多帧路径只有在 `activeMultiFrameCount>1` 且模式互斥为真时才真正进入 GPU 堆叠**；否则退化为单帧直出（帧数设置自然“无效”）。

---

## 1. 模式互斥关系

```kotlin
// CameraViewModel.resolveJpgMaxActive
val resolveJpgMaxActive = !useRaw && !useLivePhoto
```

| 模式 | 触发条件 | 多帧路径 |
|------|----------|----------|
| **JPG MAX** | `!useRaw && !useLivePhoto`（resolveJpgMaxActive=true） | 多帧 JPEG 融合/降噪/锐化 |
| **RAW MAX** | `useRaw` | 多帧 RAW 堆叠 |
| **Live Photo** | `useLivePhoto` | 静态帧多帧 + 音视频录制合成 |

三者**互斥、三选一**：同一时刻只有一个多帧后处理路径在跑。这是上游 PhotonCamera 的硬约束，本项目不得破坏。

---

## 2. 当前项目状态（修复前）

- **Bug 1/2**：多帧 GPU 堆叠在慢设备上未完成，2s 看门狗就误判“收齐”并 `forceResetCaptureState`，**打断堆叠** → 多帧/降噪/锐化从未真正完成 → 帧数设置无效。
- **Bug 3**：`LivePhotoRecorder.initAudio` 需要 `RECORD_AUDIO` 权限；Manifest 已声明，但运行时 `neededPerms` 只请求了 `CAMERA`（旧 API 加 `WRITE_EXTERNAL_STORAGE`）。运行时未授权麦克风 → `initAudio` 提前 `return` → **只有图片没有视频**。
- **Bug 4**：AUTO（AWB 开）下 UI 写死显示静态 `awbTemperature`（默认 5000K）；相机实测色温 `actualAwbTemperature` 只在“手动冻结”时被读取。默认显示错误，需用户拨一次 AWB 才正确。
- **Bug 5**：`setZoomRatio` 下钳位死在 `zoomRatioRange.lower`（=1f）。超广角物理镜头 `intrinsicZoomRatio≈0.5`，因 `minZoom` 被钉在 1f，`resolveRequestedPhysicalCameraId` 永远选不到广角镜头，`recreateSessionForPhysicalZoomIfNeeded` 永不触发切镜 → **1x→广角不可用**。
- **Bug 6**：`doCapture` 用固定 `delay(1200)` 后 `capturing=false`。真实存储（落盘 + `imageSavedEvent`）远晚于 1200ms → 动画提前结束、之后很久才存储；相册存入闪屏也早于真实写入。
- **Bug 7**：Profile 的高光/阴影只经 **LUT 后处理**（`LutImageProcessor` / `LutRenderer`），未进入 RAW 去马赛克阶段。用户要求 Profile 在 **JPEG 生成之前** 参与成像并动态影响高光/阴影。

---

## 3. Profile 参与点（修复后）

```
currentLutId (默认 "standard")
   └─ currentRecipeParams : StateFlow<ColorRecipeParams>   // LutManager 按 LUT 读取
        ├─ highlights
        └─ shadows
             │
             ├─(旧) → LutRenderer / LutImageProcessor   // 预览 & JPEG 后 LUT
             └─(新增, Bug7) → resolveCaptureRawToneMappingParameters
                                → RawToneMappingParameters.profileHighlights/Shadows
                                   → RawDemosaicProcessor.ShadowsHighlightsParams
                                      → 去马赛克阶段（JPEG 生成前）✅
```

`resolveCaptureRawToneMappingParameters`（CameraViewModel.kt:1044）统一在 5 个调用点注入当前 profile 配方。

---

## 4. 各 BUG 的 Diff → Root Cause → 最小修复方案

### BUG 1 & 2 — RAW 关/开时 JPG MAX / RAW MAX 不走多帧融合降噪锐化，帧数设置无效

**Root Cause**：2s 看门狗 `handleMultiFrameSequenceFinished` 在 GPU 堆叠未完成时误判收齐并 `forceResetCaptureState`，打断 `processStacking`；慢设备上多帧路径被中途复位，退化为单帧直出 → 帧数无效。

**最小修复**：引入 `multiFrameDispatched` 标志，正常路径启动 `processStacking` 后置 `true`；看门狗检测到该标志则跳过强制复位（说明真实堆叠已接管）。

```kotlin
// CameraViewModel.kt
private var multiFrameDispatched = false            // :1716 字段
// onImageCaptured 收齐后：
multiFrameDispatched = true                          // :1855
// capture() 发起新一次捕获前复位：
multiFrameDispatched = false                         // :2993
// handleMultiFrameSequenceFinished 看门狗：
if (multiFrameDispatched) { /* 真实堆叠已接管，跳过强制复位 */ return@launch }  // :5596
```

**验证**：5 个调用点（`resolveCaptureRawToneMappingParameters` @ 2845/5225/5452/5736/6187）不受影响；标志在 `capture()` 起点复位，避免状态泄漏。

---

### BUG 3 — Live 图只有图片没有视频，未合成 Live 图

**Root Cause**：运行时权限清单 `neededPerms` 未包含 `RECORD_AUDIO`，应用启动时未请求麦克风 → `LivePhotoRecorder.initAudio`（检查权限后未授权即 `return`）提前退出 → 只录了静态帧、音视频轨缺失，`MotionPhotoWriter` 无法合成。静态帧本身按互斥规则本就走多帧（非 bug）。

**最小修复**：在 `neededPerms` 中补 `RECORD_AUDIO`（Manifest 权限早已声明，仅缺运行时请求）。

```kotlin
// CameraScreen.kt :186
val neededPerms = buildList {
    add(Manifest.permission.CAMERA)
    // 0.9.8 修复：Live Photo / 视频录制需要麦克风权限录制音轨
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { /* 旧 API 存储权限 */ }
}
```

---

### BUG 4 — WB 默认显示错误色温，需拨一次 AWB 才正确

**Root Cause**：AUTO 模式下 UI 写死显示静态 `awbTemperature`（默认 5000K），未接入相机实时回传的 `actualAwbTemperature`；该实测值仅在手动冻结分支被读取。

**最小修复**：AUTO 下文本与滑块改显示 `actualAwbTemperature`（无实测值时回退 `awbTemperature`），手动模式才用冻结的 `adjWbTemp`。

```kotlin
// CameraScreen.kt :1058-1066
Text("色温 ${(if (awbOn) (state.actualAwbTemperature ?: state.awbTemperature) else adjWbTemp.roundToInt())}K")
AdjustSlider(
    value = if (awbOn) (state.actualAwbTemperature ?: state.awbTemperature).toFloat() else adjWbTemp,
    center = 5000f, /* AUTO 下滑块灰置仅显示 */
)
```

---

### BUG 5 — 1x 到广角无法使用；1x 到广角不需要取景框动画

**Root Cause**：`setZoomRatio` 下钳位死在 `zoomRatioRange.lower`（=1f），阻断向 `intrinsicZoomRatio≈0.5` 的超广角物理镜头路由；`resolveRequestedPhysicalCameraId` 依 `getBoundPhysicalCameraId(zoomRatioByMain)` 选最近物理镜头，因 `minZoom` 钉在 1f 永远选不到广角，`recreateSessionForPhysicalZoomIfNeeded` 永不切镜。

**最小修复**：下钳位改为“全部物理镜头最小 `intrinsicZoomRatio`”，让缩放路由能落到广角；预览框 `applyZoomRequestSettings` 仍用 `effectiveZoom = minOf(zoomRatio, 1f)` 保持 1x 不动（满足“无取景框动画”）。

```kotlin
// Camera2Controller.kt :4575 / :5839（两处相同钳位，replace_all 一次改完）
val zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
val wideMinZoom = state.getCurrentCameraInfo()?.physicalCameras
    ?.minOfOrNull { it.intrinsicZoomRatio } ?: 1f
val minZoom = minOf(zoomRatioRange?.lower ?: 1f, wideMinZoom)
```

---

### BUG 6 — 拍摄处理中动画与真实进度不符；相册存入闪屏应存储完成后才出现

**Root Cause**：`doCapture` 用固定 `delay(1200)` 后 `capturing=false`，与真实落盘（`imageSavedEvent`）脱钩；动画提前结束，相册闪屏也早于实际写入。

**最小修复**：移除固定 delay，由 `imageSavedEvent` 驱动「停止处理中动画 + 播放存入相册闪屏」。

```kotlin
// CameraScreen.kt :451
LaunchedEffect(Unit) {
    pvm.imageSavedEvent.collect {
        capturing = false          // 真实存储完成才停动画
        triggerCaptureFeedback()   // 存入相册闪屏
    }
}
// doCapture 中仅负责发起 pvm.capture()，不再 delay 后强制置 false
```

---

### BUG 7 — Profile 应在 JPEG 生成前参与成像，可动态影响高光/阴影

**Root Cause**：Profile 高光/阴影只走 LUT 后处理，未进入 RAW 去马赛克（JPEG 生成前）阶段，无法“动态影响高光和阴影”。

**最小修复**：在 `RawToneMappingParameters` 增加 `profileHighlights/profileShadows` 字段并 `normalized()` 透传；`RawDemosaicProcessor` 在 `ShadowsHighlightsParams` 叠加；`CameraViewModel.resolveCaptureRawToneMappingParameters` 注入当前 `currentRecipeParams`。

```kotlin
// RawToneMappingParameters.kt :14-15 / :48-49
val profileHighlights: Float = 0f,
val profileShadows: Float = 0f
// normalized() 中透传：
copy(..., profileHighlights = profileHighlights, profileShadows = profileShadows)

// RawDemosaicProcessor.kt :3530-3531
val shadowsHighlightsParams = ShadowsHighlightsParams(
    highlights = effectiveHighlightsAdjustment + rawToneMappingParameters.profileHighlights,
    shadows   = rawShadowsAdjustment      + rawToneMappingParameters.profileShadows,
)

// CameraViewModel.kt :1044-1051
private fun resolveCaptureRawToneMappingParameters(userPrefs: UserPreferences?): RawToneMappingParameters {
    val base = userPrefs?.rawToneMappingParameters ?: RawToneMappingParameters.DEFAULT
    val recipe = currentRecipeParams.value
    return base.withPhotonHdr(true)
        .copy(profileHighlights = recipe.highlights, profileShadows = recipe.shadows)
}
```

---

## 5. 交付清单（已全部完成 ✅）

- [x] BUG 1/2：多帧看门狗竞态（`multiFrameDispatched` 守卫）
- [x] BUG 3：运行时 `RECORD_AUDIO` 权限请求
- [x] BUG 4：AUTO 色温跟随 `actualAwbTemperature`
- [x] BUG 5：广角感知缩放下钳位（两处）
- [x] BUG 6：`imageSavedEvent` 驱动动画收尾
- [x] BUG 7：Profile 高光/阴影注入 RAW 去马赛克
- [x] 版本号 0.9.7 → **0.9.8**（code 37 → 38）
- [x] 编译 `assembleDebug`：**BUILD SUCCESSFUL**（仅既有 deprecation 警告，无错误）
- [x] 上传 R2：APK 69.6MB → HTTP 200；`version.json` 371B → HTTP 200

**公开下载**
- APK：`https://app.tybtool.top/PhotographerCamera-0.9.8.apk`
- 版本检查：`https://app.tybtool.top/version.json`

## 6. 编译中修复的衍生问题（Bug5 实现修正）

首轮编译报错：`CameraState` 无 `getCurrentCameraInfo()`，且 `setZoomRatio` 作用域内**不存在 `state` 变量**（`applyZoomRequestSettings` 有 `state: CameraState` 参数，而 `setZoomRatio` 须改用控制器属性 `_state.value`）。最终两处统一改为：

```kotlin
val wideMinZoom = _state.value.getCurrentCameraInfo()?.minZoom ?: 1f
```

`CameraInfo.minZoom` 即「广角时 < 1.0」，直接放开下钳位到超广角（0.5x），无需遍历 `physicalCameras`。

## 7. 已知限制 / 待真机验证

- Bug 3 静态修复已编译通过，Live Photo 音视频合成需在真机确认 `MotionPhotoWriter` 正常产出。
- Bug 1/2 看门狗竞态修复依赖 `multiFrameDispatched` 在 `capture()` 起点的复位，需中低端机实测多帧出图稳定性。
- 其余 Bug 4/5/6/7 为逻辑修复，编译通过后即可验证。
