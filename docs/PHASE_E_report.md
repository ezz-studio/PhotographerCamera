# Phase E 验收报告 — 成像管线精度与一致性收尾

- **版本**：1.8.0（APP_VERSION_CODE = 80）
- **仓库**：`PhotographerCamera/`（applicationId `com.PhoGraCamera`）
- **目标设备**：OPPO PLG110 / Android 16 / ARM Mali-G1-Ultra MC12（OpenGL ES 3.2）
- **改动规模**：11 个文件，+671 / −106 行
- **结论**：Phase E 四项任务（E-1～E-4）代码改造全部落地，本地验证门禁全绿；真机验证（PLG110）与 R2 发布 / GitHub 推送按用户纪律**在用户明确"推"之前不执行**。

---

## 1. 范围与任务清单

| 任务 | 内容 | 对应缺陷 | 状态 |
|------|------|----------|------|
| E-1 | 后处理层 F16 化 | PA-02（后处理 RGBA8 量化瓶颈） | ✅ 已完成 |
| E-2 | 死字段接线（halation.radius/threshold/warmth、shadow.saturation） | 死字段 / PA-04 | ✅ 已完成 |
| E-3 | 录像路径 halation 一致性（film_curve/color_matrix 已存在，补齐 halation） | PA-08 | ✅ 已完成 |
| E-4 | 验证门禁 + 本报告 + 本地提交 | — | ✅ 已完成（本地 commit，未 push） |

---

## 2. E-1 后处理层 F16 化（PA-02）

**目标**：消除成片后处理链中途的 RGBA8 量化瓶颈，使 RAW/夜景长链路在 F16 上贯通，避免 banding 与高光细节丢失。

**改动要点**：

- `Shaders.kt` — `BEVY_BLOOM_COMPOSITE` 新增 `uSourceTexture` / `uAddSource`：
  - `uAddSource > 0.5` 时 `fragColor = vec4(clamp(source.rgb + bloom, 0.0, 1.0), 1.0)`，成片 16F 链**单次合成源图+bloom，免 GL 叠加混合**；
  - `HDF_PREVIEW_COMPOSITE` 新增 `uHalationWarmth` 暖色权重 `vec3(uHalationWarmth, 0.4, 1.0/max(1e-4, uHalationWarmth))`。
- `FilmGrainGl.kt` — 颗粒输出 FBO 受 `use16F` 门控改 RGBA16F（`ensureOutput` 16F 分支）；颗粒参数仍读 `FilmParamsStore.current`（单例竞态为已知待办，见 §6）。
- `LutImageProcessor.kt` — clarity 输出 / lutSharpen 输出 / bloom 输出三处 FBO 全部加 `use16F` 门控改 RGBA16F；
  - `renderLdrBloom` 重构为**免混合**双分支：16F 链 `uAddSource=1` 无 blend；8-bit 链保留「预绘源图 + GL 叠加混合」原行为（`uAddSource=0`）；
  - `readMain16FAs8Bit` 泛化支持任意 FBO 源；`performRender` 后处理链全程受 `postProcess16F` 门控。
- `LutRenderer.kt` — composite 显式 `uAddSource=0`，防止 program 跨调用 uniform 残留。

**回退安全**：`use16F` 默认门控保证旧 8-bit 路径行为不变；两条分支都显式设置 `uAddSource`。

---

## 3. E-2 死字段接线

**目标**：将 profile 中已定义但此前未被消费的字段真正接入渲染管线，使母版/用户档位的语义生效。

### 3.1 阴影饱和度（shadow.saturation）
- `ShadowsHighlightsShader.kt`：`bindUniforms` / `bindUniformLocations` 增加 `uShadowSaturationDeltaLocation` / `shadowSaturationDelta`；GLSL `applyShadowsHighlights` 增加 `uniform float uShadowSaturationDelta`，在早退分支前做全局饱和度缩放 `color = mix(vec3(luma), color, 1.0 + uShadowSaturationDelta)`。
- 4 个宿主声明并传入 `uShadowSaturationDelta`：`LutImageProcessor.kt`、`PreviewColorShader.kt`、`RawAdjustmentPass.kt`、`RawEngineTonePass.kt`。
- `ColorPassProgram.kt`：`ColorPassLocations` data class 增加 `uShadowSaturationDeltaLocation` 字段 + `queryLocations` 绑定。
- 调用处传值：`shadowSaturationDelta = FilmParamsStore.current.shadowSaturation - 1f`（`LutRenderer.kt:917`、`RealtimeVideoRenderer.kt:574`）。

### 3.2 Halation 死字段（radius / threshold / warmth）
- `LutImageProcessor.renderHdfBlur` / `renderHdfBlur`：阈值消费 profile 值、radius 经 texel 缩放，不再使用写死常量。
- `LutRenderer.renderHdfPreviewBlur` 同步；
- HDF 合成 `uHalationWarmth` 暖色注入（GLSL `warmthTint`）；
- `uHalationWarmth` 在 `LutImageProcessor.performRender` 绑定（line 1229 / 4020 / 4358）。

---

## 4. E-3 录像路径 halation 一致性（PA-08）

**诊断结论**：录像端（`RealtimeVideoRenderer`）与预览端共用 `PreviewColorShader`，且**已绑定** `uPrimaryCalibrationMatrix`、`uCurveTexture`/`uCurveEnabled`（film_curve 与 color_matrix 部分**此前已解决**）。**真缺口仅剩 halation**：录像端无 HDF 模糊 pass、无空间效果 uniform 绑定。

