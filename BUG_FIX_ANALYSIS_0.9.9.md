# 0.9.9 变更分析链（Upstream 状态机 → 当前项目状态 → Diff → Root Cause → 最小修复方案）

> 上游基准：bjzhou/PhotonCamera @ main（com.hinnka.mycamera），本地镜像 `C:/Users/70898/WorkBuddy/upstream_PhotonCamera/`
> 本项目：PhotographerCamera 0.9.8 → 0.9.9（com.photographercamera）

---

## 点1 LIVE 模式没有视频

**Upstream 状态机**：LivePhotoRecorder 已重写为「预采集缓冲 + 关键帧保底」：
- `preCaptureBufferingEnabled=true`（默认开）→ 预览帧持续进环形缓冲（`onPreviewFrame` 无条件入栈）
- 拍摄经 `PendingCaptureStart(CompletableDeferred, 2s 超时)`：编码器请求 `REQUEST_SYNC_FRAME`，等到**关键帧**才 `ready.complete()` → 视频起点保证可解码
- `retainCaptureFrom/releaseCaptureRetention` 锁住拍摄区间帧，防止环形缓冲覆盖
- `recordVideo(timestampUs, captureStartTimestampUs, onCaptured)`；`onCaptured(File("error"),0)` 仅真异常

**当前项目状态**：旧版 recorder + 0.9.7 看门狗补丁（`CAPTURE_WATCHDOG_MS=10s`、`cancelPendingCapture`、`pendingCaptureCallback`）。`recordVideo` 有 4 个静默失败分支（缓冲空/无关键帧/样本过少/中心点越界）→ 产出 `File("error")` → GalleryManager 收到 error 文件 → **不合并视频**。链路两端（GL 喂帧 `LutRenderer.onPreviewFrame`、VM 5311/5823 请求视频、MotionPhotoWriter 合成）均完整。

**Diff**：~231 行。上游把「视频起点」从事后找关键帧（赌运气，找不到就 error）改为「事前锁定关键帧起点（请求 sync frame + retention）」。

**Root Cause**：当前 recorder 是旧实现，拍摄时视频起点靠事后回溯关键帧；一旦关键帧不在保留区间/样本不足即静默失败 → 只有图片没有视频。

**最小修复**：整体移植上游 `LivePhotoRecorder.kt`（仅改包名/import；附加 fork 兼容 shim `cancelPendingCapture` 供 4 处现有调用点编译）；`Camera2Controller.recordLivePhotoVideo` 增加 `captureStartTimestampUs` 参数（现有点传 null = 非闪光灯路径）；不移植手电筒拍摄流（`startCaptureAfterTorch` 等），行为与上游非闪光灯场景一致。0.9.8 已加的 RECORD_AUDIO 运行时请求保留（音轨）。

## 点2 JPG MAX 帧数默认 5

`MultiFrameConfig.DEFAULT_DENOISE_FRAME_COUNT` 当前**已是 5**（与上游一致）。用户仍见 6：旧版本默认 6 已被持久化（`jpg_multi_frame_denoise_frame_count`）。**修复**：读取处一次性迁移 stored==6 → 5（注释标明旧默认迁移），AboutCard「默认 6 帧」文案修正为 5。

## 点3 RAW MAX HDR+ 帧数默认 5 + HDR+ 开关答疑

- 上游默认 `DEFAULT_HDR_PLUS_FRAME_COUNT=3` → 按指令改 **5**（同样迁移 stored==3 → 5）。
- **HDR+ 没有独立开关**：上游/当前 `use_raw_max`（RAW MAX 模式）**就是** HDR+ 开关，默认 false，进入 RAW MAX 模式即启用（且需 RAW 开）。无需新增开关。

## 点4 去掉 P3 色域 + 10位YUV 开关，固定关

**当前状态**：IMAGING 页两个 SwitchRow（AppSettingsScreen ~523/539），prefs `use_p3_color_space`/`use_p010`。
**修复**：删除两行 UI；`UserPreferencesRepository` 映射处两字段**硬编码 false**（忽略存储值，注释标明 0.9.9 固定关）。控制器逻辑保留（恒收 false）。

## 点5 配置文件色调映射开关有用吗？

**有用，保留**。它控制 RAW 显影是否走 profile 色调映射曲线（`RawProfileToneMapMode.Profile` vs `Default`），上游默认 true，经 `RawToneMappingParameters.profileToneMapMode` → MultiFrameStacker/去马赛克消费。与 0.9.8 注入的 profile 高光/阴影独立。

## 点6 降噪等级/锐化等级/色调映射/修复预览异常/修复拍摄异常

- **降噪等级** `nr_level`（0=Off,1=Fast,2=HQ,3=ZSL,4=Minimal，默认 2=HQ）：上游在 PHOTO_MODE 页；当前 prefs+VM 同步+Camera2 `NOISE_REDUCTION_MODE` 全链路在，**仅缺 UI** → 加回（枚举行，上游 key/默认/范围）。
- **锐化等级** `edge_level`（0-3，默认 1）：同上，`EDGE_MODE` 链路在 → 加回。
- **修复色调映射预览/拍摄异常**：**上游无此二开关**（全库 0 匹配；本项目 0.9.0 注释亦记录「引擎无对应物」已移除）→ 不恢复。
- 色调映射：见点8（引擎选择器）。

## 点7 RAW MAX 调优组放回成像与色彩

当前 prefs **全部已存在且被 GalleryManager RAW 管线消费**（`raw_max_sharpening` 0.4 / `raw_max_noise_reduction` 1.0 / `raw_max_chroma_noise_reduction` 1.0 / `raw_max_output_scale` 1..2 / `use_jpeg_444_export` false + Jpeg444ExportEncoder），**仅缺 UI**。上游没有名为「画质调优」的开关（最接近=输出倍率滑杆）。**修复**：IMAGING 页加：锐化滑杆(0..1)、亮度降噪滑杆、色度降噪滑杆、输出倍率滑杆(1..2)、JPEG 4:4:4 开关——全部照上游 key/默认/范围接线到现有 setter。

