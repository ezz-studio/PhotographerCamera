// common.glsl — shared helpers for PhotographerCamera GLSL layers.
// NOTE: luminance matches the CPU reference (arithmetic mean (r+g+b)/3), NOT Rec.709,
// so GPU output stays consistent with tools/profile_renderer.py.

float pc_luma(vec3 c) {
    return (c.r + c.g + c.b) / 3.0;
}

vec3 pc_rgb2hsv(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float v = mx;
    float s = mx > 0.0 ? (mx - mn) / mx : 0.0;
    // Guard is `== 0.0 ? 1.0 : diff` (NOT max(diff,1e-6)) so the hue matches the
    // CPU reference bit-for-bit; for achromatic pixels s == 0 makes hue irrelevant.
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
    float h = hsv.x;
    float s = hsv.y;
    float v = hsv.z;
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

// Hue-range index for the HSL LUT (matches Python _HUE_RANGES, 7 ranges):
// 0 red(345-360 / 0-15) 1 orange 2 yellow 3 green 4 cyan 5 blue 6 purple
int pc_hue_index(float hd) {
    if (hd >= 345.0 || hd < 15.0) return 0;
    if (hd < 45.0) return 1;
    if (hd < 70.0) return 2;
    if (hd < 160.0) return 3;
    if (hd < 200.0) return 4;
    if (hd < 260.0) return 5;
    return 6;
}
