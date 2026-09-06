# PhotographerCamera v0.3.5 交付报告

**版本**: 0.3.5 / code 13
**APK**: `PhotographerCamera-0.3.5.apk`（22.7 MB）
**性质**: RAW 开发冻结（默认关+实验性标注） + YUV 直采画质补强 + WB 采样修复（0.3.4 首次随包）

---

## 一、偏色归因修正（0.3.3 日志 + 用户实测描述交叉验证）

用户实测描述："1、5 关 raw，2/3/4 开 raw；2-4 只有绿色，3 只有红色"。与日志数据完全吻合：

| # | 模式 | 日志数据 | 偏色 |
|---|------|---------|------|
| 2 | RAW 1x | center=46,79,56 → G 碾压 | **绿** ✓ |
| 3 | RAW 0.6x 超广角 | center=62,47,47 → R 最高 | **红** ✓ |
| 4 | RAW 4.18x | avg=105,117,89 → G 最高 | **绿** ✓ |

**统一根因**：0.3.3 gray-world 采样 bug（偶数步长只采单一 CFA 相位）输出假 gains `R1.0, B0.5`
—— 蓝色增益被直接砍半（蓝消失），红色增益假 1.0 不补偿，场景里 R/G 谁强谁主导：冷调场景剩绿（#2/#4）、暖调场景剩红（#3）。

**0.3.4 的 quad 步进采样修复正是对症药，但用户还没装**——本版（0.3.5）包含该修复，且 RAW 默认已关。

## 二、RAW 开发冻结（用户决策执行）

- **RAW 偏色已修**（0.3.4 quad 采样 + 空数据守卫），代码冻结，不再迭代 RAW 路径
- **默认设置为关**：
  - `CameraEngine` 读取默认值 `raw_isp_enabled` true→false
  - `CameraScreen` UI 状态默认值 true→false
  - **0.3.5 一次性迁移**：首次启动把历史遗留的 `true` 强制重置为 `false`（迁移标记 `raw_isp_default_off_migrated` 保证只跑一次；之后尊重用户手动选择）
- **设置页开关改名**："RAW 开关" → **"RAW（实验性功能）"**，副标题 "默认关闭：YUV 直采（无 JPEG 压缩）"

## 三、YUV 直采画质差调研与修复

### 根因分析

1. **HAL ISP 档位**（主因，本轮修复）：YUV_420_888 still 的降噪/锐化档位默认跟随 FAST；
   OEM 相机 JPEG 路径隐含多帧合成 + HIGH_QUALITY ISP 处理，YUV 直采被 HAL 区别对待
   → 暗光彩噪明显、细节发肉。javap 反查 CameraX 1.6.2：`ImageCapture.Builder` **没有**
   setNoiseReductionMode/setEdgeMode，需走 camera2 interop。
2. **JPEG 编码质量**（次因，本轮修复）：链路终点 JPEG 95 在 12.6MP 高倍放大下可见
   压缩伪影。
3. **非问题项（排除）**：
   - BT.601 full-range 矩阵：符合 CDD 对 camera YUV 输出的规定，保持
   - 4:2:0 色度半分辨率：与 OEM JPEG 编码相同，非差异点
   - GL 链 RGBA8 中间量化：每 pass 1/255 误差，可接受
   - 分辨率：0.3.3 已解决（4096x3072 全分辨率直采，12.6MP）

### 修复

- **Camera2Interop 强制 HIGH_QUALITY**（仅 YUV 直采模式，try-catch 不阻塞 bind）：
  - `NOISE_REDUCTION_MODE_HIGH_QUALITY`
  - `EDGE_MODE_HIGH_QUALITY`
  - `COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY`
  - 成功时日志 `yuv capture: HQ noise-reduction/edge/aberration requested`
- **JPEG 质量 95→100**（CaptureSaver）

## 四、验证指引（装 0.3.5 后）

| 检查项 | 预期 |
|--------|------|
| 设置页 | RAW 显示"RAW（实验性功能）"，**默认关闭**（即使之前开过也会被重置一次） |
| RAW 彩色成片（手动开启验证） | 偏色应正常（真实 WB gains，白天 R≈1.4~2.2 / B≈1.1~1.8） |
| `wb gray-world gains=` | 真实数值，不撞 0.5/2.5 边界，无 `unusable`/`CLAMPED` |
| `yuv capture: HQ noise-reduction/edge/aberration requested` | 出现 = HAL 高质量档已请求 |
| YUV 暗光成片 | 彩噪应明显低于 0.3.3（HQ 降噪生效），细节更扎实 |
| 成片体积 | JPEG 100 下 12.6MP 应 4~7MB 级别 |

## 五、改动文件

- `CameraEngine.kt` — RAW 默认关+一次性迁移；Camera2Interop HQ 三项请求（imports + @OptIn）
- `CameraScreen.kt` — RAW UI 默认值 false；开关标签"RAW（实验性功能）"
- `CaptureSaver.kt` — JPEG 95→100
- `build.gradle.kts` — 0.3.5 / code 13

## 六、遗留观察

- HAL 对 interop HQ 请求的响应程度因 OEM 而异（MTK 平台一般尊重 NR/EDGE key）——以成片观感为准
- capture guard timeout（4.18x 变焦时）仍为 benign，继续观察
- RAW 代码冻结后不再动；如未来重开，cfa 上报与真实排列的交叉验证是第一个课题
