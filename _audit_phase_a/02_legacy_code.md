# 旧代码依赖表 · Phase A 存活判定（只读审计）

- 审计基准：HEAD = `c4523dc`（feat(jpeg): JPEG MAX 对齐 RAW MAX 软件降噪）
- 审计范围：`C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera`
- Android 源码根：`.../android/app/src/main/java/com/photographercamera`
- 对比基线：`docs/AUDIT_060_architecture_compliance.md`（0.7.2 旧基线，2026-09-06）
- 审计性质：**只读**。未删除/未修改任何文件；唯一写操作是本报告。
- 结论速览：**DEAD 7 组（含 16+1 个 shader 文件、5+2 个已删 Kotlin 文件）／LIVE 0／UNVERIFIED 0**；
  另发现 2 个"防误删"事实与 2 处工具链/文档过期残留（详见 ②-8、②-9）。

---

## ① 判定方法说明

对每个对象执行以下引用面全查（全项目 Grep，覆盖 Kotlin/Java/XML/gradle/CMake/Python/JS/JSON/MD）：

1. **符号/文件名 Grep**：类名、文件名（含扩展名）、assets 相对路径（如 `shaders/xxx.frag`、`raw_isp`）。
2. **依赖注入/反射排查**：Grep `Class.forName` / `newInstance` / `loadClass`，确认无运行时反射加载旧类。
3. **资源加载面**：Grep `assets.open` / `openFd` / `assets.list` / `loadShader` / `readAssetBytes` 等，
   枚举 APK 侧全部资产加载点，核对是否触及目标 shader。
4. **AndroidManifest 组件声明**：核对 activity/service/provider/receiver 是否引用旧组件。
5. **同文件被活类引用**：对幸存文件检查其自身是否被其他活类 import/实例化。
6. **Git 考古**：`git log --diff-filter=D` + `git show <commit>^:<path>` 验证"疑似死代码"
   是被物理删除还是仍在树内，并还原删除前内容作为佐证。
7. **工具链面**：Grep `tools/*.py`、`tests/*`、`desktop|server_web|studio_worker/*.js`，
   排除"桌面端/服务端引用被误判为 APK 活引用"。

分级标准：
- **LIVE**：存在活引用（给出 `file:line` 证据）。
- **DEAD**：全项目零引用（列出查过的引用面）。
- **UNVERIFIED**：仅反射/运行时可能触发（说明原因）。

关键背景（架构已变的证据）：
- 提交 `e252068`（0.8.0 engine switch）接入 photon 全链路；提交 `4c463ab`（0.9.0(30)，
  2026-09-07）物理删除旧 CameraEngine 管线共 15 个文件（-6145 行）。
- 现行拍摄链：`CameraScreen.kt:184`（`val pvm: CameraViewModel = viewModel()`） →
  `CameraViewModel.kt:508`（`private val cameraController = Camera2Controller(application)`） →
  `photon/camera/Camera2Controller.kt`。**Camera2Controller 已真实实例化，引擎切换确已完成**，
  旧报告"零实例化"的结论已作废。
- RAW 开关现状：`CameraScreen.kt:1046`（`pvm.setUseRaw(rawOn)`）直连 photon 引擎，
  与自研 `raw_isp.frag` 无关。

---

## ② 逐个对象条目

### 对象 1：core/camera/ 旧 CameraX CameraEngine 全套 —— 判定：DEAD（已物理删除）

- 路径：`android/app/src/main/java/com/photographercamera/core/camera/`（**目录已不存在**）
- 删除前全套（`git ls-tree -r 4c463ab^` 实证，共 5 个文件）：
  - `CameraEngine.kt`（1,949 行，shootBitmap/shootYuv/shootRawIsp 三管线）
  - `ImageYuvConverter.kt`（69 行）、`RawImageConverter.kt`（232 行）
  - `RawFrame.kt`（32 行）、`StillFrame.kt`（49 行）
