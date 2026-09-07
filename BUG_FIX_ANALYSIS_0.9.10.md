# 0.9.10 轮 7 点问题分析链与修复方案

> 生成于 2026-09-07。约束遵守：修改代码前先给出「Upstream 状态机 → 当前项目状态 → Diff → Root Cause → 最小修复方案」。
> 证据来源：线上日志 `_logs_0910/s01.txt`/`s02.txt`、上游 v2 镜像（HEAD 6df2af55）、用户上游 release APK 截图、当前代码逐行取证。

---

## 点1/A：RAW MAX 画质调优开关（上游 MAX&HDR 菜单）

**Upstream 状态机**：用户上游 release APK 的 MAX&HDR 菜单含「RAWmax 画质调优」布尔开关（默认开，描述"按当前物理传感器尺寸应用融合、降噪和锐化调优。关闭时使用默认成像参数"）、Ultra HDR 增益图（默认关）、RAWmax 融合模式（Sabre/Spatial）、RAWmax 输出倍率、Max 帧数（JPGmax 与 RAWmax 共用）、RAWmax 默认锐度。**该开关在公开源码（HEAD 6df2af55）中不存在**——用户安装的 release 比公开源码新，无法直接抄源码，只能按截图语义自实现。

**当前项目状态**：无画质调优总开关；RAWmax 融合模式硬编码 `MgcRawMaxMode.SPATIAL`（CameraViewModel.kt:5702）；Ultra HDR 增益图有完整链路（prefs `auto_enable_hdr_for_hdr_capture` + `setUltraHdrGainMapEnabled` VM:4048 + 消费端 VM:2976）但**无 UI 入口**；锐化/亮度降噪/色度降噪/输出倍率滑杆 0.9.9 已接好。

**Diff**：上游公开源码无此开关（用户 APK 更新）→ 语义自实现。

**Root Cause**：fork 时的画像调优参数链（0.9.9）只搬了滑杆，没搬总开关；融合模式写死 SPATIAL；Ultra HDR 只搬了链路没搬 UI。

**最小修复方案**：
1. prefs 增 `raw_max_quality_tuning`（默认 true）+ `raw_max_merge_mode`（"SPATIAL"/"SABRE"，默认 SPATIAL）；VM 增 StateFlow + setter。
2. `resolveCaptureSharpening` / `resolveCaptureDenoiseStrengths` 内读 `rawMaxQualityTuning`：关 → 回退默认成像参数（`RawSharpeningDefaults.DEFAULT_STRENGTH` / `RawDenoiseDefaults` 默认值），忽略用户滑杆。
3. VM:5702 硬编码改为读 prefs（valueOf 容错回退 SPATIAL）。
4. AppSettingsScreen 成像与色彩页 RAW MAX 组顶部新增：画质调优 SwitchRow、Ultra HDR 增益图 SwitchRow、融合模式 ChoiceRow（全部复用现有组件，不做视觉重设计，符合 UI 冻结令的"架构必须的最小 UI 入口"豁免——用户点名要求此开关）。

## 点2/B：右下角缩略图刷新不及时（点一次快门才刷新）

**Upstream 状态机**：上游在保存协程 launch 之后立即 `_imageSavedEvent.emit(Unit)`（v2/CameraViewModel.kt:5968）；上游 UI 不依赖该事件刷缩略图（缩略图由相册页自刷）。

**当前项目状态**：CameraScreen.kt:333-343 collect `imageSavedEvent` → `CaptureSaver.list(context).firstOrNull()` 查 MediaStore；而 VM 在**保存协程刚 launch、成片尚未 publish** 时就 emit（6 处，单帧 5372 / burst 5905）。

**Diff**：emit 时机（保存启动前 vs 发布后）。

**Root Cause**：emit 时 MediaStore 里本张照片行还不存在（或 IS_PENDING=1），`firstOrNull()` 按DATE_ADDED 排序拿到的是**上一张** → 缩略图永远滞后一次拍摄。

**最小修复方案**：把两个保存路径的 emit 移入 IO 协程内、**save 全部完成后**再 emit（此时 publish 已完成、IS_PENDING=0，firstOrNull 必中本张）。无需改事件 payload、无需改 UI——一处时序修正同时解决滞后与准确性。

## 点3/C：LIVE 图没有视频（务必修复）

**Upstream 状态机**：v2/CameraViewModel.kt:5937-5940 — `launch { GalleryManager.saveVideo(context, photoId, livePhotoVideoDeferred); GalleryManager.saveStackedPhoto(...) }`，**严格串行**：saveVideo 内部 `deferred.await()` 等录制回调把视频文件落地（copy 到 `video.mp4` + 写 presentationTimestampUs 元数据），然后才保存成片。成片导出（exportPhoto）的 Motion Photo 合成分支以 `isLivePhoto = videoFile.exists()`（GalleryManager.kt:1387）判断，此时视频必然就位。

