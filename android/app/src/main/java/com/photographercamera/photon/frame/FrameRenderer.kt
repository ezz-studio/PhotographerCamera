package com.photographercamera.photon.frame

import android.content.Context
import android.graphics.Bitmap
import com.photographercamera.photon.gallery.MediaMetadata
import com.photographercamera.photon.lut.LutManager

/**
 * FrameRenderer 占位实现。
 *
 * 上游 frame/FrameRenderer.kt（1415 行）负责给照片绘制相框/边框/增益图。
 * 本项目按指导手册未保留相框功能（配置文件不携带 frameId），PhotoProcessor
 * 仅在预设带 frameId 时才调用 render；无 frameId 时该实例不会被调用。
 * 为避免移植整条 frame 工具链（dpToPx/loadImageFrameBitmap/detectTransparentBounds
 * 等 178 个未解析引用），此处以透传占位实现满足编译：render 原样返回原图，
 * renderGainmapContents 原样返回 gainmap。功能上等价于"不加框"。
 */
class FrameRenderer(
    private val context: Context,
    private val lutManager: LutManager? = null,
) {
    fun render(
        originalBitmap: Bitmap,
        template: FrameTemplate,
        metadata: MediaMetadata,
    ): Bitmap = originalBitmap

    fun renderGainmapContents(
        originalBitmap: Bitmap,
        gainmapContents: Bitmap,
        template: FrameTemplate,
    ): Bitmap = gainmapContents
}
