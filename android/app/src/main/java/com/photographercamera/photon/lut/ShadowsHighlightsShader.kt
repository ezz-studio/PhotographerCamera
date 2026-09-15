package com.photographercamera.photon.lut

import android.opengl.GLES30

object ShadowsHighlightsShader {
    fun bindUniforms(program: Int, highlights: Float, shadows: Float) {
        bindUniformLocations(
            highlightsLocation = GLES30.glGetUniformLocation(program, "uHighlights"),
            shadowsLocation = GLES30.glGetUniformLocation(program, "uShadows"),
            highlights = highlights,
            shadows = shadows
        )
    }

    fun bindUniformLocations(
        highlightsLocation: Int,
        shadowsLocation: Int,
        highlights: Float,
        shadows: Float
    ) {
        GLES30.glUniform1f(highlightsLocation, highlights)
        GLES30.glUniform1f(shadowsLocation, shadows)
    }

    val GLSL = """
        const float SH_LAB_EPSILON = 216.0 / 24389.0;
        const float SH_LAB_KAPPA = 24389.0 / 27.0;
        const float SH_LOW_APPROX = 0.000001;
        const float SH_COMPRESS = 0.5;
        const float SH_HIGHLIGHTS_COLOR_ADJUSTMENT = 0.6;
        const float SH_SHADOWS_COLOR_ADJUSTMENT = 1.0;
        const float SH_RANGE_SIGMA = 0.105;
        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;

        float shSanitizeFloat(float value) {
            if (value != value) return 0.0;
            return value;
        }

        vec3 shSanitizeColor(vec3 color) {
            return vec3(
                shSanitizeFloat(color.r),
                shSanitizeFloat(color.g),
                shSanitizeFloat(color.b)
            );
        }

        float shSign(float value) {
            return value < 0.0 ? -1.0 : 1.0;
        }

        float shSignedInv(float value, float signSource) {
            float inv = abs(value) > SH_LOW_APPROX ? 1.0 / abs(value) : 1.0 / SH_LOW_APPROX;
            return signSource < 0.0 ? -inv : inv;
        }

        float shColorCorrection(float adjustment, float signSource) {
            return (clamp(adjustment, 0.0, 1.0) - 0.5) * shSign(signSource) + 0.5;
        }

        vec3 shLabF(vec3 value) {
            vec3 linearPart = (SH_LAB_KAPPA * value + vec3(16.0)) / 116.0;
            vec3 cubePart = pow(max(value, vec3(0.0)), vec3(1.0 / 3.0));
            return mix(linearPart, cubePart, step(vec3(SH_LAB_EPSILON), value));
        }

        vec3 shLabFInv(vec3 value) {
            vec3 cubePart = value * value * value;
            vec3 linearPart = (116.0 * value - vec3(16.0)) / SH_LAB_KAPPA;
            return mix(linearPart, cubePart, step(vec3(0.20689655172413796), value));
        }

        vec3 shXyzToLab(vec3 xyz) {
            vec3 f = shLabF(xyz / vec3(0.9642, 1.0, 0.8249));
            return vec3(116.0 * f.y - 16.0, 500.0 * (f.x - f.y), 200.0 * (f.y - f.z));
        }

        vec3 shLabToXyz(vec3 lab) {
            float fy = (lab.x + 16.0) / 116.0;
            vec3 f = vec3(lab.y / 500.0 + fy, fy, fy - lab.z / 200.0);
            return vec3(0.9642, 1.0, 0.8249) * shLabFInv(f);
        }

        vec3 shRgbToLabScaled(vec3 color) {
            vec3 lab = shXyzToLab(shRgbToXyz(color));
            return lab / vec3(100.0, 128.0, 128.0);
        }

        vec3 shLabScaledToRgb(vec3 labScaled) {
            vec3 lab = labScaled * vec3(100.0, 128.0, 128.0);
            return shXyzToRgb(shLabToXyz(lab));
        }

        float shTonalRangeWeight(float sampleL, float centerL) {
            float delta = sampleL - centerL;
            float bilateral = exp(-(delta * delta) / max(2.0 * SH_RANGE_SIGMA2, SH_LOW_APPROX));
            float edgeStop = 1.0 - smoothstep(0.12, 0.24, abs(delta));
            return bilateral * edgeStop;
        }

        void shAddBaseSample(vec2 uv, float centerL, float spatialWeight, inout float sum, inout float weightSum) {
            float sampleL = shRgbToLabScaled(sampleToneSource(clamp(uv, vec2(0.0), vec2(1.0)))).x;
            float weight = spatialWeight * shTonalRangeWeight(sampleL, centerL);
            sum += sampleL * weight;
            weightSum += weight;
        }

        vec2 shPixelOffset(float x, float y) {
            return vec2(x * uTexelSize.x, y * uTexelSize.y);
        }

        void shAddBaseSamplePair(
            vec2 uv,
            vec2 offset,
            float centerL,
            float spatialWeight,
            inout float sum,
            inout float weightSum
        ) {
            shAddBaseSample(uv + offset, centerL, spatialWeight, sum, weightSum);
            shAddBaseSample(uv - offset, centerL, spatialWeight, sum, weightSum);
        }

        float shSampleBaseL(vec2 uv, vec3 centerLab) {
            float centerL = centerLab.x;
            float sum = centerL * 0.48;
            float weightSum = 0.48;

            shAddBaseSamplePair(uv, shPixelOffset(2.5, 1.5), centerL, 0.11, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(-1.5, 3.5), centerL, 0.11, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(4.5, -2.5), centerL, 0.10, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(-4.5, -3.5), centerL, 0.10, sum, weightSum);

            shAddBaseSamplePair(uv, shPixelOffset(7.5, 4.5), centerL, 0.065, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(-6.5, 8.5), centerL, 0.06, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(10.5, -7.5), centerL, 0.055, sum, weightSum);

            shAddBaseSamplePair(uv, shPixelOffset(15.5, 11.5), centerL, 0.035, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(-18.5, 5.5), centerL, 0.03, sum, weightSum);
            shAddBaseSamplePair(uv, shPixelOffset(22.5, -13.5), centerL, 0.026, sum, weightSum);

            return sum / max(weightSum, 0.0001);
        }

        // 阴影/高光叠加必须是"凸组合"（convex blend）：混合比例 <= 1.0，结果只能落在
        // 原值与 overlay 结果之间。历史上两个版本都在这里出过问题，本处是修正版：
        //
        //  (1) 1.6.2 比例取 min(opacity * opacity, 4.0)。applyShadowsHighlights 传入
        //      opacity = 2.0 * clamp(uHighlights/uShadows, -1.0, 1.0)，|opacity| 可达 2.0，
        //      于是比例可达 4.0。暗部（shadowsXform 在 baseL=0 处为满值 1.0）会执行
        //      nextL = la + deltaL * 4.0 —— 这是"外推"，越过 overL 后再被 clamp 到
        //      [0,1]，暗部被整体抬亮、动态范围被压扁，观感就是"照片蒙上一层浅灰"；
        //      亮部同理被推向纯白，即"高光崩坏"。
        //  (2) 上游 2ef405b29c 把同一个 overlay 拆成 for (i < 4) 循环来消耗这 4 倍比例。
        //      它把亮度做成了收敛的凸组合（这一步是对的），但 chroma 每轮都乘
        //      (a.y + b.y) * chromaFactor，4 轮复利，饱和度失控 —— 即"双重调色"。
        //
        // 把比例上限钳到 1.0（凸组合）可同时消除这两个缺陷；且本 pass 与色调引擎无关
        // （Adobe / AgX / Spektrafilm / Darktable / Hncs 共用），修一次即全引擎生效。
        float shOverlayBlendAmount(float opacity, float transform) {
            float opacity2 = min(opacity * opacity, 1.0);
            float safeTransform = clamp(transform, 0.0, 1.0);
            return opacity2 * safeTransform;
        }

        vec3 shOverlay(vec3 a, vec3 b, float opacity, float transform, float ccorrect) {
            float optrans = shOverlayBlendAmount(opacity, transform);
            if (optrans <= 0.0) {
                return a;
            }

            float la = a.x;
            float lb = (b.x - 0.5) * shSign(opacity) * shSign(1.0 - la) + 0.5;
            lb = clamp(lb, 0.0, 1.0);
            float lref = shSignedInv(la, la);
            float href = shSignedInv(1.0 - la, 1.0 - la);

            float overL = la > 0.5
                ? 1.0 - (1.0 - 2.0 * (la - 0.5)) * (1.0 - lb)
                : 2.0 * la * lb;
            float deltaL = overL - la;
            // 严格凸组合：nextL 落在 [la, overL] 内，不做任何外推。因为不再外推，
            // 旧的 anchor 钳制（会在 base 跨过源 L 时产生跳变）一并去掉。
            a.x = clamp(mix(la, overL, optrans), 0.0, 1.0);

            // 色度只按最终亮度混合比例混合一次，绝不逐次累乘。
            float chromaFactor = a.x * lref * ccorrect + (1.0 - a.x) * href * (1.0 - ccorrect);
            float chromaTrans = abs(deltaL) > SH_LOW_APPROX
                ? clamp((a.x - la) / deltaL, 0.0, 1.0)
                : clamp(optrans, 0.0, 1.0);
            a.y = mix(a.y, (a.y + b.y) * chromaFactor, chromaTrans);
            a.z = mix(a.z, (a.z + b.z) * chromaFactor, chromaTrans);
            return a;
        }

        vec3 applyShadowsHighlights(vec3 inputColor, vec2 uv) {
            float highlights = 2.0 * clamp(uHighlights, -1.0, 1.0);
            float shadows = 2.0 * clamp(uShadows, -1.0, 1.0);
            if (abs(highlights) < 0.001 && abs(shadows) < 0.001) {
                return inputColor;
            }

            vec3 lab = shRgbToLabScaled(inputColor);
            float baseL = clamp(shSampleBaseL(uv, lab), 0.0, 1.0);
            vec3 maskLab = vec3(1.0 - baseL, 0.0, 0.0);
            float compressDenom = max(1.0 - SH_COMPRESS, 0.0001);

            float highlightsXform = clamp(1.0 - maskLab.x / compressDenom, 0.0, 1.0);
            float highlightsCcorrect = shColorCorrection(SH_HIGHLIGHTS_COLOR_ADJUSTMENT, -highlights);
            lab = shOverlay(lab, maskLab, -highlights, highlightsXform, 1.0 - highlightsCcorrect);

            float shadowsXform = clamp(maskLab.x / compressDenom - SH_COMPRESS / compressDenom, 0.0, 1.0);
            float shadowsCcorrect = shColorCorrection(SH_SHADOWS_COLOR_ADJUSTMENT, shadows);
            lab = shOverlay(lab, maskLab, shadows, shadowsXform, shadowsCcorrect);

            return shSanitizeColor(shLabScaledToRgb(lab));
        }
    """.trimIndent()
}