**设计决策（零成本、零副作用）**：
- 复用预览端共享 shader 的死 `includeSpatialRecipeEffects` 块，在录像端 `forPass` 仅当 `hdfEnabled` 时开启，内联 HDF 光晕 + 红晕（mirror `HDF_PREVIEW_COMPOSITE`），与预览**同源同算法**；
- 预览端保持独立 composite pass 不变，避免双重叠加；
- `hdfEnabled` 默认 `false`（母版 halation.amount ≈ 0.0037）→ 不创建任何 FBO、不跑模糊 pass、不绑定空间效果 uniform，**录像路径行为与改动前完全一致（零成本）**；仅当 profile 显式启用 halation 时才生成 HDF/红晕模糊纹理并内联叠加。

**改动要点**：

- `PreviewColorShader.kt` — 扩展 `includeSpatialRecipeEffects` 块 uniform 声明（`uHdfTexture`/`uHalation`/`uHalationWarmth`，保留 `uRedHalationTexture`/`uRedHalation`/`uSoftLightTexture`/`uSoftLight`）；`main()` 内该块加 HDF 光晕内联（mirror `HDF_PREVIEW_COMPOSITE` 的 `warmthTint` + screen 合成 + mist），后接原有 `if(uRedHalation>0.0)` 块。
- `PreviewColorShaderVariant.kt` — `forPass` 增加 `spatialRecipeEffectsEnabled: Boolean = false` 参数，置 `includeSpatialRecipeEffects`。
- `RealtimeVideoRenderer.kt`（+316 行）：
  - 字段：`hdfEnabled` getter（`FilmParamsStore.halationStrength>0.001f || layers.any{ redHalation>0.001f }`）、`spatialSource*`、`hdfTexId[2]/hdfFboId[2]`、`halationTexId[2]/halationFboId[2]`、5 个共享 shader program、`blackTexId`。
  - 方法：`ensureSpatialPrograms()`、`setupSpatialSource()`（OES→2D 拷贝 FBO，RGBA8）、`setupBlurFbos()`（RGBA16F 双 FBO，含尺寸缓存与完整性检查）、`releaseSpatialSource()`、`releaseBlurFbos()`、`renderVideoSpatialBlur()`（双 pass HDF + 双 pass 红晕模糊，阈值/强度/半径与预览一致）、`ensureBlackTexture()`、`releaseSpatialPrograms()`。
  - 门控：`renderLatestFrame` 首 `drawLayer` 前 `if(hdfEnabled){ renderVideoSpatialBlur(...) }`；`drawLayer` 内 `forPass(spatialRecipeEffectsEnabled = hdfEnabled)`；`bindRecipeUniforms` 末尾 `if(hdfEnabled){...}` 绑定 `uHalation`/`uHalationWarmth`/`uHdfTexture`(单元6)/`uRedHalation`/`uRedHalationTexture`(单元7)/`uSoftLight=0`/`uSoftLightTexture`(单元8 绑 blackTexId)。
  - **资源释放**：`release()` 内新增 `releaseSpatialSource()`、`releaseBlurFbos(hdf)`、`releaseBlurFbos(halation)`、`releaseSpatialPrograms()`、`blackTexId` 删除——均为对 0 值安全的 no-op（默认 `hdfEnabled=false` 时这些资源从未创建）。

---

## 5. 验证门禁结果（本地，全绿）

| 门禁 | 命令 | 结果 |
|------|------|------|
| GLSL 符号校验 | `python tools/check_glsl_symbols.py --entry preview` | ✓ 无条件路径上的符号全部已定义 |
| 静态交叉检查 | `python tools/static_cross_check.py` | ✓ ALL STATIC CROSS-CHECKS PASS |
| 单元测试 | `pytest tests/ -q`（venv） | ✓ 17 passed |
| Kotlin 编译 | `gradle compileDebugKotlin -PbuildDir=build2` | ✓ BUILD SUCCESSFUL（EXIT=0，已修 `byteArrayOf` 255 越界 Byte 编译错） |

> 注：Gradle 编译前临时注释了 `~/.gradle/gradle.properties` 中已下线的 7990 代理，编译后已**还原**。

---

## 6. 风险与已知待办

1. **单例竞态（E-1 顺带发现）**：`FilmParamsStore.current` 在成片/预览多线程读取下存在竞态，当前仍直接读 `current`。属独立待办，不在 Phase E 范围，需后续加只读快照/不可变 copy。
2. **真机验证未做**：PLG110 真机尚未按 `docs/PHASE_D_protocol.md` 跑 1.8.0 验证。按用户纪律——**真机验证通过前不推 GitHub**、仅本地打包 + R2 发布。
3. **发布门控**：R2 发布（`tools/_r2put.py`→`_r2upload.py`）与 GitHub push 均需用户明确"推"后再执行。本次仅做本地 commit。

---

## 7. 下一步（待用户指令）

- 用户在 PLG110 真机按 Phase D 协议验证 1.8.0（预览/成片 halation 一致性、后处理 F16 无 banding）→ 回填结果表 → AI 出 Phase D 验收报告。
- 用户确认"推"后：本地 `assembleDebug` 出包 → R2 上传（`https://app.tybtool.top/PhotographerCamera-1.8.0.apk?v=80`）→ git push 到 `ezz-studio/PhotographerCamera`。
