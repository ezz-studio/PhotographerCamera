# 编译与运行指南

本文说明如何把 PhotographerCamera 的两个软件各自编译成可运行/可分发的产物：

| 软件 | 产物 | 依赖 |
|------|------|------|
| 风格生成工作台（Studio） | `dist/PhotographerStudio.exe` | Python 3.13 + numpy + Pillow + PyInstaller |
| 相机软件（Android） | `android/app/build/outputs/apk/debug/app-debug.apk` | JDK 17 + Android SDK 34 + Gradle 8.9 |

---

## 一、风格生成工作台（Studio）

### 1. 从源码运行

```bash
python desktop/server.py                 # 默认 127.0.0.1:8765，自动打开浏览器
python desktop/server.py --port 9000     # 指定端口
python desktop/server.py --no-browser    # 不自动打开
```

仅依赖 `numpy` 与 `Pillow`（HTTP 层全部使用标准库，无 Flask / 无前端框架）。

### 2. 打包成 exe

```bash
bash tools/build_studio_exe.sh
```

产出 `dist/PhotographerStudio.exe`（单文件，约 40–60 MB）。

打包要点：

- `--add-data "desktop/web;desktop/web"` 与 `profiles/schema`：前端页面与 JSON Schema 是运行期资源，
  必须随包分发；
- `--paths .`：让 PyInstaller 能静态解析 `from tools import ...`；
- 冻结模式下代码在临时目录 `_MEIPASS`，**用户工作区在 exe 同级目录**（`profiles/`、
  `studio_session/`），因此 `server.py` 用 `BUNDLE`（资源）与 `WORK`（数据）两个根分离，
  避免写入被清理的临时目录。

### 3. 目录约定

```
PhotographerCamera/
├─ desktop/
│  ├─ server.py           后端（HTTP + 业务编排）
│  └─ web/                index.html / app.js / styles.css
├─ studio_session/        导入的照片、批量导出结果
└─ profiles/             生成的 Profile（schema、验证报告、回归基线）
```

### 4. Studio 的能力边界

- ✅ 从目录导入参考片；或在浏览器内拖拽/选择文件（前端压缩到长边 1600px 后上传）
- ✅ 端到端生成 Profile：风格分析 → v1 初稿 → 优化器 → 留出集验证
- ✅ 滑块微调 schema 暴露的全部标量参数（56 项，按模块分组），带实时重渲染
- ✅ 对比滑块视图（原图 / 渲染后），单张切换、批量渲染
- ✅ 导出到 `profiles/` 或直写 Android 资产目录
- ⚠️ 曲线控制点与 HSL 分色表由生成器/优化器产出，UI 暂不提供手绘编辑

---

## 二、相机软件（Android APK）

### 1. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | **17** | AGP 8.5.2 要求；JDK 21/25 会导致 Gradle 或 AGP 不兼容 |
| Android SDK | platform 34、build-tools 34.0.0、platform-tools | `compileSdk = 34` |
| Gradle | 8.9 | 本地发行版直接调用（`services.gradle.org` 不可达，跳过 wrapper 生成） |

### 2. 一键搭建并编译

```bash
bash tools/setup_android_env.sh
```

脚本把全部依赖装到隔离目录 `C:\Users\EDY\.workbuddy\binaries\android\`
（`jdk17/`、`android-sdk/`、`gradle-8.9/`），不污染系统环境，也不会动已有的 JDK 25。

执行流程：

1. 下载 Microsoft OpenJDK 17 并解压；
2. 下载 Android commandline-tools，通过持续喂 `y` 免交互接受协议（仅预置 license 哈希在新版
   sdkmanager 下会被静默跳过，必须喂 stdin）；
3. 安装 `platform-tools`、`platforms;android-34`、`build-tools;34.0.0`（安装后做硬性目录校验，
   防止被静默跳过导致 12 分钟构建在末尾才失败）；
4. 复用本地已下载的 Gradle 8.9 发行版，**直接运行 `assembleDebug`**（不生成 wrapper；
   `gradle wrapper` 会联网校验 `services.gradle.org` 的发行包 URL，本环境不可达）；
5. `local.properties` 写入 `sdk.dir=<正斜杠路径>`——`.properties` 文件中反斜杠是转义字符，
   `C:\Users\...` 会被 Java 属性解析器破坏，必须用 `C:/Users/...`。

### 3. 已安装环境后的重复编译

```bash
export JAVA_HOME="C:/Users/EDY/.workbuddy/binaries/android/jdk17"
export ANDROID_HOME="C:/Users/EDY/.workbuddy/binaries/android/android-sdk"
cd android
"C:/Users/EDY/.workbuddy/binaries/android/gradle-8.9/bin/gradle" assembleDebug     # 调试包
"C:/Users/EDY/.workbuddy/binaries/android/gradle-8.9/bin/gradle" assembleRelease   # 发布包（R8 混淆+资源收缩）
```

> 注：本工程不生成 Gradle wrapper（因 `services.gradle.org` 不可达），重复编译直接用本地
> Gradle 8.9 发行版二进制。若日后生成了 `gradlew`，可改用 `./gradlew assembleDebug`。

### 4. 安装到设备 / 模拟器

```bash
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. 构建配置要点（已修正）

