package com.photographercamera.photon.lut

import android.content.Context
import android.util.Base64
import android.util.LruCache
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photographercamera.core.profile.ProfileLoader
import com.photographercamera.photon.data.CustomImportManager
import com.photographercamera.photon.mgc.PhotonLookContract
import com.photographercamera.photon.model.ColorRecipeParams
import com.photographercamera.photon.utils.PLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DataStore 扩展属性
 */
private val Context.colorRecipeDataStore: DataStore<Preferences> by preferencesDataStore(name = "color_recipe_preferences")

/**
 * LUT 管理器
 *
 * 负责 LUT 的加载、缓存和管理，以及色彩配方的持久化
 */
class LutManager(private val context: Context) {

    companion object {
        private const val TAG = "LutManager"

        // LUT 缓存大小（最多缓存 5 个 LUT）
        private const val CACHE_SIZE = 5

        // stylefit profile 自带 LUT 的 lutId 前缀（CameraScreen 注入配方时写入）
        private const val PROFILE_LUT_PREFIX = "profile:"

        // 内置 LUT 目录
        private const val BUILT_IN_LUT_FOLDER = "luts"

        // 色彩配方 DataStore Key（每个 LUT ID 存一条 JSON）
        private fun recipeKey(lutId: String, target: BaselineColorCorrectionTarget? = null) =
            stringPreferencesKey(
                target?.let { "${it.name.lowercase()}_${lutId}_recipe" } ?: "${lutId}_recipe"
            )

        // 旧版逐字段 Key（仅用于迁移读取，新数据不再写入）
        private val legacyFieldNames = listOf(
            "exposure", "contrast", "saturation", "temperature", "tint", "fade", "color",
            "highlights", "shadows", "toneToe", "toneShoulder", "tonePivot",
            "paletteX", "paletteY", "paletteDensity",
            "filmGrain", "vignette", "flash", "bleachBypass", "clarity", "sharpness", "bloom", "softLight", "halation", "redHalation", "chromaticAberration",
            "noise", "lowRes",
            "skinHue", "skinChroma", "skinLightness",
            "redHue", "redChroma", "redLightness",
            "orangeHue", "orangeChroma", "orangeLightness",
            "yellowHue", "yellowChroma", "yellowLightness",
            "greenHue", "greenChroma", "greenLightness",
            "cyanHue", "cyanChroma", "cyanLightness",
            "blueHue", "blueChroma", "blueLightness",
            "purpleHue", "purpleChroma", "purpleLightness",
            "magentaHue", "magentaChroma", "magentaLightness",
            "gradingShadowHue", "gradingShadowAmount", "gradingShadowLuminance",
            "gradingMidtoneHue", "gradingMidtoneAmount", "gradingMidtoneLuminance",
            "gradingHighlightHue", "gradingHighlightAmount", "gradingHighlightLuminance",
            "gradingBalance", "gradingBlending",
            "lutIntensity"
        )

        private fun readLegacyParams(preferences: Preferences, lutId: String): ColorRecipeParams {
            fun f(name: String, default: Float = 0f) =
                preferences[floatPreferencesKey("${lutId}_$name")] ?: default
            fun s(name: String) =
                preferences[stringPreferencesKey("${lutId}_$name")] ?: ""
            return ColorRecipeParams(
                exposure = f("exposure"),
                contrast = f("contrast", 1f),
                saturation = f("saturation", 1f),
                temperature = f("temperature"),
                tint = f("tint"),
                fade = f("fade"),
                color = f("color"),
                highlights = f("highlights"),
                shadows = f("shadows"),
                toneToe = f("toneToe"),
                toneShoulder = f("toneShoulder"),
                tonePivot = f("tonePivot"),
                paletteX = f("paletteX", 0.5f),
                paletteY = f("paletteY", 0.5f),
                paletteDensity = f("paletteDensity", 1f),
                filmGrain = f("filmGrain"),
                vignette = f("vignette"),
                flash = f("flash"),
                bleachBypass = f("bleachBypass"),
                clarity = f("clarity"),
                sharpness = f("sharpness"),
                bloom = f("bloom"),
                softLight = f("softLight"),
                halation = 0f,
                redHalation = f("redHalation"),
                chromaticAberration = f("chromaticAberration"),
                noise = f("noise"),
                lowRes = f("lowRes"),
                skinHue = f("skinHue"),
                skinChroma = f("skinChroma"),
                skinLightness = f("skinLightness"),
                redHue = f("redHue"),
                redChroma = f("redChroma"),
                redLightness = f("redLightness"),
                orangeHue = f("orangeHue"),
                orangeChroma = f("orangeChroma"),
                orangeLightness = f("orangeLightness"),
                yellowHue = f("yellowHue"),
                yellowChroma = f("yellowChroma"),
                yellowLightness = f("yellowLightness"),
                greenHue = f("greenHue"),
                greenChroma = f("greenChroma"),
                greenLightness = f("greenLightness"),
                cyanHue = f("cyanHue"),
                cyanChroma = f("cyanChroma"),
                cyanLightness = f("cyanLightness"),
                blueHue = f("blueHue"),
                blueChroma = f("blueChroma"),
                blueLightness = f("blueLightness"),
                purpleHue = f("purpleHue"),
                purpleChroma = f("purpleChroma"),
                purpleLightness = f("purpleLightness"),
                magentaHue = f("magentaHue"),
                magentaChroma = f("magentaChroma"),
                magentaLightness = f("magentaLightness"),
                gradingShadowHue = f("gradingShadowHue"),
                gradingShadowAmount = f("gradingShadowAmount"),
                gradingShadowLuminance = f("gradingShadowLuminance"),
                gradingMidtoneHue = f("gradingMidtoneHue"),
                gradingMidtoneAmount = f("gradingMidtoneAmount"),
                gradingMidtoneLuminance = f("gradingMidtoneLuminance"),
                gradingHighlightHue = f("gradingHighlightHue"),
                gradingHighlightAmount = f("gradingHighlightAmount"),
                gradingHighlightLuminance = f("gradingHighlightLuminance"),
                gradingBalance = f("gradingBalance"),
                gradingBlending = f("gradingBlending", 0.5f),
                lutIntensity = f("lutIntensity", 1f),
                remarks = s("remarks"),
            )
        }

        private fun androidx.datastore.preferences.core.MutablePreferences.removeLegacyKeys(lutId: String) {
            legacyFieldNames.forEach { name -> remove(floatPreferencesKey("${lutId}_$name")) }
            remove(stringPreferencesKey("${lutId}_remarks"))
        }
    }

