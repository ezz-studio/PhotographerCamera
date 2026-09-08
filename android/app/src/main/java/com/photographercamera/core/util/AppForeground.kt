package com.photographercamera.core.util

/**
 * 1.3.6：极简前后台标记。由 MainActivity onStart/onStop 维护，相机等硬件敏感
 * 逻辑读取。
 *
 * 用途：相机错误恢复循环（Camera2Controller.scheduleCameraRecovery）在延时
 * runnable 执行时必须检查此标记——切后台后 ColorOS 会以 error=3（系统策略
 * 禁用）关闭相机，旧逻辑在后台仍尝试 open camera → 再次被拒（error=4）形成
 * 循环，且每次 open 都给 OIS 防抖马达上电（用户可闻咔哒声）。后台跳过恢复，
 * 回前台由 CameraScreen 的 ON_RESUME → pvm.openCamera（幂等）接手。
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = true
        private set

    fun onForeground() {
        isForeground = true
    }

    fun onBackground() {
        isForeground = false
    }
}
