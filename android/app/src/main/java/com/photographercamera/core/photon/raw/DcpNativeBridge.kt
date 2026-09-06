package com.photographercamera.core.photon.raw

object DcpNativeBridge {
    init {
        System.loadLibrary("my-native-lib")
    }

    external fun parseDcpToJson(filePath: String): String
}
