#version 300 es
// blur.frag — separable 5-tap Gaussian, used by Bloom/Halation downsample/upsample.
// u_dir = (1,0) for horizontal, (0,1) for vertical.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_texel;  // 1.0 / (current buffer resolution)
uniform vec2  u_dir;

void main() {
    float w0 = 0.0625, w1 = 0.25, w2 = 0.375, w3 = 0.25, w4 = 0.0625;
    vec3 acc = texture(u_input, v_uv).rgb * w2;
    acc += texture(u_input, v_uv + u_dir * 1.0 * u_texel).rgb * w1;
    acc += texture(u_input, v_uv - u_dir * 1.0 * u_texel).rgb * w1;
    acc += texture(u_input, v_uv + u_dir * 2.0 * u_texel).rgb * w0;
    acc += texture(u_input, v_uv - u_dir * 2.0 * u_texel).rgb * w0;
    fragColor = vec4(acc, 1.0);
}
