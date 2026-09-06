#version 300 es
precision highp float;
precision highp usampler2D;

// RAW ISP pass v2 (0.2.4): Bayer RAW16 -> black level -> Malvar-He-Cutler
// 5x5 demosaic -> WB -> CCM -> LINEAR-domain grading (highlight shoulder +
// shadow toe BEFORE gamma - the whole point of the RAW path: grade
// scene-referred values instead of re-curving a gamma'd image) -> gamma 2.2.
// Output is a normal RGBA8 buffer that the existing effect chain (effect.frag)
// consumes with u_tonePreLinear=1 so the chain does NOT re-apply its own
// post-gamma highlight/shadow on RAW stills.
//
// Demosaic kernels are the canonical Malvar-He-Cutler (ICIP 2004) stencils,
// all DC-preserving (coefficients sum to exactly 8 before the /8). The
// filmframe reference implementation's green-row kernels sum to 10 (DC gain
// 1.25) and were deliberately NOT copied.

in vec2 v_uv;
out vec4 fragColor;

uniform usampler2D u_raw;   // RAW16 Bayer plane (GL_R16UI)
uniform vec2  u_rawSize;    // raw width/height in pixels
uniform ivec2 u_cfa;        // position of the R site inside the 2x2 quad
                            // RGGB(0,0) GRBG(1,0) GBRG(0,1) BGGR(1,1)
uniform vec4  u_black;      // black level per quad position, index = dy*2+dx
uniform float u_white;      // sensor white level
uniform vec3  u_wb;         // per-channel gains, green normalized to 1.0
uniform mat3  u_ccm;        // camera rgb -> sRGB
uniform int   u_rot;        // CW degrees (0/90/180/270) buffer -> upright
uniform vec4  u_win;        // cover-crop window in UPRIGHT uv (x0,y0,x1,y1)

// Linear-domain grading. Anchors arrive ALREADY converted to linear light
// (caller applies v^2.2 to the display-referred profile values so the same
// slider anchors the same perceived tone, just physically pre-gamma).
uniform vec3  u_linHi;      // (threshold, strength, saturation[reserved])
uniform vec3  u_linSh;      // (blackPoint, compression, saturation)

float blackAt(ivec2 p) {
    int idx = ((p.y & 1) << 1) | (p.x & 1);
    if (idx == 0) return u_black.x;
    if (idx == 1) return u_black.y;
    if (idx == 2) return u_black.z;
    return u_black.w;
}

float fetchRaw(ivec2 p) {
    ivec2 sz = ivec2(u_rawSize);
    p = clamp(p, ivec2(0), sz - 1);
    float v = float(texelFetch(u_raw, p, 0).r);
    float blk = blackAt(p);
    return clamp((v - blk) / max(u_white - blk, 1.0), 0.0, 1.0);
}

// -- Malvar-He-Cutler 5x5 kernels (all scaled by 1/8, DC-preserving) --------
//
//   mhc_g     : G at an R/B site      (2 on axis-1, 4 center, -1 on axis-2)
//   mhc_row   : R/B at a G site whose same-color mates sit LEFT/RIGHT
//   mhc_col   : R/B at a G site whose same-color mates sit UP/DOWN
//   mhc_cross : R/B at the opposite-color site (-3/2 axis-2, 2 diag, 6 center)

float mhc_g(ivec2 p) {
    return (4.0 * fetchRaw(p)
        + 2.0 * (fetchRaw(p + ivec2( 1, 0)) + fetchRaw(p + ivec2(-1, 0))
               + fetchRaw(p + ivec2( 0, 1)) + fetchRaw(p + ivec2( 0,-1)))
        - 1.0 * (fetchRaw(p + ivec2( 2, 0)) + fetchRaw(p + ivec2(-2, 0))
               + fetchRaw(p + ivec2( 0, 2)) + fetchRaw(p + ivec2( 0,-2)))) / 8.0;
}

float mhc_row(ivec2 p) {
    return (5.0 * fetchRaw(p)
        + 4.0 * (fetchRaw(p + ivec2( 1, 0)) + fetchRaw(p + ivec2(-1, 0)))
        + 0.5 * (fetchRaw(p + ivec2( 0, 2)) + fetchRaw(p + ivec2( 0,-2)))
        - 1.0 * (fetchRaw(p + ivec2( 2, 0)) + fetchRaw(p + ivec2(-2, 0))
               + fetchRaw(p + ivec2( 1, 1)) + fetchRaw(p + ivec2(-1, 1))
               + fetchRaw(p + ivec2( 1,-1)) + fetchRaw(p + ivec2(-1,-1)))) / 8.0;
}

