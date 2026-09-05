// shadow.glsl — Shadow Response (black point, compression, saturation).
// Equivalent to apply_shadow.
vec3 pc_shadow(vec3 c, float black_point, float compression, float saturation) {
    vec3 o = c;
    if (black_point > 0.0) o = (o - black_point) / max(1e-4, 1.0 - black_point);
    if (compression > 0.0) {
        float l = pc_luma(o);
        float mask = clamp(1.0 - l, 0.0, 1.0);
        o = o + compression * mask * (0.5 - o) * 0.5;
    }
    if (saturation != 1.0) {
        float l = pc_luma(o);
        o = clamp(l + (o - l) * saturation, 0.0, 1.0);
    }
    return clamp(o, 0.0, 1.0);
}
