package com.photographercamera.core.photon.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.photographercamera.core.photon.color.LutConverter
import com.photographercamera.core.photon.color.TransferCurve
import com.photographercamera.core.photon.color.LutInfo
import com.photographercamera.core.photon.color.XmpLutParser
import com.photographercamera.core.photon.raw.ColorSpace
import com.photographercamera.core.photon.raw.DcpInfo
import com.photographercamera.core.photon.raw.RawNoiseProfileInfo
import com.photographercamera.core.photon.stack.CalibratedRawNoiseProfile
import com.photographercamera.core.photon.stack.PLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

private const val PRESET_PLUT_MAGIC = 0x54554C50
private const val PRESET_PLUT_MAX_VERSION = 4
private const val PRESET_PLUT_MAX_DIMENSION = 65

internal fun isStructurallyValidPresetPlut(bytes: ByteArray): Boolean {
    return runCatching {
        if (bytes.size < 16) return false
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.int != PRESET_PLUT_MAGIC) return false
        val version = buffer.int
        if (version !in 1..PRESET_PLUT_MAX_VERSION) return false
        val dimension = buffer.int
        if (dimension !in 2..PRESET_PLUT_MAX_DIMENSION) return false
        val dataType = buffer.int
        if (dataType !in 0..1) return false
        if (version >= 2) buffer.int
        if (version >= 3) buffer.int

        val bytesPerComponent = if (dataType == 1) 2L else 1L
        val payloadBytes = dimension.toLong() * dimension * dimension * 3L * bytesPerComponent
        val payloadEnd = buffer.position().toLong() + payloadBytes
        if (payloadEnd > bytes.size) return false
        if (version < 4) return payloadEnd == bytes.size.toLong()
        if (payloadEnd == bytes.size.toLong()) return true
        if (payloadEnd + Int.SIZE_BYTES > bytes.size) return false

        buffer.position(payloadEnd.toInt())
        val recipeBytes = buffer.int
        recipeBytes >= 0 && payloadEnd + Int.SIZE_BYTES + recipeBytes == bytes.size.toLong()
    }.getOrDefault(false)
}

/**
 * 自定义导入管理器
 *
 * 管理用户导入的自定义 LUT 和边框样式
 */
// 上游常量原在 PresetPackageManager（内容管理，未移植），此处就地保留
private const val PRESET_PACKAGE_FRAME_RESOURCE_PREFIX = "pp-resource:"

class CustomImportManager(private val context: Context) {

    companion object {
        private const val TAG = "CustomImportManager"

        // 自定义LUT目录
        private const val CUSTOM_LUT_DIR = "custom_luts"

        // 自定义边框目录
        private const val CUSTOM_FRAME_DIR = "custom_frames"
        private const val CUSTOM_DCP_DIR = "custom_dcps"
        private const val CUSTOM_RAW_NOISE_PROFILE_DIR = "custom_raw_noise_profiles"

        // 配置文件
        private const val CUSTOM_LUT_CONFIG = "custom_luts.json"
        private const val CUSTOM_FRAME_CONFIG = "custom_frames.json"
        private const val CUSTOM_DCP_CONFIG = "custom_dcps.json"
        private const val CUSTOM_RAW_NOISE_PROFILE_CONFIG = "custom_raw_noise_profiles.json"
        private const val MAX_RAW_NOISE_PROFILE_CHARS = 1024 * 1024
        private const val CATEGORY_OVERRIDES_CONFIG = "category_overrides.json"
        private const val FAVORITE_OVERRIDES_CONFIG = "favorite_overrides.json"
        private const val BUILT_IN_LUT_CATEGORY_INITIALIZED_CONFIG = "built_in_lut_categories_initialized.json"

        // 自定义字体目录
        private const val CUSTOM_FONT_DIR = "custom_fonts"

        // 自定义 Logo 目录
        private const val CUSTOM_LOGO_DIR = "custom_logos"

        private val EXTERNAL_LUT_IMPORT_EXTENSIONS = setOf("cube", "png", "xmp", "plut")
        private const val ZIP_IMPORT_EXTENSION = "zip"

        fun resolveDisplayFileName(context: Context, uri: Uri): String? {
            if (uri.scheme == "file") {
                return uri.lastPathSegment
            }

            val displayName = try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                PLog.w(TAG, "Failed to resolve display file name for $uri", e)
                null
            }

            return displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
        }

