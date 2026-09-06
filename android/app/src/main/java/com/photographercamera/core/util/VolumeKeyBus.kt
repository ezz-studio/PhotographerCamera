/*
 * VolumeKeyBus — 0.6.0 音量键功能总线。
 *
 * MainActivity onKeyDown 读取设置后经由本总线派发到 CameraScreen：
 *   拍照 → onCapture（走 doCapture，含连拍守卫/计时器语义）
 *   变焦 → onZoomStep(true=上键放大 / false=下键缩小)
 *   无   → MainActivity 直接放行系统音量
 * CameraScreen 在组合期注册、 disposal 期注销，避免泄漏旧 callback。
 */
package com.photographercamera.core.util

object VolumeKeyBus {
    @Volatile var onCapture: (() -> Unit)? = null
    @Volatile var onZoomStep: ((zoomIn: Boolean) -> Unit)? = null
}
