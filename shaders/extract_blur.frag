#version 300 es
// extract_blur.frag — bright/warm extraction + separable 5-tap Gaussian in one pass.
// u_mode: 0 = Bloom (extract bright RGB), 1 = Halation (extract warm glow).
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_texel;   // 1.0 / current buffer resolution
uniform vec2  u_dir;     // (1,0) horizontal, (0,1) vertical
uniform float u_threshold;
uniform float u_warmth;  // halation warm tint (mode 1)
uniform int   u_mode;    // 0 = bloom, 1 = halation

void main() {
    float w0 = 0.0625, w1 = 0.25, w2 = 0.375;
    vec3 c = texture(u_input, v_uv).rgb;
    vec3 a1 = texture(u_input, v_uv + u_dir * 1.0 * u_texel).rgb;
    vec3 a2 = texture(u_input, v_uv - u_dir * 1.0 * u_texel).rgb;
    vec3 a3 = texture(u_input, v_uv + u_dir * 2.0 * u_texel).rgb;
    vec3 a4 = texture(u_input, v_uv - u_dir * 2.0 * u_texel).rgb;
    vec3 acc = c * w2 + (a1 + a2) * w1 + (a3 + a4) * w0;
    if (u_mode == 1) {
        float lum = (acc.r + acc.g + acc.b) / 3.0;
        float g = max(0.0, lum - u_threshold) / max(1e-4, 1.0 - u_threshold);
        acc = vec3(g * u_warmth, g * 0.4, g / max(1e-4, u_warmth));
    } else {
        acc = max(vec3(0.0), acc - u_threshold) / max(1e-4, 1.0 - u_threshold);
    }
    fragColor = vec4(acc, 1.0);
}
