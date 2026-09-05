# 渲染管线 — Rendering Pipeline

## 1. 最终成像管线（Final Profile Pipeline）

顺序即权威，Preview 与 Capture 共用同一核心逻辑：

```
Camera Frame
  → Exposure
  → White Balance
  → Color Matrix
  → Tone Curve
  → Highlight Roll-off
  → Shadow Response
  → HSL Mapping
  → Sharpen
  → Bloom
  → Halation
  → Grain
  → Noise
  → Vignette
  → Display / Export
```

## 2. Android GPU 渲染方案

- **技术**：OpenGL ES 3.0 + GLSL；Framebuffer Object（FBO）、Texture、Uniform / UBO。
- **Pass 规划**：
  - 主链 Pass 可合并（Exposure + WB + Color Matrix + Tone Curve + HSL 在一个片段着色器中完成，降低带宽）。
  - Highlight Roll-off、Shadow Response 可并入主链或独立 Pass。
  - Bloom / Halation 需**低分辨率中间 Buffer**（Downsample → Blur → Upsample → Blend）。
  - Grain / Noise 可在单一 Pass 用噪声函数生成（或预生成噪声纹理）。
  - Vignette 作为低成本乘算并入最终 Pass 或独立。
- **纹理管理**：Curve（Tone Curve / HSL）以 1D/2D LUT 纹理上传，避免逐像素分支。
- **性能**：目标 1080p@30FPS，高端 60FPS；尽量合并 Pass、减少 CPU↔GPU 拷贝、控制 FBO 数量。

## 3. Preview 与 Capture 一致性

- 同一套成像层参数与同一套 Shader 代码；Capture 仅用更高分辨率输入、在 Export 前以相同顺序再过一遍核心链。
- 高分辨率下的 Grain/Noise/Bloom/Halation 需按分辨率做尺度归一，避免放大后质感失真。

## 4. 参数注入契约

- 每个 Shader 从 Profile JSON 取参，通过 Uniform / UBO 注入；**禁止**把某摄影师数值写死在 GLSL。
- `android/` 侧 Loader 负责 `Profile → GPU Uniform/Texture`，含 Schema 校验、版本兼容、范围校验、缓存、切换、Import/Export。

## 5. 独立 Shader 模块（Phase 25）

`shaders/` 下每个成像层一个文件，独立实现、独立测试、参数从 Profile 注入：

`exposure.glsl` `white_balance.glsl` `color_matrix.glsl` `tone_curve.glsl` `highlight.glsl` `shadow.glsl` `hsl.glsl` `sharpen.glsl` `bloom.glsl` `halation.glsl` `grain.glsl` `noise.glsl` `vignette.glsl`

每个 Shader 需有：单元测试（参数边界）+ 视觉回归测试（固定输入图对比）。

## 6. 性能验收

- 目标设备 1080p Preview ≥ 30 FPS；高端尽量 60 FPS；
- 切换 Profile 无明显卡顿；
- 拍照输出与 Preview 高度一致。
