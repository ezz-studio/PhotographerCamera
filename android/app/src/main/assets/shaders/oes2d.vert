#version 300 es
// oes2d.vert -- Pass 1 of the 2-pass GPU pipeline.
// Samples the camera external OES texture once and writes a stable RGBA
// frame into the intermediate FBO. All 25+ effect samples downstream then
// hit this stable 2D texture instead of repeatedly sampling the external
// OES texture (which can flicker on tile-based GPUs like Mali / Adreno).
//
// Layout (mirrors zoombox LutPreviewRenderer.VERT_SHADER, proven in prod):
//   a_pos      : clip-space quad vertex in [-1, 1]
//   u_stMatrix : 4x4 SurfaceTexture.getTransformMatrix() (HAL rotation)
//   u_uvWin    : (u0, v0, u1, v1) FILL_CENTER crop of the camera buffer
//                in pre-rotation buffer coordinates. Identity when unset.
layout(location = 0) in vec2 a_pos;
uniform mat4 u_stMatrix;
uniform vec4 u_uvWin;
out vec2 v_uv;
void main() {
    vec2 baseUv = (a_pos + 1.0) * 0.5;
    vec2 cropped = vec2(
        mix(u_uvWin.x, u_uvWin.z, baseUv.x),
        mix(u_uvWin.y, u_uvWin.w, baseUv.y)
    );
    v_uv = (u_stMatrix * vec4(cropped, 0.0, 1.0)).xy;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}