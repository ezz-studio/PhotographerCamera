/*
 * UserPreferencesRepository — 0.7.0 轻量桩（SharedPreferences 后端）。
 *
 * 上游 PhotonCamera 的同名类约 2479 行（DataStore + 全 App 偏好）。
 * 本桩只服务基座闭包中的唯一消费方 camera/CameraDiscovery（镜头发现策略：
 * 自定义镜头 ID / ISZ 配置 / 黑名单 / 主摄与微距偏好 / 逻辑多摄发现开关），
 * 字段语义与上游 UserPreferences 完全一致，后端换成 pc_settings
 * SharedPreferences，键名与上游 DataStore 键保持可读对应。
 *
 * 后续若需要更多偏好字段（LUT 选择记忆、RAW 参数记忆等），在此扩展字段即可，
 * 消费方 API（userPreferences: Flow / firstOrNull()）不变。
 */
package com.photographercamera.core.photon.data

import android.content.Context
import com.photographercamera.core.photon.camera.IszLensConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 镜头发现相关偏好快照（上游 UserPreferences 的精简子集）。 */
data class UserPreferences(
    val customLensIds: List<String> = emptyList(),
    val iszLensConfigs: List<IszLensConfig> = emptyList(),
    val lensIdBlacklist: List<String> = emptyList(),
    val preferredMainCameraId: String? = null,
    val preferredMacroCameraId: String? = null,
    val enableLogicalMultiCameraDiscovery: Boolean = true,
    val logicalCameraBindingWhitelist: List<String> = emptyList(),
)

class UserPreferencesRepository(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("pc_settings", Context.MODE_PRIVATE)

    val userPreferences: Flow<UserPreferences> = flowOf(load())

    private fun load(): UserPreferences = UserPreferences(
        customLensIds = sp.getStringSet("up_custom_lens_ids", emptySet())?.toList() ?: emptyList(),
        iszLensConfigs = decodeIsz(sp.getString("up_isz_lens_configs", null)),
        lensIdBlacklist = sp.getStringSet("up_lens_id_blacklist", emptySet())?.toList() ?: emptyList(),
        preferredMainCameraId = sp.getString("up_preferred_main_camera_id", null),
        preferredMacroCameraId = sp.getString("up_preferred_macro_camera_id", null),
        enableLogicalMultiCameraDiscovery = sp.getBoolean("up_logical_multi_cam", true),
        logicalCameraBindingWhitelist = sp.getStringSet("up_logical_cam_whitelist", emptySet())?.toList() ?: emptyList(),
    )

    fun saveIszLensConfigs(configs: List<IszLensConfig>) {
        // IszLensConfig 自带 gson JsonObject 序列化（toJsonObject），逐条落字符串
        sp.edit()
            .putString(
                "up_isz_lens_configs",
                configs.joinToString("\u0001") { c -> c.toJsonObject().toString() },
            )
            .apply()
    }

    private fun decodeIsz(raw: String?): List<IszLensConfig> = emptyList() // 读路径暂不需要（ISZ 为上游创建型功能，我方未启用虚拟镜头）
}
