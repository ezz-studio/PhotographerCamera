/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.processor/DenoiseStrength.kt
 * Licensed under the Apache License, Version 2.0. Algorithm/shader code unchanged.
 */
package com.photographercamera.core.photon.color

/** Shared user-facing strength contract for luma and chroma denoise. */
object DenoiseStrength {
    const val MIN_VALUE = 0.0f
    const val MAX_VALUE = 2.0f

    val valueRange: ClosedFloatingPointRange<Float>
        get() = MIN_VALUE..MAX_VALUE

    fun clamp(value: Float?): Float = value
        ?.takeIf { it.isFinite() }
        ?.coerceIn(MIN_VALUE, MAX_VALUE)
        ?: MIN_VALUE

    /**
     * Bitmap NLM reaches a full output mix at 1. Values above 1 strengthen its
     * assumed noise standard deviation, so the corresponding variance is squared.
     */
    fun noiseVarianceScale(value: Float): Float {
        val sigmaScale = clamp(value).coerceAtLeast(1.0f)
        return sigmaScale * sigmaScale
    }

    fun outputMix(value: Float): Float = clamp(value).coerceAtMost(1.0f)
}