- 删除证据：commit `4c463ab`（0.9.0(30)，"remove 11 dead files (old CameraEngine pipeline)"），
  `git show 4c463ab --stat` 列明上述文件全量删除。
- 现存 "CameraEngine" 字样全查（Grep 全项目）——**无一是活代码引用**：
  - `ui/screens/CameraScreen.kt:246`（注释："持久化在 pc_settings.raw_isp_enabled（CameraEngine.setRawIspEnabled"）
  - `ui/screens/AppSettingsScreen.kt:21,102,140`（注释，明言"CameraEngine 已退役/旧引擎退役"）
  - `android/app/build.gradle.kts:38`（注释）
  - 其余命中均在 `docs/*.md`、`PhotographerCamera-0.x-report.md`、`交接卡.json`（历史文档）
- Manifest：`AndroidManifest.xml` 无任何相关组件声明（仅 MainActivity + FileProvider + 2 个 photon provider）。
- 反射：无 `Class.forName("...CameraEngine")` 之类命中。
- 备注：0.7.2 旧报告"拍摄主力仍是 CameraX CameraEngine / Camera2Controller 零实例化"**已失效**；
  现在连 CameraEngine 文件本身都已不存在，属"死代码已清除"状态。

### 对象 2：assets 里的 raw_isp.frag —— 判定：DEAD

- 路径：`C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera/android/app/src/main/assets/shaders/raw_isp.frag`（180 行，仍在树内、仍打进 APK）
- 引用面全查（Kotlin/XML/gradle/CMake/Python/JS）：
  - 全项目 Grep `raw_isp`：命中仅为 ① 历史文档（`docs/AUDIT_060...md:25,51,56`、0.2.5/0.3.x 报告）、
    ② shader 之间注释（`assets/shaders/effect.frag:56`、`assets/shaders/yuv_copy.frag:13`）、
    ③ 其自身第 131 行注释提及 "CameraEngine capture docs"（而 CameraEngine 已删）。
  - **零加载点**：APK 侧 `assets.open(...)` 调用点全枚举（UpdateChecker/PresetListScreen/CameraScreen/
    ProfileLoader/BasicToneLut/CustomImportManager/FilmData/FrameTemplateParser/MlDelegateCache/
    DcpProfile/ShutterSoundPlayer/RemoteLog/LutParser），无一打开 `shaders/raw_isp.frag` 或 `shaders/` 下任何文件。
  - Grep `shaders/`（android/ 全部 .kt）：仅注释命中（GpuParams.kt:3,10,12,21；Validation.kt:107；
    LutImageProcessor.kt:1101；CurveUtils.kt:113），无一个字符串字面量加载路径。
  - CMake/CPP：`android/app/src/main/cpp/` 为 dng-sdk/libjpeg-turbo，不涉及该 shader。
- 旧报告所述"RAW 失败回退走 raw_isp.frag"的回退链宿主（CameraEngine）已删除；现行 RAW 链为
  photon `RawDemosaicProcessor` 等，RAW 开关经 `CameraScreen.kt:1046 pvm.setUseRaw()` 入 photon 引擎。
- 备注：shader 内 `#include` 无（自包含单文件）；仅被注释提及。随 APK 打包（约几 KB 体积成本）。

### 对象 3：core/gpu/ 自研渲染器 —— 判定：DEAD

- 目录现状（Glob/find 实证）：`core/gpu/` 仅剩 **`GpuParams.kt`（249 行）**一个文件。
- 删除前全套（`git ls-tree -r 4c463ab^`）：`ProfileRenderer.kt`（1,438 行）、`GLSL.kt`（105）、
  `GpuFilterChain.kt`（166）、`RawCalibration.kt`（36）、`YuvNative.kt`（190）——均于 `4c463ab` 删除。
