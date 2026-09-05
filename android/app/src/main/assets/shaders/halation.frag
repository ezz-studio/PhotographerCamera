#version 300 es
// halation.frag — add a warm/red glow (pre-blurred luma-bright) to highlights.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;  // main image
uniform sampler2D u_hal;    // renderer: Gaussian-blurred glow (scalar replicated to rgb)
uniform float u_amount;
uniform float u_warmth;

#include "halation.glsl"

void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    float glow = texture(u_hal, v_uv).r;
    fragColor = vec4(pc_halation(c, glow, u_amount, u_warmth), 1.0);
}
