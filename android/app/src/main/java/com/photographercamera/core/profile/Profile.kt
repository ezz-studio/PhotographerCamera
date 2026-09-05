/**
 * PhotographerProfile — Kotlin model mirroring [profiles/schema/photographer_profile.schema.json].
 *
 * Design rules (AGENTS.md / docs/profile_spec.md):
 *   - Every layer is a `@Serializable` data class with SCHEMA DEFAULTS, so a JSON
 *     profile that omits a layer still parses and renders (NULL-OBJECT fallback).
 *   - Adding a new photographer NEVER requires touching the renderer: the model
 *     is data-driven and the GPU uniforms are derived from it (see GpuParams.kt).
 *   - `conditions` carries optional lighting-scenario overrides (Phase 31); each
 *     entry is itself a (partial) PhotographerProfile.
 *   - `validationStatus` / `conditions` are metadata, never read by the GPU.
 */
package com.photographercamera.core.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Exposure(
    @SerialName("bias") val bias: Float = 0.0f,
)

@Serializable
data class WhiteBalance(
    @SerialName("temperature_bias") val temperatureBias: Float = 0.0f,
    @SerialName("tint_bias") val tintBias: Float = 0.0f,
)

@Serializable
data class ColorMatrix(
    @SerialName("matrix_3x3") val matrix3x3: List<List<Float>> = DEFAULT_MATRIX,
    @SerialName("input_gamut") val inputGamut: String = "sRGB",
    @SerialName("output_gamut") val outputGamut: String = "sRGB",
)

@Serializable
data class HslColor(
    @SerialName("hue_shift") val hueShift: Float = 0.0f,
    @SerialName("saturation") val saturation: Float = 1.0f,
    @SerialName("lightness") val lightness: Float = 1.0f,
)

@Serializable
data class Hsl(
    @SerialName("red") val red: HslColor = HslColor(),
    @SerialName("orange") val orange: HslColor = HslColor(),
    @SerialName("yellow") val yellow: HslColor = HslColor(),
    @SerialName("green") val green: HslColor = HslColor(),
    @SerialName("cyan") val cyan: HslColor = HslColor(),
    @SerialName("blue") val blue: HslColor = HslColor(),
    @SerialName("purple") val purple: HslColor = HslColor(),
)

@Serializable
data class ToneCurve(
    @SerialName("points") val points: List<List<Float>> = DEFAULT_TONE_POINTS,
)

@Serializable
data class HighlightRolloff(
    @SerialName("threshold") val threshold: Float = 0.8f,
    @SerialName("strength") val strength: Float = 0.0f,
    @SerialName("saturation") val saturation: Float = 1.0f,
)

@Serializable
data class Shadow(
    @SerialName("black_point") val blackPoint: Float = 0.0f,
    @SerialName("compression") val compression: Float = 0.0f,
    @SerialName("tint") val tint: List<Float> = listOf(0f, 0f, 0f),
    @SerialName("saturation") val saturation: Float = 1.0f,
    @SerialName("contrast") val contrast: Float = 1.0f,
)

@Serializable
data class Lens(
    @SerialName("vignette") val vignette: Float = 0.0f,
    @SerialName("chromatic_aberration") val chromaticAberration: Float = 0.0f,
    @SerialName("sharpness_falloff") val sharpnessFalloff: Float = 0.0f,
    @SerialName("distortion") val distortion: Float = 0.0f,
    @SerialName("bloom") val bloom: Float = 0.0f,
    @SerialName("flare") val flare: Float = 0.0f,
)

@Serializable
data class Grain(
    @SerialName("amount") val amount: Float = 0.0f,
    @SerialName("size") val size: Float = 1.0f,
    @SerialName("density") val density: Float = 1.0f,
)

@Serializable
data class Noise(
    @SerialName("luma") val luma: Float = 0.0f,
    @SerialName("chroma") val chroma: Float = 0.0f,
)

@Serializable
data class Halation(
    @SerialName("amount") val amount: Float = 0.0f,
    @SerialName("radius") val radius: Float = 1.0f,
    @SerialName("threshold") val threshold: Float = 0.9f,
    @SerialName("warmth") val warmth: Float = 1.0f,
)

