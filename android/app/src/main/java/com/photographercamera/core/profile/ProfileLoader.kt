/**
 * ProfileLoader — single source of truth for PhotographerProfile lifecycle on device.
 *
 * Responsibilities (docs/PHASE23-33_OVERVIEW.md, AGENTS.md rule 12: no AI runtime):
 *   1. Load bundled profiles from `assets/profiles` (any *.json; and user-imported ones
 *      from the cache dir).
 *   2. Schema + RANGE validate each profile; refuse out-of-range values.
 *   3. Resolve optional lighting `conditions` (mergeConditions).
 *   4. Materialise a [com.photographercamera.core.gpu.GpuParams] (includes the
 *      baked tone/hsl LUTs) for the renderer. LUTs are cached per (profile, aspect).
 *   5. Import / Export profiles as standalone JSON files (storage-access framework).
 *   6. Maintain a name->profile registry for instant switching.
 *
 * Adding a photographer = drop a JSON into assets/profiles (or Import it). No change
 * to this loader or the renderer is required.
 */
package com.photographercamera.core.profile

import android.content.Context
import android.net.Uri
import com.photographercamera.core.gpu.GpuParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ProfileValidationException(message: String) : Exception(message)

object ProfileLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    private val registry = LinkedHashMap<String, PhotographerProfile>()
    private val gpuCache = LinkedHashMap<String, GpuParams>()

    /** Scan assets/profiles + cache dir; populate the registry. Call once at startup. */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        registry.clear()
        gpuCache.clear()
        val am = context.assets
        runCatching {
            am.list("profiles")?.forEach { name ->
                if (name.endsWith(".json", ignoreCase = true)) {
                    val text = am.open("profiles/$name").bufferedReader().use { it.readText() }
                    loadFromText(text, nameWithoutExt(name))
                }
            }
        }
        // user-imported / exported-to-cache profiles persist across sessions
        val cacheDir = profilesCacheDir(context)
        cacheDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }?.forEach { f ->
            runCatching { loadFromText(f.readText(), f.nameWithoutExtension) }
        }
    }

    /** Parse + validate + register a profile. Throws ProfileValidationException on range error. */
    fun loadFromText(text: String, id: String): PhotographerProfile {
        val p = json.decodeFromString(PhotographerProfile.serializer(), text)
        val res = validateProfile(p)
        if (!res.ok) throw ProfileValidationException("Profile '$id' invalid: ${res.message}")
        registry[id] = p
        return p
    }

    fun listProfiles(): List<String> = registry.keys.toList()

    fun getProfile(id: String): PhotographerProfile? = registry[id]

    /**
     * Resolve the sibling icon image for a profile (`<id>.<display.icon>`,
     * shipped next to the JSON by Studio). Returns `"assets://<name>"` for a
     * bundled asset, an absolute file path for a user-imported profile, or
     * null when the profile has no icon.
     */
    fun iconPath(id: String, context: Context): String? {
        val ext = registry[id]?.display?.icon?.takeIf { it.isNotBlank() } ?: return null
        val name = "$id.$ext"
        runCatching {
            if (context.assets.list("profiles")?.contains(name) == true) return "assets://$name"
        }
        val f = File(profilesCacheDir(context), name)
        if (f.exists()) return f.absolutePath
        return null
    }

    /** Resolve a profile for a (optional) lighting scenario, then bake GpuParams. */
    fun toGpuParams(id: String, scenario: String? = null, aspect: Float = 1.0f): GpuParams {
        val base = registry[id] ?: throw IllegalArgumentException("Unknown profile: $id")
        val resolved = mergeConditions(base, scenario)
        val cacheKey = "$id|$scenario|$aspect"
        return gpuCache.getOrPut(cacheKey) { GpuParams.from(resolved, aspect) }
    }

    // ---- Import / Export -----------------------------------------------------

    /** Import a user-picked JSON file (SAF Uri) into the registry and cache. */
    suspend fun import(context: Context, uri: Uri, id: String? = null): String = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Cannot read $uri")
        val name = id ?: (uri.lastPathSegment?.substringBeforeLast('.') ?: "imported")
        loadFromText(text, name)
        exportToCache(context, registry[name]!!, name)
        name
    }

    /** Export a registered profile to the app-private cache dir as `<id>.json`. */
    suspend fun exportToCache(context: Context, profile: PhotographerProfile, id: String) =
        withContext(Dispatchers.IO) {
            val file = File(profilesCacheDir(context), "$id.json")
            file.writeText(json.encodeToString(PhotographerProfile.serializer(), profile))
        }

    /** Export a registered profile to an arbitrary SAF Uri chosen by the user. */
    suspend fun exportToUri(context: Context, uri: Uri, id: String) = withContext(Dispatchers.IO) {
        val p = registry[id] ?: throw IllegalArgumentException("Unknown profile: $id")
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write(json.encodeToString(PhotographerProfile.serializer(), p))
        } ?: throw IllegalArgumentException("Cannot write $uri")
    }

    private fun profilesCacheDir(context: Context): File {
        val dir = File(context.filesDir, "profiles")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun nameWithoutExt(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }
}
