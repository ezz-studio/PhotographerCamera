#version 300 es
// mainchain.frag — merged point/matrix chain:
// Exposure -> White Balance -> Color Matrix -> Tone Curve -> Highlight -> Shadow -> HSL.
// Sharpen is a separate pass (needs a neighbo(u)r blur); Bloom/Halation/Grain/Noise/Vignette
// are separate passes per docs/rendering_pipeline.md.
precision highp float;

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform float u_exposure;
uniform vec2  u_wb;          // x = temperature_bias, y = tint_bias
uniform mat3  u_colorMatrix; // uploaded TRANSPOSED (see color_matrix.glsl)
uniform sampler2D u_toneLut; // 1D LUT (width>=256, height=1)
uniform sampler2D u_hslLut;  // 1D LUT (256x1): R=sat, G=light, B=hueShift(deg)
uniform vec3  u_highlight;   // x=threshold, y=strength, z=saturation(reserved)
uniform vec3  u_shadow;      // x=black_point, y=compression, z=saturation

#include "common.glsl"
#include "exposure.glsl"
#include "white_balance.glsl"
#include "color_matrix.glsl"
#include "tone_curve.glsl"
#include "highlight.glsl"
#include "shadow.glsl"
#include "hsl.glsl"

void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    c = pc_exposure(c, u_exposure);
    c = pc_white_balance(c, u_wb.x, u_wb.y);
    c = pc_color_matrix(c, u_colorMatrix);
    c = pc_tone_curve(c, u_toneLut);
    c = pc_highlight(c, u_highlight.x, u_highlight.y, u_highlight.z);
    c = pc_shadow(c, u_shadow.x, u_shadow.y, u_shadow.z);
    c = pc_hsl(c, u_hslLut);
    fragColor = vec4(c, 1.0);
}
