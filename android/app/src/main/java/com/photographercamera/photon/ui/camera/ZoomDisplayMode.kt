package com.photographercamera.photon.ui.camera

/**
 * 显示模式：变焦倍率 or 35mm等效焦距（上游 ui/camera/ZoomControlBar.kt 同源）。
 */
enum class ZoomDisplayMode {
    ZOOM_RATIO,
    FOCAL_LENGTH;

    fun next(): ZoomDisplayMode = if (this == ZOOM_RATIO) FOCAL_LENGTH else ZOOM_RATIO

    companion object {
        fun fromPersistedName(name: String?): ZoomDisplayMode =
            entries.firstOrNull { it.name == name } ?: FOCAL_LENGTH
    }
}
