# PhotographerCamera v0.2.5 交付报告

**版本**: 0.2.5 (versionCode 7)  **APK**: `PhotographerCamera-0.2.5.apk` (22.7 MB)
**主题**: 统一动态计算引擎 + 设备适配层 + RAW 开关 UI

---

## 一、本轮完成

### 1. Unified Image Engine 链序重构（effect.frag 全量重排）
按用户定义的目标链序（v0.2.5 起为权威顺序）：

```
1. exposure
2. white balance
3. color matrix
4. highlight rolloff   ┐ u_tonePreLinear 门控
5. shadow              ┘ （RAW ISP 已在 linear 域做过则跳过，防双重套用）
6. film curve          （Contrast/BW 基础曲线，toe+shoulder）
7. tone curve          （独立 1D LUT —— 与 film curve 职责分离）
8. HSL
9. 3D LUT              （唯一风格色段 —— LUT 只管风格颜色映射）
10. vignette
11. bloom
12. halation
13. grain（光照感知分布）+ noise
14. sharpen            （最终细节段，永远最后）
```

- 链序语义符合胶片物理：暗角（镜头效应）→ halation（胶片底层散射）→ grain（暗角区权重最高、不被衰减）→ sharpen 收尾
- **profile 恒定原则落地**：所有段参数恒定，引擎不存在场景统计驱动的参数漂移
- sharpen/bloom/halation 保持从 Pass1 稳定纹理采样（单 pass 折衷，已在注释中说明：调色段均为低频平滑映射，细节向量 (center-blur) 在替换下成立）

### 2. Grain 光照分布（引擎唯一光照自适应项）
```
shadowW = 1.0 - 0.6·smoothstep(0.00, 0.45, lum)   // 阴影 1.0 → 中调 0.4
highW   = 0.4 + 0.45·smoothstep(0.72, 0.97, lum)  // 中调 0.4 → 强高光 0.85
mask    = max(shadowW, highW)
```
- 颗粒按局部光照落点分布：暗部致密（胶片趾部颗粒）、中间调平缓、强高光轻微存在
- amount/size/density 参数恒定 —— "颗粒根据光照多少动态添加"，特征不漂移

### 3. 设备适配层 DeviceAdapter —— SDK 36 API 形状修正（javap 铁证验证）
首轮编译暴露 3 处 API 误判，全部按 android.jar 反编译结果修正：

| 字段 | 误判 | SDK 36 真实形状（javap） | 修正 |
|---|---|---|---|
| RAW 能力常量 | `REQUEST_AVAILABLE_CAPABILITIES_RAW_SENSOR` | **`REQUEST_AVAILABLE_CAPABILITIES_RAW`** | 已改 |
| Binning factor | `Key<Integer>` | **`Key<Size>`**（quad → Size(2,2)） | `maxOf(w,h)` 取倍数 |
| 黑阶 pattern | `Key<int[]>` | **`Key<BlackLevelPattern>`**（封装类） | `getOffsetForIndex(col,row)` 直接返回 int |

附注：javap 同时确认 SDK 36 已移除 `SENSOR_NEUTRAL_COLOR_POINT`，且存在 `REMOSAIC_REPROCESSING` / `ULTRA_HIGH_RESOLUTION_SENSOR` 能力常量 —— 后续 quad bayer 全尺寸 remosaic 任务的官方入口。

### 4. RAW 开关 UI（能力检测 → 才显示）
- `CameraScreen`：`rawOn`（持久化 pc_settings.raw_isp_enabled）+ `rawCapable`（engine 探测，lensEpoch 触发重组）
- `QuickControls` 新增 RAW 快捷键：**仅 `rawCapable == true` 时渲染**（目标架构"检测到 RAW_SENSOR 才显示 RAW 开关"）；样式与网格键同款（橙=启用）
- 点击链路：UI 状态 + 持久化 → `engine.setRawIspEnabled(on)` → OUTPUT_FORMAT rebind 生效
- 修复 `StillFrame` 缺失 import；`rawCapable` remember 位置修正（lensEpoch 声明后，消除前向引用）

