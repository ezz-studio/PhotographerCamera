#version 300 es
// sharpen.frag — unsharp mask pass (3x3 Gaussian blur of the input, then c + amount*(c-blur)).
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_texel;   // 1.0 / resolution
uniform float u_amount;
uniform float u_radius;  // 1.0 default

#include "common.glsl"
#include "sharpen.glsl"

void main() {
    if (u_amount <= 0.0) { fragColor = texture(u_input, v_uv); return; }
    vec3 c = texture(u_input, v_uv).rgb;
    vec3 acc = vec3(0.0);
    float wt = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float kw = (i == 0 && j == 0) ? 4.0 : ((i == 0 || j == 0) ? 2.0 : 1.0);
            acc += texture(u_input, v_uv + vec2(float(i), float(j)) * u_texel * u_radius).rgb * kw;
            wt += kw;
        }
    }
    vec3 blur = acc / wt;
    fragColor = vec4(pc_sharpen(c, blur, u_amount), 1.0);
}
