#version 300 es
// passthrough.vert — fullscreen quad. a_pos in clip space [-1,1]; uv in [0,1].
precision highp float;

layout(location = 0) in vec2 a_pos;
out vec2 v_uv;

void main() {
    v_uv = (a_pos + 1.0) * 0.5;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
