# android/

Android Camera Engine + GPU Renderer + Compose UI（Phase 23、26–28、32–33）。

- 语言：Kotlin；相机：Camera2（CameraX 仅高层封装）；UI：Jetpack Compose；GPU：OpenGL ES 3.0 + GLSL。
- Profile 打包进 `assets/profiles/`；Loader 负责 Schema 校验、版本兼容、范围校验、缓存、切换、Import/Export。
- **禁止**依赖 Python / PyTorch / LLM 或其他 AI Runtime（AGENTS.md 规则 12）。
- Vulkan 留作后续性能路线，不作第一版必需项。

本目录为 Phase 0 占位；进入 Phase 23 时再从骨架搭建可运行 Preview。
