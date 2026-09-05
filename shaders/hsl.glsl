// hsl.glsl — HSL Mapping. Per-hue saturation / lightness / hue-shift from a 1D LUT.
//
// LUT CONTRACT (see tools/glsl_reference.py::bake_hsl_lut):
//   size      : 7 x 1 texels, CATEGORY-indexed (NOT hue-degree indexed)
//   texel k   : parameters of hue range k, in the order of pc_hue_index():
//               0 red, 1 orange, 2 yellow, 3 green, 4 cyan, 5 blue, 6 purple
//   channels  : R = saturation factor, G = lightness factor, B = hue_shift (degrees)
//   filtering : GL_NEAREST + GL_CLAMP_TO_EDGE (mandatory)
// Sampling at u = (idx + 0.5) / 7 resolves exactly to texel idx, so the per-range
// CONSTANT factors of the CPU reference are reproduced with no boundary blurring.
vec3 pc_hsl(vec3 c, sampler2D hslLut) {
    vec3 hsv = pc_rgb2hsv(c);
    float hd = hsv.x * 360.0;
    int idx = pc_hue_index(hd);
    float u = (float(idx) + 0.5) / 7.0;
    vec3 pl = texture(hslLut, vec2(u, 0.5)).rgb; // r=sat, g=light, b=hueShift(deg)
    float sat_f = pl.r;
    float light_f = pl.g;
    float hue_shift = pl.b / 360.0;
    float h2 = fract(hsv.x + hue_shift);
    float s2 = clamp(hsv.y * sat_f, 0.0, 1.0);
    float v2 = clamp(hsv.z * light_f, 0.0, 1.0);
    return clamp(pc_hsv2rgb(vec3(h2, s2, v2)), 0.0, 1.0);
}