### 5. 编译验证
- 3 轮编译迭代，共修复 6 个错误（1 前向引用 + 5 个 SDK 36 API 形状）
- 最终 `assembleDebug` **BUILD SUCCESSFUL**，APK 已按命名规则产出并复制到项目根

---

## 二、技术路线 ↔ 当前工程映射（按用户拼装表）

| 路线模块 | 路线参考项目 | 当前工程落点 | 状态 |
|---|---|---|---|
| UI / Compose | android camera-samples | `ui/`（Compose 全套） | ✅ 已有 |
| Camera 框架 | CameraX/Camera2 | `core/camera/CameraEngine.kt` | ✅ 已有 |
| 设备适配/DeviceProfile | CameraX+Camera2 探测 | `core/device/DeviceAdapter.kt`（Caps 矩阵+标准化+降级） | ✅ **本轮落地 v1** |
| RAW 采集/GPU demosaic | CinemaCamera core-camera | `shaders/raw_isp.frag`（Malvar 5×5 GPU demosaic，已修蓝本 DC 增益 bug） | ✅ 已有且更优 |
| RAW ISP 算法 | openISP | raw_isp.frag 链（BLC→WB→CCM→demosaic→linear 调色） | ✅ 参考移植 |
| 统一 GPU 引擎 | android-gpuimage-plus 架构模式 | `core/gpu/ProfileRenderer.kt` + effect.frag 2-pass 单链 | ✅ **本轮完成链序重构** |
| YUV 管线 | YUV_420_888 直采 | **任务 #38 已建**（兑现"禁止 JPEG 路线"） | ⏳ 下轮 |
| 性能层 HardwareBuffer | android-hardware-buffer-camera | **任务 #37 已建**（零拷贝移植） | ⏳ 排期 |
| RAW/DNG 导入 | LibRaw-Android | 可选阶段 | ⏳ 远期 |
| Shader 参考 | GPUVideo-android | hash12/grain/halation 等已参考其模式 | ✅ 持续 |

**原则执行情况**：不重复造轮子 —— 现有组件均为移植件（Malvar 论文核 / Dave Hoskins hash12 / openISP 链序 / gpuimage-plus 架构模式），自研已验证部分（v0.2.0-0.2.4 的 bug 修复成果）不推倒；开源可整体引入的（HardwareBuffer 桥）列为移植任务而非重写。

---

## 三、真机验证清单（OPPO PLG110）
1. 冷启动后快捷行：PLG110 支持 RAW_SENSOR → 应出现 **RAW** 快捷键（默认橙=开）
2. 点 RAW 关闭 → rebind 生效，成片走 ISP JPEG+effect 链；再点开恢复 RAW ISP 链
3. RAW 成片：暗部颗粒明显、高光区轻微颗粒（光照分布）、高光肩部/阴影趾部与 v0.2.4 一致（linear 域已做、effect 链未二次套用）
4. 普通成片：风格与 RAW 路径一致（同一 profile 函数），film curve 现作用于 highlight/shadow 之后（Contrast 基调变化属预期）
5. 远程日志关键字：`ADAPT: caps rawSensor=... quad=... binning=...`（设备适配探测）

## 四、已知限制 / 后续
- **UI/动效冻结（用户指令，即刻生效）**：后续迭代只动技术层面（引擎/管线/算法/稳定性），不改 UI 布局与动效；本轮唯一 UI 新增 = 架构图点名要求的 RAW 开关（能力检测显隐，无视觉重设计）
- 普通模式仍走 HAL ISP JPEG 输入（= YUV 管线的 HAL 代工形态）；**任务 #38** 用 ImageReader YUV_420_888 直采替换，彻底兑现"禁止 JPEG"
- Pass2 采样依赖仍含一次 GPU 内存往返；**任务 #37** 移植 HardwareBuffer 零拷贝
- 前后摄切换 / 多镜头的 lensEpoch 重组已覆盖 RAW 键显隐；物理多摄（超广角）RAW 能力逐镜头探测在 DeviceAdapter.Caps 内，无需上层改动
- CPU 参考工具 `tools/profile_renderer.py` 的链序与 shader 新链序暂时不一致（预览/成片一致性以 shader 为准）；下轮同步 Python 参考实现
