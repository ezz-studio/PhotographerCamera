package com.photographercamera.core.photon.color

import android.os.Build

fun isVideoTransformerExportSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
