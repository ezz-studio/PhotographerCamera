#version 300 es
// vignette.frag — radial darkening, final pass.
//
// UV CONVENTION: v_uv at a fragment centre is ((x + 0.5)/w, (y + 0.5)/h).
// Frame textures are uploaded row-0-first, so v_uv.y counts image rows from the
// TOP and needs NO flip. u_center must therefore be supplied in the same image
// space as the profile's `vignette.center` (y down). Any camera-orientation
// transform is applied earlier, in the first pass of the chain.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_center;
uniform float u_amount;
uniform float u_radius;
uniform float u_feather;

#include "vignette.glsl"

void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    fragColor = vec4(pc_vignette(c, v_uv, u_center, u_amount, u_radius, u_feather), 1.0);
}