## 点8 渲染引擎默认 AgX

**当前状态**：`RawRenderingEngine` 枚举含 AgX（shaderId=1, BT2020）；默认 AdobeCurve 固定在 4 处：`RawRenderingEngine.fromPersistedName` fallback、`UserPreferencesRepository` 数据类默认、VM `resolveCaptureRawRenderingEngine` 回退、VM `stateIn` 初始。AgX 曲线参数当前=上游默认（black -10 / white 6.5 / toe 1.5 / shoulder 3.3），无需改。**修复**：4 处默认改 `AgX`；IMAGING 页新增渲染引擎选择行（全枚举，接 `setRawColorEngine`）。注：若设备上已持久化 `AdobeCurve`（预设应用过），选择器里手动切 AgX 一次即可。

## 点9 成片体积 1MB vs 上游 5MB

**已验证一致**：采集尺寸选择（`getBestCaptureSize` 同源）、JPEG 质量（95-100）、4:4:4 导出编码器存在。上游多一个 `HighResolutionHelper`（多 MP 能力检测）但未接入其默认拍摄管线。**静态对比无决定性分歧** → 本版**埋点**：最终保存处记录「LUT 输入/输出 bitmap 尺寸 + JPEG 质量 + 字节数」，与既有「拍照尺寸」日志配合，一轮真机拍摄即可定位（嫌疑排序：JPG MAX 堆叠纹理上限、画幅裁切、LUT 处理尺寸）。

## 点10 保存动画：缩略图先显第一帧，完成后显成片

**Upstream 状态机**：快门时 `generateThumbnail()` 经 `glView.captureOriginalPreviewFrame` 抓**原始预览帧** → `previewThumbnail` → 相册按钮立即显示；成片存储后替换为成片（`enable_develop_animation` 默认 false）。
**当前状态**：VM 已有同款 `generateThumbnail()/previewThumbnail`（拍摄流程 3043/3061/3110 调用，帧已抓到），但 UI `LastCaptureThumb` 只绑 `lastCapture`（存储后才刷新）→ 处理期间显示**上一张旧图**。
**修复**：底栏接入 `previewThumbnail`：处理中（`capturing && previewThumbnail!=null`）优先显示预览帧；`imageSavedEvent`（0.9.8）后 `lastCapture` 刷新为成片自动接管。

## 点11 1x 以下缩放仍不可用

**Upstream 状态机**：预览直接提交 `CONTROL_ZOOM_RATIO = zoomRatio`（含 <1f，`shouldUseControlZoomRatio` 要求 `zoomRatioRange.lower<1f`），无预览钳制。
**Diff**：当前 `applyZoomRequestSettings` 多了 `forPreview` 分支：`effectiveZoom = minOf(zoomRatio, 1f)`（0.9.6 取景框冻结设计）→ **预览钳死 1x**，拖到 <1x 取景毫无变化（拍照路径 0.9.8 已放开但用户看不见）。
**Root Cause**：预览提交端钳制，非枚举/钳位问题（`CameraDiscovery` 已填 `minZoom=zoomRatioRange.lower`，UI `globalMinZoom`<1 正常）。
**最小修复**：删除预览钳制（`effectiveZoom = zoomRatio`，与上游逐字一致）；1x↔广角切换仍无取景框动画（UI 层本就无动画）。

---

## 交付清单
- [x] 点1 Live 整体移植上游 recorder（+fork shim；含 CircularSampleRecorder 升级：
      补 retainedStarts 保留区 + retainFrom/releaseRetention + trimStorage 保留优先，
      支撑预采集缓冲不被裁剪）
- [x] 点2/3 帧数默认 5 + 旧值迁移；HDR+ 答疑
- [x] 点4 P3/P010 移除并固定关
- [x] 点5 配置文件色调映射保留（有用）
- [x] 点6 降噪/锐化等级加回；修复×2 不恢复（上游无）
- [x] 点7 RAW MAX 五项 UI 接回（引擎侧全在）
- [x] 点8 默认 AgX + 引擎选择器
- [x] 点9 埋点定位（静态无决定性分歧）：exportPhoto 最终 JPEG 记录
      输入/输出 bitmap 尺寸 + quality + 字节数（log key: exportPhoto JPEG result）
- [x] 点10 缩略图先预览帧后成片（BottomPanel→LastCaptureThumb 接 processingPreview）
- [x] 点11 预览钳制删除
- [x] 版本 0.9.8 → 0.9.9（code 39）+ 编译 + R2 发布
- [x] 附加修复：bugly `latest.release` 动态版本钉死 4.1.9.3（动态版本每次构建需联网
      查元数据，网络抖动直接 fail build，本次复现）
- [x] git 提交 1bf9c4a 推送 GitHub（补齐 0.9.7/0.9.8 包迁移的 catch-up）

## 编译与发布记录
- BUILD SUCCESSFUL（gradle-9.6.0 assembleDebug，4m27s；首轮失败 =
  bugly 动态版本元数据解析 + LivePhotoRecorder shim 引用不存在的
  captureStartTimestampUs 字段（改用 captureMinimumTimestampUs）+
  CircularSampleRecorder 缺保留区 API，均已修复）
- APK: PhotographerCamera-0.9.9.apk，69,613,650 字节
- 公网: https://app.tybtool.top/PhotographerCamera-0.9.9.apk （HTTP 200，
  Content-Length 一致）；version.json → versionName 0.9.9 / versionCode 39
