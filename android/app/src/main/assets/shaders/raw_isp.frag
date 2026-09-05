#version 300 es
precision highp float;
precision highp usampler2D;

// RAW ISP pass: Bayer RAW16 -> linear -> WB -> demosaic -> CCM -> gamma.
// This replaces the device ISP for the RAW-ISP capture path (the DAZZ-style
// GPU render graph stage "RAW -> RAW ISP -> recipe"). Output is a normal
// RGBA8 buffer that the existing effect chain (effect.frag) consumes.

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

// Bilinear interpolation over the same-color sites of the Bayer pattern.
// Sites of one channel sit on a stride-2 grid anchored at their quad offset.
float chan(vec2 c, ivec2 par) {
    vec2 t = (c - vec2(par)) / 2.0 - 0.5;
    ivec2 i0 = ivec2(floor(t));
    vec2 f = t - vec2(i0);
    ivec2 s0 = par + i0 * 2;
    float v00 = fetchRaw(s0);
    float v10 = fetchRaw(s0 + ivec2(2, 0));
    float v01 = fetchRaw(s0 + ivec2(0, 2));
    float v11 = fetchRaw(s0 + ivec2(2, 2));
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
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

    vec2 c = s * u_rawSize;

    ivec2 rpar = u_cfa;
    ivec2 bpar = ivec2(1 - u_cfa.x, 1 - u_cfa.y);
    ivec2 g1par = ivec2(1 - u_cfa.x, u_cfa.y);
    ivec2 g2par = ivec2(u_cfa.x, 1 - u_cfa.y);

    float r = chan(c, rpar);
    float b = chan(c, bpar);
    float g = 0.5 * (chan(c, g1par) + chan(c, g2par));

    vec3 col = vec3(r, g, b) * u_wb;
    col = clamp(u_ccm * col, 0.0, 1.0);
    col = pow(col, vec3(1.0 / 2.2));
    fragColor = vec4(col, 1.0);
}
