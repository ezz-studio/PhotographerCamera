# Phase 23–33 交付概览：Android Camera Engine + GPU Renderer + Compose UI

> 前置门禁（AGENTS.md）：离线 Profile 验证 `validation_status = "validated"`（overall_test_loss ≈ 0.58，
> Phase 14–18 已闭合）。本阶段不修改离线 Pipeline，仅消费其产物；新增摄影师 = 新增 JSON，零渲染代码改动。

## 1. 目标与约束

- 在 Android 用 **传统图像处理 + GPU Shader 实时**模拟摄影师成像风格；**不跑任何 AI Runtime**（AGENTS.md 规则 12）。
- 风格**非单一 LUT**：15 层管线，参数全部来自 `PhotographerProfile`，经 uniform / 纹理 / UBO 注入。
- Profile 可独立 **导入 / 导出 / 版本管理**；新增摄影师不修改 Android 渲染代码。

## 2. 模块与文件

```
android/
├── build.gradle.kts / settings.gradle.kts / gradle.properties   # AGP 8.5.2, Kotlin 2.0.21, minSdk 24
├── app/
│   ├── build.gradle.kts            # CameraX/Camera2, Compose, kotlinx-serialization, proguard
│   ├── proguard-rules.pro          # 保留 core.* (GPU/Profile) 与序列化 metadata
│   ├── src/main/
│   │   ├── AndroidManifest.xml       # CAMERA 权限 + glEsVersion=0x30000(ES3.0, required)
│   │   ├── assets/
│   │   │   ├── shaders/*.frag|*.glsl|*.vert   # 15 层 GLSL 源（含 #include）
│   │   │   └── profiles/demo.json, photographer_a.json   # 内置 Profile
│   │   ├── java/com/photographercamera/
│   │   │   ├── core/profile/  Profile.kt  GpuParams.kt  Validation.kt  Conditions.kt  ProfileLoader.kt
│   │   │   ├── core/gpu/      GLSL.kt  ProfileRenderer.kt
│   │   │   ├── core/camera/   CameraEngine.kt     # Camera2 封装
│   │   │   └── ui/            CameraPreviewView.kt # GLSurfaceView 宿主
│   │   └── app/MainActivity.kt # Compose：预览 / 拍摄 / 风格切换 / 权限
```

## 3. 15 层管线（与 CPU 参考同序）

```
OES→2D → MainChain[Exposure, WhiteBalance, ColorMatrix, ToneCurve(LUT),
                   HighlightRollOff, Shadow, HSL(LUT)] → Sharpen → Bloom
        → Halation → Grain+Noise → Vignette → (屏幕 | 离屏FBO)
```

- Preview 与 Capture 共用同一 `ProfileRenderer.render*`，故**预览即成片**。
- 确定层（无 blur/RNG：Exposure/WB/Color/Tone/Highlight/Shadow/HSL/Vignette）已通过
  `tests/test_shader_equivalence.py` 证明其 **float32 单位舍入级**等价（max diff ≤ 5.96e-8），
  证明见第 4 节。
- 模糊/程序化层（Sharpen/Bloom/Halation/Grain/Noise）在桌面端无法精确复现，留 **设备端视觉 QA（Phase 32）**。

## 4. 已固化的数学约定（三处历史坑，已闭合）

1. **HSL LUT 索引约定（曾导致 0.08 级偏差）**
   - 着色器按 `u=(pc_hue_index(hd)+0.5)/7` 采样，期望 **7 texel 的色相类别 LUT**（red→purple）。
   - `GpuParams.bakeHslLut` 现生成 7 行 `(sat, light, hueShift)`，与 `shaders/common.glsl::pc_hue_index`
     顺序一致；`GL_NEAREST` 采样精确命中类别，避免按角度索引导致的区间边界模糊。

2. **Vignette uv 约定（曾导致 0.07 级偏差）**
   - 统一为 **texel 中心**：`uv = ((x+0.5)/w, (y+0.5)/h)`，y 自图像顶行计数，纹理 row-0-first 上传故**无翻转**。
   - Profile 的 `vignette.center` 为图像空间（y 向下）；渲染器上传前 `u_center=(cx, 1-cy)`。

3. **Tone Curve 量化（曾逼近 1e-3 容差边界）**
   - LUT 在 **texel 中心**烘焙：`texel i = curve((i+0.5)/N)`；`GL_LINEAR` 采样即曲线本身。
   - CPU 参考 `sample_lut1d_linear` 与 Kotlin `GpuParams.bakeToneLut` 同为 texel 中心线性插值，
     消除最近邻索引差。

