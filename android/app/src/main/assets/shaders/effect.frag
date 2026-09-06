#version 300 es
// effect.frag - single-pass effect shader (Pass 2 of the 2-pass pipeline).
//
// Architecture follows the proven 2-pass designs of zoombox (LutPreviewRenderer)
// and dazz-retro-camera (CameraGLRenderer). Pass 1 writes one stable RGBA8
// camera frame into a 2D texture; this shader samples it once per pixel and
// applies every photographic effect in a SINGLE fragment program. No FBO
// ping-pong, no RGBA16F, no LUT-as-1D-r16f. Only sampler2D inputs (RGBA8
// always, 3D LUT optional) - minimum texture-format compatibility surface.
//
// UNIFIED IMAGE ENGINE chain - user-defined order (v0.2.5):
//   1. exposure
//   2. white balance
//   3. color matrix
//   4. highlight rolloff   [skipped when the RAW ISP already graded in
//   5. shadow               LINEAR light - u_tonePreLinear gate]
//   6. film curve          (Contrast/BW base curve, toe+shoulder)
//   7. tone curve          (independent 1D LUT - separate from film curve)
//   8. HSL
//   9. 3D LUT              (style color ONLY - the single style-color stage)
//  10. vignette
//  11. bloom
//  12. halation
//  13. grain (LIGHT-AWARE distribution) + noise
//  14. sharpen             (final detail stage - always last)
//
// The profile is a DETERMINISTIC mapping function: every stage above has
// constant parameters. There is no scene-statistics driven parameter drift.
// The single light-adaptive term is grain DISTRIBUTION (pc_grain's mask):
// heavier grain in shadows, lighter in midtones, a gentle lift in strong
// highlights - the amount parameters themselves never change.
//
// All parameters are uniforms - no behavior is tied to a specific
// photographer profile.
#extension GL_OES_EGL_image_external_essl3 : enable
#extension GL_OES_texture_3D : enable
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

in vec2 v_uv;
out vec4 fragColor;

// -- Required --
uniform sampler2D u_input;          // Pass 1 RGBA8 camera frame (post-stMatrix)
// -- Main chain --
uniform float u_exposure;
uniform vec2  u_wb;                 // (temp, tint) bias
uniform mat3  u_colorMatrix;        // uploaded TRANSPOSED (see GpuParams)
// -- Tone curve: RGBA8 1024x1 LUT, baked at texel centres --
uniform sampler2D u_toneLut;        // 1024x1 RGBA8 (R = curve value)
uniform int    u_toneEnabled;       // 0 = identity curve (skip sample)
// -- Highlight / Shadow / HSL --
uniform vec3  u_highlight;          // (threshold, strength, sat)
uniform vec3  u_shadow;             // (blackPoint, compression, sat)
// 1 = the input was already graded in LINEAR light by raw_isp.frag (RAW ISP
// capture path); applying pc_highlight/pc_shadow here again would grade the
// still twice. Preview and JPEG-input paths keep the default 0.
uniform int   u_tonePreLinear;
uniform vec4  u_hsl[7];             // per-hue (sat, light, hueShiftDeg, pad)
uniform int   u_hslEnabled;         // 0 = skip HSL sample (saves a few cycles)
// -- Sharpen --
uniform float u_sharpenAmount;
uniform float u_sharpenRadius;
uniform vec2  u_texel;              // (1/w, 1/h) of the intermediate texture
// -- Bloom (uses current u_input 2D texture for stable sampling) --
uniform float u_bloomAmount;
uniform float u_bloomThreshold;
uniform float u_bloomRadius;
// -- Halation --
uniform float u_halationAmount;
uniform float u_halationThreshold;
uniform float u_halationRadius;
uniform float u_halationWarmth;
// -- Grain + Noise --
uniform float u_grainAmount;
uniform float u_grainSize;
uniform float u_grainDensity;
uniform float u_noiseLuma;
uniform float u_noiseChroma;
uniform float u_seed;
// -- Vignette --
uniform float u_vignetteAmount;
uniform float u_vignetteRadius;
uniform float u_vignetteFeather;
uniform vec2  u_vignetteCenter;
uniform vec2  u_viewSize;           // in pixels, for stable grain freq
uniform vec4  u_vwin;               // capture box uv window (preview only)
// -- Film curve (Contrast/BW base curve) --
uniform vec2  u_film;               // (floor, ceil)
uniform int   u_filmEnabled;
// -- Optional 3D LUT (zoombox/dazz proven) --
uniform sampler3D u_lut3d;
uniform int    u_lut3dEnabled;
uniform float u_lut3dStrength;

