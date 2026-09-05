/**
 * Condition merging (Phase 31). A profile may carry optional per-scenario overrides
 * under `conditions` keyed by lighting scenario (Daylight/Cloudy/Tungsten/Low Light/
 * Flash/Backlight). Each override is a *partial* profile; merge overlays only the
 * layers the override actually sets (i.e. differs from its schema default), leaving
 * the base profile's other layers untouched.
 *
 * Conservative by design: an override must opt in per-layer. This keeps a newly added
 * photographer (who ships no conditions) rendering identically to their base profile.
 */
package com.photographercamera.core.profile

private fun <T> orDefault(override: T, base: T, default: T): T =
    if (override == default) base else override

fun mergeConditions(base: PhotographerProfile, scenario: String?): PhotographerProfile {
    val ov = scenario?.let { base.conditions[it] } ?: return base
    val d = PhotographerProfile()
    return base.copy(
        exposure = if (ov.exposure == d.exposure) base.exposure else ov.exposure,
        whiteBalance = if (ov.whiteBalance == d.whiteBalance) base.whiteBalance else ov.whiteBalance,
        colorMatrix = if (ov.colorMatrix == d.colorMatrix) base.colorMatrix else ov.colorMatrix,
        hsl = if (ov.hsl == d.hsl) base.hsl else ov.hsl,
        toneCurve = if (ov.toneCurve == d.toneCurve) base.toneCurve else ov.toneCurve,
        highlightRolloff = if (ov.highlightRolloff == d.highlightRolloff) base.highlightRolloff else ov.highlightRolloff,
        shadow = if (ov.shadow == d.shadow) base.shadow else ov.shadow,
        lens = if (ov.lens == d.lens) base.lens else ov.lens,
        grain = if (ov.grain == d.grain) base.grain else ov.grain,
        noise = if (ov.noise == d.noise) base.noise else ov.noise,
        halation = if (ov.halation == d.halation) base.halation else ov.halation,
        bloom = if (ov.bloom == d.bloom) base.bloom else ov.bloom,
        vignette = if (ov.vignette == d.vignette) base.vignette else ov.vignette,
        sharpen = if (ov.sharpen == d.sharpen) base.sharpen else ov.sharpen,
    )
}
