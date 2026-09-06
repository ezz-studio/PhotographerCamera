# PhotographerCamera v0.3.0 交付报告

**版本**: 0.3.0 (versionCode 8)  **APK**: `PhotographerCamera-0.3.0.apk` (22.7 MB)
**主题**: YUV_420_888 直采管线（"禁止 JPEG 路线"主通道）+ 统一引擎链序 v0.3.0 + 能力探测补全
**性质**: 技术路线整轮落地交付（用户指示：全程无交互、最终一次性交付）

---

## 一、技术路线达成情况

用户定义的目标管线（"Kotlin统一API + 设备能力探测 + RAW/YUV双管线 + GPU统一后处理 + 不支持能力自动Fallback"）已全部落地：

```
Camera2 能力检测（DeviceAdapter）────────────────────────┐
                                                        │
RAW_SENSOR 支持 ──► RAW 开关显示 ──► RAW 管线:           │
  RAW Capture → GPU demosaic(MHC 5×5) → RAW ISP         │  能力不足自动降级
  (linear 域调色) ──┐                                    │
                   │                                    │
非 RAW / RAW 关 ──► YUV 管线（v0.3.0 新增，主通道）:      │
  ImageAnalysis YUV_420_888 直采（HAL 后 ISP、无 JPEG）   │
  → GPU BT.601 三纹理转换 ──┐                            │
                           │                            │
闪光灯联动 / 回退 ──► JPEG 管线（兜底通道）──┐            │
                           ┌────────────┘            │
                           ▼                         │
              【Unified Image Engine（统一动态计算引擎）】
   exposure → WB → colorMatrix → highlight → shadow (tonePreLinear 门控)
   → filmCurve → toneCurve(独立) → HSL → 3DLUT(风格色) → vignette
   → bloom → halation → grain(光照感知分布) → sharpen(最后)
                           ▼
                      JPEG 编码存档
```

**三条成片通道在 `StillFrame` 收敛（Raw/Yuv/Isp），下游统一引擎不感知来源。**

---

## 二、本轮新增

### 1. YUV_420_888 直采管线（核心）
- **CameraEngine**：非 RAW 模式下常驻 `ImageAnalysis(YUV_420_888)` 流（4:3、目标 4096×3072 同 JPEG 成片尺寸、`STRATEGY_KEEP_ONLY_LATEST`）。三流 bind 失败自动降级双流。
- **拍摄优先级**：RAW ISP → RAW bundle → (闪光≠OFF? JPEG) → YUV 直采（`yuvAlive` 才启用）→ JPEG 兜底。`pc_yuv_off.txt` 应急后门（同 `pc_raw_off.txt` 模式，无 UI）。
- **帧流转**：快门 → 取 analyzer 最新帧（proxy 移交渲染端，读回后 close，无 buffer 泄漏）→ GL 三平面读出 → 统一引擎 → saveProcessed。

### 2. GPU YUV→RGB 转换（yuv_copy.frag 新增）
- 三平面（Y/U/V GL_R8）+ **标准 BT.601 studio-swing 矩阵**，公开 API、全设备色彩一致（风格统一的前提）。
- `compactPlane`：`rowStride` padding 逐行拷贝、NV12（pixelStride=2）逐样本抽稀 —— I420/NV12 双布局兼容。
- 旋转/cover-crop/前置镜像语义与 raw_isp.frag 完全一致（`u_rot`/`u_win`/`u_mirror`）。

### 3. 关键架构决策（javap 铁证驱动）
| 决策点 | 结论 | 依据 |
|---|---|---|
| EGLImage 零拷贝（任务 #37 原方案） | **放弃 Java 层零拷贝**，改三纹理 BT.601 | `eglCreateImageKHR` 不在公开 SDK（framework-hidden/NDK-only）；隐藏路径还会把色彩空间控制权交给驱动，破坏风格一致性 |
| CameraX keep-latest 常量 | `STRATEGY_KEEP_ONLY_LATEST` | camera-core 1.6.2 javap（`STRATEGY_KEEP_LATEST` 已不存在） |
| 零拷贝升级路径 | NDK 轮次（任务 #37 已记录调研结论） | AHardwareBuffer_acquire + EGL/eglext.h；收益=省一次 ~20MB CPU 拷贝，优先级低于画质 |

