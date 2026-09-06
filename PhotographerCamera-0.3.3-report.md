# PhotographerCamera v0.3.3 修复报告（实测反馈轮：色彩质量 + YUV 全分辨率）

**日期**: 2026-09-06
**版本**: 0.3.3 / versionCode 11
**APK**: `PhotographerCamera/PhotographerCamera-0.3.3.apk`（md5 daf31d06…）
**编译**: BUILD SUCCESSFUL（37s，二轮修复 setBufferFormat/maxOf 后通过）
**基础**: 包含 0.3.1/0.3.2 全部修复

---

## 0.3.2 真机实测日志结论（session s1788645541277，05:59-06:00）

**验证通过**：
- ✅ 冷启动滤镜恢复：`restored last preset 'ACROS 100'`（0.3.1 修复生效）
- ✅ RAW ISP 链：`isp pass center=39,102,76`（真实场景内容，纯灰修复生效）→ 208ms → saved 319ms
- ✅ YUV 接线：关 RAW → `YUV direct capture ENABLED` + `YUV analysis alive`（0.3.1 修复生效）
- ✅ 无 FATAL/CRASH，rebind/生命周期正常

**发现的问题（本轮全部修复）**：
1. RAW 彩色成片严重绿偏（output avg=64,168,131）
2. 彩噪极高（RAW 路径绕过了 HAL 多帧降噪）
3. 关 RAW 拍摄两次全部失败：`YUV render failed :: newPosition > limit: (1472 > 1440)` → 回退 1.6MP 小图
4. 快捷行+快门行位置偏高（目标图里上移了）
5. 滤镜选择界面高亮与相机实际滤镜不一致（冷启动）

---

## 本轮修复

### 1. RAW 绿偏 → 逐帧 gray-world 白平衡估计
- **根因**: SDK 36 移除 SENSOR_NEUTRAL_COLOR_POINT，CameraX 拿不到 per-shot CaptureResult → `wbGains=(1,1,1)` 中性。白天场景传感器原始数据 G 通道天然过强，中性增益 = 绿偏。v0.3.0 用 ACROS 黑白测试时被黑白化掩盖。
- **修复**: `ProfileRenderer.grayWorldWbGains()` —— 从**本帧** Bayer 数据直接估计 R/B 增益（G 归一，黑位扣除后通道均值比），每帧重算，clamp [0.5, 2.5]。日志 `wb gray-world gains=R…,B…`。
- 采样 350k 点 <10ms（GL 线程可承受）；失败回退 calib 值。

### 2. 彩噪极高 → RAW 链新增色度去噪 pass
- **根因**: RAW 路径完全绕过 HAL ISP，HAL 的多帧降噪/色度降噪全部不参与；Malvar 去马赛克后逐像素彩噪裸露。
- **修复**: 新 shader `chroma_denoise.frag`（5×5 边缘停止的**色度平滑**）：亮度通道 bit-exact 保留（锐度不变），只平滑 chroma（rgb−luma），空间衰减 + 亮度边缘停止权重。链路：ISP pass(mainFbo) → 色度去噪(auxFbo) → effect 链(outFbo)。新增 `progChroma` + `ensureAux`（编译失败自动跳过，不阻塞 RAW 路径）。仅 RAW 链启用（YUV/JPEG 的 HAL 已降噪）。

### 3. YUV 直采崩溃 + 只有 1.6MP → 两个修复
- **3a. 崩溃根因**（`compactPlane` 自身 bug）: 行循环里先 `position(row*1472)` 再 `limit(...)`——第 0 行把 limit 压到 1440，第 1 行 position(1472) 超当前 limit 抛 `IllegalArgumentException`。**修复 = limit 先于 position**（加 fits 检查，缓冲区与上报 stride 不一致时自动取 packed 布局）。
- **3b. 1.6MP 根因**: analysis 流固定 1440x1088，成片分辨率天花板就是 1.6MP。
- **修复**: **ImageCapture 切 YUV 输出**（RAW 关时 `setBufferFormat(ImageFormat.YUV_420_888)`，12.5MP binned 与 JPEG 同分辨率）——CameraX 1.6 移除了 `setOutputImageFormat`，`setBufferFormat` 是替代 API（已反查 1.6.2 jar 确认）。新增 `shootYuvCapture()`：takePicture → 全分辨率 YUV ImageProxy → 既有 GPU 链。闪光 precapture 联动照常（输出仍是 YUV）。
- **回退链**: 全分辨率 YUV 失败 → analysis 流帧（1.6MP）→ JPEG。
- shoot() 优先级：RAW ISP → RAW+DNG → **yuvCaptureOn（全分辨率 YUV，含闪光）** → flash/JPEG → analysis YUV → JPEG。

### 4. 快捷行+快门行上移 24dp
- `BottomPanel` padding bottom 10→34dp；`bottomReserve` 272→248dp（取景框底部下探补回，缩放条与取景框间距不变）。

### 5. 滤镜选择界面高亮不一致
- **根因**: `PresetListScreen` 只从导航 savedStateHandle 读选中态，冷启动该参数不存在 → 回退列表第一项。
- **修复**: 回退链 = 本次会话用户选过的 → `sp.last_preset`（相机端已用它渲染）→ 列表第一项。

---

## 真机验证指引（v0.3.3）

### A. RAW 开（彩色 profile，如 CLASSIC 320）
- 绿偏应消失，日志看 `wb gray-world gains=R…,B…`（白天典型 R≈1.5-2.2, B≈1.1-1.8）
- 彩噪应明显降低（锐度/颗粒感保留）；`output avg` 三通道应接近场景真实色彩
- 文件 ~5.5MB / 12MP 不变

### B. RAW 关（重点：全分辨率 YUV 首验）
- 日志关键字：`yuv capture mode ON (full-res YUV_420_888 stills, no HAL JPEG)` → `yuv capture frame 4096x3072 …` → `YUV direct chain done 3072x4096` → `YUV yuv pass center=` / `yuv chain output avg=`
- **成片应为 12MP 级别**（不再是 1102x1470 小图），不再出现 `newPosition > limit` 崩溃
- 闪光开：应正常闪（YUV 输出 + precapture）
- 若 HAL 不支持全分辨率 YUV → 自动回退链，日志可见 `yuv capture failed - fallback`

### C. UI
- 快捷行+快门行整体上移一档；缩放条仍在快捷行上方、紧贴取景框下方

### D. 滤镜界面
- 冷启动直接打开滤镜选择页：高亮应与相机当前滤镜一致（如 ACROS 100）

### E. 回归确认
- 开 RAW 拍照：不再纯灰、有真实图像
- 冷启动恢复上次滤镜
