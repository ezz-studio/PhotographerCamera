/*
 * Ported from PhotonCamera (https://github.com/bjzhou/PhotonCamera)
 * original: com.hinnka.mycamera.lut/BasicToneLutShader.kt
 * Licensed under the Apache License, Version 2.0.
 */
package com.photographercamera.core.photon.color

/**
 * Shader-only Basic Tone module shared with the standalone MGC hook.
 *
 * Texture ownership and asset loading stay in [BasicToneLut] so the hook can compile this
 * source without depending on Photon app runtime classes.
 */
internal object BasicToneLutShader {
    val GLSL = """
        uniform mediump sampler3D uBasicToneLut;
        uniform float uBasicToneIntensity;

        vec3 applyBasicToneLut(vec3 color) {
            float intensity = clamp(uBasicToneIntensity, 0.0, 1.0);
            if (intensity < 0.001) {
                return color;
            }
            const float lutSize = 32.0;
            float scale = (lutSize - 1.0) / lutSize;
            float offset = 1.0 / (2.0 * lutSize);
            vec3 coordinate = clamp(color, 0.0, 1.0) * scale + offset;
            vec3 endpointColor = texture(uBasicToneLut, coordinate).rgb;
            return mix(color, endpointColor, intensity);
        }
    """.trimIndent()
}
