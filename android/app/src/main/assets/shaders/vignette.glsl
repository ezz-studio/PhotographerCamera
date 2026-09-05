// vignette.glsl — Vignette (radial darkening). uv/center in [0,1].
vec3 pc_vignette(vec3 c, vec2 uv, vec2 center, float amount, float radius, float feather) {
    if (amount <= 0.0) return c;
    vec2 d = uv - center;
    float dist = length(d) / (radius * 0.7071);
    float m = 1.0 - amount * clamp((dist - (1.0 - feather)) / max(1e-4, feather), 0.0, 1.0);
    return clamp(c * m, 0.0, 1.0);
}
