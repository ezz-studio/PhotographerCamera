# 0.9.13 画质/崩溃修复分析链

上游仓库：https://github.com/bjzhou/PhotonCamera/ （HEAD，包名 `com.hinnka.mycamera`）
本仓映射：`com.hinnka.mycamera.*` → `com.photographercamera.photon.*`

---

## 问题 A：JPEG max 模式 APP 闪退

### Upstream 状态机
- Kotlin：`processor/GlesHardwareBufferImage.kt`（`external fun create/bind/destroy`）、
  `processor/GlesGpuTimerQuery.kt`（`external fun begin/end/poll/...`）
- Native：**存在对应实现**
  - `app/src/main/cpp/gles_hardware_buffer_jni.cpp`（4,440 B）
  - `app/src/main/cpp/gles_gpu_timer_query_jni.cpp`（5,504 B）
  - `app/src/main/cpp/CMakeLists.txt` 中注册进 `add_library(my-native-lib SHARED ...)`：
    ```
    native-lib.cpp
    gles_gpu_timer_query_jni.cpp
    gles_hardware_buffer_jni.cpp
    ```
  - JNI 符号名：`Java_com_hinnka_mycamera_processor_GlesHardwareBufferImage_create` 等

### 当前项目状态（0.9.11/0.9.12 之前）
- 0.9.11 为对齐上游 JPGmax YUV 管线，照搬了这两个 Kotlin 类
- 但 `app/src/main/cpp/` **没有**这两个 JNI 实现，CMakeLists 也没注册
- fork 的 native 是另一套（旧包名 `com.photographercamera.core.photon.*`，靠 `gen_jni_bridge.py`
  生成转发桥；桥只扫描 `Java_com_photographercamera_core_photon_*` 前缀，无法覆盖新类）

### Profile 参与点
无（与摄影配置文件/profile 无关，纯 JNI 绑定层）。

### Diff（真机日志，会话 `s1788797778916` / `s1788797638158`）
```
00:16:30.366 [CRASH] uncaught exception :: UnsatisfiedLinkError:
  No implementation found for long com.photographercamera.photon.processor
  .GlesHardwareBufferImage.create(android.hardware.HardwareBuffer)
  (tried Java_com_photographercamera_photon_processor_GlesHa...)
```
崩溃点在 JPEG max 首次进入 YUV HardwareBuffer 零拷贝输入路径时（Kotlin `init {}` 之后的首次调用）。

### Root Cause
跨语言照搬不完整：Kotlin 侧引入上游类，native 侧未同步引入对应 JNI 实现，
`System.loadLibrary("my-native-lib")` 成功但符号表中没有 `Java_com_photographercamera_photon_processor_GlesHardwareBufferImage_create`。

### 最小修复方案（已实施）
1. 照搬上游 `gles_hardware_buffer_jni.cpp`、`gles_gpu_timer_query_jni.cpp`
2. 仅做包名映射：`Java_com_hinnka_mycamera_processor_` → `Java_com_photographercamera_photon_processor_`
   （不改任何算法逻辑；共 3 + 6 处符号）
3. 在 fork `CMakeLists.txt` 的 `add_library(my-native-lib SHARED ...)` 中按上游顺序注册两个源文件
   （`EGL`/`GLESv3`/`android`/`jnigraphics` 已在 `target_link_libraries` 中，无需新增）

---

## 问题 B：RAW max 成片 1.4MB（上游 6.4MB，同机位同场景）

### Upstream 状态机
RAW max（MGC Spatial RGB）融合栈 `processor/GlesMgcRawSpatialStacker.kt`：
- 全局对齐：**GPU compute 路径** `GlesSpatialGlobalAlignment(cpuCompatibleMean = true)`
  ```kotlin
  val gpuCandidate = globalAlignment?.estimate(...)
  val globalCandidate = if (gpuCandidate == 0) estimateGlobalAlignmentCandidate(alignment) else null
  ```
- rejection 引导滤波：`GlesMgcRawSpatialShaders.rejectionDownsample`
  （bicubic，base 与 alternate guide 使用同一滤波器）
