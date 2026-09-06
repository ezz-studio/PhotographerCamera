#version 300 es
// grain_noise.frag — procedural grain + sensor noise in one pass.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform float u_grain;
uniform float u_grainSize;
uniform float u_grainDensity;
uniform float u_noiseLuma;
uniform float u_noiseChroma;
uniform float u_seed;
uniform vec2  u_resolution; // in pixels, for stable grain frequency

#include "grain.glsl"
#include "noise.glsl"

float hash(vec2 p) {
    // sin-free hash12 — see effect.frag pc_hash note (regular banding at
    // full-res coordinates with the old sin hash).
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    vec2 p = v_uv * u_resolution;
    float n = hash(p + vec2(u_seed, u_seed)) - 0.5;
    c = pc_grain(c, n, u_grain, u_grainSize, u_grainDensity);
    float nl = hash(p.yx + vec2(u_seed + 1.3, u_seed + 1.3)) - 0.5;
    float ncr = hash(p + vec2(u_seed + 2.7, u_seed + 2.7)) - 0.5;
    c = pc_noise(c, nl, ncr, u_noiseLuma, u_noiseChroma);
    fragColor = vec4(c, 1.0);
}
