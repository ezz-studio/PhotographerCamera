# Phase D 真机验证协议 · PLG110 / PhotographerCamera 1.8.0

- 版本：**1.8.0 / versionCode 80**（本地 commit `f8456ba` 系列，含 Phase A/B/C 全部改动）
- 安装：<https://app.tybtool.top/PhotographerCamera-1.8.0.apk?v=80>（覆盖安装，同签名；Room 39→40 迁移自动执行，旧相册数据保留）
- 设备：OPPO PLG110 / Android 16（SDK 36）/ ARM Mali-G1-Ultra MC12（OpenGL ES 3.2）
- 日志：远程日志服务器 <http://106.53.7.242:18888/>（session = `PLG110`；App 打开即上报）
- 代码状态：GitHub 远端保持 `c4523dc`（1.7.6）**未推**——本协议全部通过后再推（用户指令）

---

## 1. 固定场景搭建（一次搭好，全部模式复用）

| 要求 | 说明 |
|---|---|
| 机位 | 三脚架固定，锁定构图，全程不移动 |
| 光照 | 稳定（室内常亮灯或阴天室外）；避免直射阳光/移动光源 |
| 画面要素 | ① 平滑渐变（白墙/天空）→ 验 8-bit 量化条带；② 高对比直边（窗框/建筑线条）→ 验锐化/色差；③ 暗部纹理（阴影中的细节）→ 验暗部与降噪；④ 小面积高光（灯/反光点）→ 验高光保持；⑤ 彩色物体（肤色/红衣/绿植）→ 验色彩 |
| 距离 | 主体 1.5–3m，填满画面 2/3；**禁用闪光灯** |
| 记录 | 每轮开拍前后记下时间戳（精确到分），用于日志切片 |

## 2. 测试矩阵

UI 映射：RAW 开关关 + JPEG MAX 关 = **普通 JPEG**；RAW 开关关 + JPEG MAX 开 = **JPEG MAX**；RAW 开关开 = **RAW MAX**。
「降噪设置」= 设置页 RAW MAX 区的 `rawMaxQualityTuning` 开关与亮度/色度降噪滑杆。

| # | 模式 | Profile | 降噪设置 | 目的 | 张数 |
|---|---|---|---|---|---|
| M1 | 普通 JPEG | native | 默认 | 基线 | 2（一横一竖） |
| M2 | 普通 JPEG | CINEMA 80 | 默认 | 非零 lens（CA=0.08）观感基线 | 2 |
| M3 | JPEG MAX | native | 默认 | JPEG MAX 基线 | 2 |
| M4 | JPEG MAX | CINEMA 80 | QualityTuning 开 + 滑杆 0.5 | 1.7.6 JPEG 软件降噪接线回归 | 2 |
| M5 | RAW MAX | native | 默认 | RAW 基线 | 2 |
| M6 | RAW MAX | CINEMA 80 | 默认 | **PA-01 关键样本**（16F lens 链） | 2 |
| M7 | RAW MAX | CINEMA 80 | QualityTuning 开 + 滑杆 0.5 | RAW 降噪滑杆回归 | 2 |
| M8 | 连拍 3 张 | CINEMA 80 | 默认 | 快门快照闭环 + 多帧融合元数据 | 3 |

> CINEMA 80 是内置胶片 profile 中 lens.chromatic_aberration 最强的一档（0.08），
> 可稳定触发非零 lens 路径；native 为全零基线。

## 3. 通过标准

### D-1 三输出路径画质对比（需求 4.5）
- [ ] 三条路径均正常出片，无黑屏 / 花屏 / 崩溃 / 卡死
- [ ] JPEG MAX 细节与体积 ≥ 普通 JPEG；RAW MAX 最优（记录每张文件大小）
- [ ] M1 vs M2：CINEMA 80 胶片风格可见，无异常色偏/断层

