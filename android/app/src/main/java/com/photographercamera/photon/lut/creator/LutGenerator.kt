package com.photographercamera.photon.lut.creator

import android.content.Context

/**
 * 占位：上游 lut/creator/LutGenerator.kt（AI/本地 LUT 生成器）。
 * 本项目按指导手册去掉了 AI 服务与 LUT 创作器 UI，仅保留接口签名
 * 以兼容 BakedLutExporter / CameraViewModel 的引用。导出始终返回空串。
 */
object LutGenerator {
    fun exportToCubeString(floatArray: FloatArray, size: Int, name: String): String = ""
}
