// sharpen.glsl — Sharpen (unsharp mask). c + amount*(c - blur).
vec3 pc_sharpen(vec3 c, vec3 blur, float amount) {
    if (amount <= 0.0) return c;
    return clamp(c + amount * (c - blur), 0.0, 1.0);
}