### D-2 PA-01（P0）LensStage 16F 主链修复（需求 3.5 + V1/V2）
- [ ] M6 日志中 `applyLensStage` 走 F16 路径；**无** `8-bit fallback` / `want16F=false` 告警
- [ ] M6 平滑渐变（天空/白墙）无可见 8-bit 条带；与 M2 对比渐变更平滑
- [ ] 全程无 GL / EGL 错误日志（Mali-G1-Ultra 上 `GL_RGBA16F` + `GL_HALF_FLOAT` 组合回归）

### D-3 元数据追溯（需求 2.7 + Phase B 快照 + V3）
- [ ] M2/M4/M6/M8 成片可追溯到 profileId（CINEMA 80 标识）与 profileVersion（非空）
- [ ] M8 连拍 3 张参数一致（快门快照闭环，处理中途不串味）
- [ ] 旧相册（1.7.6 及更早拍摄）正常打开；抽查旧照片 profileId = NULL（39→40 迁移生效）

### D-4 多帧融合元数据（需求 4.7）
- [ ] M8 成片尺寸 = 全尺寸；竖拍方向正确；无旋转 / 裁剪错位

### D-5 Ultra HDR gain map 与方向元数据（需求 10.5）
- [ ] JPEG MAX / RAW MAX 的 Ultra HDR 照片在系统相册提升亮度时高光正常展开
- [ ] 四条输出路径方向元数据一致（竖拍不横置）

### D-6 降级路径可观测性（需求 3.8，观察项）
- [ ] 日志无 `link failed` / `minimalFallback` / `profile NOT applied` → 未触发降级（可接受）
- [ ] 若触发：记录现场照片 + 日志时间戳

### 回归项（防 1.7.x 已修问题复发）
- [ ] M4 vs M3：JPEG MAX + 降噪滑杆 0.5 → 暗部/纯净区噪点可见减少（1.7.6 接线仍在）
- [ ] M7：RAW MAX 降噪滑杆生效
- [ ] 预览链：切换 profile 时取景无黑屏 / 无闪烁（1.7.4/1.7.5 冻结项回归）
- [ ] 快门优先：按快门到出片耗时无异常变长（prewarm 取消逻辑回归）

## 4. 日志取证

- Web UI：<http://106.53.7.242:18888/>；或 `GET /api/logs?session=PLG110&tail=5000`
- 每轮测试前后记录时间戳；测毕导出全文存档
- 关键检索词：`applyLensStage`、`want16F`、`fallback`、`profileId`、`CaptureProfileStore`、`link failed`、`minimalFallback`

## 5. 证据归档

1. 手机 USB 连接，成片拷贝到 `PhotographerCamera/_phase_d_evidence/2026-10-15/`
2. 命名规则：`M1a_横.jpg` / `M1b_竖.jpg` / … / `M8c.jpg`
3. 日志导出存同目录 `logs.txt`
4. 回填 §6 结果表 → 交我出 Phase D 报告与后续 Phase E 排期

## 6. 结果汇总表（真机回填）

| 项 | 结果 | 证据文件 | 备注 |
|---|---|---|---|
| D-1 三路径画质 | ☐ | | |
| D-2 PA-01 16F 链 | ☐ | | |
| D-3 元数据追溯 | ☐ | | |
| D-4 融合元数据 | ☐ | | |
| D-5 Ultra HDR | ☐ | | |
| D-6 降级可观测 | ☐ | | |
| 回归：JPEG 降噪 | ☐ | | |
| 回归：RAW 滑杆 | ☐ | | |
| 回归：预览无黑屏 | ☐ | | |
| 回归：快门优先 | ☐ | | |

## 7. 异常处理

- 任一 P0 项（D-2）失败：**暂停后续测试**，保留现场照片与日志时间戳，回传定位
- 安装失败 / 崩溃：远程日志自动捕获，附上发生时间即可
- Room 迁移异常（旧相册打不开）：立即回传——这属于 V3 失败，需热修

---

*协议依据：`docs/PHASE_C_report.md` §5、`docs/AUDIT_060_architecture_compliance.md` §4、`docs/IMAGE_DATA_CONTRACT.md` §7。*
