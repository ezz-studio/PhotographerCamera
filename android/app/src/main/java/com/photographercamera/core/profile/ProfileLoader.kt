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
import com.photographercamera.core.debug.DebugLog
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

    // Idempotency guard: init() used to clear() + rescan the registry on EVERY
    // CameraScreen entry (the screen keeps `profiles` as remember-scoped state,
    // so it looks empty again after leaving). While the async rescan ran,
    // toGpuParams() could hit the cleared registry and throw "Unknown profile"
    // — the preset name logged "applied" but the GL params were never set.
    // After the first load the registry is PERMANENT; re-init is a no-op.
    @Volatile private var initialized = false

    /** Scan assets/profiles + cache dir; populate the registry. Call once at startup. */
    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (initialized) {
            DebugLog.log("PROFILE", "init skipped (registry cached: ${registry.size} profiles)")
            return@withContext
        }
        registry.clear()
        gpuCache.clear()
        val hiddenBundled = deletedBundledSet(context)
        val am = context.assets
        runCatching {
            am.list("profiles")?.forEach { name ->
                if (name.endsWith(".json", ignoreCase = true) &&
                    name.removeSuffix(".json") !in hiddenBundled
                ) {
                    val text = am.open("profiles/$name").bufferedReader().use { it.readText() }
                    // Each profile may fail independently — log it (never throw, so one
                    // bad preset can't blank out the whole list on a real device).
                    runCatching { loadFromText(text, nameWithoutExt(name)) }
                        .onFailure { e -> DebugLog.logError("PROFILE", "load bundled '$name' failed", e) }
                }
            }
        }.onFailure { e -> DebugLog.logError("PROFILE", "scan assets/profiles failed", e) }
        // user-imported / exported-to-cache profiles persist across sessions
        val cacheDir = profilesCacheDir(context)
        cacheDir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }?.forEach { f ->
            runCatching { loadFromText(f.readText(), f.nameWithoutExtension) }
                .onFailure { e -> DebugLog.logError("PROFILE", "load cached '${f.name}' failed", e) }
        }
        DebugLog.log("PROFILE", "init done: ${registry.size} profiles loaded")
        initialized = true
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

    /**
     * Import a user-picked icon image (SAF uri) for a profile. Saved as
     * `<id>.<png|jpg|webp>` in the private profiles dir — the same location
     * iconPath()/deleteProfile already manage.
     */
    suspend fun importIcon(context: Context, uri: Uri, id: String): Boolean =
        withContext(Dispatchers.IO) {
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            runCatching {
                val dest = File(profilesCacheDir(context), "$id.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: return@withContext false
                DebugLog.log("PROFILE", "icon imported for '$id' -> ${dest.name}")
                true
            }.getOrDefault(false)
        }

    // ---- Public inbox import / delete ---------------------------------------

    /**
     * Public inbox folder on shared storage: `/storage/emulated/0/PhotographerCamera`.
     * Users drop `<name>.json` (+ optional same-name `<name>.<png|jpg|...>` icon)
     * here from a PC or file manager; the app copies them into its private dir.
     */
    fun publicInboxDir(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "PhotographerCamera")

    /**
     * Scan the public inbox, validate + copy every profile JSON (and its
     * same-name icon) into the private profiles dir, then register it.
     * Files are COPIED, never moved — the user keeps their originals.
     * Without All-Files-Access the inbox simply reads as absent (logged).
     */
    suspend fun importFromPublicInbox(context: Context): List<String> = withContext(Dispatchers.IO) {
        val inbox = publicInboxDir()
        if (!inbox.isDirectory) {
            DebugLog.log("PROFILE", "public inbox absent: ${inbox.path}")
            return@withContext emptyList()
        }
        val dest = profilesCacheDir(context)
        val imported = mutableListOf<String>()
        inbox.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
            ?.forEach { f ->
                val id = f.nameWithoutExtension
                runCatching {
                    val text = f.readText()
                    loadFromText(text, id) // validates BEFORE anything is copied
                    f.copyTo(File(dest, "$id.json"), overwrite = true)
                    copyInboxIcon(f, id, dest)
                    imported += id
                }.onFailure { e -> DebugLog.logError("PROFILE", "inbox import '${f.name}' failed", e) }
            }
        DebugLog.log(
            "PROFILE",
            "public inbox: ${imported.size} imported ${imported} (scan=${inbox.path})",
        )
        imported
    }

    /** Copy `<id>.<iconExt>` declared in the profile's display.icon (probe common exts). */
    private fun copyInboxIcon(jsonFile: File, id: String, dest: File) {
        val declared = registry[id]?.display?.icon?.takeIf { it.isNotBlank() }
        val exts = listOfNotNull(declared, "png", "jpg", "jpeg", "webp").distinct()
        for (ext in exts) {
            val icon = File(jsonFile.parentFile, "$id.$ext")
            if (icon.isFile) {
                icon.copyTo(File(dest, "$id.$ext"), overwrite = true)
                return
            }
        }
    }

    /** True when the profile is bundled in assets (bundled ones cannot be deleted). */
    fun isBundled(id: String, context: Context): Boolean =
        runCatching { context.assets.list("profiles")?.contains("$id.json") == true }
            .getOrDefault(false)

    /**
     * Delete a profile: removes its JSON + icon from the private dir and drops
     * it from the registry. Bundled (assets) profiles cannot have their files
     * removed, so they are persisted to a "deleted bundled" set (sp) and
     * filtered from the registry on every init — the user sees them gone.
     */
    suspend fun deleteProfile(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        if (isBundled(id, context)) {
            deletedBundledPrefs(context).edit()
                .putStringSet("deleted_bundled_presets", deletedBundledSet(context) + id)
                .apply()
            registry.remove(id)
            gpuCache.keys.removeAll { it.startsWith("$id|") }
            DebugLog.log("PROFILE", "delete '$id' -> bundled hidden")
            return@withContext true
        }
        val dir = profilesCacheDir(context)
        var removed = false
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == id }?.forEach { f ->
            removed = f.delete() || removed
        }
        if (removed) {
            registry.remove(id)
            gpuCache.keys.removeAll { it.startsWith("$id|") }
        }
        DebugLog.log("PROFILE", "delete '$id' -> removed=$removed")
        removed
    }

    /** Restore all bundled presets hidden by deleteProfile (settings "restore" path). */
    suspend fun restoreBundled(context: Context) = withContext(Dispatchers.IO) {
        deletedBundledPrefs(context).edit()
            .putStringSet("deleted_bundled_presets", emptySet())
            .apply()
    }

    /** SharedPreferences-backed set of bundled profile ids the user deleted. */
    private fun deletedBundledPrefs(context: Context) =
        context.getSharedPreferences("pc_settings", android.content.Context.MODE_PRIVATE)

    private fun deletedBundledSet(context: Context): Set<String> =
        deletedBundledPrefs(context).getStringSet("deleted_bundled_presets", emptySet()) ?: emptySet()

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
