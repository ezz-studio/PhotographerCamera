# PhotographerCamera v0.3.2 修复报告（UI 位置微调轮）

**日期**: 2026-09-06
**版本**: 0.3.2 / versionCode 10
**APK**: `PhotographerCamera/PhotographerCamera-0.3.2.apk`（md5 d8389113…，22.7MB）
**编译**: BUILD SUCCESSFUL（40s）
**基础**: v0.3.1 四问题修复全部包含在内（u_uvWin / YUV 接线 / EV 太阳 / 快捷行 5 项+缩放条顺序 / RAW 迁设置页）

---

## 本轮修改（对齐目标 UI 图的两处移动）

### 1. TopBar 整体下移（Y 轴）
- **原**: `padding(top = 24.dp)` —— 图标贴顶过高
- **改**: `padding(top = 44.dp)`
- 取景框顶部在 `topReserve(100dp)`；顶栏 44+40=84dp，仍留 16dp 不压框
- 位置: `CameraScreen.kt` TopBar 调用处 modifier

### 2. 预设卡 + 相册缩略图内收，贴近快门（X 轴）
- **原**: 快门行 `padding(horizontal = 14.dp)` + SpaceBetween → 两侧元素顶到屏幕边缘，离快门很远
- **改**: `padding(horizontal = 48.dp)` → 预设卡/缩略图各内收 ~34dp，与快门的水平间距对称缩小
- 预设卡 58dp / 快门 66dp / 缩略图 58dp 尺寸不变
- 位置: `CameraScreen.kt` BottomPanel 快门行 Row modifier

> 数值是对目标图的估计量。若装机后还差一档，改两个常量即可：
> 顶栏 `top = 44.dp`（下移更多→调大）、快门行 `horizontal = 48.dp`（更靠近快门→调大）。

---

## 真机验证清单（0.3.2 全量）
1. **UI 三处位置**: 顶栏下移、预设卡/缩略图靠近快门、快捷行 5 项+缩放条在上
2. **开 RAW** 拍摄: 成片有真实图像（非纯灰），日志 `RAW isp pass center=`
3. **关 RAW** 拍摄: **YUV 直采链首验**，日志 `YUV direct capture ENABLED` / `YUV analysis alive` / `yuv direct frame` / `YUV yuv chain output avg=`
4. **EV 太阳**: 与对焦点严格水平
5. **冷启动**: 切一次滤镜后杀进程重启，恢复上次滤镜
