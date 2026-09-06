package com.photographercamera.photon.lut.creator

import android.content.Context

/**
 * 占位：上游 lut/creator/OpenAIApiClient.kt（AI LUT 生成的 OpenAI 接入）。
 * 本项目按指导手册去掉 AI 服务，仅保留接口签名以兼容 CameraViewModel 的引用。
 * initialize/getAvailableModels 永远返回空可用模型列表（即 UI 上无可用 AI 模型）。
 */
class OpenAIApiClient {
    fun initialize(context: Context) {}
    fun getAvailableModels(): Result<List<String>> = Result.success(emptyList())
}
