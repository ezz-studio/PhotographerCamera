#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_input;
uniform vec2 u_center;
uniform float u_amount;
uniform float u_radius;
uniform float u_feather;
uniform vec4 u_vwin;
void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    vec2 uvw = (v_uv - u_vwin.xy) / max(u_vwin.zw, vec2(1e-4)) + 0.5;
    vec2 dv = uvw - u_center;
    float r = length(dv);
    float t = clamp((r - u_radius) / max(1e-4, u_feather), 0.0, 1.0);
    float k = 1.0 - u_amount * t * t * (3.0 - 2.0 * t);
    fragColor = vec4(c * k, 1.0);
}
