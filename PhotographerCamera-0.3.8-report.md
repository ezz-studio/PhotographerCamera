# PhotographerCamera v0.3.8 交付报告 — 开源重构第一轮

**版本**: 0.3.8 / code 16
**APK**: `PhotographerCamera-0.3.8.apk`
**性质**: 架构重构轮（并行双 Agent 开发 + 统一审核合并）— 行为等价，无新功能
**Commit**: db25d17

---

## 一、重构架构（用户拍板的目标管线）

```
原厂 HAL/ISP → YUV_420_888 高质量非 JPEG 输出 → GPU Dynamic Color Engine → 自有 JPEG encode
```
- **RAW 路线已冻结为实验性功能**（用户拍板停止修复），本轮未触碰任何 RAW 代码
- libjpeg-turbo：第一版按方案保留 Android 自带编码（评估报告见下）

## 二、两个并行 Agent 的交付（审核全部通过）

### gpu-filter（GPU 管线，core/gpu 领地）

| 移植源 | 产出 | 对照源码 |
|---|---|---|
| libyuv（lemenkov/libyuv） | `YuvNative.kt` Kotlin 移植（NDK 不可用降级路径），`renderYuvChain` 已接入，`compactPlane` 完整保留为 Java 兜底（native 异常/返回 null 均回退，一次性告警） | CopyPlane@planar_functions.cc:29、SplitUVRow_C@row_common.cc:2852、CopyRow_C@3273 逐行核对一致；补 row-coalesce 快路径 |
| android-gpuimage-plus（wysaid） | `GpuFilterChain.kt` filter 链抽象（GpuFilter.attach/render/release 生命周期 + ping-pong FBO 链），YUV 链 pass 组织已迁移 | CGEImageHandler（m_vecFilters + swapBufferFBO ping-pong）、CGEImageFilterInterfaceAbstract::render2Texture，行为 bit 级等价 |
| yuv_copy.frag 校准 | **保留现有 BT.601 limited-range 矩阵**——gpuimage-plus 用的是 full-range（面向 MediaCodec 视频帧），套用会使成片偏暗偏色；文件头加完整校准注释（来源、差异点、切换指南） | TextureDrawerCodec.MATRIX_YUV2RGB、fshI420ToRGB |

- 顺带修复基线预存的 2 个编译错误：ChromaFilter/EffectFilter 嵌套类无法访问外层 drawQuad()/runEffectPass() → 改 inner class
- 0.3.7 全部修复（coverWindow 双重换位修复、chroma denoise、u_uvWin 恒等重置）原样保留

### capture-config（捕获配置，core/camera 领地）

- clone `vendor/camera-samples`（2026-09-06 快照）、`vendor/libjpeg-turbo` 3.2.1
- **捕获配置交叉核对结论：无实质偏差，零逻辑改动**——仅 CameraEngine.kt 加 4 处来源标注注释（MAXIMIZE_QUALITY、ResolutionStrategy、setBufferFormat、Camera2Interop HQ 三件套，各自标注对应样本路径；原文档指的 CameraXAdvanced/Camera2Basic 已不存在于现仓库布局，改用功能等价样本 camerax-rawcapture/takeaphoto/ultrahdr/effects）
- 产出评估报告 `PhotographerCamera-libjpeg-turbo-eval.md`：
  - 平台经 Skia 已在用 libjpeg-turbo，自集成收益是"参数可控"（4:4:4、ACCURATEDCT）而非速度
  - **quality=100 陷阱**（README §Performance Pitfalls）：quality∈[98,100] 触发 SIMD 量化回退，编码慢最多 40%（只影响保存耗时 ~300ms，不影响画质；本轮保持 100 不动，画质优先）
  - 结论：0.3.x 不集成；列了 4 条重评触发信号

## 三、验证

- 统一编译 `assembleDebug -q` → BUILD_OK（41s），产物自动重命名 PhotographerCamera-0.3.8.apk
- 集成审核：两 Agent 文件领地零重叠（gpu/* vs camera/CameraEngine），git diff 逐项核对通过
- 待真机确认：YUV 拍摄回归（12.6MP、色彩、彩噪观感）——本轮架构等价替换，预期与 0.3.7 观感一致；若出现 `libyuv compact failed - Java fallback active` 日志说明走了兜底（功能不受影响，回报即可）

## 四、遗留与下轮

- RAW 全红：已冻结，不再修复（实验性功能）
- libjpeg-turbo：按评估报告，触发信号出现时再集成（TurboJPEG + TJSAMP_444 + quality≈95，仅替换 CaptureSaver 编码入口）
- NDK 可用后可将 YuvNative 升级为真 JNI libyuv（接口已预留）
