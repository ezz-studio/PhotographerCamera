# PhotographerCamera 0.6.0 版本报告 — PhotonCamera 深度整合（任务书 15 项落地）

> 日期：2026-09-06 ｜ commit：见 git log ｜ APK：`PhotographerCamera-0.6.0.apk`（versionCode 19）

## 本轮目标

以 PhotonCamera 为技术底座 + 我方 UI/动效/Profile 体系，落地任务书第一优先级批次：
JPEG MAX/RAW MAX 默认化、测光五模式、AWB 开关、全屏设置改版、音量键、快门反馈等。

## 已落地（本 APK 可验证）

| # | 任务书条目 | 实现 |
|---|-----------|------|
| 1 | MAX 帧数默认 4 | `CameraEngine.burstCount` 默认 6→**4**（`pc_burst.txt`仍可覆盖 2-12） |
| 2 | 测光图标=测光模式切换 | TopBar 测光图标点击**循环切换**：系统默认→中央重点→平均→高光优先→点测，`Toast` 提示当前模式，每模式独立图标；`MeteringMode` 枚举 + `applyMeteringRegion`（CameraX FocusMeteringAction，点测 6%/中央重点 25%/高光优先 8%/平均全画面） |
| 3 | 高光优先 | 分析流亮度扫描（上 10% 均值质心）实时锁定画面最亮区域 |
| 4 | RAW 开关迁移 TopBar | 从设置页迁入顶栏（仅 RAW-capable 设备显示，橙色=开启），设置页保留"RAW ISP 引擎"应急修复开关 |
| 5 | WB 增加 AWB 开关 | WB 面板新增"AWB 自动白平衡"开关：**开=色温/色调滑块灰置不可调**（alpha 0.35 + 手势禁用）；关=用户接管可调；持久化 `pc_settings/awb_on`，默认开 |
| 6 | 设置 UI 改版 | 新建 `AppSettingsScreen`：**全屏列表+分组卡片**（PhotonCamera 风格），替代底部半透明弹层；分组=拍摄/镜头/成像与色彩/维护 |
| 7 | 设置新开关 | 快门声音✓ / 拍摄震动✓ / 音量键功能（拍照·变焦·无）✓ / HDR 显示（预留，当前管线未生成增益图）✓ / **保存地址位置（默认关，开启时请求位置权限，GPS EXIF 写入照片）**✓ |
| 8 | 去掉构图网格开关 | 设置页已移除（BottomBar 快捷网格切换保留） |
| 9 | 写死参数（不显示、不可调） | JPEG MAX=普通拍摄默认路线✓（0.5.0 即 YUV 直采+多帧堆栈）；照片质量 100✓；拍摄后自动保存✓；降噪/锐化高质量✓（MGC 空间降噪链）；画面比例固定 4:3✓。**帧数 4 已落实（见 #1）** |
| 10 | 音量键 | `MainActivity.onKeyDown` → `VolumeKeyBus` → `doCapture()`（含连拍守卫/计时器全语义）或 1.2× 变焦步进 |
| 11 | 快门声音/震动 | 系统快门音（`MediaActionSound`）+ 30ms 短震动，跟随设置开关 |

**镜头信息**：设置页直读 CameraManager（前/后置、焦段、有效像素），无虚拟镜头/景深项。
**色彩映射+修复开关**：色彩映射（sRGB 输出说明）+ YUV 直采/多帧连拍/RAW ISP 三个应急修复开关。
**检查更新 + 调试日志**：从 0.4.0 原样迁移（`UpdateCheckRow` 提为 internal 复用，单一实现）。

## 任务书明确排除项

AI 服务、界面样式、幻影、内容管理、数据维护、多重曝光、画面比例修改、工具箱 —— 均未引入。

## 下一轮（已在任务书内，本轮未动）

1. **LIVE 图**（TopBar 开关）+ **视频使用配置文件** —— 需 CameraX VideoCapture + 滤镜链实时化
2. **RAW MAX**（RAW 默认开启 RAW MAX 画质调优、Spatial 融合模式）—— RAW ISP 引擎已预留，待实机验证后启用多帧 RAW
3. **JPEG 4:4:4 导出** —— 待 libjpeg-turbo 集成（当前 HAL JPEG 为 4:2:0）
4. 镜头阴影校正、Adobe 曲线 RAW 渲染、Camera2 API 降噪模型 —— 随 RAW MAX 一起
5. 对焦与镜头菜单（不含虚拟镜头/景深）进拍摄界面

## 技术说明

- **AWB 语义**：CameraX 1.6.2 无 `setWhiteBalanceMode` API（beta 提案已移除），本轮 AWB 开关为 UI 层门控——滑块本身是 GPU 后段相对调整，AWB 关=用户接管白平衡滑块。相机侧 HARD AWB LOCK 需 Camera2Interop 重 bind，列入后续 RAW MAX 轮次评估。
- **测光**：CameraX `startFocusAndMetering(FLAG_AE)` 只动 AE 不动 AF；高光优先模式依赖分析流扫描（无数据时暂用中心，下一帧生效）。
- **UI/动效**：仅新增设置页与 TopBar 两枚图标位，原拍摄界面布局/动效零改动。

## 已知限制

- HDR 显示开关为预留（当前不生成 UltraHDR 增益图）
- 保存位置需位置权限，拒绝后开关回落关闭态
- 音量键=变焦时步进倍率固定 1.2×（无连续长按加速）

## 回归风险点（实机重点验证）

1. 测光五模式切换后 EV/对焦行为（应只动测光不动对焦）
2. 音量键拍照与计时器/连拍守卫的相互作用
3. AWB 关闭后 GPU 色温色调滑块手感
4. 设置页各项开关的即时生效性（声音/震动/GPS EXIF）