- 幸存者 `GpuParams.kt` 的活引用核查（全项目 Grep `GpuParams`）：
  - 唯一代码引用：`core/profile/ProfileLoader.kt:22`（import）、`:41`（gpuCache 字段）、
    `:115/:119`（`toGpuParams()` 定义与缓存）。
  - **`ProfileLoader.toGpuParams()` 全项目零调用者**（Grep `toGpuParams` 仅命中定义处
    ProfileLoader.kt:115 与自身注释 :46）。活链是 `CameraScreen.kt:500/504`
    （`ProfileLoader.getProfile` → `ProfileToRecipeMapper.map` → `ColorRecipeParams`），不经 GpuParams。
  - 桌面端/测试无引用（命中均为 docs/report/交接卡）。
- 备注：`ProfileLoader` 本体 LIVE（init/listProfiles/getProfile/import/iconPath/restoreBundled 均被
  CameraScreen/PresetListScreen/AppSettingsScreen 调用），**死的只是 `toGpuParams()` 方法 + `GpuParams.kt` 整文件**。
  GpuParams 仍参与编译但不产生任何运行时行为；建议随下一轮与文档同步处置（见 ④）。

### 对象 4：旧预览组件 ui/preview/CameraPreviewGL.kt + ui/preview/CameraGLSurfaceView.kt —— 判定：DEAD（旧路径已物理删除）；**同名活组件 LIVE，严禁误删**

- 旧路径（`ui/preview/`，删除前 `git ls-tree -r 4c463ab^` 实证 2 个文件）：
  - `ui/preview/CameraGLSurfaceView.kt`（442 行）、`ui/preview/CameraPreviewGL.kt`（335 行）
  - 于 `4c463ab` 删除；现 `ui/` 下仅剩 `components/ screens/ theme/`（find 实证）。
- **防误删事实（LIVE）**：现存同名组件位于 photon 包下且是活链：
  - `android/app/src/main/java/com/photographercamera/photon/ui/camera/CameraPreviewGL.kt`（335 行，Compose 组件）
    —— `ui/screens/CameraScreen.kt:815` 实例化 `CameraPreviewGL(...)`（import 见 :126）。
  - `android/app/src/main/java/com/photographercamera/photon/ui/camera/CameraGLSurfaceView.kt`（442 行）
    —— `photon/viewmodel/CameraViewModel.kt:84` import、`:1742` `var glSurfaceView: CameraGLSurfaceView? = null`；
    并由 `CameraPreviewGL.kt:169` 在 AndroidView factory 内创建。
  - 引入提交：`e252068`（0.8.0 engine switch）。运行时佐证：`_logs_0910/s02.txt` 中
    `CameraGLSurfaceView initialized` / `CameraPreviewGL onSurfaceReady` 日志刷屏。
- 备注：旧报告"预览双实现死分支"表述已过期——死的那半（ui/preview）已删，活的这半在 photon/ui/camera。
  任何按"CameraPreviewGL/CameraGLSurfaceView 关键字批量清理"的操作都会误删活预览，**必须按路径区分**。

### 对象 5：旧报告列的 16 个未加载 shader —— 判定：DEAD（16/16 零加载）

- 真实路径：`C:/Users/70898/WorkBuddy/workspace_transfer_2026-09-05/PhotographerCamera/android/app/src/main/assets/shaders/`（Glob 实证共 31 个文件）
- 逐一核对（文件名 Grep 全项目 + assets 加载点枚举）：

  | # | 文件 | 行数 | 加载引用 | 判定 |
  |---|---|---|---|---|
  | 1 | bloom.frag | 13 | 无（仅被 bloom.glsl 自我 include 链/文档提及） | DEAD |
  | 2 | bloom.glsl | 6 | 无 | DEAD |
  | 3 | blur.frag | 21 | 无 | DEAD |
  | 4 | exposure.glsl | 5 | 无 | DEAD |
  | 5 | extract_blur.frag | 32 | 无 | DEAD |
  | 6 | grain.glsl | 7 | 无 | DEAD |
  | 7 | grain_noise.frag | 37 | 无 | DEAD |
  | 8 | halation.frag | 19 | 无 | DEAD |
  | 9 | halation.glsl | 9 | 无 | DEAD |
  | 10 | highlight.glsl | 9 | 无 | DEAD |
  | 11 | hsl.glsl | 24 | 无 | DEAD |
  | 12 | mainchain.frag | 39 | 无 | DEAD |
  | 13 | noise.glsl | 12 | 无 | DEAD |
  | 14 | shadow.glsl | 16 | 无 | DEAD |
  | 15 | sharpen.glsl | 5 | 无 | DEAD |
  | 16 | tone_curve.glsl | 16 | 无 | DEAD |

  合计 270 行。命中项全部为：shader 自身首行注释、shader 间 `#include`（如
  `halation.frag:13 #include "halation.glsl"`、`grain_noise.frag:17-18`、`mainchain.frag:21-27`）、
  docs（`docs/rendering_pipeline.md:51`）、`交接卡.json:24,27`（陈旧条目）。
