package com.photographercamera.photon.lut

/**
 * Display-referred film grain shared by still, preview and video pipelines.
 *
 * The continuous simplex field follows the structure recovered from Phocus' film-grain
 * shaders, while the density-domain response preserves PhotonMGC's highlight protection
 * and subtle dye-cloud variation.
 */
internal object FilmGrainShaders {
    val FUNCTIONS = """
        uniform float uGrainLumaContrast;
        // grain.size / grain.density：此前 pc_grain（effect.frag）是唯一读取它们的实现，
        // 而该着色器已无 Kotlin 引用（死代码）→ 两个 profile 参数在 APP 内完全不生效。
        // 现在按桌面 tools/profile_renderer.apply_grain 的语义接进实时通路：
        //   size    → 颗粒团块的空间频率（越大=颗粒越粗），默认 1.0 与修复前逐位一致
        //   density → 银盐密度的噪声幅度增益，默认 1.0 与修复前逐位一致
        uniform float uGrainSize;
        uniform float uGrainDensity;
        vec3 grainRandom3(vec3 c) {
            float pos = dot(c, vec3(17.0, 59.4, 15.0));
            pos = mod(pos, 1608.49543864);
            return fract(vec3(262144.0, 32768.0, 2097152.0) * sin(pos)) - 0.5;
        }

        float grainSimplex(vec3 p) {
            const float F3 = 1.0 / 3.0;
            const float G3 = 1.0 / 6.0;
            vec3 s = floor(p + dot(p, vec3(F3)));
            vec3 x = p - s + dot(s, vec3(G3));

            vec3 i1;
            vec3 i2;
            if (x.x >= x.y) {
                if (x.y >= x.z) {
                    i1 = vec3(1.0, 0.0, 0.0);
                    i2 = vec3(1.0, 1.0, 0.0);
                } else if (x.x >= x.z) {
                    i1 = vec3(1.0, 0.0, 0.0);
                    i2 = vec3(1.0, 0.0, 1.0);
                } else {
                    i1 = vec3(0.0, 0.0, 1.0);
                    i2 = vec3(1.0, 0.0, 1.0);
                }
            } else {
                if (x.y < x.z) {
                    i1 = vec3(0.0, 0.0, 1.0);
                    i2 = vec3(0.0, 1.0, 1.0);
                } else if (x.x < x.z) {
                    i1 = vec3(0.0, 1.0, 0.0);
                    i2 = vec3(0.0, 1.0, 1.0);
                } else {
                    i1 = vec3(0.0, 1.0, 0.0);
                    i2 = vec3(1.0, 1.0, 0.0);
                }
            }

            vec3 x1 = x - i1 + G3;
            vec3 x2 = x - i2 + 2.0 * G3;
            vec3 x3 = x - 1.0 + 3.0 * G3;
            vec4 w = max(
                vec4(0.6) - vec4(dot(x, x), dot(x1, x1), dot(x2, x2), dot(x3, x3)),
                0.0
            );
            vec4 w2 = w * w;
            vec4 w4 = w2 * w2;
            vec4 d = vec4(
                dot(grainRandom3(s), x),
                dot(grainRandom3(s + i1), x1),
                dot(grainRandom3(s + i2), x2),
                dot(grainRandom3(s + 1.0), x3)
            );
            return dot(d * w4, vec4(52.0));
        }

        vec3 grainSrgbToLinear(vec3 c) {
            vec3 safe = max(c, vec3(0.0));
            return mix(
                safe / 12.92,
                pow((safe + 0.055) / 1.055, vec3(2.4)),
                step(vec3(0.04045), safe)
            );
        }

        vec3 grainLinearToSrgb(vec3 c) {
            vec3 safe = max(c, vec3(0.0));
            return mix(
                safe * 12.92,
                1.055 * pow(safe, vec3(1.0 / 2.4)) - 0.055,
                step(vec3(0.0031308), safe)
            );
        }

        vec3 applyDensityFilmGrain(
            vec3 srgbColor,
            vec2 outputPixel,
            float amount,
            float frameSeed,
            float pixelScale
        ) {
            float grainAmount = pow(clamp(amount, 0.0, 1.0), 0.58);
            vec3 linearColor = max(grainSrgbToLinear(srgbColor), vec3(1e-4));
            vec3 density = -log(linearColor) * 0.4342944819;
            vec3 densityMin = vec3(0.03);
            vec3 densityMax = vec3(2.23);
            vec3 development = clamp((density + densityMin) / densityMax, vec3(0.02), vec3(0.98));

            float effectivePixelSizeUm = mix(5.2, 1.8, grainAmount);
            vec3 particleScale = vec3(1.6, 1.6, 3.2);
            vec3 particles = (effectivePixelSizeUm * effectivePixelSizeUm) / (0.2 * particleScale);
            vec3 saturation = 1.0 - development * vec3(0.97, 0.99, 0.97);
            vec3 densityStd = densityMax * sqrt(
                max(development * (1.0 - development) * saturation, vec3(0.001)) /
                max(particles, vec3(1.0))
            );

            vec2 seedOffset = vec2(
                fract(frameSeed * 0.754877666),
                fract(frameSeed * 0.569840296)
            ) * 4096.0;
            float seedPlane = mod(frameSeed, 4096.0) * 0.03125;
            // grain.size：颗粒团块尺寸——除以 size 降低空间频率（块更大）。
            // pixelScale 只随渲染分辨率变化，profile 的 size 在此叠加。
            float grainSizeScale = clamp(uGrainSize, 0.25, 4.0);
            vec2 grainPixel = outputPixel / max(pixelScale * grainSizeScale, 0.25) + seedOffset;
            float lumaGrain = grainSimplex(vec3(grainPixel * 0.42, seedPlane));
            float dyeR = grainSimplex(vec3(grainPixel * 0.12, seedPlane + 17.0));
            float dyeB = grainSimplex(vec3(grainPixel * 0.12, seedPlane + 43.0));
            vec3 dyeCloud = vec3(dyeR, (dyeR + dyeB) * 0.5, dyeB);
            float lumaDensityStd = dot(densityStd, vec3(0.333333));
            float positiveLuma = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));
            float highlightMask = smoothstep(0.55, 0.92, positiveLuma);
            // uGrainLumaContrast: 1=强胶片感（保留历史默认 0.32 高光抑制），0=全画面均匀（假噪点）。
            float grainLc = clamp(uGrainLumaContrast, 0.0, 1.0);
            float highlightFloor = mix(1.0, 0.32, grainLc);
            float highlightVisibility = mix(1.0, highlightFloor, highlightMask);
            vec3 densityNoise = vec3(lumaGrain * lumaDensityStd * 2.7);
            densityNoise += dyeCloud * densityStd * 0.22;
            densityNoise *= highlightVisibility;
            // grain.density：银盐密度越高，单位面积颗粒起伏越大 → 幅度增益。
            float grainDensityScale = clamp(uGrainDensity, 0.25, 4.0);
            density = max(density + densityNoise * grainAmount * 1.8 * grainDensityScale, vec3(0.0));
            return grainLinearToSrgb(exp(-density * 2.302585093));
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision highp float;
        precision highp int;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform sampler2D uInputTexture;
        uniform float uAmount;
        uniform float uFrameSeed;
        uniform float uPixelScale;

        $FUNCTIONS

        void main() {
            vec4 source = texture(uInputTexture, vTexCoord);
            vec3 grained = applyDensityFilmGrain(
                source.rgb,
                gl_FragCoord.xy,
                uAmount,
                uFrameSeed,
                uPixelScale
            );
            fragColor = vec4(clamp(grained, 0.0, 1.0), source.a);
        }
    """.trimIndent()

    fun pixelScale(width: Int, height: Int): Float {
        return (minOf(width, height).coerceAtLeast(1) / 1080f).coerceIn(0.5f, 4f)
    }

    fun frameSeed(timestampNs: Long): Float {
        if (timestampNs <= 0L) return 0f
        return ((timestampNs / FRAME_SEED_TICK_NS) % SEED_PERIOD).toFloat()
    }

    fun videoFrameSeed(presentationTimeUs: Long): Float {
        if (presentationTimeUs <= 0L) return 0f
        return frameSeed(presentationTimeUs * 1_000L)
    }

    private const val FRAME_SEED_TICK_NS = 8_333_333L
    private const val SEED_PERIOD = 4096L
}
