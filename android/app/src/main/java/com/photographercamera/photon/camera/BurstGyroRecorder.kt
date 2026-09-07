package com.photographercamera.photon.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.photographercamera.photon.utils.PLog
import java.util.ArrayDeque

class BurstGyroRecorder(context: Context) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val samples = ArrayDeque<GyroSample>(MAX_SAMPLES)
    private val lock = Any()

    @Volatile
    private var recording = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!recording || event.values.size < 3) return
            val hasBias = event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED && event.values.size >= 6
            val sample = GyroSample(
                timestampNs = event.timestamp,
                x = event.values[0] - if (hasBias) event.values[3] else 0f,
                y = event.values[1] - if (hasBias) event.values[4] else 0f,
                z = event.values[2] - if (hasBias) event.values[5] else 0f,
            )
            synchronized(lock) {
                while (samples.size >= MAX_SAMPLES) samples.removeFirst()
                samples.addLast(sample)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 注册陀螺仪监听。
     *
     * 0.9.7 修复：Android 12（API 31）起，采样周期 0µs（SENSOR_DELAY_FASTEST，
     * 以及部分固件上更快的周期）需要 HIGH_SAMPLING_RATE_SENSORS 权限，缺失时
     * registerListener 直接抛 SecurityException。该异常原先会沿调用栈冒到
     * Camera2Controller.capture()，把整次多帧（JPEG MAX / RAW MAX）拍摄打断成
     * “capture setup FAILED”——两种 MAX 模式都拍不出照片的根因。
     *
     * 这里改为「从快到慢」降级注册：任一档被拒绝（抛异常或返回 false）就试下一档，
     * 保证陀螺仪缺失/受限时多帧拍摄照常进行（只是卷帘补偿精度下降）。
     */
    fun start(handler: Handler?): Boolean {
        if (recording) return true
        val gyro = sensor ?: run {
            PLog.w(TAG, "Burst Gyro unavailable")
            return false
        }
        synchronized(lock) { samples.clear() }
        var registered = false
        for (periodUs in SAMPLING_PERIOD_CANDIDATES_US) {
            registered = try {
                sensorManager.registerListener(listener, gyro, periodUs, handler)
            } catch (e: SecurityException) {
                PLog.w(TAG, "Gyro sampling period ${periodUs}us needs HIGH_SAMPLING_RATE_SENSORS: ${e.message}")
                false
            } catch (e: Exception) {
                PLog.w(TAG, "Gyro registration failed at ${periodUs}us", e)
                false
            }
            if (registered) {
                if (periodUs != SAMPLING_PERIOD_CANDIDATES_US.first()) {
                    PLog.i(TAG, "Burst Gyro registered with fallback period=${periodUs}us")
                }
                break
            }
        }
        recording = registered
        if (!recording) PLog.w(TAG, "Failed to register burst Gyro listener")
        return recording
    }

    fun stop() {
        if (!recording) return
        recording = false
        sensorManager.unregisterListener(listener)
    }

    fun exposureWindow(startTimestampNs: Long, exposureTimeNs: Long): GyroExposureWindow {
        val safeExposureNs = exposureTimeNs.coerceAtLeast(0L)
        val endTimestampNs = startTimestampNs.saturatingAdd(safeExposureNs)
        if (safeExposureNs == 0L) {
            return GyroExposureWindow.unavailable(startTimestampNs, endTimestampNs)
        }
        val guardStart = (startTimestampNs - WINDOW_GUARD_NS).coerceAtLeast(0L)
        val guardEnd = endTimestampNs.saturatingAdd(WINDOW_GUARD_NS)
        val snapshot = synchronized(lock) {
            samples.filter { it.timestampNs in guardStart..guardEnd }
        }
        return GyroExposureWindowIntegrator.integrate(snapshot, startTimestampNs, endTimestampNs)
    }

    fun release() {
        stop()
        synchronized(lock) { samples.clear() }
    }

    private fun Long.saturatingAdd(value: Long): Long {
        return if (value > 0L && this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
    }

    private companion object {
        const val TAG = "BurstGyroRecorder"
        const val MAX_SAMPLES = 8_192
        const val WINDOW_GUARD_NS = 2_000_000L

        /** 由快到慢的采样周期候选（微秒）：0(FASTEST) / 200Hz / 50Hz / 15Hz。 */
        val SAMPLING_PERIOD_CANDIDATES_US = intArrayOf(
            SensorManager.SENSOR_DELAY_FASTEST,
            5_000,
            SensorManager.SENSOR_DELAY_GAME,
            SensorManager.SENSOR_DELAY_UI,
        )
    }
}
