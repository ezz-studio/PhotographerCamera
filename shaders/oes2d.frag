#version 300 es
// oes2d.frag — copy an EXTERNAL_OES camera texture into a standard 2D RGBA16F
// texture (GLSL cannot sample OES inside the multi-pass chain) and apply the
// sensor->display UV rotation (u_rot, 2x2).
precision highp float;

#extension GL_OES_EGL_image_external_essl3 : require
uniform samplerExternalOES u_input;
uniform mat2 u_rot;

in vec2 v_uv;
out vec4 fragColor;

void main() {
    vec2 uv = u_rot * v_uv;
    fragColor = texture(u_input, uv);
}
