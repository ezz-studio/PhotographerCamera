# 上游跟随同步地图（PhotographerCamera ← bjzhou/PhotonCamera）

> 目的：标出本仓每个文件与上游仓库的对应关系，让以后上游更新时能快速判断
> 「哪些能整文件覆盖、哪些必须手工合并、哪些是 fork 自研绝不能动」。
> 同步基线：**上游 `a81be3d`（2026-09-09, "feat: LUT intensity range expansion…"）**

---

## 1. 路径与命名映射

| 层 | 上游 | 本仓（fork） |
|---|---|---|
| Kotlin 源码根 | `app/src/main/java/com/hinnka/mycamera/` | `android/app/src/main/java/com/photographercamera/photon/` |
| Kotlin 包名 | `com.hinnka.mycamera` / `com/hinnka/mycamera` | `com.photographercamera.photon` |
| Native 源码根 | `app/src/main/cpp/` | `android/app/src/main/cpp/` |
| **JNI 函数前缀** | `Java_com_hinnka_mycamera_` | **`Java_com_photographercamera_core_photon_`**（注意含 `core`） |
| 自研 UI（**不对应上游**） | `photon/ui/**`（CameraScreen、SettingsScreen…） | `com/photographercamera/ui/screens/**` ← fork 独立实现 |
| 测试 | `app/src/test/java/com/hinnka/mycamera/` | 本仓无 test 源集 |
| 上游本地副本 | — | `_diag/up_src/upstream`（`git clone --depth 1`，大文件比对用这个，别走 API） |

⚠️ JNI 前缀里的 `core` 是历史遗留（`photon_jni_bridge.cpp` / `gen_jni_bridge.py` 桥接旧前缀）。
搬上游 `.cpp` 时**只替换 `Java_com_hinnka_mycamera_` → `Java_com_photographercamera_core_photon_`**，
不要动 JNI_OnLoad / 注册代码。

---

## 2. 当前分歧面（对上游 `a81be3d` 全量扫描）

| 类别 | 数量 | 含义 | 同步策略 |
|---|---|---|---|
| IDENTICAL | 274 | 包名归一化后与上游逐字节相同 | 上游再改 → 可直接整文件覆盖 |
| DIFF | 84 | 有差异（含 fork 自研改动 / fork 落后） | 逐个判定，见 §4 |
| LOCAL_MISSING | 103 | 上游有、本仓没有 | 绝大多数是上游 `ui/**`（本仓自研 UI 替代）→ **不需要补** |
| FORK_ONLY | 4 | 本仓独有、上游没有 | **永不覆盖** |

FORK_ONLY（fork 自研，上游无对应文件，同步时必须跳过）：
- `billing/BillingManagerImpl.kt`
- `lut/BloomLdrSettings.kt`
- `ui/camera/ZoomDisplayMode.kt`
- `utils/BuglyHelper.kt`

---

## 3. 工具链（全部从仓库根运行，用 `.venv/Scripts/python.exe`）

| 工具 | 用途 |
|---|---|
| `tools/_up_full_diff.py` | **全量扫描**：本地 vs 上游 HEAD，产出 `_diag/up_full_diff.json`（IDENTICAL/DIFF/LOCAL_MISSING/FORK_ONLY 分类） |
| `tools/_up_file_diff.py <rel>...` | 单文件 diff（`raw/Foo.kt`），包名已归一化 |
| `tools/_up_recent_map.py --since 2026-09-05` | 上游近期提交 → 按文件聚合的变更地图（`_diag/up_recent_map.json`），用来找「上游最近改了什么」 |
| `tools/_port_up.py [--dry-run] [--cpp] <files>` | 从 `_diag/up_src/upstream` 移植文件并自动做包名/JNI 前缀映射 |
| `tools/_fetch_mapped.py --ref <commit> <rel>` | 抓**指定 commit** 版本的文件（大文件 >400KB 走 API 会 IncompleteRead，用下面的分段法） |
| `tools/_uhdr_scan.py` / `_uhdr_tri.py` | 三方比对（pre-fix / post-fix / 本地）判定「是否已含修复」 |

大文件（如 `RawDemosaicProcessor.kt` ~400KB）抓取：
```bash
U=https://raw.githubusercontent.com/bjzhou/PhotonCamera/<sha>/app/src/main/java/com/hinnka/mycamera/raw/RawDemosaicProcessor.kt
curl -s -r 0-199999 "$U" >> part.txt; curl -s -r 200000-399999 "$U" >> part.txt
```
（整文件一次性下载会被掐断，Range 分段可用。）

刷新上游副本：
```bash
cd _diag/up_src && git -C upstream fetch --depth 1 origin main && git -C upstream reset --hard origin/main
```

---

## 4. 判定「能否整文件覆盖」的标准流程

1. `git log --oneline -- <本地文件>` —— **只有 1 条提交（0.8.0 引擎导入 `e252068`）= fork 从未改过 = 可安全整文件覆盖**。
   出现 2 条及以上 = 有 fork 自研内容，必须手工合并。
2. 检查上游版本是否引入本仓缺失的新依赖（例：上游 `RawMetadata.kt` 依赖 `CameraMetadataReader.kt` +
   `RawCameraCalibration.kt`，本仓都没有 → 不能直接用 HEAD 版，要退到引入依赖之前的 commit）。
3. 涉及 `external fun` / `.cpp` 时必须**两侧同步搬**：Kotlin 改 JNI 签名而 native 不同步 = `UnsatisfiedLinkError` 闪退。
4. 覆盖后跑 `assembleDebug`（Kotlin + CMake 一起验），仅 `compileDebugKotlin` 验不出 native 不匹配。

