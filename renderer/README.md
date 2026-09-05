# renderer/

GPU 渲染管线（Pass 管理、FBO、Texture 概念与接口）。

- 责任：管理多 Pass 渲染顺序、Framebuffer、将 PhotographerProfile 参数上传到 Shader（Uniform/UBO）。
- 与 `shaders/` 一一对应：每个成像层一个 Shader 文件。
- 约束（来自 AGENTS.md）：Preview 与 Capture 共用同一核心成像逻辑；Profile 参数不进 GLSL。
- Phase 24/33 详细实现；本目录为 Phase 0 占位。