- 噪声 LUT 缓存：`noiseLutCache` / `CachedNoiseLut`

### 当前项目状态
同文件仍停留在旧版：
```kotlin
val globalCandidate = estimateGlobalAlignmentCandidate(alignment)   // CPU 版，fork
```
rejection 用旧 `rejectionPixelDifferenceDownsample`（`rejectionPixelDifferenceDownsampleProgram`）。

### Profile 参与点
无。摄影 profile（LUT/曲线）在融合之后的色彩阶段生效，不改变融合的高频保留量。

### Diff（关键证据）
1. **埋点日志**（本机 0.9.9 埋点，会话 `s1788797792305` / `_logs_0911/s03.json`）：
   ```
   exportPhoto JPEG result: output=3072x4096(ARGB_8888, sRGB IEC61966-2.1),
     input=3072x4096, quality=95, jpeg444=false, bytes=1557336, encodeMs=144
   23:30:16.749 ... bytes=1432344
   ```
   → 输出分辨率 12.58MP ✓、`quality=95` ✓、`ARGB_8888` ✓：
   **编码器参数与上游一致，差异 100% 来自图像内容本身**。
2. 源码 diff（`tools/_diff_engine.py`，包名归一化后）：
   ```
   processor/GlesMgcRawSpatialStacker.kt   366 changed lines
   processor/GlesMgcRawSpatialShaders.kt   713 changed lines
   processor/GlesMgcRawSabreShaders.kt      67 changed lines
   processor/CalibratedRawNoiseProfile.kt   33 changed lines
   ```
3. 同场景真机参数（RAW max，ISO 8230 / 5 帧）：
   ```
   MGC Spatial RGB merge complete frames=5 output=4096x3072 referenceSnr=6.60 finishRawDenoiseSnr=14.49
   MgcFullResolutionDenoise ... snr=14.49 luma=true(1.0995) chroma=true(1.1007)
   ```

### Root Cause
RAW 路径与上一轮已修复的 YUV 路径是**同一个病根**：
CPU 版 `estimateGlobalAlignmentCandidate` 在弱纹理/夜景（ISO 8230）下把失败 tile 钳到统计域角点，
得到错误的全局先验（YUV 路径实测 `global=mode(-64.0,-64.0,n≈1116)` 四帧恒定）→
整幅对齐场被错误平移 → 多帧重影 → rejection 大面积判失败 → 时域累计退化为过度平均 →
高频细节与 micro-contrast 被抹平 → **12.58MP @ q95 只写出 1.4–1.5MB**（上游 6.4MB），
8bit 量化后平滑渐变区出现肉眼可见色带。

### 最小修复方案（已实施）
照搬上游 RAW 融合栈三件套（仅包名映射，不改逻辑）：
- `processor/GlesMgcRawSpatialStacker.kt`（GPU 全局对齐 + rejectionDownsample + noiseLutCache）
- `processor/GlesMgcRawSpatialShaders.kt`
- `processor/GlesMgcRawSabreShaders.kt`
- 依赖补齐：`processor/CalibratedRawNoiseProfile.kt`（提供 `minimumCompatibleSensitivity`，
  `compatibleSensitivityAt` 由 `coerceAtMost(max)` 修正为 `coerceIn(min, max)`）

---

## 验证要点（真机）
1. JPEG max 不再闪退，日志出现 `GLES stack ... alignment=rg16f-sparse2`
2. RAW max / JPEG max 日志中 `global=` 不再恒为 `mode(-64.0,-64.0)`
3. 同机位同场景：12MP 成片体积向上游看齐（RAW max ≈ 6MB 量级）
4. 夜景平滑区无色彩断层，多帧无重影

## 未改动（保持 fork 现状，非本轮范围）
- `camera/MultiFrameConfig.DEFAULT_HDR_PLUS_FRAME_COUNT = 5`（0.9.9 用户指定，上游为 3）
- `useJpeg444Export` 默认 false（上游默认同为 false，非差异项）
- `MultiFrameFocusLockPolicy` / `CameraState` / `CaptureInfo` 等 fork 自有改动
