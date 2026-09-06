# PhotographerCamera — libjpeg-turbo 自集成评估报告

> 范围：仅评估「Android 自带 JPEG 编码 → 自集成 libjpeg-turbo」的权衡，供后续决策。
> 不改任何代码；CameraEngine 的捕获配置校准见同仓 `CameraEngine.kt` 内 `capture config cross-checked against camera-samples` 注释块。
> 参考仓库：`vendor/libjpeg-turbo`（克隆版本 **3.2.1**，最新稳定）、`vendor/camera-samples`（2026-09-06 快照）。

---

## 0. 结论先行（给后续决策）

**当前 0.3.x 阶段：不推荐自集成 libjpeg-turbo。** 详见 §5。

核心判断：Android 平台此刻**已经在用 libjpeg-turbo**（经 Skia 的 JPEG 编解码被 `Bitmap.compress` 调用），所以"自集成"的收益不是"变快"，而是"参数可控"（采样因子、质量/速度档、输入色彩空间）。该可控性对当前产品痛点的杠杆有限，而 NDK 集成/维护成本在 0.3.x 主线（YUV 直采管线 + GPU Color Engine + 真机调优）阶段属于非核心投入。

---

## 1. 现状与瓶颈

- **当前编码入口**：`core/storage/CaptureSaver.kt:68` → `bmp.compress(Bitmap.CompressFormat.JPEG, 100, os)`（Android 自带编码，即 Skia 的 JPEG 编解码）。
- **平台栈底层**：AOSP `external/libjpeg-turbo`（自 Android 7 起取代 IJG libjpeg），经 Skia 的 JPEG 编解码被 `Bitmap.compress` 调用。换言之**我们此刻已经在用 libjpeg-turbo**，只是无法控制其参数。
- **瓶颈（不是"慢"，而是"不可控 + 隐性代价"）**：

  1. **强制 4:2:0 色度子采样**：`Bitmap.compress` 不暴露采样因子参数，Android 默认 4:2:0。即便 `quality=100`，色度分辨率已被砍半（人眼对色度不敏感，但高饱和/细线条场景仍可感知；对"摄影机"定位是遗憾）。自集成可指定 4:4:4。
  2. **`quality=100` 触发 SIMD 量化回退（性能陷阱，重要）**：据 `vendor/libjpeg-turbo/README.md` §Performance Pitfalls，"Fast Integer Forward DCT at High Quality Levels"——当使用 fast integer forward DCT 且 quality ∈ [98,100] 时，SIMD 量化函数无法给出正确结果，库被迫回退到非 SIMD 量化，**编码性能下降最多 40%**。当前 `compress(JPEG, 100)` 正好落在该区间：即在"无损质量"诉求下反而最慢，且仍受 4:2:0 限制，100 并未换来真正无损。
  3. **双重色彩空间转换**：GPU Color Engine 产出 `ARGB_8888` → `Bitmap.compress` 内部 RGB→YUV(4:2:0)→Huffman。YUV 直采路线本意是"绕开 HAL JPEG 有损压缩"得到干净 YUV 喂给 GPU，但成片仍须压成 JPEG 落盘/分享/画廊兼容，这一道 RGB→JPEG 无法避免。
  4. **单次编码、单质量**：无法在一次编码里同时产出全图 + 缩略图，或双质量档。

---

## 2. 自集成收益（NDK + TurboJPEG）

- **直接编码**：TurboJPEG API（`tjCompress*`）支持从 `JCS_EXT_ARGB` / `JCS_EXT_RGBA` 等扩展色彩空间**直接压缩**（README §Colorspace Extensions），省去显式 RGB 拷贝；也支持 planar YUV 输入（`tjEncodeYUVPlanes`）。
- **采样因子可选**：`TJSAMP_444` 关闭色度子采样，保留全色度分辨率（代价：文件约大 1/3）；亦支持 4:2:2 / 4:1:1 / 4:2:0。
- **质量/速度可控**：可置 `TJFLAG_ACCURATEDCT` 配合高质量档规避 §1.2 的 SIMD 回退；可单独设 luma / chroma 质量（cjpeg `-qt` 能力）；可用 `tj3Compress` 一次产出多尺寸/多质量。
- **性能**：官方称相对 libjpeg 2–6×；在已用 SIMD 的平台上相对 Skia 默认路径提升**有限（同内核）**，主要收益来自**参数可控**而非"更快"。
- **版本/稳定性**：vendor 克隆为 **3.2.1**；API/ABI 稳定（libjpeg v6b 兼容；含 `jpeg_mem_dest` / `jpeg_mem_src` 内存目标，README §In-Memory Source/Destination Managers）。

