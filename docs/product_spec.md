# 产品规格 — Product Spec

## 1. 产品定位

**PhotographerCamera** 是一款 Android 相机应用，核心价值是用 GPU 实时渲染把"某位摄影师的成片风格"变成用户拍摄时可直接套用的 Camera Profile。它与普通滤镜 App 的本质区别：

- 普通滤镜 = 一张 LUT / 一套叠加层；
- PhotographerCamera = 一组**参数化成像层**（曝光、白平衡、色彩矩阵、HSL、色调曲线、高光滚降、阴影响应、镜头、颗粒、噪点、光晕、泛光、暗角、锐化），共同定义一个摄影师。

## 2. 目标用户与场景

- 高客单本地约拍 / 写真摄影师：把自身风格沉淀为一个可分发、可版本化的 Profile。
- 普通用户：打开 App 选择某位摄影师的 Camera，实时看到"像他拍的"预览并直接出片。

## 3. 核心功能

1. **离线 Profile 构建（PC）**：导入摄影师成片 → 分析 → AI 生成初始 Profile → 优化器拟合 → 验证 → 导出 JSON Profile。
2. **Profile 管理**：导入 / 导出 / 版本 / 启用停用 / 元数据（作者、描述、验证状态）。
3. **Android 实时相机**：Camera2 取帧 → GPU 多 Pass 渲染 → 实时预览 → 高分辨率拍摄 → JPEG/HEIF 导出。
4. **桌面查看器（PC）**：Original / Simulated / Before-After / Split / 100% / 直方图 / 曲线 / 参数面板，用于校验与人工微调。

## 4. 约束（Constraints）

| 约束 | 影响 |
| --- | --- |
| 无 RAW，仅 JPG/HEIC 成片 | 所有特征从已处理的最终像素 + EXIF 反推，不声称恢复 Sensor 真实曝光 |
| Android 不跑 AI | AI 仅离线；Android 只有传统算法 + Shader |
| 禁止单一 LUT 实现风格 | 必须多成像层参数化 |
| 禁止生成式 AI 重绘用户照片 | 仅做风格模拟，不重建内容 |
| Profile 可独立导入/导出/版本化 | 需 Schema + 版本号 + 校验 |
| 新增摄影师不改渲染代码 | 渲染只读 Profile，新增只加 JSON |

## 5. 非目标（第一阶段）

社交、云同步、账号、商城、App 内 AI、完整 Lightroom 功能、生成式重绘。

## 6. 验收标准（Definition of Done）

- Profile Builder：100–1000+ 张成片导入、EXIF 自动分析、清洗、多维统计、初始 Profile 生成、优化拟合、独立 Test Set 验证、JSON 导出。
- Desktop Viewer：任意图片/Profile 加载、实时应用、Before-After、直方图、调参保存。
- Android：Camera2/CameraX、实时 Preview、加载/切换 Profile、GPU Shader 渲染、高分辨率拍摄、JPEG/HEIF 导出、无 AI Runtime。
- 质量：Test Set 上风格稳定、非单一 LUT、Preview 与成片一致、加摄影师只加 Profile。

## 7. 性能基线（目标）

- 1080p Preview ≥ 30 FPS（目标设备），高端设备尽量 60 FPS；
- 切换 Profile 无明显卡顿；
- 拍照输出与 Preview 高度一致。
