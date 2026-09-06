package com.photographercamera.photon.screencapture

import android.app.Activity
import android.os.Bundle

/**
 * 占位：上游 screencapture/ScreenCapturePipActivity.kt（幻影画中画录制 Activity）。
 * 本项目未启用幻影功能，仅保留空 Activity 以兼容 ScreenCapturePermissionActivity
 * 的 Intent 跳转引用（启动后立即 finish，不实际录制）。
 */
class ScreenCapturePipActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
