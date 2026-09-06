#version 300 es
precision highp float;

// Chroma denoise pass (RAW ISP chain only): 5x5 edge-stopped CHROMA smoothing.
//
// Why: the RAW path bypasses the HAL ISP entirely, so the multi-frame noise
// reduction the vendor HAL applies to its own JPEG/YUV output never runs.
// Raw demosaic at 12.5MP therefore shows strong per-pixel COLOR noise
// (luma noise is film-grain-like and stays). This pass averages the CHROMA
// (rgb - luma) of a 5x5 neighborhood with
//   - spatial falloff        (closer taps weigh more)
//   - luma edge-stop         (taps across a brightness edge are suppressed,
//                             so real detail/edges keep their color)
// and re-attaches the SMOOTHED chroma to the pixel's OWN luma: luma detail
// (perceived sharpness) is preserved bit-exact, only color noise is averaged
// away. YUV/JPEG paths never run this pass - the HAL already denoised those.

in vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_input;
uniform vec2  u_texel;   // 1/width, 1/height of the input
uniform float u_amount;  // 0..1 blend strength

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec3 c0 = texture(u_input, v_uv).rgb;
    float y0 = luma(c0);

    vec3 chroma = vec3(0.0);
    float wsum = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            if (dx == 0 && dy == 0) continue;
            vec3 n = texture(u_input, v_uv + vec2(float(dx), float(dy)) * u_texel).rgb;
            float yn = luma(n);
            float wd = 1.0 / (1.0 + float(dx * dx + dy * dy));  // spatial falloff
            float we = 1.0 / (1.0 + abs(yn - y0) * 16.0);       // luma edge stop
            float w = wd * we;
            chroma += (n - vec3(yn)) * w;
            wsum += w;
        }
    }
    chroma /= max(wsum, 1e-4);

    vec3 o = max(vec3(y0) + chroma, vec3(0.0));
    fragColor = vec4(mix(c0, o, u_amount), 1.0);
}