**当前项目状态**：单帧路径 VM:5344-5369 / burst 路径 VM:5865-5896 — `videoSaveJob = async { saveVideo(...) }` 与 `savePhoto/saveStackedPhoto` **并行**，`videoSaveJob.await()` 在 save 之后。线上日志铁证：`Attempting to create Motion Photo: JPEG=5156696, Video=0` → OppoMotionVideoExtender setDataSource 失败 → MotionPhotoWriter result:true → Published（无视频版）→ 之后 LivePhotoRecorder 才 Muxed 118 帧。并行化是 **0.9.9 为缩略图提速引入的回归**。

**Diff**：串行（上游） vs 并行（0.9.9 回归）。

**Root Cause**：exportPhoto 合成 Motion Photo 时 `video.mp4` 不存在/0 字节 → `Attempting to create Motion Photo: Video=0` → 发布无视频版本；saveVideo 完成后的二次合成不重新 publish。

**最小修复方案**：两个保存分支恢复上游严格串行——先 `GalleryManager.saveVideo(...)`（内部 await deferred，保留 fork 的 `LIVE_PHOTO_VIDEO_TIMEOUT_MS` 防呆超时：录制器无响应时降级无视频发布，绝不永久卡住保存链），再保存成片。缩略图时序由点B 的 emit 后移保证，不再依赖并行提速。合成时的 presentationTimestampUs 也随元数据先行写入而正确（修复 TS=0）。

## 点4/D：JPEG MAX 4.5MB vs RAW MAX 1.8MB

**Upstream 状态机**：上游 RAW MAX 同样走 AgX 渲染 + 多帧融合降噪，无体积相关开关。

**当前项目状态**（日志取证）：两种模式分辨率一致（RAW 2880x3840/3072x4096、采集 4096x3072 vs YUV 3840x2880）、编码参数一致（q95、4:4:4）——0.9.9 埋的 exportPhoto 日志已证实非分辨率/质量/采样因素。

**Diff**：唯一变量是**内容熵**：RAW 栈管线 = 多帧对齐融合 + 亮度/色度降噪（默认强度 1.0）+ AgX 色调映射（高光压缩、局部对比平滑）→ 场景细节被平滑，JPEG 熵编码后体积显著变小；JPEG MAX 是 YUV 直采 + 较轻处理，纹理噪声残留多 → 体积大。

**Root Cause**：预期行为偏差的疑点，非缺陷级 BUG；但需一轮受控对比实验定位是"AgX 曲线"还是"RAW 栈降噪平滑"贡献主导。

**最小修复方案**：不加代码。0.9.9 已具备实验条件（渲染引擎切换 UI + exportPhoto 字节日志）。实验协议：同场景固定机位 → ①RAW MAX（AgX 引擎）拍一张 ②RAW MAX（Adobe 曲线引擎）拍一张 ③JPEG MAX 拍一张，对比三者 bytes 与 100% 放大纹理。若 Adobe 版体积回升且细节更多 → 确认 AgX+降噪为根因（行为符合预期，可调降噪滑杆）；若 Adobe 版仍 1.8MB → 疑 RAW 导出管线存在额外平滑，下轮深挖。

## 点5/E：保存期间再按快门 → 永久转圈、相机失效

**Upstream 状态机**：上游三层——①保存期间再按快门被 controller guard 直接拒绝（不做并行成像）；②上游 UI **不绑定 isCapturing**（无转圈动画），拒绝发生时用户无感知；③复位靠 onImageRelease（图像引用计数归零），上游无 watchdog。所以上游"没这个 BUG"的真相是：拒绝机制 + 无 UI 反馈，不存在卡死的可见症状。

**当前项目状态**（三层取证）：
- **UI 层**（CameraScreen.kt:433-471）：`capturing` 状态 + 双 shutter guard；guardJob 8s 兜底在 `finally` 里被 cancel——而 `pvm.capture()` 非 suspend 立即返回，finally 瞬时执行 → **8s 兜底从未生效**；`capturing` 唯一复位源 = imageSavedEvent。
- **VM 层**：saveImage 单帧路径的保存协程**无 try/catch**（异常击穿全局 handler）；early-return（characteristics null 5320-5323、photoId null 5334-5337）、catch（5373-5374）、burst 路径 catch（5897-5902）均只记日志/关图，**不 emit 任何事件** → UI 转圈永久。
- **Controller 层**：isCapturing 复位靠 onImageRelease + fork 自带的 30s capture watchdog（Camera2Controller.kt:246-265）——但 watchdog 只复位 controller 状态，UI `capturing` 不知情。

