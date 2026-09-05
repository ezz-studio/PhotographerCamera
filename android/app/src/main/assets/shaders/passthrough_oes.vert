#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat4 u_stMatrix;
uniform vec4 u_uvWin;
out vec2 v_uv;
void main() {
    vec2 baseUv = (a_pos + 1.0) * 0.5;
    vec2 mapped = (u_stMatrix * vec4(baseUv, 0.0, 1.0)).xy;
    v_uv = u_uvWin.xy * mapped + u_uvWin.zw;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