---

## 3. NDK 集成成本

- **构建**：用 CMake 编 libjpeg-turbo 为 `armeabi-v7a` + `arm64-v8a`（目标天玑 9300 / API 36，arm64 为主，v7a 兼容老设备）。官方 `BUILDING.md` 提供指引，可用 prefab 或直接编 `.so`。
- **打包**：`libturbojpeg.so`（TurboJPEG 静态/动态库，约数百 KB~1MB/ABI）+ JNI 胶水封装 `tjCompress` / 内存目标。
- **维护**：随 AGP / NDK 升级回归；需保证与系统 libjpeg-turbo 符号不冲突（TurboJPEG 单独命名空间，风险低）；TurboJPEG 句柄**非线程安全**，需池化（与当前 `DeviceCompat.captureExecutor` 离线编码模型可对接）。
- **人力**：首次集成中~高；后续维护低。

---

## 4. 与本项目路线的关系

- YUV 直采路线的差异化价值在于"绕开 HAL JPEG 有损压缩"得到干净 YUV 喂给 GPU Color Engine；但成片仍须 JPEG 落盘/分享/画廊兼容（JPEG 是互换格式）。编码器的替换只影响"最后一道压码"，不改变核心管线。
- GPU 伙伴（`core/gpu`，独立领地）负责渲染链；编码应在落盘前由编码层完成。当前 `CaptureSaver`（本任务不触碰）里 `compress(100)`。
- RAW 路线已冻结（开发停止，不碰），不纳入本评估。

---

## 5. 结论与建议

**0.3.x 阶段：不集成。** 依据：

1. 平台已是 libjpeg-turbo，纯编解码"更快"的边际收益小，差异仅在"参数可控"，对该差异当前产品痛点的杠杆有限；
2. 4:4:4 的文件膨胀与"摄影机"目标（高保真）可取，但 GPU Color Engine 已做高质量 tone mapping，4:4:4 在 12.5MP 场景的感知增益有限，且需 gallery/分享链路配合；
3. NDK 集成/维护成本在 0.3.x（核心做 YUV 管线 + GPU 引擎 + 真机调优）阶段属非核心投入，应让位于产品主线；
4. 现有 `compress(JPEG, 100)` 有"100 反而触发 SIMD 回退变慢"的隐性代价，但属 `core/storage` 问题，本任务边界外（不动），仅作为后续发现记录。

**后续触发重评的信号**（满足任一即重启评估）：

- 真机测得落盘编码耗时超目标（如超出帧预算）；
- 需要 4:4:4 存档 / HDR 容器 / 双质量（全图 + 缩略）一次编码；
- 需可控 chroma 采样以匹配 GPU 引擎输出特性；
- 引入自研 RAW ISP 后需要线性 / sRGB 精确编码路径。

**若将来决定集成**：优先 TurboJPEG（非裸 libjpeg API），`TJSAMP_444` + `TJFLAG_ACCURATEDCT` 在 `quality ≈ 95` 取质量/速度平衡；先仅替换 `CaptureSaver` 编码入口，不动 YUV 采集/渲染。

---

## 6. 参考

- `vendor/libjpeg-turbo`（3.2.1）：`README.md`（Performance Pitfalls / Colorspace Extensions / TurboJPEG API）、`CMakeLists.txt`（`VERSION 3.2.1`）、`BUILDING.md`
- 当前编码入口：`core/storage/CaptureSaver.kt:68`（`bmp.compress(JPEG, 100, os)`）—— **不在本任务修改范围**
- 捕获配置校准：`android/app/src/main/java/com/photographercamera/core/camera/CameraEngine.kt`（含 `capture config cross-checked against camera-samples` 注释块）
- 现状说明：`AGENTS.md`、交接卡、`PhotographerCamera-0.3.x-report.md`（YUV 直采主通道、RAW 冻结）
