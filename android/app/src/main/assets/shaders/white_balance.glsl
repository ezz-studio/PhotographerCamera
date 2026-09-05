// white_balance.glsl — White Balance. temp = temperature_bias (+ warmer), tint = tint_bias (+ magenta).
// Equivalent to apply_white_balance.
vec3 pc_white_balance(vec3 c, float temp, float tint) {
    if (temp == 0.0 && tint == 0.0) return c;
    vec3 o = c;
    o.r *= (1.0 + temp * 0.2);
    o.b *= (1.0 - temp * 0.2);
    o.r *= (1.0 + tint * 0.1);
    o.b *= (1.0 + tint * 0.1);
    o.g *= (1.0 - tint * 0.1);
    return clamp(o, 0.0, 1.0);
}