- **Compose 编译器**：Kotlin 2.0 起必须由 `org.jetbrains.kotlin.plugin.compose` 插件提供，
  已从 app 模块移除废弃的 `composeOptions.kotlinCompilerExtensionVersion`，
  改为在 root / app 两处声明该插件。
- **ABI**：输出 `armeabi-v7a / arm64-v8a / x86 / x86_64`，真机与模拟器均可安装。
- **minSdk 26**：GLES 3.0 与 Camera2 的能力下限；启动图标采用纯 PNG 方案（5 密度），
  已彻底规避 `<adaptive-icon>`（adaptive-icon 要求 minSdk ≥ 26，且 AAPT2 会从
  `mipmap-anydpi-v26` 生成密度副本导致反复冲突）。

---

### 6. 真机编译已通关（最终状态）

`app-debug.apk` 已成功产出（约 18 MB，包名 `com.photographercamera` v0.1.0-alpha，
minSdk 26 / target 34，含 `CAMERA` 权限）。本次打通共修复以下真实问题：

**构建环境类**
| 问题 | 根因 | 修复 |
|------|------|------|
| AGP 8.5 不兼容 JDK 25 | JDK 版本过高 | 隔离安装 Microsoft OpenJDK 17 |
| `gradle wrapper` 联网失败 | `services.gradle.org` 不可达 | 本地 Gradle 8.9 直跑 `assembleDebug` |
| `sdk.dir` 反斜杠破坏 | `.properties` 反斜杠为转义符 | 改正斜杠 `C:/Users/...` |
| 缓存 `metadata.bin` 拒绝访问 | 默认 `~/.gradle` 写权限 | 隔离 `GRADLE_USER_HOME` + `-g` 参数 |

**Kotlin 源码类（块注释根因 → 级联）**
| 文件 | 错误 | 修复 |
|------|------|------|
| `GpuParams.kt:3` / `ProfileLoader.kt:5` | KDoc 内 `shaders/*.frag` / `assets/profiles/*.json` 的 `/*` 触发 **Kotlin 嵌套块注释**，导致顶层 `/**` 永久未闭合，整类无法解析并级联出数十处 Unresolved reference | 将 KDoc 内的 `/*` 拆开改写为普通文本 |
| `ProfileRenderer.kt` | 两个 `companion object` 块（Kotlin 仅允许一个），压垮模块符号解析 | 合并为单一 `companion object` |
| `MainActivity.kt` | `setContent` 导入错指向内部 `AbstractComposeView.setContent` | 改为 `androidx.activity.compose.setContent` |
| `CameraEngine.kt:111` | `surfaceTexture: SurfaceTexture?` 未做空安全 | `val st = surfaceTexture ?: return` |
| `GpuParams.kt` | `when(size){ >=2 -> }` 比较分支解析失败 | 改为 `when { size >= 2 -> }` |
| `ProfileRenderer.kt:169` | `GLES30.gl_FRAMEBUFFER` 误作函数 | 改为常量 `GLES30.GL_FRAMEBUFFER` |
| `ProfileRenderer.kt:307` | `glGetInteger` 函数名错误 + 参数不全 | `glGetIntegerv(pname, intArrayOf(), 0)` |
| `ProfileRenderer.kt:261` | `Double` 实参传给 `Float` 形参 | `seed` 末尾加 `.toFloat()` |
| `ProfileLoader.kt:86` | `nameWithoutExt()` 非标准 API | `substringBeforeLast('.')` |

**沙箱提示**：在受限沙箱环境中，最后阶段 `mergeDebugJavaRes` / `packageDebug` 写构建中间产物
（`app/build/intermediates/.../zip-cache/`）会被沙箱拦截（`拒绝访问`）。在可写权限的常规环境
（或显式授予构建目录写权限）下自动通过，无需改代码。

---

## 三、无 SDK 环境下的替代校验

在没有 Android SDK 的机器上，可用静态交叉检查代替编译期校验：

```bash
python tools/static_cross_check.py
```

核对四类跨语言一致性：Profile JSON 键名 ↔ Kotlin `@SerialName`、uniform ↔ 着色器声明、
shader 资产完整性、`Validation.kt` 数值范围 ↔ JSON Schema 边界。该检查已纳入
`tests/run_tests.py`。

---

## 四、测试

```bash
python tests/run_tests.py              # 离线 Pipeline + GLSL 等价 + 静态交叉检查
python tests/test_studio_smoke.py      # Studio HTTP 端到端（合成数据，无需浏览器）
```
