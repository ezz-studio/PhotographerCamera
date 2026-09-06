# PhotographerCamera v0.3.6 交付报告

**版本**: 0.3.6 / code 14
**APK**: `PhotographerCamera-0.3.6.apk`（22.9 MB）
**性质**: RAW 偏色真正根因修复（CFA 180° quirk + 官方 as-shot WB） + 滤镜列表滚动 bug 修复

---

## 一、滤镜选择列表滚动 bug（已修）

**症状**：列表打开时显示中间部分，第一个滤镜看不见，需手动滑到最上面。

**根因**：`LazyColumn` 的 `LazyListState` 默认走 `rememberSaveable`，导航返回栈会保存
上次浏览的滚动位置——再次进入时视口停在中间，第一个滤镜在视口上方。

**修复**：进入屏幕及 profiles 加载完成后强制归顶（`LaunchedEffect(profiles.size) { scrollToItem(0) }`）。

## 二、RAW 偏色真正根因（本轮核心修复）

### 调研过程（按用户要求查证 CameraX/Camera2 现有方法）

1. **官方 per-shot 元数据通道确认**：`Camera2Interop.Extender(builder).setSessionCaptureCallback`
   → `onCaptureCompleted` 给 `TotalCaptureResult`，含 HAL 3A 算好的
   **`COLOR_CORRECTION_GAINS`**（as-shot 白平衡增益）——这是官方现成 API，无需自己估计。
2. **MTK CFA quirk 社区佐证**：CSDN MTK 案例记录"驱动 SRGGB vs DTBO BGGR 配置不一致 →
   用 BGGR 模板解析 RGGB 数据 → **全图偏绿**"；IMX258 案例确认 MTK 系 CFA 配置
   错位是常见问题。

### 本机实测数据（0.3.5 日志）的根因判定

三张 RAW 全部 `wb gains=R2.5, B2.5 (CLAMPED)`，且 demosaic 后通道塌零（#1 R=0、#2 G=0）：

- R/B 增益**双撞上限** = R 相位桶和 B 相位桶采到的值都极弱、G 桶极强
- 四种 Bayer 排列中**唯一**产生该特征的是：真实排列 = **RGGB**（R/B 分居对角线、
  G 占反对角线），而 HAL 上报 `BGGR(3)` —— BGGR↔RGGB 恰好差 **180°**（R/B 对角互换）
- 0.3.3"偏绿"（错位 demosaic → R/B 都弱 → G 主导）同源

### 修复（0.3.6）

1. **CFA 标定修正**（`DeviceAdapter.normalizeCalibration`）：MTK 平台
   （`Build.HARDWARE` 前缀 mt）+ arrangement=3 → 按实测标定改为 RGGB(0,0)，
   带 `MTK quirk` 日志。demosaic（u_cfa）与 CPU 采样共用同一标定源，一处修正全链生效。
2. **WB 主通道换官方 API**（`CameraEngine`）：RAW ISP 模式的 ImageCapture 挂
   `setSessionCaptureCallback`，缓存每帧 `COLOR_CORRECTION_GAINS`（G 归一），
   经 `RawFrame.asShotGains` 传入渲染链；`renderRawChain` 优先使用（日志
   `wb as-shot (HAL 3A) gains=R…,B…`），gray-world 估计降级为兜底。

## 三、"原厂 ISP + 自家调色 + 自家 encode"可行性结论

**可行，且就是当前 YUV 直采主通道的架构**（已在跑）：

| 环节 | 状态 |
|------|------|
| 原厂 ISP 处理 | ✓ YUV_420_888 = HAL ISP 后、JPEG encode 前的帧（0.3.5 起 interop 请求 HQ 档） |
| 插入摄影师风格调色 | ✓ GPU 风格链在全分辨率 YUV→RGB 后执行 |
| 自己 encode | ✓ JPEG 100 |

与原厂 JPEG 的唯一本质差距：HAL 的**多帧合成**（ZSL/HDR merge）不对 YUV still 请求
开放，公开 API 无解；暗光差距由 HQ 降噪档位尽量弥补。色彩空间（BT.601 full-range）
符合 CDD 惯例，非差异点。

## 四、已知问题（下轮排查）

- **YUV 变焦成片"变形/与预览不一致"**：日志显示 zoom=5.76 时保存 1536x2048
  （光学 2.88x + 数字 2x 裁切，比例 3:4 无拉伸）。"变形"更可能是裁切区域与取景框
  视野不匹配（变焦-裁切链），需要下一轮带日志定位。

## 五、验证指引（装 0.3.6 后）

| 检查项 | 预期 |
|--------|------|
| `MTK quirk: reported BGGR(3) corrected to RGGB(0,0)` | bind 时出现 |
| `RAW ISP: as-shot WB gains via per-shot CaptureResult enabled` | RAW bind 时出现 |
| `wb as-shot (HAL 3A) gains=R…,B…` | RAW 拍摄时出现（取代 gray-world 行） |
| gains 数值 | 真实白天值（R 1.4~2.2 / B 1.1~1.8），不再撞 2.5 |
| RAW 彩色成片 | 偏色应基本正常（CFA 修正 + 真实 WB 双修复） |
| 滤镜列表 | 每次进入都从第一个滤镜开始显示 |

## 六、改动文件

- `DeviceAdapter.kt` — MTK CFA 180° quirk 修正
- `CameraEngine.kt` — as-shot gains 缓存 + interop 回调；RawFrame 传参
- `RawFrame.kt` — asShotGains 字段
- `ProfileRenderer.kt` — renderRawChain asShotGains 参数；WB 主通道切换
- `CameraPreviewView.kt` — 调用点传参
- `PresetListScreen.kt` — 列表进入归顶