float mhc_col(ivec2 p) {
    return (5.0 * fetchRaw(p)
        + 4.0 * (fetchRaw(p + ivec2( 0, 1)) + fetchRaw(p + ivec2( 0,-1)))
        + 0.5 * (fetchRaw(p + ivec2( 2, 0)) + fetchRaw(p + ivec2(-2, 0)))
        - 1.0 * (fetchRaw(p + ivec2( 0, 2)) + fetchRaw(p + ivec2( 0,-2))
               + fetchRaw(p + ivec2( 1, 1)) + fetchRaw(p + ivec2(-1, 1))
               + fetchRaw(p + ivec2( 1,-1)) + fetchRaw(p + ivec2(-1,-1)))) / 8.0;
}

float mhc_cross(ivec2 p) {
    return (6.0 * fetchRaw(p)
        + 2.0 * (fetchRaw(p + ivec2( 1, 1)) + fetchRaw(p + ivec2(-1, 1))
               + fetchRaw(p + ivec2( 1,-1)) + fetchRaw(p + ivec2(-1,-1)))
        - 1.5 * (fetchRaw(p + ivec2( 2, 0)) + fetchRaw(p + ivec2(-2, 0))
               + fetchRaw(p + ivec2( 0, 2)) + fetchRaw(p + ivec2( 0,-2)))) / 8.0;
}

// -- Linear-domain grading (same math as effect.frag pc_highlight/pc_shadow,
//    applied in the physically correct domain, before gamma) ----------------

float linLuma(vec3 c) { return (c.r + c.g + c.b) / 3.0; }

vec3 linHighlight(vec3 c) {
    float strength = u_linHi.y;
    if (strength <= 0.0) return c;
    float l = linLuma(c);
    float amt = clamp((l - u_linHi.x) / max(1e-4, 1.0 - u_linHi.x), 0.0, 1.0) * strength;
    return c - amt * (c - u_linHi.x) * (1.0 - amt) * 0.5;
}

vec3 linShadow(vec3 c) {
    float bp = u_linSh.x;
    float compression = u_linSh.y;
    float sat = u_linSh.z;
    vec3 o = c;
    if (bp > 0.0) o = (o - bp) / max(1e-4, 1.0 - bp);
    if (compression > 0.0) {
        float l = linLuma(o);
        float mask = clamp(1.0 - l, 0.0, 1.0);
        o = o + compression * mask * (0.5 - o) * 0.5;
    }
    if (sat != 1.0) {
        float l = linLuma(o);
        o = l + (o - l) * sat;
    }
    return max(o, vec3(0.0));
}

void main() {
    // upright uv -> cover-crop window
    vec2 u = mix(u_win.xy, u_win.zw, v_uv);

    // invert the CW rotation (upright uv -> sensor uv).
    // u = (1-sy, sx) for 90; s = (uy, 1-ux). See CameraEngine capture docs.
    vec2 s;
    if (u_rot == 90)       s = vec2(u.y, 1.0 - u.x);
    else if (u_rot == 180) s = vec2(1.0 - u.x, 1.0 - u.y);
    else if (u_rot == 270) s = vec2(1.0 - u.y, u.x);
    else                   s = u;

    // Malvar kernels are phase-aligned 5x5 stencils on the INTEGER sensor
    // grid. The cover-crop window makes the continuous sample coordinate
    // fractional (3:4 crop + mild downscale), so snap to the nearest sensor
    // pixel - residual offset <= 0.5px, invisible on a downscaled still.
    vec2 sc = clamp(s * u_rawSize, vec2(0.0), u_rawSize - 1.0);
    ivec2 p = clamp(ivec2(sc + 0.5), ivec2(0), ivec2(u_rawSize) - 1);

    // CFA phase of the snapped pixel, relative to u_cfa:
    //   (0,0)=R site  (1,1)=B site
    //   (1,0)=G with R mates left/right   (0,1)=G with R mates up/down
    int relX = (p.x & 1) ^ (u_cfa.x & 1);
    int relY = (p.y & 1) ^ (u_cfa.y & 1);

    float r, g, b;
    if (relX == 0 && relY == 0) {
        r = fetchRaw(p);
        g = mhc_g(p);
        b = mhc_cross(p);
    } else if (relX == 1 && relY == 1) {
        b = fetchRaw(p);
        g = mhc_g(p);
        r = mhc_cross(p);
    } else if (relX == 1) {
        g = fetchRaw(p);
        r = mhc_row(p);
        b = mhc_col(p);
    } else {
        g = fetchRaw(p);
        r = mhc_col(p);
        b = mhc_row(p);
    }

    // Per-channel WB gains commute with the (linear) kernels, so applying
    // them after demosaic is equivalent to scaling each tap pre-kernel.
    vec3 col = clamp(vec3(r, g, b), 0.0, 1.0) * u_wb;
    col = clamp(u_ccm * col, 0.0, 1.0);

    // LINEAR-domain grade, THEN gamma - never the other way round.
    col = linHighlight(col);
    col = linShadow(col);
    col = pow(max(col, 0.0), vec3(1.0 / 2.2));
    fragColor = vec4(col, 1.0);
}