- 加载机制已消亡的根因：唯一加载器 `core/gpu/ProfileRenderer.kt` + `core/gpu/GLSL.kt`（原经
  `"shaders/xxx.frag"` 字符串加载）已于 `4c463ab` 删除；现行 photon 引擎用 Kotlin 内联 GLSL
  （如 `photon/lut/FilmGrainShaders.kt`、`ClarityShaders.kt`、`FilmGrainGl.kt`、`RawGlesProgram.kt` 的
  shaderDefinition 字符串），不读 assets 文件。
- **同目录扩展发现（超出 16 个清单，同样零加载）**：`assets/shaders/` 其余 15 个文件
  （blit.frag 8、chroma_denoise.frag 49、effect.frag 407、film_curve.frag 27、luma_denoise.frag 56、
  oes2d.frag 9、oes2d.vert 24、passthrough.vert 9、passthrough_oes.vert 11、sharpen.frag 25、
  vignette.frag 19、vignette.glsl 8、white_balance.glsl 12、yuv_copy.frag 75、color_matrix.glsl 9、
  common.glsl 59）亦无任何加载点——整个 `assets/shaders/`（31 文件 / 2,463 行）自 0.9.0 起已是
  "无加载器的资产包"。其中 effect.frag（407 行）是体积最大单体。
- 备注：`交接卡.json:27,31,35` 仍把 `assets/shaders/mainchain.frag`、`film_curve.frag`、`common.glsl`
  列为"权威文件/改动禁区"——该交接卡内容已过期（宿主加载器已不存在）。

### 对象 6：pc_* 应急后门机制（pc_yuv_off / pc_burst_off / pc_raw_isp_off.txt）—— 判定：DEAD

- Grep `pc_yuv_off|pc_burst_off|pc_raw_isp_off` 全项目：命中仅 `docs/AUDIT_060...md:51`、
  `PhotographerCamera-0.3.0-report.md:43,87`、`0.5.0-report.md:20`（历史文档），**活代码零命中**。
- 删除前实证（`git show 4c463ab^:.../core/camera/CameraEngine.kt`）：后门机制确曾存在于已删的
  CameraEngine 内——旧 `CameraEngine.kt:176-178`（`File(appContext.filesDir, "pc_yuv_off.txt").exists()`）、
  `:194-199`（pc_burst_off.txt）、`:497-499`（pc_raw_off.txt / pc_raw_isp_off.txt）、`:555`（pc_raw_bundle.txt）。
  随 `4c463ab` 整文件删除，机制不复存在。
- 现存文件面复查（防漏判）：Grep `\.txt"`（android/ .kt）仅余无关文件——`crash_log.txt`
  （MainActivity.kt:174,213）、`PhotographerCamera_debug.txt`（DebugLog.kt:36）、
  `remote_log_endpoint.txt`（UpdateChecker.kt:63 / RemoteLog.kt:85，正常资产）。
- **易混淆点（勿误判）**：现行代码中的 `pc_settings` / `pc_debug` 是 **SharedPreferences 文件名**
  （MainActivity.kt:71,100,121；CameraScreen.kt:235,248,273；AppSettingsScreen.kt:106；
  PresetListScreen.kt:153,330；ProfileLoader.kt:318；RemoteLog.kt:83），与 pc_* 文件后门无关，且均在用。
  `raw_isp_enabled`（CameraScreen.kt:249,1045）是 UI 开关 pref，现经 `pvm.setUseRaw()` 生效，亦非后门。
