# shaders/

GLSL Shader 模块，每个成像层一个文件（Phase 25）。

- `exposure.glsl` `white_balance.glsl` `color_matrix.glsl` `tone_curve.glsl`
- `highlight.glsl` `shadow.glsl` `hsl.glsl` `sharpen.glsl`
- `bloom.glsl` `halation.glsl` `grain.glsl` `noise.glsl` `vignette.glsl`

每个 Shader：
1. 顶部注释写明层名、输入、输出、参数范围；
2. 参数全部经 uniform/ubo 从 Profile 注入，**禁止硬编码摄影师数值**；
3. 需有单元测试（参数边界）+ 视觉回归测试。

技术：OpenGL ES 3.0 + GLSL；移动端默认 `mediump`，关键累加用 `highp`。
