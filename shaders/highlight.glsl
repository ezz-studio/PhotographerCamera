// highlight.glsl — Highlight Roll-off. (saturation param reserved, unused in the math,
// matching the CPU reference which ignores it.)
vec3 pc_highlight(vec3 c, float threshold, float strength, float sat) {
    if (strength <= 0.0) return c;
    float l = pc_luma(c);
    float amt = clamp((l - threshold) / max(1e-4, 1.0 - threshold), 0.0, 1.0) * strength;
    vec3 o = c - amt * (c - threshold) * (1.0 - amt) * 0.5;
    return clamp(o, 0.0, 1.0);
}
