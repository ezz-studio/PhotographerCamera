# PhotographerCamera 0.4.0 交付报告

**版本**：0.4.0 / versionCode 17（功能轮 minor+1）
**APK**：`PhotographerCamera-0.4.0.apk`（22.7MB，MD5 `1d29ad53cc8b2ac743ebde3715625948`）
**OTA**：已发布到更新服务器 —— `http://106.53.7.242:18888/apk/PhotographerCamera-0.4.0.apk`

---

## 一、模糊 + 噪点根因（0.3.8 日志分析结论）

0.3.8 真机日志（422 行，7 拍）链路本身零异常：全分辨率 4096x3072、无 fallback、直采稳定。
画质问题根因有三层：

1. **YUV_420_888 still 绕过 HAL 多帧降噪**——HAL 的 NR 只在自家 JPEG/RAW-develop 路径
   激活（Camera2Interop 的 HQ 请求对 YUV still 基本被忽略）→ 暗光单帧亮度噪点重
2. **零锐化**——HAL 对 YUV still 不做 EDGE 处理，且 profile 的 `sharpen.amount = 0.0`
   （效果链的锐化段被风格参数关闭）→ 成片没有任何锐化来源 = "模糊/发肉"
3. 0.3.7 已加的色度降噪只压彩噪，亮度噪点未处理

## 二、修复（引擎级，不改 profile 调色语义）

YUV 链新 pass 序列（`core/gpu/ProfileRenderer.kt`，RAW 链冻结未动）：

```
YUV→RGB → 色度降噪(已有) → 亮度降噪(新) → 捕获锐化(新) → 风格 effect 链
```

| Pass | Shader | 实现 | 参数 |
|---|---|---|---|
| 亮度降噪 | `luma_denoise.frag`（新） | 5×5 双边：空间衰减 + 亮度/色度边缘停止；单 pass 结构对照 gpuimage-plus `cgeBilateralBlurFilter` | amount 0.55 |
| 捕获锐化 | `sharpen.frag`（复用现有资产） | 3×3 加权 unsharp `c + a·(c−blur)`；HAL 对 YUV still 无锐化 → 这是链路唯一锐化 | amount 0.35, radius 1px |

- 锐化在 grain（风格）**之前**，不会放大颗粒
- shader 编译失败各自降级（跳过该 pass），不阻塞拍摄
- 验证日志行：`direct chain done ... nr=[chroma=true luma=true sharpen=true]`

## 三、应用内"检测更新"（新功能）

- **App 端**：设置页新增"检查更新"行（`UpdateCheckRow`）——检查 → 发现新版直接流式下载
  （实时百分比进度）→ 下载完成点击"安装"拉起系统安装器。
  `core/update/UpdateChecker.kt`（HttpURLConnection 流式下载 → `externalCacheDir/update.apk`
  → FileProvider + `ACTION_VIEW`）；Manifest 加 `REQUEST_INSTALL_PACKAGES` + FileProvider
  （`res/xml/file_paths.xml`）。首次安装时系统会要求授予"安装未知应用"权限。
- **服务端 v2**（已部署到 106.53.7.242，`/root/photographer_log/server.py`，systemd 已重启）：
  - `GET /api/version` → `{versionName, versionCode, url, size, notes}`
  - `GET /apk/<file>` → APK 下载（防目录穿越）
  - `POST /api/upload/apk?token=…&vname=…&code=…&notes=…` → 换新 APK、**自动删除旧 APK**、
    更新 version.json、**自动清理 3 天前的旧调试日志**
  - 上传令牌存服务器 `upload.token`（chmod 600），本地副本 `server/upload.token.local`
    （已 gitignore，不入库）
  - 回归验证：/log 上报 200 正常；服务器端 MD5 与本地构建一致
- **构建发布流程**（每轮打包自动执行）：`gradle assembleDebug` → `curl POST /api/upload/apk`
  → 旧 APK/旧日志自动清理 → 手机端"检查更新"即可拉到新版

## 四、注意

- 服务器公网出口带宽 ~1Mbps，App 内下载 22.7MB 约 3 分钟——进度条正常跳动即属预期
- RAW 仍为冻结的实验性功能，本轮未触碰（日志中 09:18:47 的 RAW 全红为已知冻结项）

## 五、装 0.4.0 后验证点

1. 暗光 YUV 拍摄：彩噪与亮度噪点应明显下降、细节不发肉（对比 0.3.8 同场景）
2. 设置 → 检查更新：应显示"已是最新版本（服务器 v0.4.0）"；下一轮发新版后可直接 App 内升级
3. 日志确认：`nr=[chroma=true luma=true sharpen=true]`
