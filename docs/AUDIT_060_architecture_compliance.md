# 架构合规与死代码审计报告（v2 · 1.7.6 基线）

- 审计时间：2026-10-15
- 审计基线：当前 main HEAD = `c4523dc`（1.7.6，JPEG MAX 降噪对齐 RAW MAX 已发布）
- 范围：android/app 全部 Kotlin + assets + tools + tests
- 方法：全项目符号引用分析（调用图）+ 逐段代码阅读 + file:line 证据 + 三级结论分级（已确认事实 / 代码推断 / 需真机验证）
- 配套报告（同目录 `_audit_phase_a/`，本报告为其架构部分摘要）：
  - `01_callchain.md` — 真实调用链（六条拍摄路径逐段链路）
  - `02_legacy_code.md` — 旧代码/死代码依赖表
  - `03_profile_param_map.md` — Profile 全字段参数映射表
  - `04_format_precision.md` — 图像格式与精度审计表
  - `05_test_baseline.md` — 测试基线与真实运行结果

---

## 0. 旧基线声明（0.7.2 版报告结论作废项）

本文档取代 2026-09-06 的 0.7.2 基线报告。旧报告以下结论**经复核已作废**，不得再引用：

| 旧结论 | 现状 | 证据 |
|---|---|---|
| 「Camera2Controller（8,799 行）零实例化，唯一引用是一行注释」 | **作废**。CameraViewModel.kt:508 `private val cameraController = Camera2Controller(application)` 是全项目唯一实例化点，init/openCamera/closeCamera/release 全链路由 VM 管理 | CameraViewModel.kt:508/1868/2663/2701/6677 |
| 「拍摄主力仍是旧 CameraX CameraEngine」 | **作废**。旧 core/camera/ 引擎已于 commit `4c463ab`（0.9.0）物理删除（15 文件 / -6,145 行），目录不存在；`shootBitmap/shootYuv/shootRawIsp` 零命中 | git ls-tree 4c463ab^ |
| 「RAW 回退链残留自研 raw_isp.frag」 | raw_isp.frag 仍在 assets 但**零加载点**，RAW 活链已全走 photon（CameraScreen.kt:1046 `pvm.setUseRaw`） | 02_legacy_code.md |
| 「CustomImportManager 是死代码」 | 引擎切换后导入链路已另建（HANDOFF_android_profile_import.md），本轮未复核其存活，见待办 | — |
| 「photon 栈利用率极低（295 模块仅 16 被引用）」 | 随引擎切换已反转，Camera2Controller/RawDemosaicProcessor/GlesMgc* 均为活链核心 | 01_callchain.md |

旧报告中「待引擎切换后处置」条目大部分已随 `4c463ab` 一次性清理；剩余项见第 3 节。

---

## 1. 当前架构（已确认事实）

```
CameraScreen.kt（纯 UI，:184 viewModel() / :185 collectAsState / :604 capture()）
    │ StateFlow + 事件（imageSavedEvent / captureFailedEvent，:613-626）
    ▼
CameraViewModel.kt（唯一引擎持有者）
    ├─ cameraController = Camera2Controller(application)   [:508]
    ├─ 生命周期：initialize() [:1868] / openCamera() [:2663] / closeCamera() [:2701] / release() [:6677]
    ├─ 拍摄总入口 capture() [:3062] → Camera2Controller.capture() [:7454]
    └─ 格式判定 Camera2Controller.kt:2451-2457（RAW_SENSOR / P010 / YUV_420_888）
            │ 帧回调
            ▼
    处理层（VM 内）：saveImage [:5355] / processStacking [:5858] / processHdrBracket [:6187] / prepareBurst 等
            ▼
    GalleryManager.kt（保存与导出编排）
            ▼
    PhotoProcessor → { RawDemosaicProcessor.kt（RAW 去马赛克/多帧）, LutImageProcessor.kt（Profile 调色/质感） }
```

- 单 Activity、Manifest 干净；CameraScreen 不再直接持有任何相机引擎。
- Live Photo 旁路：LivePhotoRecorder + MotionPhotoWriter（exportPhoto 内 :1511），失败回退纯 JPEG。

## 2. 真实调用链摘要（详见 01_callchain.md）

六条路径全部经 photon 引擎，总入口 `CameraViewModel.capture():3062`：