4. **Color Matrix 转置约定（贯穿全程）**
   - GLSL `mat3` 列主序且 `m * c`；渲染器 `glUniformMatrix3fv(..., transpose=true, ...)` 上传 `Mᵀ`，
     使 GPU 计算 `Mᵀ·c == CPU 的 v @ Mᵀ`（行向量数学）。GLSL 侧**不再转置**（见 `color_matrix.glsl`）。

## 5. 关键构建要点（实现时踩过的工程坑）

- **着色器 `#include`**：GLSL 无原生预处理。`GLSL.resolveIncludes` 在加载期递归内联 `*.glsl` 库文件。
- **FBO 纹理精度**：中间 buffer 用 `RGBA16F`（HALF_FLOAT）+ `EXT_color_buffer_half_float`，
  承载 Tone Curve 可能 >1 的 HDR 值；LUT 纹理用 `R16F`（tone）/ `RGBA16F`（hsl）。
- **OES 纹理**：相机输出为 `GL_TEXTURE_EXTERNAL_OES`，不能在多 pass 链中直接采样；`oes2d.frag`
  先把它复制进标准 2D `RGBA16F` 并应用传感器→显示旋转，再进入主链。
- **LUT 重传**：每次 `passMain` 用 `glTexImage2D` 重传 tone/hsl LUT（成本低，且支持实时切换风格）。
- **Grain/Noise 种子**：每帧用 `timestamp*0.06 % 1000` 作为 hash 种子，使颗粒随时间轻微变化而非静止。
- **ProGuard**：`core.*` 与 kotlinx 序列化 metadata 已在 `proguard-rules.pro` 保留，release 不会误删。

## 6. QA 计划（Phase 32）

- 确定性层：CI 已用 `test_shader_equivalence.py`（30 组随机 Profile + 随机图 + 非方/极端图）锁等价。
- 模糊/程序化层：在真机用 demo / photographer_a profile 做 A/B（GPU 预览 vs 桌面 `profile_renderer` 出图），
  以视觉回归比对；差异预期仅在 Bloom/Halation 半径与颗粒感上，属风格容差范围。
- 性能：目标中端机预览 ≥ 30fps；FBO 链全分辨率，bloom/halation 走 1/4 降采样（v1 单步，后续可迭代金字塔）。

## 7. 已知限制 / 后续

- 传感器→显示旋转当前为 `displayRotationDegrees` 的近似 2×2 UV 旋转；不同设备/前后摄的精确镜像与翻转
  留作 Phase 32 实机校准项。
- `conditions`（Phase 31 光照条件覆盖）已实现合并逻辑（`Conditions.kt`），待离线端产出带 conditions 的 Profile 后启用。
- Vulkan 路径留作性能路线，不作第一版必需项。

## 8. 门禁状态

- ✅ 离线 Profile 验证通过（`validated`），Phase 14–18 闭环。
- ✅ GLSL 等价验证通过（float32 单位舍入级），Phase 25 交付。
- ✅ Android：Camera2 + CameraX 依赖、ES3.0 GLSL 15 层链、Compose UI、Import/Export、条件合并、范围校验 —— 代码完整。
- ⏳ 未在真机编译运行（环境无 Android SDK / NDK）；需 `./gradlew assembleDebug` 实机/模拟器验证。

## 9. 收尾轮修复记录（本轮静态审查 + 门禁复核）

### 9.1 离线门禁缺陷（两处，均已修复并回归）

1. **`profile_validator.validate()` 无条件写 `validated`**
   - 原：无论 loss 高低一律置 `validated`，AGENTS.md 门禁形同虚设。
   - 修复：新增 `VALIDATED_LOSS_THRESHOLD = 1.0`（与测试断言一致），按
     `overall_test_loss < threshold` 判定 `validated` / `pending`；
     验证报告新增 `loss_threshold` 与 `validation_status` 字段。
2. **`build_profile.py` 在 `validate()` 之前写盘 `profile_final.json`**
   - 原：`validate()` 在内存中盖状态戳后，磁盘文件仍停留 `pending`，
     导出产物永远无法通过"validated 才可进 Android"的门禁。
   - 修复：`profile_final.json` 改为在 `validate()` 之后写出。

### 9.2 Demo Profile 重建（可复现）

