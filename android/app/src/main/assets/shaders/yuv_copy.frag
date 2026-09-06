#version 300 es
// yuv_copy.frag - YUV_420_888 direct-capture pass (the "no-JPEG" still path).
//
// The HAL's post-ISP YUV frame (ImageAnalysis, OUTPUT_IMAGE_FORMAT_YUV_420_888)
// arrives UNCOMPRESSED - no JPEG quantization, no baked-in vendor color. The
// Y/U/V planes are uploaded as three GL_R8 textures and converted to RGB here
// with the standard BT.601 studio-swing matrix (CameraX YUV_420_888 follows
// BT.601 limited range per the camera docs: Y in [16,235]/255, UV in [16,240]).
// This keeps the whole conversion in OUR hands (public API only - no hidden
// EGL extension, no driver-defined colorspace) so every device converts
// identically, which is exactly what a style profile demands.
//
// Rotation semantics are IDENTICAL to raw_isp.frag so both capture paths stay
// diagnosable against the same mental model:
//   u_rot: CW degrees (0/90/180/270) buffer -> upright
//   u_win: cover-crop window in UPRIGHT uv (x0,y0,x1,y1)
//   u_mirror: front camera - HAL JPEG output is mirrored by the capture HAL;
//             the raw YUV analysis stream is NOT, so we flip to match.
precision highp float;

uniform sampler2D u_y;   // luma plane  (GL_R8, W x H)
uniform sampler2D u_u;   // chroma U    (GL_R8, W/2 x H/2)
uniform sampler2D u_v;   // chroma V    (GL_R8, W/2 x H/2)
uniform int  u_rot;      // CW degrees (0/90/180/270) buffer -> upright
uniform vec4 u_win;      // cover-crop window in UPRIGHT uv (x0,y0,x1,y1)
uniform int  u_mirror;   // 1 = horizontal flip (front camera)

in vec2 v_uv;
out vec4 fragColor;

// BT.601 studio swing: Y' = (Y - 16/255) * 255/219
//   R = 1.164*Y' + 1.596*(V-0.5)
//   G = 1.164*Y' - 0.392*(U-0.5) - 0.813*(V-0.5)
//   B = 1.164*Y' + 2.017*(U-0.5)
vec3 yuv2rgb(float y, float u, float v) {
    float yy = (y - 0.0625) * 1.164;
    float uu = u - 0.5;
    float vv = v - 0.5;
    return vec3(
        yy + 1.596 * vv,
        yy - 0.392 * uu - 0.813 * vv,
        yy + 2.017 * uu
    );
}

void main() {
    vec2 u = mix(u_win.xy, u_win.zw, v_uv);
    vec2 s = u;
    if (u_rot == 90)       s = vec2(u.y, 1.0 - u.x);
    else if (u_rot == 180) s = vec2(1.0 - u.x, 1.0 - u.y);
    else if (u_rot == 270) s = vec2(1.0 - u.y, u.x);
    if (u_mirror == 1) s.x = 1.0 - s.x;
    float y = texture(u_y, s).r;
    float cb = texture(u_u, s).r;
    float cr = texture(u_v, s).r;
    fragColor = vec4(clamp(yuv2rgb(y, cb, cr), 0.0, 1.0), 1.0);
}
