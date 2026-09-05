// tone_curve.glsl — Tone Curve applied per-channel via a 1D LUT texture.
//
// LUT CONTRACT (see tools/glsl_reference.py::bake_tone_lut):
//   size      : 1024 x 1 texels (TONE_LUT_SIZE)
//   texel i   : curve((i + 0.5) / 1024)  <-- baked at TEXEL CENTRES
//   channels  : R (single channel is enough; G/B may mirror R)
//   filtering : GL_LINEAR + GL_CLAMP_TO_EDGE (mandatory)
// Because the LUT is baked at texel centres, a GL_LINEAR fetch at u = c is the
// piecewise-linear curve itself; residual vs. the continuous CPU reference is the
// LUT discretisation only (< 1e-4 for control-point slopes of order 1).
vec3 pc_tone_curve(vec3 c, sampler2D lut) {
    float yr = texture(lut, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r;
    float yg = texture(lut, vec2(clamp(c.g, 0.0, 1.0), 0.5)).r;
    float yb = texture(lut, vec2(clamp(c.b, 0.0, 1.0), 0.5)).r;
    return vec3(yr, yg, yb);
}
