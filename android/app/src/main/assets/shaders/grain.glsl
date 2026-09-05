// grain.glsl — Photographic grain. `n` is a zero-mean noise sample in ~[-0.5,0.5].
// scale = amount * 0.08 * density / max(0.5, size)  -> smaller size => finer (stronger) grain.
vec3 pc_grain(vec3 c, float n, float amount, float size, float density) {
    if (amount <= 0.0) return c;
    float scale = amount * 0.08 * density / max(0.5, size);
    return clamp(c + n * scale, 0.0, 1.0);
}
