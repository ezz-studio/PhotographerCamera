// exposure.glsl — Exposure layer. bias in EV. Equivalent to apply_exposure (2^bias).
vec3 pc_exposure(vec3 c, float bias) {
    if (bias == 0.0) return c;
    return clamp(c * pow(2.0, bias), 0.0, 1.0);
}
