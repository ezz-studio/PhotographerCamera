# Phase C 报告 · 中间量化 / 色彩空间 / F16 数据丢失修复

- 基线：main = `c4523dc`（1.7.6）+ 本阶段改动（未提交，见 §6）
- 准入依据：`docs/PHASE_B_report.md`（测试门禁 17/17 + static_cross_check ALL PASS 已建立为回归基线）
- 需求来源：成像管线治理方案第三节（RGBA_F16 高精度处理链）+ Phase B 未完成项（需求 2.7 元数据一环）

---

## 1. 修复清单（C-1 ~ C-5）

### C-1 / PA-01（P0）LensStage 8-bit 量化瓶颈 — 已修复 ✅

**问题证据**（Phase A 04 报告 F3/I1，`_audit_phase_a/04_format_precision.md`）：
`applyLensStage` 位于 16F 主链**最前端**（Stage 0，先于一切 LUT/recipe 层），却用
`glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 读回 + `Bitmap(ARGB_8888)` 出口
（旧 `LIP.kt:658/664`）；LensStageGl 全部 8 个 RT 为 `GL_RGBA8`
（旧 `LSG.kt:198/265`）。触发条件 = profile lens 参数非零 → 整条 16F 链降级 8-bit。

**实现**（三处，预览链行为零变化）：

1. `core/photon/lens/LensStageGl.kt`：
   - `Rt` / `makeRt` 增加 `half` 模式：`GL_RGBA16F + GL_HALF_FLOAT` vs `GL_RGBA8 + GL_UNSIGNED_BYTE`；
   - `process()` 新增 `halfFloat: Boolean = false` 参数（默认 false = 预览链原行为）；
   - 新增 `rtHalf` 实例状态：模式切换时 `releaseRts()` 整体重建 RT（program 保留）；
   - `release()` 拆出 `releaseRts()`，重置 `rtHalf`。
   - shader 无需改动（全部 `precision highp float` + vec4 输出，与 RT 格式解耦）。
2. `photon/lut/LutImageProcessor.kt` `applyLensStage`：
   - `want16F = input.config == RGBA_F16`；
   - 上传：F16 走 `DirectBufferAllocator` + `glTexImage2D(GL_RGBA16F, HALF_FLOAT)` 直传
     （与 `uploadImageTexture` 同策略），分配失败降级 8-bit 并告警；否则原 GLUtils 路径；
   - `process(..., halfFloat = want16F)`；
   - 读回：F16 走 `glReadPixels(GL_RGBA, HALF_FLOAT)` + `createBitmap(w, h, RGBA_F16, colorSpace = input.colorSpace ?: SRGB)`；
     OOM 记 `PLog.e` 并 passthrough；8-bit 路径逐字节保持原逻辑；
   - catch 补 `PLog.w`（含 want16F，需求 3.8）。
3. 预览 `LutRenderer.kt:1498` 调用不传参 → `halfFloat=false`，预览 8-bit 设计链不变。

**附带修复**：LensStage 出口丢失 ColorSpace tag 的契约缺口（04 报告③风险 1）——
出口 Bitmap 现以 `input.colorSpace` 创建。

**量化点变化**：04 报告 Q1/Q2（P0 高危）消除；中间 8-bit 量化瓶颈计数
3 → 2（余 P1 后处理层 Q3、P2 降级路径 Q4）。

### C-3 / 需求 2.7 MediaMetadata 记录 Profile 标识 — 已完成 ✅

**问题证据**：Phase B 报告未完成项第 1 条——快门快照未进元数据，无法追溯成片实际使用的 Profile 参数代。

**实现**（五处）：
- `MediaMetadata.kt`：新增 `profileId: String?` / `profileVersion: Long?`（默认 null）；
  `fromLegacyJson` 补解析（旧 JSON 缺失字段 → null，兼容不破）。
- `CameraViewModel.buildPhotoMetadata`：从 `CaptureProfileStore.current` 填值
  （快门入口已建快照，buildPhotoMetadata 在同流程内 → 有值；截屏等无快照路径记 null）。
- `GalleryMediaEntity.kt`：新增两列（可空）。
- `GalleryMediaStore.kt`：`toEntity` / `toMetadata` 双向映射。
- `GalleryDatabase.kt`：version 39→40 + `MIGRATION_39_40`
  （`ALTER TABLE gallery_media ADD COLUMN profileId TEXT` / `profileVersion INTEGER`，可空列，旧行 NULL）+ 注册。

### C-5 / 需求 3.8 异常回退可观测性 — 已完成 ✅

针对 04 报告④表静默项（不改回退行为，只补记录）：
- `applyLut` initialize 失败（两处同构）：补 `PLog.w("...profile NOT applied")`；
- `initShaderProgram()`：Unit → Boolean，补 `GL_LINK_STATUS` 检查与 `PLog.e`
  （修复 04 报告 F10「返回值未检查 → program 0 绘制静默劣化」）；
- compute 不支持跳过硬核降噪：补 `PLog.w` 说明原因；
- LensStage catch / F16 上传分配失败 / F16 OOM：均补 PLog（见 C-1）。
- 主 FBO 不完整已有 `PLog.e`（1484 行），满足要求未改。

### C-2 / 数据契约成文 — 已完成 ✅

新建 `docs/IMAGE_DATA_CONTRACT.md`：唯一量化点原则、四条链路契约表
（RAW 16F 主链 / YUV 8-bit 链 / 预览链 / 周边链，逐阶段格式+色彩空间+证据行）、
色彩空间与传递函数约定、Alpha 语义、方向约定、量化规则表、异常回退契约表、
三项待真机验证（V1~V3）。**后续改动 §1 表格内任何格式/色彩空间/方向的提交必须同步更新该文档。**

---

## 2. 修改文件清单

| 文件 | 改动 |
|---|---|
| `android/.../core/photon/lens/LensStageGl.kt` | Rt/makeRt half 模式、process(halfFloat)、releaseRts、rtHalf |
| `android/.../photon/lut/LutImageProcessor.kt` | applyLensStage 双模（16F/8-bit）+ colorSpace；initShaderProgram→Boolean+链接检查；applyLut/compute 回退日志 |
| `android/.../photon/gallery/MediaMetadata.kt` | profileId/profileVersion 字段 + JSON 解析 |
| `android/.../photon/viewmodel/CameraViewModel.kt` | buildPhotoMetadata 填快照标识 |
| `android/.../photon/gallery/db/GalleryMediaEntity.kt` | 两列（可空） |
| `android/.../photon/gallery/db/GalleryMediaStore.kt` | 双向映射 |
| `android/.../photon/gallery/db/GalleryDatabase.kt` | version 40 + MIGRATION_39_40 |
| `docs/IMAGE_DATA_CONTRACT.md` | 新建（数据契约 v1.0） |

未改动（受保护）：c4523dc JPEG 降噪接线、成片保存直传 Bitmap 规避、预览链 8-bit 设计、
38→39 无显式迁移的上游历史遗留（不在本阶段范围）。

---

## 3. 测试命令与真实结果

| 验证 | 命令 | 结果 |
|---|---|---|
| Kotlin 编译 | `gradle compileDebugKotlin` | **BUILD SUCCESSFUL**（2m34s；仅 2 处无关弃用警告。Room schema 变更经 KSP 编译验证） |
| uniform 门禁 | `python tools/static_cross_check.py` | **ALL PASS**（59 活链文件 / 134 uniforms looked up / 617 declared / 0 误报） |
| Python 测试 | `pytest tests/ -q` | **17 passed in 11.19s** |

> 编译过程记录：首次失败 1 处（`createBitmap(colorSpace = input.colorSpace)` 可空类型不匹配），
> 补 `?: ColorSpace.get(SRGB)` 后通过。无 GLSL 改动，未跑 check_glsl_symbols。

---

## 4. 未完成事项（如实）

1. **真机验证未做**（无设备在手）：V1 非零 lens profile + RAW 成片出口为 F16；
   V2 16F RT + HALF_FLOAT 读回在 Mali/Adreno 组合合法性回归；V3 Room 39→40 迁移后旧相册可开。
   装包后按 `docs/IMAGE_DATA_CONTRACT.md` §7 执行。
2. **P1 后处理层量化（Q3）未修**：clarity/sharpen/bloom/grain 的 8-bit 出口导致中间层
   实为 8-bit（LIP.kt:1224-1227 注释承认）——排期 Phase E（风格效果阶段），
   与本阶段「数据契约成文 + 链头修复」目标分离，避免多因素叠加。
3. **版本号未升**：本阶段为管线治理改动（未发布 APK），版本仍 1.7.6/76；
   按项目惯例应在打包发布轮次统一升 minor（1.8.0/80）。
4. **DB 38→39 历史遗留**：`version=39` 无对应 MIGRATION_38_39（上游遗留，早于本仓 Phase A 审计基线），
   本次新增 39→40 不受影响；建议后续单独核实（若真机无崩溃则可能是 rebuild 路径处理）。

## 5. Phase D 准入条件

1. 本阶段三项回归（编译 / static_cross_check / pytest）保持绿 → **已满足**；
2. PA-01 修复需真机验收图：非零 lens profile 固定场景实拍对比（Phase D 4.5 一并采集）；
3. 数据契约文档已作为 Phase D「三种模式固定场景对比」的格式记录模板。

## 6. 提交状态

本阶段改动**尚未 git commit**（等工作树审查后按发布流程提交；按项目惯例一次一个 commit、
先提交再跑 `_r2put` 取 notes）。