// -- Helpers -----------------------------------------------------------------

float pc_luma(vec3 c) {
    return (c.r + c.g + c.b) / 3.0;
}

// HSL index matching common.glsl::pc_hue_index (0..6).
int pc_hue_index(float hd) {
    if (hd >= 345.0 || hd < 15.0) return 0;
    if (hd < 45.0) return 1;
    if (hd < 70.0) return 2;
    if (hd < 160.0) return 3;
    if (hd < 200.0) return 4;
    if (hd < 260.0) return 5;
    return 6;
}

vec3 pc_rgb2hsv(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float v = mx;
    float s = mx > 0.0 ? (mx - mn) / mx : 0.0;
    float diff = mx - mn;
    float dv = diff == 0.0 ? 1.0 : diff;
    float rc = (mx - c.r) / dv;
    float gc = (mx - c.g) / dv;
    float bc = (mx - c.b) / dv;
    float h;
    if (mx == c.r) h = bc - gc;
    else if (mx == c.g) h = 2.0 + rc - bc;
    else h = 4.0 + gc - rc;
    h = fract(h / 6.0);
    return vec3(h, s, v);
}

vec3 pc_hsv2rgb(vec3 hsv) {
    float h = hsv.x, s = hsv.y, v = hsv.z;
    float i = floor(h * 6.0);
    float f = h * 6.0 - i;
    float p = v * (1.0 - s);
    float q = v * (1.0 - f * s);
    float t = v * (1.0 - (1.0 - f) * s);
    vec3 rgb;
    int idx = int(mod(i, 6.0));
    if (idx == 0) rgb = vec3(v, t, p);
    else if (idx == 1) rgb = vec3(q, v, p);
    else if (idx == 2) rgb = vec3(p, v, t);
    else if (idx == 3) rgb = vec3(p, q, v);
    else if (idx == 4) rgb = vec3(t, p, v);
    else rgb = vec3(v, p, q);
    return rgb;
}