@Serializable
data class Bloom(
    @SerialName("amount") val amount: Float = 0.0f,
    @SerialName("radius") val radius: Float = 1.0f,
    @SerialName("threshold") val threshold: Float = 0.9f,
)

@Serializable
data class Vignette(
    @SerialName("amount") val amount: Float = 0.0f,
    @SerialName("radius") val radius: Float = 1.0f,
    @SerialName("feather") val feather: Float = 0.5f,
    @SerialName("center") val center: List<Float> = listOf(0.5f, 0.5f),
)

@Serializable
data class Sharpen(
    @SerialName("amount") val amount: Float = 0.0f,
    @SerialName("radius") val radius: Float = 1.0f,
)

/**
 * FilmCurve — the output-consistency layer applied as the LAST stage of the GPU
 * chain. It is NOT a fixed +N adjustment: it is a soft-knee remap that adapts to
 * each image's own histogram — anything above the highlight knee rolls off toward
 * [highlightCeiling], anything below the shadow knee is lifted toward
 * [shadowFloor]. The result: every photo saved with this profile shares the SAME
 * highlight ceiling (248), the SAME shadow floor (8) and therefore a consistent
 * metering / brightness / curve signature. Units are 0-255 display levels.
 */
@Serializable
data class FilmCurve(
    @SerialName("shadow_floor") val shadowFloor: Float = 8.0f,
    @SerialName("highlight_ceiling") val highlightCeiling: Float = 248.0f,
)

/**
 * Optional presentation metadata for the preset list UI (never read by the GPU).
 * Studio writes it from the "滤镜简介 / 滤镜图标" fields; `icon` is the file
 * extension of the sibling icon image (<profile_file_name>.<icon>) shipped in
 * the same folder as the profile JSON (assets/profiles or filesDir/profiles).
 */
@Serializable
data class Display(
    @SerialName("name") val name: String = "",
    @SerialName("intro") val intro: String = "",
    @SerialName("icon") val icon: String = "",
)

@Serializable
data class PhotographerProfile(
    @SerialName("version") val version: Int = 1,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("name") val name: String = "Untitled",
    @SerialName("author") val author: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("validation_status")
    @Suppress("EnumEntryName")
    val validationStatus: String = "pending",
    @SerialName("display") val display: Display? = null,
    @SerialName("conditions") val conditions: Map<String, PhotographerProfile> = emptyMap(),
    @SerialName("exposure") val exposure: Exposure = Exposure(),
    @SerialName("white_balance") val whiteBalance: WhiteBalance = WhiteBalance(),
    @SerialName("color_matrix") val colorMatrix: ColorMatrix = ColorMatrix(),
    @SerialName("hsl") val hsl: Hsl = Hsl(),
    @SerialName("tone_curve") val toneCurve: ToneCurve = ToneCurve(),
    @SerialName("highlight_rolloff") val highlightRolloff: HighlightRolloff = HighlightRolloff(),
    @SerialName("shadow") val shadow: Shadow = Shadow(),
    @SerialName("lens") val lens: Lens = Lens(),
    @SerialName("grain") val grain: Grain = Grain(),
    @SerialName("noise") val noise: Noise = Noise(),
    @SerialName("halation") val halation: Halation = Halation(),
    @SerialName("bloom") val bloom: Bloom = Bloom(),
    @SerialName("vignette") val vignette: Vignette = Vignette(),
    @SerialName("sharpen") val sharpen: Sharpen = Sharpen(),
    @SerialName("film_curve") val filmCurve: FilmCurve = FilmCurve(),
) {
    companion object {
        val IDENTITY = PhotographerProfile()
    }
}

val DEFAULT_MATRIX: List<List<Float>> = listOf(
    listOf(1f, 0f, 0f),
    listOf(0f, 1f, 0f),
    listOf(0f, 0f, 1f),
)

val DEFAULT_TONE_POINTS: List<List<Float>> = listOf(
    listOf(0f, 0f),
    listOf(1f, 1f),
)
