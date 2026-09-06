package com.photographercamera.core.device

/**
 * Device probes ported from PhotonCamera utils/DeviceUtil.kt (minimal subset).
 * Only what the app currently needs: HarmonyOS detection (HDR colorMode gate).
 */
object DeviceUtil {

    val isHarmonyOS: Boolean
        get() {
            val list = listOf(
                "ro.product.anco.devicetype",
                "ro.sys.anco.product.software.version",
                "ro.product.os.dist.anco.apiversion",
                "ro.product.os.dist.anco.releasetype",
            )
            return list.any { getSystemProperty(it)?.isNotEmpty() == true }
        }

    private fun getSystemProperty(key: String): String? = runCatching {
        @Suppress("PrivateApi", "DiscouragedPrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getDeclaredMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