- 备注：filesDir 基线扫描（`Environment.getExternalStorageDirectory` 等）仅剩正常功能：
  CaptureSaver.kt:55（存片）、MainActivity.kt:210（ inbox 目录）、ProfileLoader.kt:195
  （/sdcard/PhotographerCamera 预设导入）。

### 对象 7：旧报告 D 类「引擎切换后必删项」其他项 —— 判定：DEAD（均已随 4c463ab 清除）

- D 类原三项（CameraEngine 三管线 / raw_isp.frag 回退 / pc_* 后门）已分别在上文 ①-③ 判定，
  其中 Kotlin 侧已物理清除，资产侧（raw_isp.frag + 16 shader）待清。
- A 类"确认可删"旧项复核（同样已删）：`core/camera/ImageYuvConverter.kt`、`RawImageConverter.kt`
  （Grep 仅命中 `docs/architecture.md:86` 与 AUDIT_060:34 两处文档）、`ui/preview/*`（见对象 4）。
- 另删除的相关旧件（git stat 实证，供台账完整性）：`core/device/DeviceAdapter.kt`（209 行）、
  `core/photon/PhotonStackPipeline.kt`（87 行）、`ui/CameraPreviewView.kt`（658 行，旧活预览）。
- 新发现残留（**工具链/文档面，非 APK 运行面**）：
  1. `tools/static_cross_check.py:64` 仍 `read(.../core/gpu/ProfileRenderer.kt)`——该文件已删，
     此脚本现在一跑就 FileNotFoundError（`tests/run_tests.py:46` 会调它）。属**过期工具引用**，应修或删。
  2. `交接卡.json:24,27-39` 仍以 `GpuParams.kt`/`ProfileRenderer.kt`/`mainchain.frag` 为"改动禁区"——
     权威链已换成 photon（ColorRecipe/FilmGrainGl/LutRenderer），交接卡需重写。
- 反射面终检：全项目 `Class.forName` 仅 2 处且均与旧代码无关——
  `core/device/DeviceUtil.kt:22`、`photon/utils/SystemPropUtil.kt:10`（`android.os.SystemProperties`）。
  无任何反射可直达本报告任一 DEAD 对象，故无 UNVERIFIED 项。

---

## ③ 汇总表

