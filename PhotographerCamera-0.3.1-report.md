# PhotographerCamera v0.3.1 修复报告

**日期**: 2026-09-06
**版本**: 0.3.1 / versionCode 9（纯修复轮，patch+1）
**APK**: `PhotographerCamera/PhotographerCamera-0.3.1.apk`（22.7MB）
**编译**: BUILD SUCCESSFUL（两轮：首轮四问题修复 43s；二轮 UI 顺序修正 27s）

---

## 一、四问题修复映射

### 问题 1：开 RAW 开关后成片纯灰（预览正常）
- **现象**: RAW ISP 模式拍摄输出纯灰/纯色图
- **根因**: `ProfileRenderer.renderRawChain / renderYuvChain` 漏设 passthrough.vert 的 `u_uvWin` uniform。GLSL uniform 默认值为 `(0,0,0,0)` → `v_uv = u_uvWin.xy * baseUv + u_uvWin.zw` 恒为 `(0,0)` → 整帧采样**同一个像素点** → 纯色图。该坑在 `runCopyPass`（711-716 行注释）已踩过一次，新函数重蹈覆辙。
- **修复**: 两处 drawQuad 前显式恒等重置：
  ```kotlin
  GLES30.glUniform4f(GLES30.glGetUniformLocation(prog, "u_uvWin"), 1f, 1f, 0f, 0f)
  ```
- **附带**: 新增分段诊断日志 `probeCenterStr`（FBO 中心 1px）+ `bmpAvgStr`（4×4 缩样均值），区分"转换段输出灰"vs"effect 段输出灰"，下轮同类问题一击定位。

### 问题 2：UI 未按目标图执行（含挪动上下/左右/顺序）
- **现象**: 快捷行多出 RAW 按钮；缩放条与快捷行上下顺序颠倒
- **根因**: ① RAW 快捷按钮是历史遗留，目标图快捷行固定 5 项；② BottomPanel 内 ZoomRotor 与 QuickControls 顺序写反。
- **修复**（严格逐项比对目标图）:
  - 顶栏 4 图标：太阳EV / 闪光 / 对焦 / 设置 ✓
  - 快捷行 5 项：WB / Grain / 焦距mm / 计时 / 翻转（**RAW 已移除**）
  - **BottomPanel 顺序改为：缩放条（中央 pill 读数）→ 快捷行 → 快门行**（与图一致，原顺序相反）
  - 快门行：profile 卡 / 大白圆快门 / 缩略图 ✓
- **RAW 开关新入口**: 移至设置页（`SettingsSheet`），仅 rawCapable 设备显示，副文案说明开启/关闭行为。

### 问题 3：冷启动滤镜不生效
- **诊断结论**: 非 UI bug、非持久化 bug，是**旧版 APK 残留脏数据** + 本轮引擎接线 bug 双重因素。
  - 日志证据：05:25:29 冷启动 `restored last preset 'native'`（用户上次实际用 CINEMA 80）——旧版从未写入 `sp.last_preset`，native 全零参数 = 直通观感。
  - 新版写入链正确：LaunchedEffect(selected, previewRef, profiles.size) 每次选择即写 sp（05:26:36 切 ACROS 已正确写入）。
  - **自愈**: 用新版正常拍摄/切换一次滤镜后，冷启动恢复即正确。
- **附带定性"冷启动 RAW 默认"**: UI 与引擎读同一 sp 键（`raw_isp_enabled`，默认 true）→ 持久化一致；真正的问题是引擎 else 分支接线（见问题 6/下文 YUV），已修。

### 问题 4：手动对焦旁边的小太阳位置不对（动态出现在右下角）
- **根因**: ring 坐标是**相对预览框（fw×fh）的归一化值**（onTap 的 size 就是预览框），EV 容器也嵌在预览 Box 内；但 baseCx/baseCy 用的是 maxW/maxH（全屏）→ 垂直偏差 `ny*(maxH-fh)` → 太阳被推到右下角。
- **修复**:
  ```kotlin
  val baseCx = ring.first * fw
  val baseCy = ring.second * fh
  ```
  太阳现在与对焦点严格水平。

---

## 二、YUV 直采主通道接线（用户质疑"非 RAW 模式还是旧管线"——完全正确）

- **根因**: 关 RAW 开关 → else 分支 `rawMode = true`（旧 RAW+DNG 档案管线）→ 437 行 ImageAnalysis 仅在 `!rawMode` 建立 → **永不创建**；shoot() 优先级 `rawMode → shootRawBundle()` 排在 YUV 分支之前 → **YUV 分支是死代码**。用户关了 RAW 也永远走不到 YUV。
- **修复**（CameraEngine else 分支重写）:
  ```kotlin
  } else {
      // RAW ISP 关（UI 开关 off）→ YUV 直采主通道（禁 JPEG 路线）
      // 旧 RAW+DNG 档案模式降级为后门旗标 files/pc_raw_bundle.txt
      rawMode = File(appContext.filesDir, "pc_raw_bundle.txt").exists()
      ...
  }
  ```
- **三态语义（修复后）**:
  | RAW 开关 | 行为 |
  |---|---|
  | 开 | rawIspMode + rawMode（RAW ISP GPU 直出，开发中） |
  | 关 | rawMode=false → **YUV 直采主通道**（无 JPEG 压缩） |
  | 后门旗标 | files/pc_raw_bundle.txt 存在 → 旧 RAW+DNG 双输出（档案模式） |
- rawCapable=false 的设备（如当前测试机无 RAW 能力上报时）自动落 "JPEG only" 分支 → rawMode=false → 走 YUV ✓

---

## 三、真机验证指引（v0.3.1）

### A. RAW ISP 纯灰是否修好
开 RAW 开关 → 拍 1-2 张：
- 成片应有真实图像（不再是纯灰/纯色）
- 日志关键字：`RAW isp pass center=`（应为真实像素值，不再是单色）→ `RAW raw chain output avg=`

### B. YUV 直采链首次真机验证（重点）
关 RAW 开关 → 拍 1-2 张：
- 日志关键字（首次验证，此前从未走到）：
  - `YUV direct capture ENABLED (no-JPEG main channel; RAW ISP off)`
  - `YUV analysis alive`
  - `yuv direct frame` / `direct chain done (3-plane BT.601)`
  - `YUV yuv pass center=` / `YUV yuv chain output avg=`
- 成片应有真实图像（u_uvWin 同源 bug 已同步修复，YUV 链受益）

### C. UI 比对
- 快捷行严格 5 项（无 RAW）
- **缩放条在快捷行上方**（中央有 pill 读数）
- RAW 开关在设置页内
- 对焦后小太阳与对焦点水平，不再跑右下角

### D. 冷启动滤镜
用新版正常使用一次后杀进程重启 → 应恢复上次滤镜（上次残留 native 的脏数据自愈）。

---

## 四、已知限制
- RAW ISP 仍为 GPU 开发路径，色彩科学持续迭代中
- HardwareBuffer 零拷贝（任务 #37）未启动，YUV 当前走 ImageAnalysis + CPU 中转
- 旧 RAW+DNG 档案模式需手动放后门旗标文件才会启用