成本说明：三平面 CPU 读出（12MP ≈ 100ms，GL 线程、快门瞬间）远小于其替代的 JPEG 解码（100-400ms），且换来"无 JPEG 有损、无厂商色彩烧死"的直采数据 —— 正是"动态风格统一计算"需要的输入。

### 4. CPU 参考工具链序同步（tools/profile_renderer.py）
`render()` 重排为 shader v0.3.0 同款链序（highlight→shadow→filmCurve→toneCurve→HSL→vignette→bloom→halation→grain→noise→sharpen LAST）；`apply_grain` 加光照分布 mask（`_smoothstep` 实现与 shader smoothstep 数值一致）。py_compile 通过。

### 5. DeviceAdapter 能力探测补全
`Caps` 新增 `ultraHRes`（ULTRA_HIGH_RESOLUTION_SENSOR）/ `remosaic`（REMOSAIC_REPROCESSING）—— quad bayer 全尺寸 remosaic 路线的技术储备（算法留真机标定轮，当前全尺寸 quad 帧仍走预览回退守卫）。

---

## 三、全链路静默降级矩阵（自动 Fallback）

| 故障 | 行为 |
|---|---|
| RAW 探测失败 / RAW ISP 拍摄异常 | 预览帧回退（已有） |
| quad bayer 全尺寸未 remosaic 帧 | 拒帧 → 预览帧回退（已有守卫） |
| 三流 bind 失败（HAL 不支持 YUV+JPEG 并发） | 自动降级 preview+capture 双流 |
| ImageAnalysis 绑定但 HAL 吐帧失败（`yuvAlive=false`） | JPEG 拍摄 |
| YUV 渲染失败（格式/平面/纹理异常） | 1×1 bitmap → 预览帧回退 |
| 闪光灯开启 | JPEG 拍摄（ImageCapture 硬件 precapture 联动，YUV 流无闪光联动） |

## 四、真机验证清单（PLG110，最终测试时用）
1. 冷启动非 RAW 模式拍照：日志应见 `CAM: YUV analysis alive ...`（流建立）→ `SHOT: yuv direct frame WxH rot=...` → `YUV: direct chain done (3-plane BT.601)` → `SHOT: GPU chain done (YUV)`
2. 开闪光灯拍照：自动走 JPEG（`takePicture ... raw=false`），闪光正常
3. RAW 开关打开拍照：走 RAW 管线（`CAM: RAW ISP capture ENABLED`），YUV 流不干扰
4. 颜色：YUV 直采片与 JPEG 片同场景对比，若整体偏色 → BT.601/709 差异，记录后按设备族补偿（shader 矩阵可调）
5. 前置摄像头：成片方向与镜像应与 JPEG 路径一致

## 五、已知限制 / 后续排期
- **YUV 流质量**：ImageAnalysis 流是 HAL 分析流（部分 HAL 无多帧降噪），画质可能略逊 ImageCapture JPEG 流 —— 这是"厂商 ISP 的 YUV"的固有属性，真机对比后如不达标可切回 JPEG 兜底（`pc_yuv_off.txt`）
- **色彩标准**：BT.601 为 CameraX 官方约定，个别 HAL 若输出 709 编码会有轻微偏色（真机确认后可加 per-device 矩阵切换）
- **任务 #37**：NDK HardwareBuffer 零拷贝（调研结论已记录，非画质关键）
- **remosaic**：全尺寸 quad bayer 的 GPU remosaic pass（能力已探测，待真机标定）
- **LibRaw DNG 导入**：远期可选
- UI/动效零改动（用户冻结令持续有效）