float pc_hash(vec2 p) {
    // Dave Hoskins hash12 — sin-free. The old fract(sin(dot(p, K))*43758.5)
    // degenerates into REGULAR banded patterns once |p| exceeds float-precision
    // sane ranges (full-res stills: p.y up to 4096, Mali especially) — the
    // saved photo showed structured chroma noise while the small preview hid it.
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// LIGHT-AWARE grain distribution (the profile's ONLY adaptive term).
// The amount/size/density parameters are constant for a given profile; what
// varies per-pixel is WHERE the grain lands, driven by local luminance:
//   shadows : weight 1.0 -> 0.4 as luminance rises to 0.45 (film-like dense
//             grain in the toe)
//   mids    : flat 0.4 floor
//   highlights: 0.4 -> 0.85 as luminance passes 0.72..0.97 (gentle sheen on
//             blown areas, grain survives in speculars)
//   mask    = max(shadowW, highW) keeps a smooth single-valued curve.
float pc_grain(vec3 c, float n, float amount, float size, float density) {
    if (amount <= 0.0) return 0.0;
    float lum = pc_luma(c);
    float shadowW = 1.0 - 0.6 * smoothstep(0.0, 0.45, lum);
    float highW   = 0.4 + 0.45 * smoothstep(0.72, 0.97, lum);
    float mask = max(shadowW, highW);
    float strength = amount * 0.08 * density / max(0.5, size) * mask;
    return n * strength;
}

float pc_noise_luma(float n, float amount) {
    return n * amount;
}
float pc_noise_chroma(float n, float amount) {
    return n * amount;
}

// -- Stage 1: exposure ------------------------------------------------------
vec3 pc_exposure(vec3 c) {
    if (u_exposure == 0.0) return c;
    return clamp(c * pow(2.0, u_exposure), 0.0, 1.0);
}

// -- Stage 2: white balance -------------------------------------------------
vec3 pc_white_balance(vec3 c) {
    float t = u_wb.x;
    float n = u_wb.y;
    if (t == 0.0 && n == 0.0) return c;
    vec3 o = c;
    o.r *= (1.0 + t * 0.2);
    o.b *= (1.0 - t * 0.2);
    o.r *= (1.0 + n * 0.1);
    o.b *= (1.0 + n * 0.1);
    o.g *= (1.0 - n * 0.1);
    return clamp(o, 0.0, 1.0);
}

// -- Stage 3: color matrix --------------------------------------------------
vec3 pc_color_matrix(vec3 c) {
    return clamp(u_colorMatrix * c, 0.0, 1.0);
}

// -- Stage 4: highlight rolloff ---------------------------------------------
vec3 pc_highlight(vec3 c) {
    float strength = u_highlight.y;
    if (strength <= 0.0) return c;
    float l = pc_luma(c);
    float amt = clamp((l - u_highlight.x) / max(1e-4, 1.0 - u_highlight.x), 0.0, 1.0) * strength;
    vec3 o = c - amt * (c - u_highlight.x) * (1.0 - amt) * 0.5;
    return clamp(o, 0.0, 1.0);
}

// -- Stage 5: shadow --------------------------------------------------------
vec3 pc_shadow(vec3 c) {
    vec3 o = c;
    float bp = u_shadow.x;
    float compression = u_shadow.y;
    float sat = u_shadow.z;
    if (bp > 0.0) o = (o - bp) / max(1e-4, 1.0 - bp);
    if (compression > 0.0) {
        float l = pc_luma(o);
        float mask = clamp(1.0 - l, 0.0, 1.0);
        o = o + compression * mask * (0.5 - o) * 0.5;
    }
    if (sat != 1.0) {
        float l = pc_luma(o);
        o = clamp(l + (o - l) * sat, 0.0, 1.0);
    }
    return clamp(o, 0.0, 1.0);
}

// -- Stage 6: film curve (Contrast/BW base curve, toe + shoulder) ----------
vec3 pc_film_curve(vec3 c) {
    if (u_filmEnabled == 0) return c;
    float lo = u_film.x;
    float hi = u_film.y;
    float kt = clamp(lo + 0.13, 0.04, 0.45);
    float kh = clamp(hi - 0.17, 0.55, 0.97);
    vec3 r;
    for (int ch = 0; ch < 3; ch++) {
        float x = (ch == 0) ? c.r : (ch == 1) ? c.g : c.b;
        if (x < kt) {
            float t = x / max(kt, 1e-4);
            float v = lo + (kt - lo) * t * t * (2.0 - t);
            if (ch == 0) r.r = v; else if (ch == 1) r.g = v; else r.b = v;
        } else if (x > kh) {
            float t = (x - kh) / max(1.0 - kh, 1e-4);
            float v = kh + (hi - kh) * t * (1.0 + t - t * t);
            if (ch == 0) r.r = v; else if (ch == 1) r.g = v; else r.b = v;
        } else {
            if (ch == 0) r.r = x; else if (ch == 1) r.g = x; else r.b = x;
        }
    }
    return clamp(r, 0.0, 1.0);
}

// -- Stage 7: tone curve via RGBA8 1D LUT (independent stage) ---------------
vec3 pc_tone_curve(vec3 c) {
    if (u_toneEnabled == 0) return c;
    float yr = texture(u_toneLut, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;
    float yg = texture(u_toneLut, vec2(clamp(c.g, 0.0, 1.0), 0.5)).r;
    float yb = texture(u_toneLut, vec2(clamp(c.b, 0.0, 1.0), 0.5)).r;
    return vec3(yr, yg, yb);
}

// -- Stage 8: HSL -----------------------------------------------------------
vec3 pc_hsl(vec3 c) {
    if (u_hslEnabled == 0) return c;
    vec3 hsv = pc_rgb2hsv(c);
    float hd = hsv.x * 360.0;
    int idx = pc_hue_index(hd);
    vec4 pl = u_hsl[idx];                    // r=sat, g=light, b=hueShift(deg)
    float sat_f = pl.r;
    float light_f = pl.g;
    float hue_shift = pl.b / 360.0;
    float h2 = fract(hsv.x + hue_shift);
    float s2 = clamp(hsv.y * sat_f, 0.0, 1.0);
    float v2 = clamp(hsv.z * light_f, 0.0, 1.0);
    return clamp(pc_hsv2rgb(vec3(h2, s2, v2)), 0.0, 1.0);
}

// -- Stage 9 helper: sharpen (9-tap weighted blur) --------------------------
// NOTE: neighbors are sampled from u_input (the Pass-1 stable texture), not
// from the in-flight graded color - a single pass cannot re-grade 9 taps.
// Grading stages are low-frequency (curves/LUT/bloom are smooth mappings), so
// the detail vector (center - blur) survives the substitution in practice.
vec3 pc_sharpen(vec2 uv, vec3 center) {
    if (u_sharpenAmount <= 0.0) return center;
    vec3 acc = vec3(0.0);
    float wt = 0.0;
    vec2 step = u_texel * u_sharpenRadius;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float kw = (i == 0 && j == 0) ? 4.0 : ((i == 0 || j == 0) ? 2.0 : 1.0);
            vec2 off = vec2(float(i), float(j)) * step;
            acc += texture(u_input, uv + off).rgb * kw;
            wt += kw;
        }
    }
    vec3 blur = acc / wt;
    return clamp(center + u_sharpenAmount * (center - blur), 0.0, 1.0);
}

