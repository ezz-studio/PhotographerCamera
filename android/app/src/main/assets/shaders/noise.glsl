// noise.glsl — Sensor noise. nl/ncr are zero-mean samples in ~[-0.5,0.5].
// luma adds to all channels (*0.05); chroma adds to R and B (*0.04).
vec3 pc_noise(vec3 c, float nl, float ncr, float luma, float chroma) {
    if (luma <= 0.0 && chroma <= 0.0) return c;
    vec3 o = c;
    if (luma > 0.0) o += nl * luma * 0.05;
    if (chroma > 0.0) {
        o.r += ncr * chroma * 0.04;
        o.b += ncr * chroma * 0.04;
    }
    return clamp(o, 0.0, 1.0);
}
