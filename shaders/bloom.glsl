// bloom.glsl — Bloom (optical glow). Adds a pre-blurred bright extraction.
// The renderer supplies `bloomed` = Gaussian-blurred (clamp(rgb-threshold)/(1-threshold)).
vec3 pc_bloom(vec3 c, vec3 bloomed, float amount) {
    if (amount <= 0.0) return c;
    return clamp(c + amount * bloomed, 0.0, 1.0);
}
