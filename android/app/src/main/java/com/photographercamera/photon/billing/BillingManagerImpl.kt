package com.photographercamera.photon.billing

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BillingManagerImpl 占位：本项目去掉付费/计费，所有功能默认已购。
 * CameraViewModel 通过 isPurchased 门控专业功能——这里恒为 true，
 * 全部专业参数（RAW MAX/HDR+/色调映射等）对用户开放。
 */
class BillingManagerImpl(application: Application) {
    val isPurchased: StateFlow<Boolean> = MutableStateFlow(true)
    fun purchase(activity: android.app.Activity) {}  // 占位：本项目无付费，purchase 无操作
}
