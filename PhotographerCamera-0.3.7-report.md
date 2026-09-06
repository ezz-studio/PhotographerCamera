# PhotographerCamera v0.3.7 交付报告

**版本**: 0.3.7 / code 15
**APK**: `PhotographerCamera-0.3.7.apk`（22.7 MB）
**性质**: 纯修复轮 — 针对 0.3.6 实测三连 bug（RAW 全红 / RAW+YUV 变形 / YUV 极差）

---

## 一、三个 bug 的根因与修复

### Bug 1：RAW 全红 → 0.3.6 的 MTK quirk 修正方向搞反了

0.3.6 曾把 HAL 上报的 BGGR 强改为 RGGB（"180° R/B 互换 quirk"）。实测证明这是**误判**：

- 0.3.6 RAW 成片全红 = demosaic R/B 互换的直接后果（户外蓝天大面积变红）
- 交叉验证：0.3.5 gray-world 采样 gains R2.5/B2.5（按 BGGR 解）与 0.3.6 HAL as-shot 实测 R2.33/B1.58（`wb as-shot (HAL 3A)` 日志）高度吻合 —— R/B 桶落的就是**真实弱通道**，正是正常 daylight 白平衡增益需求，不是排列错位
- 0.3.3 时代"偏绿"的真正根因是当时 wbGains 恒中性（无 WB 补偿），0.3.4 起 WB 通道已修

**修复**：`DeviceAdapter.normalizeCalibration()` 撤销 quirk，CFA 恢复按 HAL 上报值（BGGR → (1,1)）。

### Bug 2：RAW + YUV 都变形 → coverWindow 双重换位

`renderYuvChain` / `renderRawChain` 把**已经 upright 化**的 upW/upH 传给 `coverWindow(..., isRot90=true)`，而 coverWindow 内部对 isRotated90 又换位一次 → 窗口按 4:3 横帧计算 → 3:4 竖帧画面窗口裁掉 25% 宽度、保留全高 → 输出**纵向压缩 0.75x（人变矮胖）**。

**修复**：两处调用改传 `isRotated90=false`（窗口直接在 upright 域计算，不再二次换位）。3:4 源现在得到全窗口 (0,0,1,1)，内容 1:1 无变形。预览路径不受影响（其调用传的是原始 sensor 尺寸，语义本来就对）。

### Bug 3：YUV 极差 → YUV_420_888 still 不走 HAL 多帧降噪，补 GPU 色度降噪

`YUV_420_888` still 输出**不经过 HAL 的多帧降噪管线**（那只在 HAL 自己的 JPEG/RAW develop 路径激活），单帧 YUV 彩噪严重。0.3.6 的 chroma_denoise 只挂在 RAW 链上。

**修复**：YUV 链在 effect 链前插入同一 5×5 边缘停止色度降噪 pass（`mainFbo → auxFbo → effect`），亮度细节 bit-exact（锐度不受损）。HAL 端 HQ 档请求（NR/edge/aberration）0.3.6 已有，保留。

---

## 二、0.3.6 会话日志回顾（s1788651404183，本次修复依据）

- 9 拍全成功无异常：3 YUV（zoom 1.0 / 4.87×2）+ 6 RAW ISP
- as-shot WB（HAL 3A per-shot CaptureResult）**首次工作**：gains R1.69~2.33 / B1.46~1.58，随场景变化，数值真实
- 变焦 crop 倍数与 digital zoom 一致（4.87x → 1817x2423，1.69x digital），zoom 逻辑本身正确
- 变形/全红为几何与色彩 bug（本轮修复），非管线崩溃

## 三、验证指引（装 0.3.7 后）

| 检查项 | 预期 |
|--------|------|
| RAW 成片色彩 | 不再全红；as-shot gains 日志照常（R 1.7~2.3 / B 1.5 左右） |
| RAW + YUV 几何 | 竖帧不再压扁；3:4 全视场与预览一致 |
| YUV 彩噪 | 明显改善（5×5 色度降噪生效；亮度/锐度不变） |
| 日志 | 无 `MTK quirk` 行（已撤销）；`yuv pass center=` / `raw chain output avg=` 照常 |

## 四、改动文件

- `android/.../core/device/DeviceAdapter.kt` — 撤销 BGGR→RGGB quirk，CFA 按 HAL 上报值
- `android/.../core/gpu/ProfileRenderer.kt` — coverWindow 双重换位修复（RAW+YUV 两处）；YUV 链接入 chroma denoise pass
- `android/app/build.gradle.kts` — 0.3.7 / code 15

## 五、遗留观察

- YUV 单帧仍无 HAL 多帧降噪，暗光 luma 噪点会高于 HAL JPEG 路线——属"禁止 JPEG 路线"架构的已知代价；若暗光实测不可接受，下一轮在 GPU 链补 luma 降噪（需与锐度权衡）
- as-shot gains 与 gray-world 兜底链路均已工作；CLAMPED 告警仅在 as-shot 缺失且场景极端时出现
