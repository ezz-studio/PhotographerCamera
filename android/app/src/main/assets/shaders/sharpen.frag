#version 300 es
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform sampler2D u_input;
uniform vec2 u_texel;
uniform float u_amount;
uniform float u_radius;
void main() {
    vec3 c = texture(u_input, v_uv).rgb;
    if (u_amount <= 0.0) { fragColor = vec4(c, 1.0); return; }
    vec3 acc = vec3(0.0);
    float wt = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float kw = (i == 0 && j == 0) ? 4.0 : ((i == 0 || j == 0) ? 2.0 : 1.0);
            vec2 off = vec2(float(i), float(j)) * u_texel * u_radius;
            acc += texture(u_input, v_uv + off).rgb * kw;
            wt += kw;
        }
    }
    vec3 blur = acc / wt;
    vec3 result = c + u_amount * (c - blur);
    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