**Diff**：上游无转圈（无复位需求） vs 本 fork 有转圈但复位链只有一条且失败路径全断。

**Root Cause**：转圈复位是单点依赖（imageSavedEvent），所有失败路径（保存异常、early-return、onCameraError）都不触发它；guard 兜底被 finally 秒取消。

**最小修复方案**（保持上游"保存期间拒绝新拍摄"语义不变，只把复位链修可靠）：
1. VM 增 `captureFailedEvent: SharedFlow<Unit>`（extraBufferCapacity=8）。
2. emit 点：saveImage 两处 early-return、外层 catch、保存协程新加的 try/catch；burst 路径 characteristics/photoId early-return、外层 catch、保存协程 catch；`onCameraError`。
3. CameraScreen collect captureFailedEvent → `capturing = false`（无闪屏/无缩略图动画）。
4. doCapture guard 兜底真实化：不再被 finally 取消，独立驻留 30s（与 controller watchdog 对齐），仅当 capturing 仍 true 时复位；每次快门取消旧 guard 换新 guard。
5. 顺带补上单帧保存协程缺失的 try/catch（防异常击穿全局 handler 闪退 + 图像泄漏）。

## 点6/F：两个帧数滑块调整时反复跳动

**Upstream 状态机**：上游 SliderSettingItem 同样是 flow 绑定，上游设置页为独立 Activity/低频重组，回流滞后不可见。

**当前项目状态**：JPG max 帧数（AppSettingsScreen.kt:286-320）、HDR+ 帧数（321-354）+ 0.9.9 的 FloatSliderRow helper（807-843，覆盖 RAW MAX 锐化/亮度降噪/色度降噪/输出倍率 4 个滑杆）——全部是 `drag ?: flowValue ?: fallback` 单层模式。

**Diff**：无 pending 锁定。

**Root Cause**：松手瞬间 `drag=null`，setter 异步写 DataStore → flow 回流滞后一拍 → shown 短暂回跳旧值、flow 到达后再跳新值 = "反复跳动"。

**最小修复方案**：pending 确认模式——松手时 `pending = 提交值`，shown = `drag ?: pending ?: flowValue ?: fallback`；`LaunchedEffect(flowValue)` 检测 flow 追上（Float 用 0.005 容差、Int 精确比较）后清除 pending。共修 6 个滑杆（2 手写 + FloatSliderRow 内部 1 处覆盖 4 个）。

## 点7/G：假开关全面审计

逐条核对 AppSettingsScreen 全部 setter（24 个）的「UI → VM setter → prefs → 消费端」链：

| 开关 | 链路 | 判定 |
|---|---|---|
| 快门声音/震动/音量键/前置镜像/保存地址 | controller/preferences 直连 | 真 |
| JPG/HDR 帧数 | MultiFrameConfig + 多帧调度 | 真 |
| 对焦/眼控对焦/默认焦段/朝向偏移/逻辑多摄/镜头黑名单/微距偏好 | 0.9.6-0.9.8 已验证链 | 真 |
| 降噪/锐化等级 | 上游同款 NRLevel/EdgeLevel | 真 |
| RAW MAX 锐化/亮度降噪/色度降噪/输出倍率 | resolveCapture* → 成像管线 | 真（受点A 总开关管辖后语义更完整） |
| JPEG 4:4:4 | exportPhoto jpeg444（日志证实消费） | 真 |
| RAW 渲染引擎 | RawRenderingEngine 全链 | 真 |
| 配置文件色调映射 | RAW 显影分支 | 真 |
| **Ultra HDR 增益图** | 链路真、**UI 缺失** | 本轮补 UI（点A） |
| **RAW MAX 画质调优** | 本轮新建 | 本轮落地（点A） |
| **RAW MAX 融合模式** | 硬编码 SPATIAL（假开关形态） | 本轮持久化+UI（点A） |

**结论**：无"点了没反应"的纯假开关；缺口是 1 个无 UI 的真链路（Ultra HDR）+ 2 个本轮新增项。关于页 AboutCard 文案随融合模式可配置化同步修正。

## 实施清单（本文件落盘后立即执行）

1. `UserPreferencesRepository.kt`：+2 字段/+2 key/+2 save 函数/mapper 读取
2. `CameraViewModel.kt`：captureFailedEvent、resolve* 调优开关、rawMaxMode 读 prefs、2 组 setters、单帧+burst 保存路径串行化（点C）+ try/catch + emit 后移（点B）+ 失败 emit（点E）、onCameraError emit
3. `CameraScreen.kt`：guard 兜底真实化、captureFailedEvent collect
4. `AppSettingsScreen.kt`：滑块 pending 模式（6 杆）、画质调优/Ultra HDR/融合模式三控件
5. 版本 0.9.10(40) → 编译 → review → R2 发布 → git push
