// color_matrix.glsl — 3x3 color transform (e.g. white-balance mix, film emulation).
//
// UPLOAD CONTRACT: the renderer uploads M^T (transposed) into u_colorMatrix.
// GLSL mat3 is column-major and computes `m * c`, so with M^T uploaded this becomes
// M^T * c, which equals the CPU reference's row-vector math v @ M^T. Do NOT transpose
// again on the GLSL side.
vec3 pc_color_matrix(vec3 c, mat3 m) {
    return clamp(m * c, 0.0, 1.0);
}