    // LUT 缓存
    private val lutCache = LruCache<String, LutConfig>(CACHE_SIZE)

    // profile 自带 LUT 的数据指纹（同 lutId 重新导入时检测变化、重建纹理配置）
    private val profileLutFingerprints = mutableMapOf<String, Int>()

    // LUT 色彩倾向缓存 (ID -> [R, G, B])
    private val tendencyCache = mutableMapOf<String, FloatArray>()

    // 可用 LUT 列表
    private var availableLuts: List<LutInfo> = emptyList()

    // 自定义导入管理器
    private val customImportManager = CustomImportManager(context)

    /**
     * 获取指定 LUT 的色彩配方参数 Flow
     */
    fun getColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ): Flow<ColorRecipeParams> {
        return context.colorRecipeDataStore.data.map { preferences ->
            val json = preferences[recipeKey(lutId, target)]
            if (json != null) ColorRecipeParams.fromJson(json)
            else if (target == null) readLegacyParams(preferences, lutId) else ColorRecipeParams.DEFAULT
        }
    }

    /**
     * 初始化，扫描可用的 LUT 文件（包括内置和自定义）
     */
    fun initialize() {
        val configuredBuiltInLuts = LutParser.listAvailableLuts(context, BUILT_IN_LUT_FOLDER)
        customImportManager.initializeBuiltInLutCategoriesIfNeeded(configuredBuiltInLuts)
        val builtInLuts = configuredBuiltInLuts.map { it.copy(category = "") }
        val customLuts = customImportManager.getCustomLuts()
        val categoryOverrides = customImportManager.getCategoryOverrides()
        val favoriteOverrides = customImportManager.getFavoriteOverrides()

        // 合并列表并以 ID 去重，避免 ID 重复导致的 Jetpack Compose 主键重复崩溃
        val allLuts = (customLuts + builtInLuts).distinctBy { it.id }

        // 应用分类重写 (用户手动创建的分类会通过这里恢复)
        availableLuts = allLuts.map { lut ->
            val overriddenCategory = categoryOverrides[lut.id]
            val overriddenFavorite = favoriteOverrides[lut.id]
            lut.copy(
                category = overriddenCategory ?: lut.category,
                isFavorite = overriddenFavorite ?: lut.isFavorite
            )
        }

        PLog.d(TAG, "Found ${availableLuts.size} LUT files (${customLuts.size} custom, ${builtInLuts.size} built-in)")
    }

    /**
     * 获取可用的 LUT 列表（自定义 LUT 在前）
     */
    fun getAvailableLuts(): List<LutInfo> = availableLuts

    /**
     * 通过 ID 获取 LUT 信息
     */
    fun getLutInfo(id: String): LutInfo? {
        return availableLuts.find { it.id == id }
    }

    /**
     * 加载 LUT 配置
     *
     * @param id LUT ID
     * @return LUT 配置，如果加载失败返回 null
     */
    fun loadLut(id: String): LutConfig? {
        // stylefit profile 自带的 3D 风格 LUT（lutId = "profile:<名字>"）：
        // 从 profile JSON 的 color_lut payload 构建，复用整条创意 LUT 采样管线
        // （shader 在 sRGB 编码值上采样 + 整数栅格约定，与桌面 lut3d.sample 逐像素等价）。
        // 必须放在通用缓存命中之前：profile 重导入（同 lutId、LUT 数据变化）时
        // 需要经指纹校验决定是否重建，不能无条件吃旧缓存。
        if (id.startsWith(PROFILE_LUT_PREFIX)) {
            return loadProfileLut(id)
        }

        // 先从缓存查找
        lutCache.get(id)?.let {
            //PLog.d(TAG, "LUT loaded from cache: $id")
            return it
        }

        // 查找 LUT 信息
        val lutInfo = getLutInfo(id) ?: run {
//            PLog.w(TAG, "LUT not found: $id")
            return null
        }

        // 从文件加载
        return try {
            val lutConfig = if (lutInfo.isBuiltIn) {
                if (lutInfo.fileName.isBlank()) {
                    return null
                }
                // 内置 LUT 从 assets 加载
                LutParser.parseFromAssets(context, lutInfo.fileName)
            } else {
                // 自定义 LUT 从文件系统加载
                java.io.File(lutInfo.fileName).inputStream().use { inputStream ->
                    LutParser.parse(inputStream, lutInfo.getName())
                }
            }

            if (lutConfig.isValid()) {
                // 添加到缓存
                lutCache.put(id, lutConfig)
//                PLog.d(TAG, "LUT loaded: $id, size: ${lutConfig.size}")
                lutConfig
            } else {
                PLog.e(TAG, "Invalid LUT data: $id")
                null
            }
        } catch (e: Exception) {
            PLog.w(TAG, "Failed to load LUT: $id", e)
            null
        }
    }

    /**
     * 清除缓存中的特定 LUT
     */
    fun evictLut(id: String) {
        lutCache.remove(id)
        profileLutFingerprints.remove(id)
    }

    /**
     * 构建 stylefit profile 自带的 3D LUT（"profile:<名字>"）。
     * payload 约定（tools/stylefit/lut3d.py to_payload）：base64 → uint8 → /scale，
     * R 最快平铺——与 GL RGB8 texImage3D 的内存布局完全一致，无需重排。
     * 采样语义走 LutConfig 默认（curve=SRGB/colorSpace=SRGB）：shader 端
     * linearToSrgb 后采样 = 桌面 lut3d.sample(lut, srgb) 的整数栅格约定。
     * 指纹缓存：同名 profile 重新导入（LUT 数据变化）时自动重建纹理配置。
     */
    private fun loadProfileLut(id: String): LutConfig? {
        val name = id.removePrefix(PROFILE_LUT_PREFIX)
        val profile = ProfileLoader.getProfile(name)
        val payload = profile?.colorLut
        if (payload == null || profile?.colorLayer != "lut" || payload.data.isBlank()) {
            PLog.w(TAG, "profile LUT unavailable: '$name' (colorLayer=${profile?.colorLayer})")
            return null
        }
        val fingerprint = payload.data.hashCode()
        lutCache.get(id)?.let { cached ->
            if (profileLutFingerprints[id] == fingerprint) return cached
        }
        val config = runCatching { buildProfileLutConfig(payload) }
            .onFailure { PLog.e(TAG, "profile LUT decode failed for '$name'", it) }
            .getOrNull()
        if (config == null) return null
        profileLutFingerprints[id] = fingerprint
        lutCache.put(id, config)
        PLog.i(TAG, "profile LUT built: '$name' size=${config.size} bytes=${config.toByteBuffer().capacity()}")
        return config
    }

    private fun buildProfileLutConfig(payload: com.photographercamera.core.profile.ColorLutPayload): LutConfig? {
        if (payload.dtype != "uint8") {
            PLog.e(TAG, "unsupported color_lut dtype: ${payload.dtype}")
            return null
        }
        val n = payload.size
        if (n < 2 || n > 65) {
            PLog.e(TAG, "unsupported color_lut size: $n")
            return null
        }
        val raw = Base64.decode(payload.data, Base64.NO_WRAP or Base64.NO_PADDING)
        val expected = n * n * n * 3
        if (raw.size != expected) {
            PLog.e(TAG, "color_lut size mismatch: got ${raw.size}, expected $expected")
            return null
        }
        val buffer = ByteBuffer.allocateDirect(raw.size).order(ByteOrder.nativeOrder())
        buffer.put(raw)
        buffer.position(0)
        val scale = if (payload.scale > 0f) payload.scale else 255f
        // scale 允许非 255 的量化基准；uint8 路径 glTexImage3D 直接按 0-255 归一，
        // scale != 255 时需预除到 0-255 域（当前 stylefit 恒为 255，防御性处理）。
        val normalized = if (kotlin.math.abs(scale - 255f) > 0.5f) {
            val out = ByteBuffer.allocateDirect(raw.size).order(ByteOrder.nativeOrder())
            for (b in raw) {
                out.put(((b.toInt() and 0xFF) * (255f / scale) + 0.5f).toInt().coerceIn(0, 255).toByte())
            }
            out.position(0)
            out
        } else {
            buffer
        }
        return LutConfig(
            size = n,
            byteBuffer = normalized,
            title = "stylefit",
            configDataType = LutConfig.CONFIG_DATA_TYPE_UINT8,
        )
    }

    /**
     * 清除所有缓存
     */
    fun clearCache() {
        lutCache.evictAll()
        PLog.d(TAG, "LUT cache cleared")
    }

    /**
     * 获取缓存状态信息
     */
    fun getCacheInfo(): String {
        return "LUT Cache: ${lutCache.size()}/${CACHE_SIZE}, hits=${lutCache.hitCount()}, misses=${lutCache.missCount()}"
    }

    /**
     * 获取指定 LUT 的色彩倾向性（带缓存）
     */
    fun getLutTendency(id: String): FloatArray? {
        tendencyCache[id]?.let { return it }
        
        val lutConfig = loadLut(id) ?: return null
        val tendency = LutColorAnalyzer.analyzeTendency(lutConfig)
        tendencyCache[id] = tendency
        return tendency
    }

    /**
     * 为指定颜色推荐最合适的 LUT 列表
     * @param targetColor 目标颜色 (Color Int)
     * @param limit 推荐数量
     * @return 按匹配度排序的 LUT 列表
     */
    fun recommendLutsForColor(targetColor: Int, limit: Int = 5): List<LutInfo> {
        return availableLuts
            .mapNotNull { info ->
                val tendency = getLutTendency(info.id) ?: return@mapNotNull null
                val score = LutColorAnalyzer.calculateSuitability(targetColor, tendency)
                info to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    // ========== 色彩配方持久化方法 ==========

    /**
     * 保存指定 LUT 的色彩配方参数
     *
     * @param lutId LUT ID
     * @param params 色彩配方参数
     */
    suspend fun saveColorRecipeParams(
        lutId: String,
        params: ColorRecipeParams,
        target: BaselineColorCorrectionTarget? = null
    ) {
        context.colorRecipeDataStore.edit { preferences ->
            preferences[recipeKey(lutId, target)] = params.toJson()
            if (target == null) {
                preferences.removeLegacyKeys(lutId)
            }
        }
        if (target == null) {
            PhotonLookContract.notifyLookChanged(context)
        }
//        PLog.d(TAG, "Color recipe params saved for LUT [$lutId]: $params")
    }

    /**
     * 加载指定 LUT 的色彩配方参数（一次性读取）
     *
     * @param lutId LUT ID
     * @return 色彩配方参数，如果未设置则返回默认值
     */
    suspend fun loadColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ): ColorRecipeParams {
        return context.colorRecipeDataStore.data.map { preferences ->
            val json = preferences[recipeKey(lutId, target)]
            if (json != null) ColorRecipeParams.fromJson(json)
            else if (target == null) readLegacyParams(preferences, lutId) else ColorRecipeParams.DEFAULT
        }.firstOrNull() ?: ColorRecipeParams.DEFAULT
    }

    /**
     * 重置指定 LUT 的色彩配方参数为默认值
     *
     * @param lutId LUT ID
     */
    suspend fun resetColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ) {
        saveColorRecipeParams(lutId, ColorRecipeParams.DEFAULT, target)
        PLog.d(TAG, "Color recipe params reset to default for LUT [$lutId]")
    }

    /**
     * 删除指定 LUT 的色彩配方参数
     *
     * @param lutId LUT ID
     */
    suspend fun deleteColorRecipeParams(
        lutId: String,
        target: BaselineColorCorrectionTarget? = null
    ) {
        context.colorRecipeDataStore.edit { preferences ->
            preferences.remove(recipeKey(lutId, target))
            if (target == null) {
                preferences.removeLegacyKeys(lutId)
            }
        }
        PLog.d(TAG, "Color recipe params deleted for LUT [$lutId]")
    }
}
