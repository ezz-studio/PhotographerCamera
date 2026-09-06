/*
 * ContentRepository — 0.7.0 轻量桩（上游同名服务定位器的精简版）。
 * 保留基座闭包消费的成员：lutManager / dcpManager / rawNoiseProfileManager /
 * availableLuts / availableDcps / availableRawNoiseProfiles + initialize/refresh。
 * 上游的 frameManager / depthBokehProcessor / galleryRepository / photoProcessor
 * 属任务书排除项（内容管理/景深/相册管理），不移植。
 */
package com.photographercamera.core.photon.data

import android.content.Context
import com.photographercamera.core.photon.color.LutInfo
import com.photographercamera.core.photon.color.LutManager
import com.photographercamera.core.photon.raw.DcpManager
import com.photographercamera.core.photon.raw.DcpInfo
import com.photographercamera.core.photon.raw.RawNoiseProfileInfo
import com.photographercamera.core.photon.raw.RawNoiseProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContentRepository private constructor(context: Context) {

    companion object {
        @Volatile private var instance: ContentRepository? = null
        fun getInstance(context: Context): ContentRepository =
            instance ?: synchronized(this) {
                instance ?: ContentRepository(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext

    val lutManager: LutManager = LutManager(appContext)
    val dcpManager: DcpManager = DcpManager(appContext)
    val rawNoiseProfileManager: RawNoiseProfileManager = RawNoiseProfileManager(appContext)
    val customImportManager: com.photographercamera.core.photon.data.CustomImportManager =
        CustomImportManager(appContext)
    val imageProcessor: com.photographercamera.core.photon.color.LutImageProcessor =
        com.photographercamera.core.photon.color.LutImageProcessor(appContext)

    private val _availableLuts = MutableStateFlow<List<LutInfo>>(emptyList())
    val availableLuts: StateFlow<List<LutInfo>> = _availableLuts.asStateFlow()

    private val _availableDcps = MutableStateFlow<List<DcpInfo>>(emptyList())
    val availableDcps: StateFlow<List<DcpInfo>> = _availableDcps.asStateFlow()

    private val _availableRawNoiseProfiles = MutableStateFlow<List<RawNoiseProfileInfo>>(emptyList())
    val availableRawNoiseProfiles: StateFlow<List<RawNoiseProfileInfo>> =
        _availableRawNoiseProfiles.asStateFlow()

    fun initialize() {
        lutManager.initialize()
        _availableLuts.value = lutManager.getAvailableLuts()
        _availableDcps.value = dcpManager.getAvailableDcps()
        _availableRawNoiseProfiles.value = rawNoiseProfileManager.getAvailableProfiles()
    }

    fun getAvailableLuts(): List<LutInfo> = _availableLuts.value

    fun getAvailableDcps(): List<DcpInfo> = _availableDcps.value

    fun getAvailableRawNoiseProfiles(): List<RawNoiseProfileInfo> =
        _availableRawNoiseProfiles.value

    fun refreshCustomContent() {
        lutManager.initialize()
        _availableLuts.value = lutManager.getAvailableLuts()
        _availableDcps.value = dcpManager.getAvailableDcps()
        _availableRawNoiseProfiles.value = rawNoiseProfileManager.getAvailableProfiles()
    }
}
