// halation.glsl — Halation (red/warm glow around highlights).
vec3 pc_halation(vec3 c, float glow, float amount, float warmth) {
    if (amount <= 0.0) return c;
    vec3 o = c;
    o.r += amount * glow * warmth;
    o.g += amount * glow * 0.4;
    o.b += amount * glow * (1.0 / max(1e-4, warmth));
    return clamp(o, 0.0, 1.0);
}
