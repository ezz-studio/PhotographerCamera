#version 300 es
// bloom.frag — add a pre-blurred bright extraction to the image.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;   // main image
uniform sampler2D u_bloom;   // renderer: Gaussian-blurred bright extraction
uniform float u_amount;

#include "bloom.glsl"

void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    vec3 bloomed = texture(u_bloom, v_uv).rgb;
    fragColor = vec4(pc_bloom(c, bloomed, u_amount), 1.0);
}
