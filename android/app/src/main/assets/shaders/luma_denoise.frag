#version 300 es
precision highp float;

// Luma denoise pass (YUV direct-capture chain only): 5x5 edge-stopped
// BILATERAL smoothing of the full RGB signal.
//
// Why: the YUV_420_888 still output does NOT go through the HAL's
// multi-frame noise reduction (that pipeline only activates for the HAL's
// own JPEG/RAW-develop path), so a single-frame YUV still carries heavy
// luma AND chroma noise. The chroma component is already smoothed by the
// preceding chroma_denoise pass (luma bit-exact there); this pass targets
// the remaining LUMA noise with a bilateral kernel:
//   - spatial falloff        (closer taps weigh more)
//   - luma edge-stop         (taps across a brightness edge are suppressed,
//                             so real detail survives)
//   - chroma edge-stop       (color edges also stop the kernel)
//
// PORT REFERENCE: single-pass bilateral structure per android-gpuimage-plus
// cgeBilateralBlurFilter.cpp (CGE bilateral: space+range weighted average)
// and the classic GPUImageBilateralFilter; kernel topology matches our
// chroma_denoise.frag (same 5x5 loop, weights shared shape) so the two
// passes stay cache-friendly on tile-based GPUs (Mali G1).
//
// Strength is deliberately moderate: over-smoothing luma is what makes
// vendor "beauty-mode" stills look waxy. u_amount 0.4-0.7 is the sweet spot.

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_texel;   // 1/width, 1/height of the input
uniform float u_amount;  // 0..1 blend strength

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec3 c0 = texture(u_input, v_uv).rgb;
    float y0 = luma(c0);

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec3 n = texture(u_input, v_uv + vec2(float(dx), float(dy)) * u_texel).rgb;
            float yn = luma(n);
            float wd = 1.0 / (1.0 + float(dx * dx + dy * dy));   // spatial falloff
            float we = 1.0 / (1.0 + abs(yn - y0) * abs(yn - y0) * 12.0); // luma edge stop
            float wc = 1.0 / (1.0 + dot(n - c0, n - c0) * 24.0); // chroma edge stop
            float w = wd * we * wc;
            sum += n * w;
            wsum += w;
        }
    }
    vec3 o = sum / max(wsum, 1e-4);
    fragColor = vec4(mix(c0, o, u_amount), 1.0);
}
