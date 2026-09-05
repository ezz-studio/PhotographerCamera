#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_input;
uniform sampler2D u_bloom;
uniform float u_amount;
void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    vec3 b = texture(u_bloom, v_uv).rgb;
    vec3 outc = c + b * u_amount;
    fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);
}
