#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_input;
uniform vec2 u_film;
float pc_film_curve(float x, float lo, float hi) {
    float kt = clamp(lo + 0.13, 0.04, 0.45);
    float kh = clamp(hi - 0.17, 0.55, 0.97);
    if (x < kt) {
        float t = x / max(kt, 1e-4);
        return lo + (kt - lo) * t * t * (2.0 - t);
    } else if (x > kh) {
        float t = (x - kh) / max(1.0 - kh, 1e-4);
        return kh + (hi - kh) * t * (1.0 + t - t * t);
    }
    return x;
}
void main() {
    vec3 c = clamp(texture(u_input, v_uv).rgb, 0.0, 1.0);
    float lo = u_film.x;
    float hi = u_film.y;
    c.r = pc_film_curve(c.r, lo, hi);
    c.g = pc_film_curve(c.g, lo, hi);
    c.b = pc_film_curve(c.b, lo, hi);
    fragColor = vec4(c, 1.0);
}