---

## 5. 本轮（2026-09-09）跟随记录

### 5.1 JPEG max 重构 —— **已确认本仓已同步，无需动作**
上游 `e2c3fa7c` / `486a4bc8` / `ddb73b24`（YUV stacker 全链）涉及的 11 个文件，
本仓全部 `IDENTICAL`：
`GlesYuvStacker` / `GlesYuvTiming` / `GlesYuvHardwareBufferInput` / `GlesYuvAlignmentShaders` /
`GlesYuvSpatialShaders` / `GlesMgcRawSpatialStacker` / `GlesMgcRawSpatialShaders` /
`GlesSpatialGlobalAlignment` / `GlesGpuTimerQuery` / `GlesHardwareBufferImage`
（后两个是 0.9.12 已补的 JNI 类 + 对应 `gles_*_jni.cpp`）。

本轮补了同批提交里漏掉的 3 个配套文件：
`camera/MultiFrameFocusLockPolicy.kt`、`stabilization/MgcEisNativeBridge.kt`、
`lut/MgcEisHardwareBufferRenderer.kt` + native `mgc_eis_reconstruction_jni.cpp`。
（`MgcEisNativeBridge` 的 3 个 HardwareBuffer JNI 已迁到 `GlesHardwareBufferImage`，Kotlin 与 native 同步删。）

### 5.2 RAW max 曝光优化（上游 `9d91dd48` HDRNet 后曝光 SLM rolloff）—— **本轮移植**
核心语义：曝光匹配从「亮度网格」改为「线性 RGB 网格」+ SLM 增益响应（rolloff/数字增益拆分）。

Kotlin（6）：`RawLegacyAutoExposureMatcher` / `RawLegacyAutoExposureNativeBridge` /
`DngHdrNetProfileGainTableNative` / `DngPhotonProfileGainTableAlgorithm` /
`DngPhotonProfileGainTableGenerator` / `RawDngProfilePreparation`
Native（3）：`raw_legacy_auto_exposure_solver.cpp` / `dng_hdrnet_pgtm_jni.cpp` /
`raw_hdrnet_post_exposure.h`（新增）

⚠️ 注意：`RawDngProfilePreparation.kt` 用的是 **`9d91dd48` 版本**而非 HEAD ——
HEAD 版混入了 `acd6d7f8`（相机元数据健壮性）的 `readMetadataOrNull`，会依赖本仓缺失的
`CameraMetadataReader` / `RawCameraCalibration`。以后同步该文件同理要挑版本。

### 5.3 Ultra HDR（`0e3d2241` + `f0c4e04b`）—— 本轮收尾完成
其他 Agent 已改 6 个文件（`GpuReferenceGainmapProducer` / `RawGainmapMath` /
`DngProfileGainTableRenderShader` / `RawHdrReferenceMath` / `RawHdrReferencePass` / `RawOutputPass`），
但漏了配套，导致编译失败。本轮补齐：
- `RawEngineTonePass.kt`、`RawRenderingEngineToneAlgorithm.kt`（用 `0e3d2241` 版本，避开后续 Lumix 依赖）
- `RawDemosaicProcessor.kt` 接线：签名 `sdrLinearTextureId: Int` → `sceneExposureGain: Float`，
  新增 `hdrNetSceneExposureGain`（来自 `RawHdrReferenceMath.hdrNetSceneExposureGain`）与
  `hdrReferenceSceneExposureGain`，config 新增同名字段，三处调用点改传参。

### 5.4 待跟进（本轮**未**移植，有意为之）
| 上游提交 | 内容 | 不移植原因 |
|---|---|---|
| `b57725f8` | DNG 色彩校准对齐 + 移除 raw AWB 估计 | 改 `native-lib.cpp` JNI 签名，风险高 |
| `ab33de2f` / `f0fffd81` / `2d747629` | Lumix RAW 引擎、HNCS 等效相机校准 | 缺 `LumixToneAlgorithm` / `RawCameraCalibration` / `EquivalentCameraLut*` 等一批新文件，属新引擎级引入 |
| `79131996` | highlight diffusion & bloom 重构 | 缺 `HighlightDiffusionGl` / `HighlightDiffusionShaders` 等新文件 |
| `acd6d7f8` | 相机元数据健壮性 | 引入 `CameraMetadataReader`（`readMetadataOrNull/Throw`），牵连面广 |
| `3d62bbfd` / `a1f51d14` / `dc0e50d5` | Live Photo（Pro / 手电筒 / 环形录制） | 本仓 livephoto 链独立，需单独评估 |
| `a81be3dd` / `083b66df` / `7b1e9e62` | LUT 强度扩展、网格样式、水平仪 | UI/参数层，与 fork 自研 UI 冲突，需手工合并 |

---

## 6. 同步 SOP（下次上游更新照此执行）

1. `cd _diag/up_src/upstream && env -u http_proxy -u https_proxy git fetch --depth 1 origin main && git reset --hard origin/main`
2. `.venv/Scripts/python.exe tools/_up_full_diff.py` → 看新增了哪些 DIFF / LOCAL_MISSING
3. `.venv/Scripts/python.exe tools/_up_recent_map.py --since <上次同步日期>` → 上游改了什么、哪个 commit
4. 对目标文件跑 `git log --oneline -- <file>` 判 fork 是否动过（§4 第 1 条）
5. 未动过 → `tools/_port_up.py [--cpp] <file>`（或 `--ref` 抓历史版本）；动过 → 手工合并
6. `cd android && gradle.bat assembleDebug`（必须跑完整打包，验 native）
7. 更新本文档 §5（追加记录）与 §2 的分歧面统计