| # | 对象 | 路径（绝对路径根部 = PhotographerCamera/） | 行数 | 判定 | 关键证据 |
|---|---|---|---|---|---|
| 1 | 旧 CameraX CameraEngine 全套（5 文件） | android/app/src/main/java/com/photographercamera/core/camera/ | 2,331（删前） | **DEAD（已删除）** | 4c463ab 删除；残留引用仅注释（CameraScreen.kt:246 等）与历史文档 |
| 2 | raw_isp.frag | android/app/src/main/assets/shaders/raw_isp.frag | 180 | **DEAD** | 零加载点；仅注释/文档提及 |
| 3 | core/gpu 自研渲染器（删 5 文件 + 幸存 GpuParams.kt） | android/app/src/main/java/com/photographercamera/core/gpu/ | 删 1,935；幸存 249 | **DEAD** | GpuParams 唯一链 toGpuParams 零调用（ProfileLoader.kt:115）；活链走 ProfileToRecipeMapper（CameraScreen.kt:504） |
| 4a | 旧预览组件 ui/preview/*（2 文件） | android/app/src/main/java/com/photographercamera/ui/preview/ | 777（删前） | **DEAD（已删除）** | 4c463ab 删除；ui/ 下已无 preview 目录 |
| 4b | （防误删）photon 版同名预览组件 | android/app/src/main/java/com/photographercamera/photon/ui/camera/ | 777 | **LIVE** | CameraScreen.kt:815 实例化；CameraViewModel.kt:84,1742 持有 |
| 5 | 16 个未加载 shader | android/app/src/main/assets/shaders/{bloom.frag,bloom.glsl,blur.frag,exposure.glsl,extract_blur.frag,grain.glsl,grain_noise.frag,halation.frag,halation.glsl,highlight.glsl,hsl.glsl,mainchain.frag,noise.glsl,shadow.glsl,sharpen.glsl,tone_curve.glsl} | 270 | **DEAD（16/16）** | 全项目零加载；仅自身 #include 与文档 |
| 5b | （扩展）assets/shaders/ 其余 15 文件 | android/app/src/main/assets/shaders/ | 2,193 | **DEAD（同类）** | 加载器 ProfileRenderer/GLSL.kt 已删，整目录无加载点 |
| 6 | pc_* 应急后门（pc_yuv_off/pc_burst_off/pc_raw_isp_off.txt） | （机制原宿主 core/camera/CameraEngine.kt） | — | **DEAD（已删除）** | 4c463ab 删 CameraEngine；活代码零命中；pc_settings/pc_debug 为 SharedPreferences 名，勿混淆 |
| 7 | D 类其他项（ImageYuvConverter/RawImageConverter/DeviceAdapter/PhotonStackPipeline/CameraPreviewView 等） | android/app/src/main/java/com/photographercamera/{core/camera,core/device,core/photon,ui}/ | ~1,400（删前） | **DEAD（已删除）** | 4c463ab；Grep 仅剩文档命中 |
| 8 | （新发现）tools/static_cross_check.py 读已删 ProfileRenderer.kt | tools/static_cross_check.py:64 | — | **过期工具引用（非 APK 死代码）** | 文件已删，脚本会崩；tests/run_tests.py:46 调用 |
| 9 | （新发现）交接卡.json 权威文件条目过期 | 交接卡.json:24,27-39 | — | **过期文档** | 权威链已换 photon |

计数：**DEAD 7 组**（对象 1-7，其中 3 组已在 `4c463ab` 物理删除、4 组仍在树内待清）；
**LIVE 1 组**（4b photon 同名预览组件，防误删）；**UNVERIFIED 0**（反射面已穷尽，无运行时触发路径）。

## ④ 后续处置建议（仅供参考，**本轮不执行**）

1. **优先级 P1（零风险，直接删）**：`assets/shaders/` 整目录 31 个文件（2,463 行）——含 16 个清单项、
   raw_isp.frag 及其余 15 个同类。删除后可省 APK 包体；删前跑一次 `./gradlew assembleDebug` 验证
   （预期无编译影响，因无任何代码引用该目录）。
2. **优先级 P2（低风险）**：删 `core/gpu/GpuParams.kt` + `ProfileLoader.toGpuParams()` 方法体。
   注意保留 `ProfileLoader` 其余 API（活链）；删后同步修 `tools/static_cross_check.py`
   （去掉对 ProfileRenderer.kt/GpuParams 的读取，否则 CI 静态检查继续崩）。
3. **优先级 P3（文档/工具债）**：重写 `交接卡.json`（权威链改为 photon：ColorRecipeParams /
   FilmGrainGl / LutRenderer / Camera2Controller）；`docs/AUDIT_060` 标注"已被 Phase A 审计取代"。
4. **明确禁手**：`photon/ui/camera/CameraPreviewGL.kt` 与 `CameraGLSurfaceView.kt` 是活预览组件，
   任何"按类名清理旧预览"的操作必须排除这两个文件；`pc_settings`/`pc_debug`/`raw_isp_enabled`
   是在用的 SharedPreferences 键，不属于后门机制。
5. **验证闭环**：上述任何清理执行后，建议回归路径——编译 + 真机预览/拍照/RAW 开关三连测
   （活链：CameraScreen → CameraViewModel → Camera2Controller）。

---

*审计方法：全项目 Grep（符号/文件名/资产路径/反射/Manifest/assets 加载面）+ git 删除考古
（4c463ab / e252068）。只读完成，未改动任何被审计文件。*
