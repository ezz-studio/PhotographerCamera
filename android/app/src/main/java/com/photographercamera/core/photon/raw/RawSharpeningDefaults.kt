package com.photographercamera.core.photon.raw

object RawSharpeningDefaults {
    // 用户规格：RAW MAX 默认锐度 0.5（上游原默认 0.4）
    const val DEFAULT_STRENGTH = 0.5f
    const val ALGORITHM_STRENGTH_SCALE = 2f
    const val MAX_ALGORITHM_STRENGTH = 2f

    fun normalize(value: Float): Float = if (value.isFinite()) {
        value.coerceIn(0f, 1f)
    } else {
        DEFAULT_STRENGTH
    }

    /** Maps the persisted/UI 0..1 RAW sharpening control onto the algorithm's 0..2 domain. */
    fun toAlgorithmStrength(value: Float): Float =
        normalize(value) * ALGORITHM_STRENGTH_SCALE
}
