/**
 * Range validation for PhotographerProfile.
 *
 * The offline pipeline already gates profiles via profile_validator.py; the Android
 * side re-validates defensively at load time so a malformed or hand-edited profile
 * (e.g. an out-of-range color-matrix or negative radius) cannot crash the GPU pass.
 * Ranges are kept in lock-step with profiles/schema/photographer_profile.schema.json;
 * the model's own defaults act as the floor when a field is absent.
 */
package com.photographercamera.core.profile

data class FieldError(val path: String, val value: Float, val min: Float, val max: Float)

data class ValidationResult(
    val ok: Boolean,
    val errors: List<FieldError>,
) {
    val message: String get() = errors.joinToString("; ") { e ->
        "${e.path}=${e.value} not in [${e.min},${e.max}]"
    }
}

private fun check(out: MutableList<FieldError>, path: String, v: Float, min: Float, max: Float) {
    if (v < min || v > max) out.add(FieldError(path, v, min, max))
}

fun validateProfile(p: PhotographerProfile): ValidationResult {
    val errs = mutableListOf<FieldError>()
    check(errs, "exposure.bias", p.exposure.bias, -2f, 2f)
    check(errs, "white_balance.temperature_bias", p.whiteBalance.temperatureBias, -1f, 1f)
    check(errs, "white_balance.tint_bias", p.whiteBalance.tintBias, -1f, 1f)

    p.colorMatrix.matrix3x3.forEachIndexed { r, row ->
        row.forEachIndexed { c, v ->
            check(errs, "color_matrix.matrix_3x3[$r][$c]", v, -2f, 2f)
        }
    }
    if (p.colorMatrix.matrix3x3.size != 3 ||
        p.colorMatrix.matrix3x3.any { it.size != 3 }
    ) {
        errs.add(FieldError("color_matrix.matrix_3x3", 0f, 3f, 3f))
    }

    p.hsl.let { h ->
        listOf(
            "hsl.red" to h.red,
            "hsl.orange" to h.orange,
            "hsl.yellow" to h.yellow,
            "hsl.green" to h.green,
            "hsl.cyan" to h.cyan,
            "hsl.blue" to h.blue,
            "hsl.purple" to h.purple,
        ).forEach { (name, c) ->
            check(errs, "$name.hue_shift", c.hueShift, -30f, 30f)
            check(errs, "$name.saturation", c.saturation, 0f, 2f)
            check(errs, "$name.lightness", c.lightness, 0f, 2f)
        }
    }

    for ((i, pt) in p.toneCurve.points.withIndex()) {
        if (pt.size == 2) {
            check(errs, "tone_curve.points[$i].x", pt[0], 0f, 1f)
            check(errs, "tone_curve.points[$i].y", pt[1], 0f, 1f)
        }
    }

    check(errs, "highlight_rolloff.threshold", p.highlightRolloff.threshold, 0f, 1f)
    check(errs, "highlight_rolloff.strength", p.highlightRolloff.strength, 0f, 1f)
    check(errs, "highlight_rolloff.saturation", p.highlightRolloff.saturation, 0f, 2f)

    check(errs, "shadow.black_point", p.shadow.blackPoint, 0f, 0.2f)
    check(errs, "shadow.compression", p.shadow.compression, 0f, 1f)
    check(errs, "shadow.saturation", p.shadow.saturation, 0f, 2f)
    check(errs, "shadow.contrast", p.shadow.contrast, 0f, 2f)

    check(errs, "lens.vignette", p.lens.vignette, 0f, 1f)
    check(errs, "lens.chromatic_aberration", p.lens.chromaticAberration, 0f, 1f)
    check(errs, "lens.sharpness_falloff", p.lens.sharpnessFalloff, 0f, 1f)
    check(errs, "lens.distortion", p.lens.distortion, -1f, 1f)
    check(errs, "lens.bloom", p.lens.bloom, 0f, 1f)
    check(errs, "lens.flare", p.lens.flare, 0f, 1f)

    check(errs, "grain.amount", p.grain.amount, 0f, 1f)
    check(errs, "grain.size", p.grain.size, 0.5f, 3f)
    check(errs, "grain.density", p.grain.density, 0.5f, 3f)

    check(errs, "noise.luma", p.noise.luma, 0f, 1f)
    check(errs, "noise.chroma", p.noise.chroma, 0f, 1f)

    check(errs, "halation.amount", p.halation.amount, 0f, 1f)
    check(errs, "halation.radius", p.halation.radius, 0.5f, 4f)
    check(errs, "halation.threshold", p.halation.threshold, 0f, 1f)
    check(errs, "halation.warmth", p.halation.warmth, 0f, 2f)

    check(errs, "bloom.amount", p.bloom.amount, 0f, 1f)
    check(errs, "bloom.radius", p.bloom.radius, 0.5f, 4f)
    check(errs, "bloom.threshold", p.bloom.threshold, 0f, 1f)

    check(errs, "vignette.amount", p.vignette.amount, 0f, 1f)
    check(errs, "vignette.radius", p.vignette.radius, 0f, 1f)
    check(errs, "vignette.feather", p.vignette.feather, 0f, 1f)

    check(errs, "sharpen.amount", p.sharpen.amount, 0f, 1f)
    check(errs, "sharpen.radius", p.sharpen.radius, 0.5f, 3f)

    // film_curve: 0-255 display levels (LAST chain pass; see shaders/film_curve.frag)
    check(errs, "film_curve.shadow_floor", p.filmCurve.shadowFloor, 0f, 64f)
    check(errs, "film_curve.highlight_ceiling", p.filmCurve.highlightCeiling, 191f, 255f)

    return ValidationResult(errs.isEmpty(), errs)
}
