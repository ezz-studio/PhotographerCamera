# PhotographerCamera v0.3.4 交付报告

**版本**: 0.3.4 / code 12
**APK**: `PhotographerCamera-0.3.4.apk`（22.7 MB，与 0.3.3 同字节数属对齐 padding 巧合，md5 不同）
**性质**: 纯修复轮 — gray-world 白平衡采样 bug 修复

---

## 一、本轮起点：0.3.3 真机日志的重大成果

用户 6:40 后实测（session s1788648114979，257 行），五张拍摄全成功：

| # | 模式 | 结果 |
|---|------|------|
| 1 | YUV 直采（RAW 关） | **首次全分辨率成功**：`yuv capture frame 4096x3072` → `direct chain done -> 3072x4096` → saved 12.6MP |
| 2-4 | RAW ISP | 成功，绿偏大幅缓解（output avg 三组：64,168,131 → 96,131,96 等） |
| 5 | RAW @ 4.18x 变焦 | 成功（伴随 benign 的 capture guard timeout，自动恢复） |

- 0.3.2 的 `newPosition > limit (1472 > 1440)` stride 崩溃：**清零**，compactPlane fits 回退生效
- 全分辨率 YUV 直采链（CameraX 1.6 `setBufferFormat(YUV_420_888)`）在 PLG110 上验证通过，存图从 ~500KB/1.6MP 级别跃升至 12.6MP

## 二、日志暴露的 bug：gray-world 白平衡采样失效

0.3.3 日志出现：

```
wb gray-world gains=R1.000, B0.500
```

这是**假数值**——不是我预期的真实估计（白天场景应约 R 1.4~2.2 / B 1.1~1.8）。

### 根因（我自己在 0.3.3 引入）

gray-world 采样循环写成了：

```kotlin
var y = 0; var x = 0
while (y < h) { while (x < w) { ...; x += 6 }; y += 6 }
```

- **步长 6 是偶数、起点是偶数** → 所有样本坐标恒为 (偶, 偶)
- Bayer CFA 中 (偶,偶) 永远是**同一个相位**（PLG110 上报 cfa=(1,1)=BGGR → (x&1,y&1)=(0,0) 落在 B 站点）
- 于是 sr（红通道均值）恒 0、sg 恒 0 → 走 fallback：R gain = 1.0，B gain 撞 clamp 0.5

**教训：Bayer 采样若按步长跳格，步长必须是 2 的倍数（含相位覆盖），否则只采单相位。**

### 修复（0.3.4）

`ProfileRenderer.grayWorldWbGains()` 重写为 **2×2 CFA quad 步进**：

1. 外层步进 y/x += 12（保持采样密度量级）
2. 内层遍历 dx/dy ∈ {0,1}，**每个 quad 的 4 个相位全采**（R / G×2 / B 各归其位）
3. 相位分类：`relX = (x&1) ^ cfa[0]`、`relY = (y&1) ^ cfa[1]`（R: 00 / B: 11 / 其余 G），黑位扣除后 clamp[0,1]
4. **空数据守卫**：`if (sr <= 0 || sb <= 0 || sg <= 0)` → 保留标定增益并 log `wb estimate unusable`，不再输出假 1.0
5. gains clamp[0.5, 2.5]；撞边界时 log 追加 `(CLAMPED - check cfa/layout)`，便于下轮诊断

## 三、验证指引（装 0.3.4 后）

| 检查项 | 预期 |
|--------|------|
| `wb gray-world gains=R…,B…` | 真实估计值（白天 R≈1.4~2.2 / B≈1.1~1.8），**不撞** 0.5/2.5 边界 |
| 若 log 出现 `wb estimate unusable` | 采样仍异常，回报日志即可 |
| 若 gains 撞 CLAMPED | cfa 上报与真实排列可能不符，需交叉验证 |
| RAW 彩色成片 | 色彩精度应比 0.3.3 更准（0.3.3 的 R1.0/B0.5 本身就是错值） |
| 彩噪观感 | 0.3.3 chroma_denoise 已生效，请对比确认 |
| 全分辨率 YUV | 回归确认 12.6MP 正常 |

## 四、遗留观察

- `capture guard timeout — auto reset`：4.18x 变焦 RAW 时出现，200ms 守护自动重置、capture 619ms 照常完成，benign，继续观察频率
- cfa=(1,1)=BGGR 是否为真实排列：修复后的 gains 数值可作交叉验证（若白天 R gain 明显 >1 且 B gain 明显 <1 的方向相反，则排列标注有误）

## 五、改动文件

- `android/.../core/gpu/ProfileRenderer.kt` — grayWorldWbGains() 重写（quad 步进 + 守卫 + CLAMPED 告警）
- `android/app/build.gradle.kts` — 0.3.4 / code 12
