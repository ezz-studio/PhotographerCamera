#version 300 es
layout(location = 0) in vec2 a_pos;
uniform vec4 u_uvWin;
out vec2 v_uv;
void main() {
    vec2 baseUv = (a_pos + 1.0) * 0.5;
    v_uv = u_uvWin.xy * baseUv + u_uvWin.zw;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
