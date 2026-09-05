#version 300 es
precision highp float;
uniform sampler2D u_input;
in vec2 v_uv;
out vec4 fragColor;
void main() {
    fragColor = texture(u_input, v_uv);
}