| 路径 | 入口 | 核心处理 | 输出 | 保存函数 |
|---|---|---|---|---|
| a. 普通 JPEG | saveImage:5355 → saveYuvPhoto:2000 | YuvProcessor → exportPhoto:1241 | JPEG | MediaStore |
| b. JPEG MAX | processStacking:5858 → saveYuvStackedPhoto:2901 | RawmaxExposurePlanner → MultiFrameStacker → GlesYuvStacker | JPEG | 同上 |
| c. RAW MAX | saveRawStackedPhoto:2971 | RawDemosaicProcessor.runStackingOnGlContext:3132 → GlesMgcRawFusion → GlesMgcRawSpatialStacker/SABRE | DNG + JPEG | trySaveStackedRawDng:3400 |
| d. HDR bracket | processHdrBracket:6187 → composeHdrBracketPhoto:2718 | GlesYuvStacker.processHdr | JPEG | saveBitmapPhoto:2668 |
| e. Live Photo | recordLivePhotoVideo:9129 → saveVideo:1925 | MotionPhotoWriter.write:1511 | Motion Photo JPEG | exportPhoto 内嵌 |
| f. 无 RAW 回退 | isRawSupported:2323 → effectivelyUseRaw 取反:2434 | 落回路径 a（YUV/P010 单帧） | JPEG | 同 a |

- 输入格式：RAW_SENSOR（Bayer）/ P010 / YUV_420_888；RAW 引擎向调色器交接 **RGBA_F16（HALF_FLOAT 读回）**（RawDemosaicProcessor.kt:8665/8676/8707/8714）。
- 缺帧收敛：JPEG MAX 有 2s 收敛 / 快速失败双分支（VM:5794）。

## 3. 已确认的废弃代码（详见 02_legacy_code.md；**本轮只判定不删除**）

| 对象 | 判定 | 证据摘要 |
|---|---|---|
| 旧 core/camera/ CameraEngine 全套 | **已清除**（DEAD-已删） | 4c463ab 删除；残留字样仅注释与历史文档 |
| raw_isp.frag（180 行） | DEAD | 全项目零加载点 |
| core/gpu/ 自研渲染器（ProfileRenderer 等 5 文件） | 已删；仅剩 GpuParams.kt | GpuParams 唯一链 ProfileLoader.toGpuParams() 零调用者（ProfileLoader.kt:115）→ 遗留死链路 |
| ui/preview/ 旧预览双实现 | 已删 | — |
| assets 16 个早期 shader + 扩展 15 个 | DEAD 31/31 | 整目录自 0.9.0 起无加载器 |
| pc_* 应急后门机制 | DEAD | 宿主为已删 CameraEngine；勿与在用 SharedPreferences 键 `pc_settings/pc_debug/raw_isp_enabled` 混淆 |

⚠️ **误删防护**：photon/ui/camera/ 下存在与已删 ui/preview/ **同名类**的活组件（CameraScreen.kt:815 实例化、CameraViewModel.kt:84/1742 持有）。后续任何按类名批量清理必须先核对包路径。

⚠️ **测试基础设施损坏**：`tools/static_cross_check.py:64` 仍读已删的 `core/gpu/ProfileRenderer.kt`，运行即 FileNotFoundError（05_test_baseline.md ④.1）。

## 4. 待真机验证事项（Phase D/G 准入）

1. 三条输出路径（普通 JPEG / JPEG MAX / RAW MAX）固定场景画质对比（需求 4.5）——需 PLG110 实拍。
2. RAW MAX 仅在设备支持时测试；无 RAW 设备回退路径实机确认（需求 4.8）。
3. LensStage 参数非零时 16F 链降级的实际观感影响（PA-01，需造非零 lens profile 实拍）。
4. 多帧融合后的尺寸/裁剪/旋转/镜头元数据正确性（需求 4.7）。
5. Ultra HDR gain map 与方向元数据在四条输出路径的一致性（需求 10.5）。
6. ColorPassProgram.minimalFallback 降级路径真机表现（编译失败时的最小变体画面）。

## 5. 与本报告相关的既有修复（1.7.6 已发布，勿回退）

- JPEG MAX 软件降噪已接入设置项（CameraViewModel.resolveCaptureDenoiseStrengths，commit c4523dc）——需求 4.2 明确要求保留，**不得重新硬编码为零**。
- 成片保存直传 Bitmap 规避方案（docs/DIAG_成片体积差异_jpgmax_rawmax.md）——需求 10.2 明确要求保留。

## 6. 结论

架构合规判定：✅ **符合**（相对 0.7.2 的「过渡态」已质变）。photon 引擎全链路接管，旧 CameraX 引擎物理清除，六条拍摄路径均可追踪到唯一入口与保存函数。遗留问题集中在 Profile 参数语义（见 03）、中间量化（见 04）与测试基线（见 05），已按严重度编号进入 Phase A 主报告问题清单，分派 Phase B–G 处理。