// -- Stage 11: bloom (warm neighbor blur from 2D, threshold gated) ----------
vec3 pc_bloom(vec2 uv, vec3 center) {
    if (u_bloomAmount <= 0.0) return vec3(0.0);
    vec3 acc = vec3(0.0);
    float wt = 0.0;
    vec2 step = u_texel * u_bloomRadius;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float w = (i == 0 && j == 0) ? 0.4 : 0.15;
            vec2 off = vec2(float(i), float(j)) * step;
            acc += texture(u_input, uv + off).rgb * w;
            wt += w;
        }
    }
    vec3 blur = acc / wt;
    vec3 above = max(vec3(0.0), blur - vec3(u_bloomThreshold)) / max(1e-4, 1.0 - u_bloomThreshold);
    return above;
}

// -- Stage 12: halation (warm/red highlight bleed) -------------------------
vec3 pc_halation(vec2 uv, vec3 center) {
    if (u_halationAmount <= 0.0) return vec3(0.0);
    vec3 acc = vec3(0.0);
    float wt = 0.0;
    vec2 step = u_texel * u_halationRadius;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            float w = (i == 0 && j == 0) ? 0.4 : 0.15;
            vec2 off = vec2(float(i), float(j)) * step;
            vec3 s = texture(u_input, uv + off).rgb;
            float lum = (s.r + s.g + s.b) / 3.0;
            float g = max(0.0, lum - u_halationThreshold) / max(1e-4, 1.0 - u_halationThreshold);
            acc += vec3(g * u_halationWarmth, g * 0.4, g / max(1e-4, u_halationWarmth)) * w;
            wt += w;
        }
    }
    return (acc / wt) * u_halationAmount;
}

// -- Main -------------------------------------------------------------------
void main() {
    vec2 uv = v_uv;

    vec3 c = texture(u_input, uv).rgb;

    // -- Unified engine chain (user-defined order, see header) --
    c = pc_exposure(c);
    c = pc_white_balance(c);
    c = pc_color_matrix(c);
    if (u_tonePreLinear == 0) {
        c = pc_highlight(c);
        c = pc_shadow(c);
    }
    c = pc_film_curve(c);       // Contrast/BW base curve
    c = pc_tone_curve(c);       // independent tone curve
    c = pc_hsl(c);

    // -- Style color: 3D LUT is the single style-color stage --
    if (u_lut3dEnabled == 1) {
        vec3 lutC = texture(u_lut3d, c).rgb;
        c = mix(c, lutC, u_lut3dStrength);
    }

    // -- Vignette (in preview pipeline with capture box window) --
    if (u_vignetteAmount > 0.0) {
        vec2 uvw = (v_uv - u_vwin.xy) / max(u_vwin.zw, vec2(1e-4)) + 0.5;
        vec2 dv = uvw - u_vignetteCenter;
        float r = length(dv);
        float t = clamp((r - u_vignetteRadius) / max(1e-4, u_vignetteFeather), 0.0, 1.0);
        float k = 1.0 - u_vignetteAmount * t * t * (3.0 - 2.0 * t);
        c *= k;
    }

    // -- Bloom + Halation: warm glow layered over the styled color --
    vec3 bloomed = pc_bloom(uv, c);
    vec3 halated = pc_halation(uv, c);
    c = clamp(c + bloomed + halated, 0.0, 1.0);

    // -- Grain (light-aware distribution) + noise --
    vec2 px = uv * u_viewSize;
    float n  = pc_hash(px + vec2(u_seed, u_seed)) - 0.5;
    float nGrain = pc_grain(c, n, u_grainAmount, u_grainSize, u_grainDensity);
    c += vec3(nGrain);
    float nl = pc_hash(px.yx + vec2(u_seed + 1.3, u_seed + 1.3)) - 0.5;
    float nc = pc_hash(px + vec2(u_seed + 2.7, u_seed + 2.7)) - 0.5;
    c += vec3(pc_noise_luma(nl, u_noiseLuma));
    c.r += pc_noise_chroma(nc, u_noiseChroma);
    c.g += pc_noise_chroma(nc, u_noiseChroma * 0.6);
    c.b += pc_noise_chroma(nc, u_noiseChroma * 0.9);
    c = clamp(c, 0.0, 1.0);

    // -- Sharpen: FINAL detail stage (always last) --
    c = pc_sharpen(uv, c);

    fragColor = vec4(c, 1.0);
}