        fun isExternalLutImportFileName(fileName: String?): Boolean {
            val extension = fileName?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.US)
                ?: return false
            return extension in EXTERNAL_LUT_IMPORT_EXTENSIONS || extension == ZIP_IMPORT_EXTENSION
        }

        fun isCubeImportFileName(fileName: String?): Boolean {
            return fileName?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.US) == "cube"
        }

        fun isZipImportFileName(fileName: String?): Boolean {
            return fileName?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.US) == ZIP_IMPORT_EXTENSION
        }
    }

    private fun sanitizeCustomLutCategory(category: String?): String {
        val trimmedCategory = category?.trim().orEmpty()
        if (trimmedCategory.isEmpty()) return ""

        val reservedCategoryNames = setOf(
            "Built-in",
            "Uncategorized"
        )
        return if (trimmedCategory in reservedCategoryNames) "" else trimmedCategory
    }

    private fun isCustomLutId(lutId: String): Boolean = lutId.startsWith("custom_")

    /**
     * 获取分类重定向/重写映射
     */
    fun getCategoryOverrides(): Map<String, String> {
        return try {
            val file = File(context.filesDir, CATEGORY_OVERRIDES_CONFIG)
            if (!file.exists()) return emptyMap()
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to get category overrides", e)
            emptyMap()
        }
    }

    fun initializeBuiltInLutCategoriesIfNeeded(luts: List<LutInfo>) {
        try {
            val categorizedLuts = luts
                .filter { it.isBuiltIn && it.category.isNotEmpty() }
                .associate { it.id to it.category }
            if (categorizedLuts.isEmpty()) return

            val initializedFile = File(context.filesDir, BUILT_IN_LUT_CATEGORY_INITIALIZED_CONFIG)
            val initializedJson = if (initializedFile.exists()) {
                JSONObject(initializedFile.readText())
            } else {
                JSONObject()
            }

            val overridesFile = File(context.filesDir, CATEGORY_OVERRIDES_CONFIG)
            val overridesJson = if (overridesFile.exists()) {
                JSONObject(overridesFile.readText())
            } else {
                JSONObject()
            }

            var updatedCount = 0
            categorizedLuts.forEach { (id, category) ->
                if (!initializedJson.optBoolean(id, false)) {
                    overridesJson.put(id, category)
                    initializedJson.put(id, true)
                    updatedCount++
                }
            }

            if (updatedCount > 0) {
                overridesFile.writeText(overridesJson.toString())
                initializedFile.writeText(initializedJson.toString())
            }
            PLog.d(TAG, "Built-in LUT categories initialized: $updatedCount")
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to initialize built-in LUT categories", e)
        }
    }

    /**
     * 获取收藏重写映射。
     */
    fun getFavoriteOverrides(): Map<String, Boolean> {
        return try {
            val file = File(context.filesDir, FAVORITE_OVERRIDES_CONFIG)
            if (!file.exists()) return emptyMap()
            val json = JSONObject(file.readText())
            val map = mutableMapOf<String, Boolean>()
            json.keys().forEach { key ->
                map[key] = json.optBoolean(key, false)
            }
            map
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to get favorite overrides", e)
            emptyMap()
        }
    }

    private val customLutDir: File
        get() = File(context.filesDir, CUSTOM_LUT_DIR).apply { mkdirs() }

    private val customFrameDir: File
        get() = File(context.filesDir, CUSTOM_FRAME_DIR).apply { mkdirs() }

    private val customDcpDir: File
        get() = File(context.filesDir, CUSTOM_DCP_DIR).apply { mkdirs() }

    private val customRawNoiseProfileDir: File
        get() = File(context.filesDir, CUSTOM_RAW_NOISE_PROFILE_DIR).apply { mkdirs() }

    private val customFontDir: File
        get() = File(context.filesDir, CUSTOM_FONT_DIR).apply { mkdirs() }

    private val customLogoDir: File
        get() = File(context.filesDir, CUSTOM_LOGO_DIR).apply { mkdirs() }

    /**
     * 导入 LUT 文件 (.cube)
     *
     * @param uri 选择的 .cube 文件 URI
     * @param displayName 用户自定义的显示名称（可选）
     * @param category 分类名称（可选）
     * @param curve 输入曲线类型（可选）
     * @return 导入成功的 LUT ID，失败返回 null
     */
    fun importLut(uri: Uri, displayName: String? = null, category: String? = null, colorSpace: ColorSpace = ColorSpace.SRGB, curve: TransferCurve = TransferCurve.SRGB): String? {
        return try {
            val fileName = getFileName(uri) ?: "lut_${System.currentTimeMillis()}.cube"
            val lutId = "custom_${UUID.randomUUID()}"
            val plutFileName = "$lutId.plut"
            val plutFile = File(customLutDir, plutFileName)

            // 读取 .cube / .png / .xmp / .plut 文件并转换（或复制）为内部 .plut (v3)
            openInputStream(uri)?.use { inputStream ->
                FileOutputStream(plutFile).use { outputStream ->
                    val success = when {
                        fileName.endsWith(".xmp", ignoreCase = true) ->
                            XmpLutParser.parse(inputStream, outputStream, colorSpace = colorSpace, curve = curve)
                        fileName.endsWith(".png", ignoreCase = true) ->
                            LutConverter.convertPngToplut(inputStream, outputStream, colorSpace = colorSpace, curve = curve)
                        fileName.endsWith(".plut", ignoreCase = true) ->
                            LutConverter.importPlutStrippingRecipe(inputStream, outputStream)
                        else ->
                            LutConverter.convertCubeToplut(inputStream, outputStream, colorSpace = colorSpace, curve = curve)
                    }

                    if (!success) {
                        plutFile.delete()
                        return null
                    }
                }
            } ?: return null

            var parsedName: String? = null
            if (fileName.endsWith(".xmp", ignoreCase = true) && displayName.isNullOrBlank()) {
                openInputStream(uri)?.use { inputStream ->
                    parsedName = XmpLutParser.parseName(inputStream)
                }
            }

            // 生成显示名称
            val name = displayName?.takeIf { it.isNotBlank() } ?: parsedName ?: fileName.substringBeforeLast('.')
            val sanitizedCategory = sanitizeCustomLutCategory(category)

            // 保存到配置文件
            saveLutToConfig(lutId, name, plutFileName, sanitizedCategory)

            // 如果有分类，也同步到 overrides (保持一致性)
            if (sanitizedCategory.isNotEmpty()) {
                updateLutCategory(lutId, sanitizedCategory)
            }

            PLog.d(TAG, "LUT imported successfully: $lutId ($name)")
            lutId
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to import LUT", e)
            null
        }
    }

    /**
     * 从预设包导入应用原生 .plut，并保留资源的名称与分类元数据。
     */
    internal fun importPresetLut(
        bytes: ByteArray,
        nameMap: Map<String, String>,
        category: String,
        isFavorite: Boolean,
    ): String? {
        val lutId = "custom_${UUID.randomUUID()}"
        val plutFileName = "$lutId.plut"
        val plutFile = File(customLutDir, plutFileName)
        return try {
            require(isStructurallyValidPresetPlut(bytes)) { "Invalid LUT data in preset package" }
            val success = ByteArrayInputStream(bytes).use { input ->
                FileOutputStream(plutFile).use { output ->
                    LutConverter.importPlutStrippingRecipe(input, output)
                }
            }
            if (!success) {
                plutFile.delete()
                return null
            }

            val resolvedNameMap = nameMap
                .filter { (language, name) -> language.isNotBlank() && name.isNotBlank() }
                .ifEmpty { mapOf("en" to lutId) }
            saveLutToConfig(
                lutId = lutId,
                nameMap = resolvedNameMap,
                fileName = plutFileName,
                category = sanitizeCustomLutCategory(category),
                isFavorite = isFavorite,
            )
            lutId
        } catch (e: Exception) {
            plutFile.delete()
            PLog.e(TAG, "Failed to import LUT from preset package", e)
            null
        }
    }

    /**
     * 复制 LUT
     *
     * @param lut 要复制的 LUT 信息
     * @param copyName 复制后的显示名称
     * @return 复制成功的 LUT ID，失败返回 null
     */
    fun copyLut(lut: LutInfo, copyName: String): String? {
        return try {
            val lutId = "custom_${UUID.randomUUID()}"
            val plutFileName = "$lutId.plut"
            val plutFile = File(customLutDir, plutFileName)

            if (lut.isBuiltIn) {
                // 如果是内置 LUT，从 assets 复制到 custom 目录
                context.assets.open(lut.fileName).use { inputStream ->
                    FileOutputStream(plutFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } else {
                // 如果是自定义 LUT，直接从文件复制
                val originalFile = File(lut.fileName)
                if (originalFile.exists()) {
                    originalFile.inputStream().use { inputStream ->
                        FileOutputStream(plutFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } else {
                    PLog.e(TAG, "Copy failed: original file not found: ${lut.fileName}")
                    return null
                }
            }

            // 保存到配置文件
            saveLutToConfig(lutId, copyName, plutFileName)

            // 如果原 LUT 有分类，也同步分类
            if (lut.category.isNotEmpty()) {
                updateLutCategory(lutId, lut.category)
            }

            PLog.d(TAG, "LUT copied successfully: $lutId ($copyName)")
            lutId
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to copy LUT", e)
            null
        }
    }

    fun importDcp(uri: Uri, displayName: String? = null): String? {
        return try {
            val fileName = getFileName(uri) ?: "profile_${System.currentTimeMillis()}.dcp"
            val dcpId = "custom_dcp_${UUID.randomUUID()}"
            val normalizedFileName = "$dcpId.dcp"
            val dcpFile = File(customDcpDir, normalizedFileName)

            openInputStream(uri)?.use { inputStream ->
                FileOutputStream(dcpFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null

            val name = displayName ?: fileName.substringBeforeLast('.')
            saveDcpToConfig(dcpId, name, normalizedFileName)
            PLog.d(TAG, "DCP imported successfully: $dcpId ($name)")
            dcpId
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to import DCP", e)
            null
        }
    }

    /** 从预设包导入原始 DCP 文件，并保留预设包中的显示名称。 */
    internal fun importPresetDcp(
        bytes: ByteArray,
        nameMap: Map<String, String>,
    ): String? {
        val dcpId = "custom_dcp_${UUID.randomUUID()}"
        val normalizedFileName = "$dcpId.dcp"
        val dcpFile = File(customDcpDir, normalizedFileName)
        return try {
            require(bytes.isNotEmpty()) { "DCP data is empty" }
            FileOutputStream(dcpFile).use { it.write(bytes) }
            val resolvedNameMap = nameMap
                .filter { (language, name) -> language.isNotBlank() && name.isNotBlank() }
                .ifEmpty { mapOf("en" to dcpId, "zh" to dcpId) }
            saveDcpToConfig(dcpId, resolvedNameMap, normalizedFileName)
            dcpId
        } catch (e: Exception) {
            dcpFile.delete()
            PLog.e(TAG, "Failed to import DCP from preset package", e)
            null
        }
    }

    fun importRawNoiseProfile(uri: Uri, displayName: String? = null): String? {
        var profileFile: File? = null
        return try {
            val fileName = getFileName(uri) ?: return null
            if (!fileName.endsWith(".c", ignoreCase = true)) return null
            val source = openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val text = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(text.length + count <= MAX_RAW_NOISE_PROFILE_CHARS) {
                        "RAW noise profile exceeds the import size limit"
                    }
                    text.append(chunk, 0, count)
                }
                text.toString()
            } ?: return null
            val profileId = "custom_raw_noise_${UUID.randomUUID()}"
            CalibratedRawNoiseProfile.parseGcamC(profileId, source)

            val normalizedFileName = "$profileId.c"
            val destination = File(customRawNoiseProfileDir, normalizedFileName)
            profileFile = destination
            FileOutputStream(destination).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(source)
            }
            val name = displayName ?: fileName.substringBeforeLast('.')
            saveRawNoiseProfileToConfig(profileId, name, normalizedFileName)
            PLog.d(TAG, "RAW noise profile imported successfully: $profileId ($name)")
            profileId
        } catch (e: Exception) {
            profileFile?.delete()
            PLog.e(TAG, "Failed to import RAW noise profile", e)
            null
        }
    }

    /**
     * 导入字体文件
     */
    fun importFont(uri: Uri): String? {
        return try {
            val fileName = getFileName(uri) ?: "font_${UUID.randomUUID()}.ttf"
            val fontFile = File(customFontDir, fileName)

            openInputStream(uri)?.use { inputStream ->
                fontFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null

            fontFile.absolutePath
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to import font", e)
            null
        }
    }

    /**
     * 导入 Logo 文件
     */
    fun importLogo(uri: Uri): String? {
        return try {
            val fileName = getFileName(uri) ?: "logo_${UUID.randomUUID()}.png"
            val logoFile = File(customLogoDir, fileName)

            openInputStream(uri)?.use { inputStream ->
                logoFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return null

            logoFile.absolutePath
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to import logo", e)
            null
        }
    }

    /**
     * 获取所有自定义 LUT
     */
    fun getCustomLuts(): List<LutInfo> {
        return try {
            val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)
            if (!configFile.exists()) {
                return emptyList()
            }

            val configJson = configFile.readText()
            val jsonArray = JSONArray(configJson)

            val lutList = mutableListOf<LutInfo>()
            for (i in 0 until jsonArray.length()) {
                val lutObj = jsonArray.getJSONObject(i)
                val id = lutObj.getString("id")
                val nameObj = lutObj.getJSONObject("name")
                val fileName = lutObj.getString("fileName")

                // 检查文件是否存在
                val lutFile = File(customLutDir, fileName)
                if (!lutFile.exists()) {
                    continue
                }

                val nameMap = mutableMapOf<String, String>()
                nameObj.keys().forEach { lang ->
                    nameMap[lang] = nameObj.getString(lang)
                }

                lutList.add(
                    LutInfo(
                        id = id,
                        nameMap = nameMap,
                        fileName = lutFile.absolutePath,
                        isBuiltIn = false,
                        isDefault = false,
                        isVip = false,
                        category = lutObj.optString("category", ""),
                        isFavorite = lutObj.optBoolean("isFavorite", false)
                    )
                )
            }

            lutList
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load custom LUTs", e)
            emptyList()
        }
    }

    fun getCustomDcps(): List<DcpInfo> {
        return try {
            val configFile = File(context.filesDir, CUSTOM_DCP_CONFIG)
            if (!configFile.exists()) return emptyList()

            val jsonArray = JSONArray(configFile.readText())
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val dcpObject = jsonArray.getJSONObject(index)
                    val id = dcpObject.getString("id")
                    val nameObject = dcpObject.getJSONObject("name")
                    val fileName = dcpObject.getString("fileName")
                    val file = File(customDcpDir, fileName)
                    if (!file.exists()) continue

                    val nameMap = mutableMapOf<String, String>()
                    nameObject.keys().forEach { lang ->
                        nameMap[lang] = nameObject.getString(lang)
                    }

                    add(
                        DcpInfo(
                            id = id,
                            nameMap = nameMap,
                            filePath = file.absolutePath,
                            isBuiltIn = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load custom DCPs", e)
            emptyList()
        }
    }

    fun getCustomRawNoiseProfiles(): List<RawNoiseProfileInfo> {
        return try {
            val configFile = File(context.filesDir, CUSTOM_RAW_NOISE_PROFILE_CONFIG)
            if (!configFile.exists()) return emptyList()
            val jsonArray = JSONArray(configFile.readText())
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val profileObject = jsonArray.getJSONObject(index)
                    val id = profileObject.getString("id")
                    val fileName = profileObject.getString("fileName")
                    val file = File(customRawNoiseProfileDir, fileName)
                    if (!file.exists()) continue
                    val nameObject = profileObject.getJSONObject("name")
                    val nameMap = buildMap {
                        nameObject.keys().forEach { language ->
                            put(language, nameObject.getString(language))
                        }
                    }
                    add(
                        RawNoiseProfileInfo(
                            id = id,
                            nameMap = nameMap,
                            filePath = file.absolutePath,
                            isBuiltIn = false,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to load custom RAW noise profiles", e)
            emptyList()
        }
    }

    /**
     * 更新自定义 LUT 名称
     */
    fun updateLutName(lutId: String, newName: String): Boolean {
        return try {
            val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)
            if (!configFile.exists()) {
                return false
            }

            val configJson = configFile.readText()
            val jsonArray = JSONArray(configJson)
            val newArray = JSONArray()

            var updated = false
            for (i in 0 until jsonArray.length()) {
                val lutObj = jsonArray.getJSONObject(i)
                if (lutObj.getString("id") == lutId) {
                    // 更新名称
                    lutObj.put("name", JSONObject().apply {
                        put("en", newName)
                        put("zh", newName)
                    })
                    updated = true
                }
                newArray.put(lutObj)
            }

            if (updated) {
                configFile.writeText(newArray.toString())
                PLog.d(TAG, "LUT name updated: $lutId -> $newName")
            }

            updated
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to update LUT name", e)
            false
        }
    }

    /**
     * 更新 LUT 分类（支持内置和自定义）
     */
    fun updateLutCategory(lutId: String, newCategory: String): Boolean {
        return try {
            val sanitizedCategory = if (isCustomLutId(lutId)) {
                sanitizeCustomLutCategory(newCategory)
            } else {
                newCategory.trim()
            }

            // 1. 同步到分类重写文件 (核心：支持内置)
            val overridesFile = File(context.filesDir, CATEGORY_OVERRIDES_CONFIG)
            val overridesJson = if (overridesFile.exists()) {
                JSONObject(overridesFile.readText())
            } else {
                JSONObject()
            }
            overridesJson.put(lutId, sanitizedCategory)
            overridesFile.writeText(overridesJson.toString())

            // 2. 如果是自定义滤镜，也同步更新 custom_luts.json (保持一致性)
            val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)
            if (configFile.exists()) {
                val configJson = configFile.readText()
                val jsonArray = JSONArray(configJson)
                val newArray = JSONArray()
                var updated = false
                for (i in 0 until jsonArray.length()) {
                    val lutObj = jsonArray.getJSONObject(i)
                    if (lutObj.getString("id") == lutId) {
                        lutObj.put("category", sanitizedCategory)
                        updated = true
                    }
                    newArray.put(lutObj)
                }
                if (updated) {
                    configFile.writeText(newArray.toString())
                }
            }

            PLog.d(TAG, "LUT category updated: $lutId -> $sanitizedCategory")
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to update LUT category", e)
            false
        }
    }

    /**
     * 更新 LUT 收藏状态（支持内置和自定义）。
     */
    fun updateLutFavorite(lutId: String, isFavorite: Boolean): Boolean {
        return try {
            val overridesFile = File(context.filesDir, FAVORITE_OVERRIDES_CONFIG)
            val overridesJson = if (overridesFile.exists()) {
                JSONObject(overridesFile.readText())
            } else {
                JSONObject()
            }
            overridesJson.put(lutId, isFavorite)
            overridesFile.writeText(overridesJson.toString())

            val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)
            if (configFile.exists()) {
                val configJson = configFile.readText()
                val jsonArray = JSONArray(configJson)
                val newArray = JSONArray()
                var updated = false
                for (i in 0 until jsonArray.length()) {
                    val lutObj = jsonArray.getJSONObject(i)
                    if (lutObj.getString("id") == lutId) {
                        lutObj.put("isFavorite", isFavorite)
                        updated = true
                    }
                    newArray.put(lutObj)
                }
                if (updated) {
                    configFile.writeText(newArray.toString())
                }
            }

            PLog.d(TAG, "LUT favorite updated: $lutId -> $isFavorite")
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to update LUT favorite", e)
            false
        }
    }

    /**
     * 删除自定义 LUT
     */
    fun deleteCustomLut(lutId: String): Boolean {
        return try {
            // 从配置文件中移除
            val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)
            if (configFile.exists()) {
                val configJson = configFile.readText()
                val jsonArray = JSONArray(configJson)
                val newArray = JSONArray()

                var fileName: String? = null
                for (i in 0 until jsonArray.length()) {
                    val lutObj = jsonArray.getJSONObject(i)
                    if (lutObj.getString("id") == lutId) {
                        fileName = lutObj.getString("fileName")
                    } else {
                        newArray.put(lutObj)
                    }
                }

                configFile.writeText(newArray.toString())

                // 删除文件
                fileName?.let {
                    File(customLutDir, it).delete()
                }
            }

            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to delete custom LUT", e)
            false
        }
    }

    /**
     * 删除自定义 DCP
     */
    fun deleteCustomDcp(dcpId: String): Boolean {
        return try {
            val configFile = File(context.filesDir, CUSTOM_DCP_CONFIG)
            if (!configFile.exists()) {
                return false
            }

            val jsonArray = JSONArray(configFile.readText())
            val newArray = JSONArray()

            var fileName: String? = null
            for (i in 0 until jsonArray.length()) {
                val dcpObj = jsonArray.getJSONObject(i)
                if (dcpObj.getString("id") == dcpId) {
                    fileName = dcpObj.getString("fileName")
                } else {
                    newArray.put(dcpObj)
                }
            }

            if (fileName == null) {
                return false
            }

            configFile.writeText(newArray.toString())
            File(customDcpDir, fileName).delete()
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to delete custom DCP", e)
            false
        }
    }

    fun deleteCustomRawNoiseProfile(profileId: String): Boolean {
        return try {
            val configFile = File(context.filesDir, CUSTOM_RAW_NOISE_PROFILE_CONFIG)
            if (!configFile.exists()) return false
            val jsonArray = JSONArray(configFile.readText())
            val updated = JSONArray()
            var fileName: String? = null
            for (index in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(index)
                if (item.getString("id") == profileId) {
                    fileName = item.getString("fileName")
                } else {
                    updated.put(item)
                }
            }
            val resolvedFileName = fileName ?: return false
            configFile.writeText(updated.toString())
            File(customRawNoiseProfileDir, resolvedFileName).delete()
            true
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to delete custom RAW noise profile", e)
            false
        }
    }

    /**
     * 保存 LUT 到配置文件
     */
    private fun saveLutToConfig(lutId: String, name: String, fileName: String, category: String? = null) {
        saveLutToConfig(
            lutId = lutId,
            nameMap = mapOf("en" to name, "zh" to name),
            fileName = fileName,
            category = category,
            isFavorite = false,
        )
    }

    private fun saveLutToConfig(
        lutId: String,
        nameMap: Map<String, String>,
        fileName: String,
        category: String? = null,
        isFavorite: Boolean = false,
    ) {
        val configFile = File(context.filesDir, CUSTOM_LUT_CONFIG)

        val jsonArray = if (configFile.exists()) {
            JSONArray(configFile.readText())
        } else {
            JSONArray()
        }

        val lutObj = JSONObject().apply {
            put("id", lutId)
            put("name", JSONObject().apply {
                nameMap.toSortedMap().forEach { (language, name) ->
                    put(language, name)
                }
            })
            put("fileName", fileName)
            if (!category.isNullOrEmpty()) {
                put("category", category)
            }
            if (isFavorite) {
                put("isFavorite", true)
            }
        }

        jsonArray.put(lutObj)
        configFile.writeText(jsonArray.toString())
    }

    private fun saveDcpToConfig(dcpId: String, name: String, fileName: String) {
        saveDcpToConfig(
            dcpId = dcpId,
            nameMap = mapOf("en" to name, "zh" to name),
            fileName = fileName,
        )
    }

    private fun saveDcpToConfig(
        dcpId: String,
        nameMap: Map<String, String>,
        fileName: String,
    ) {
        val configFile = File(context.filesDir, CUSTOM_DCP_CONFIG)
        val jsonArray = if (configFile.exists()) JSONArray(configFile.readText()) else JSONArray()
        jsonArray.put(
            JSONObject().apply {
                put("id", dcpId)
                put("name", JSONObject().apply {
                    nameMap.toSortedMap().forEach { (language, name) ->
                        put(language, name)
                    }
                })
                put("fileName", fileName)
            }
        )
        configFile.writeText(jsonArray.toString())
    }

    private fun saveRawNoiseProfileToConfig(profileId: String, name: String, fileName: String) {
        val configFile = File(context.filesDir, CUSTOM_RAW_NOISE_PROFILE_CONFIG)
        val jsonArray = if (configFile.exists()) JSONArray(configFile.readText()) else JSONArray()
        jsonArray.put(
            JSONObject().apply {
                put("id", profileId)
                put("name", JSONObject().apply {
                    put("en", name)
                    put("zh", name)
                })
                put("fileName", fileName)
            },
        )
        configFile.writeText(jsonArray.toString())
    }

    /**
     * 从 URI 获取文件名
     */
    private fun getFileName(uri: Uri): String? {
        return resolveDisplayFileName(context, uri)
    }

    private fun openInputStream(uri: Uri): java.io.InputStream? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                java.io.FileInputStream(path)
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            PLog.e(TAG, "Failed to open input stream for $uri", e)
            null
        }
    }
}
