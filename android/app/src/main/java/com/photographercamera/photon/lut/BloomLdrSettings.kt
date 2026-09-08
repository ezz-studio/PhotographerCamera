package com.photographercamera.photon.lut

internal object BloomLdrSettings {
    const val MAX_MIP_DIMENSION = 512
    const val MIP_COUNT = 10

    private const val HIGHLIGHT_THRESHOLD = 0.9f
    private const val HIGHLIGHT_THRESHOLD_SOFTNESS = 0.2f
    private const val MAX_COMPOSITE_STRENGTH = 0.8f

    /**
     * 0.9.17：profile.bloom 的 threshold/radius 覆盖（对齐桌面端 apply_bloom）。
     * 从 FilmParamsStore 读取；未注入 profile 时返回 null → 保持上游默认语义。
     */
    private fun profileBloomThreshold(): Float? {
        val fp = com.photographercamera.core.photon.color.FilmParamsStore.current
        return if (fp.profileActive) fp.bloomThreshold?.coerceIn(0f, 1f) else null
    }

    /** 桌面 bloom.radius(0.5..4) → 0..1 mip 选择因子（radius=1 → 0.5 中性）。 */
    private fun profileBloomRadiusFactor(): Float? {
        val fp = com.photographercamera.core.photon.color.FilmParamsStore.current
        if (!fp.profileActive) return null
        return fp.bloomRadius?.let { ((it - 1f) / 3f + 0.5f).coerceIn(0f, 1f) }
    }

    fun thresholdPrecomputations(): FloatArray {
        val threshold = profileBloomThreshold() ?: HIGHLIGHT_THRESHOLD
        val knee = threshold * HIGHLIGHT_THRESHOLD_SOFTNESS.coerceIn(0f, 1f)
        return floatArrayOf(
            threshold,
            threshold - knee,
            2.0f * knee,
            0.25f / (knee + 0.00001f)
        )
    }

    fun mipAddWeight(sourceMip: Int, mipCount: Int, bloom: Float): Float {
        if (mipCount <= 1) return 1f
        val radius = profileBloomRadiusFactor() ?: smooth01(bloom.coerceIn(0f, 1f))
        val mipPosition = sourceMip.toFloat() / (mipCount - 1).toFloat()
        val localDamping = 0.45f + mipPosition * 0.75f
        val radiusBoost = 0.7f + radius * 0.8f
        return (localDamping * radiusBoost).coerceIn(0.25f, 1.5f)
    }

    fun compositeMipLowerIndex(mipCount: Int, bloom: Float): Int {
        return compositeMipPosition(mipCount, bloom).toInt()
    }

    fun compositeMipUpperIndex(mipCount: Int, bloom: Float): Int {
        val lower = compositeMipLowerIndex(mipCount, bloom)
        val maxMip = (mipCount - 1).coerceAtLeast(0)
        return (lower + 1).coerceAtMost(maxMip)
    }

    fun compositeMipBlend(mipCount: Int, bloom: Float): Float {
        val position = compositeMipPosition(mipCount, bloom)
        return (position - position.toInt().toFloat()).coerceIn(0f, 1f)
    }

    fun compositeStrength(bloom: Float): Float {
        val strength = bloom.coerceIn(0f, 1f)
        return (strength * strength * MAX_COMPOSITE_STRENGTH).coerceIn(0f, 1f)
    }

    private fun compositeMipPosition(mipCount: Int, bloom: Float): Float {
        val maxMip = (mipCount - 1).coerceAtLeast(0)
        if (maxMip == 0) return 0f
        val maxSelectableMip = minOf(maxMip, 5)
        val radius = profileBloomRadiusFactor() ?: smooth01(bloom.coerceIn(0f, 1f))
        return radius * maxSelectableMip.toFloat()
    }

    private fun smooth01(value: Float): Float {
        return value * value * (3f - 2f * value)
    }
}