- 用测试同款固定种子合成数据集（`_make_dataset`, rng=11, n=12）端到端重建
  `profiles/demo/`，`test_loss = 0.44041`（Tone Curve texel-center 约定修正后更优），
  `validation_status = "validated"` 已持久化；`android/.../assets/profiles/demo.json` 同步。

### 9.3 Android 端缺陷（三处编译/运行期问题，已修复）

1. **编译错误**：`CameraEngine` 构造器残留 `surfaceTexture` 必填参数，与同名属性冲突，
   且 `MainActivity` 只传 2 参 —— 已删除该构造参数。
2. **GL 上下文错误**：`open()` 在主线程（无 EGL 上下文）调用 `glGenTextures` 生成无效纹理名。
   改为 `SurfaceTexture(0)` + GL 线程 `attachToGLContext(oesTex)`；`updateTexImage` 仅在
   attach 之后调用，texName=0 不会被采样。
3. **预览竞态**：GL Surface 先于 `open()` 就绪时 `getSurfaceTexture()` 返回 null，
   预览永久黑屏。`CameraPreviewView` 抽出 `tryBindCamera()`，`onDrawFrame` 逐帧重试直至绑定成功。
   另：`onDetachedFromWindow` 不再释放引擎持有的 SurfaceTexture（避免会话断裂）；
   `CameraEngine.onDestroy` 补上 `surfaceTexture.release()`（修复泄漏）。

### 9.4 门禁状态（更新）

- ✅ 离线 Profile 验证通过且状态**持久化**（`validated`，loss 0.44041 < 1.0 阈值）。
- ✅ 全测试套件通过（`run_tests.py`，含 GLSL 等价、offline 闭环、回归基线）。
- ⏳ 真机编译与实机 QA（Phase 32）仍需 Android SDK 环境。

## 10. 静态交叉验证（无 SDK 环境的 QA 补充）

新增 `tools/static_cross_check.py`（已纳入 `run_tests.py`），在无 Android SDK 的环境下
静态核对 Python 侧产物与 Kotlin/资产的一致性：

| 检查项 | 结果 |
|--------|------|
| demo.json / photographer_a.json 全部键名 ↔ Profile.kt `@SerialName`（58 个） | ✅ 全覆盖（kotlinx 序列化不会运行期失败） |
| ProfileRenderer.kt 引用的 27 个 uniform ↔ 着色器声明 | ✅ 一一对应 |
| Kotlin 引用的 shader 资产文件存在性 | ✅ 完整 |
| Validation.kt 37 项范围校验 ↔ schema 边界（精确路径匹配 33 项） | ✅ 全部一致 |

注意：真机编译（`./gradlew assembleDebug`，需 Android SDK + Gradle wrapper）与 Phase 32
实机视觉 QA 仍为本项目剩余的外部环境依赖项。

## 11. 真机编译环境打通（本轮：用户要求双端都编译出来测试）

此前"本机无 Android SDK"的结论已推翻——本环境可下载，已搭建隔离工具链：

`C:/Users/EDY/.workbuddy/binaries/android/` = jdk17 / android-sdk / gradle-8.9

一键脚本 `tools/setup_android_env.sh` 幂等，已踩坑并修复 4 处：

1. **sdkmanager 静默跳过**：仅预置 license 哈希会被新版 cmdline-tools 忽略（仍弹 y/N 并跳过）。
   现持续喂 `y` 接受协议，且安装后做目录硬校验（缺任一组件即失败，杜绝"12 分钟构建末尾才崩"。）
2. **`gradle wrapper` 不可达**：该任务联网校验 `services.gradle.org` 的发行包 URL，本环境不可达。
   改用本地已下载的 Gradle 8.9 发行版**直接** `assembleDebug`（不生成 wrapper）。
3. **`local.properties` 的 `sdk.dir` 路径破坏**：反斜杠在 Java `.properties` 中是转义字符，
   `C:/Users/...` 被解析器破坏导致 `SdkLocator.validateSdkPath` 失败。改为正斜杠 `C:/Users/...`。
4. **camera 坐标写错**：app 依赖 `androidx.camera:camera2:1.4.0` 的 artifact 名错误，
   正确为 `androidx.camera:camera-camera2:1.4.0`（camera2 模块名带 `camera-` 前缀），
   原写法导致 `Could not find androidx.camera:camera2:1.4.0`。已修正。

> 桌面端 Studio 已产出 `dist/PhotographerStudio.exe`（56 MB 单文件），端到端验证通过
> （54 个可调控件 / 预览 / 调参 / 校验 / 导出）。APK 构建状态见 build5 日志。
