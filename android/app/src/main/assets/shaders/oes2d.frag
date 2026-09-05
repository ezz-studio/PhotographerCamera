#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 v_uv;
out vec4 fragColor;
uniform samplerExternalOES u_input;
void main() {
    fragColor = texture(u_input, v_uv);
}
