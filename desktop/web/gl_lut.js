/* PhotographerCamera Studio — client-side full-pipeline WebGL2 renderer.
 *
 * Two passes, all on the client's GPU, pixel-identical to the desktop reference
 * (tools/profile_renderer.py) for the colour chain and functionally matched for
 * the spatial layers:
 *
 *   Pass 1 (-> FBO): lens(optical) -> exposure -> white balance -> color matrix
 *                    -> tone curve -> highlight -> shadow -> film curve -> HSL
 *   Pass 2 (-> screen): sharpen -> bloom -> halation -> lens bloom -> lens flare
 *                    -> vignette -> grain -> noise
 *
 * The colour chain reuses the AUTHORITATIVE shaders/shaders/*.glsl verbatim
 * (pc_exposure / pc_white_balance / pc_color_matrix / pc_tone_curve /
 * pc_highlight / pc_shadow / pc_hsl) so GPU output matches the CPU reference and
 * the exported .cube / Android App. bloom/halation/lens glow are single-pass
 * bright-extraction approximations (the App uses dedicated multi-pass blurs) —
 * fine for live tuning; the authoritative full-fidelity pass stays server-side
 * on export / on-device.
 *
 * No image pixel ever leaves the browser.
 */
(function (global) {
  "use strict";

  const VERT = `#version 300 es
in vec2 aPos; out vec2 v_uv;
void main(){ v_uv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }`;

  // Shared helpers (from shaders/common.glsl)
  const HELPERS = `
float pc_luma(vec3 c){ return (c.r + c.g + c.b) / 3.0; }
vec3 pc_rgb2hsv(vec3 c){
  float mx = max(c.r, max(c.g, c.b)); float mn = min(c.r, min(c.g, c.b));
  float v = mx; float s = mx > 0.0 ? (mx - mn) / mx : 0.0;
  float diff = mx - mn; float dv = diff == 0.0 ? 1.0 : diff;
  float rc = (mx - c.r) / dv; float gc = (mx - c.g) / dv; float bc = (mx - c.b) / dv;
  float h; if (mx == c.r) h = bc - gc; else if (mx == c.g) h = 2.0 + rc - bc; else h = 4.0 + gc - rc;
  h = fract(h / 6.0); return vec3(h, s, v);
}
vec3 pc_hsv2rgb(vec3 hsv){
  float h = hsv.x; float s = hsv.y; float v = hsv.z;
  float i = floor(h * 6.0); float f = h * 6.0 - i;
  float p = v * (1.0 - s); float q = v * (1.0 - f * s); float t = v * (1.0 - (1.0 - f) * s);
  vec3 rgb; int idx = int(mod(i, 6.0));
  if (idx == 0) rgb = vec3(v, t, p); else if (idx == 1) rgb = vec3(q, v, p);
  else if (idx == 2) rgb = vec3(p, v, t); else if (idx == 3) rgb = vec3(p, q, v);
  else if (idx == 4) rgb = vec3(t, p, v); else rgb = vec3(v, p, q);
  return rgb;
}
int pc_hue_index(float hd){
  if (hd >= 345.0 || hd < 15.0) return 0; if (hd < 45.0) return 1; if (hd < 70.0) return 2;
  if (hd < 160.0) return 3; if (hd < 200.0) return 4; if (hd < 260.0) return 5; return 6;
}`;

  // ---- Pass 1: colour chain + optical lens (verbatim pc_* from shaders/*.glsl)
  const PASS1 = `#version 300 es
precision highp float;
in vec2 v_uv; out vec4 fragColor;
uniform sampler2D u_input;
uniform vec2 u_texel;
uniform float u_exposure;
uniform vec2 u_wb;
uniform mat3 u_colorMatrix;
uniform sampler2D u_toneLut;
uniform vec3 u_highlight;
uniform vec3 u_shadow;
uniform float u_shadowContrast;
uniform vec2 u_film;
uniform sampler2D u_hslLut;
uniform highp sampler3D u_lut3d;
uniform float u_useLut;
uniform float u_lutN;
uniform float u_lensDistort, u_lensCA, u_lensFalloff, u_lensVignette, u_lensBloom, u_lensFlare;
${HELPERS}
vec3 pc_exposure(vec3 c, float bias){ if (bias == 0.0) return c; return clamp(c * pow(2.0, bias), 0.0, 1.0); }
vec3 pc_white_balance(vec3 c, float temp, float tint){
  if (temp == 0.0 && tint == 0.0) return c; vec3 o = c;
  o.r *= (1.0 + temp * 0.2); o.b *= (1.0 - temp * 0.2);
  o.r *= (1.0 + tint * 0.1); o.b *= (1.0 + tint * 0.1); o.g *= (1.0 - tint * 0.1);
  return clamp(o, 0.0, 1.0);
}
vec3 pc_color_matrix(vec3 c, mat3 m){ return clamp(c * m, 0.0, 1.0); }
vec3 pc_tone_curve(vec3 c, sampler2D lut){
  float yr = texture(lut, vec2(clamp(c.r,0.0,1.0),0.5)).r;
  float yg = texture(lut, vec2(clamp(c.g,0.0,1.0),0.5)).r;
  float yb = texture(lut, vec2(clamp(c.b,0.0,1.0),0.5)).r;
  return vec3(yr, yg, yb);
}
vec3 pc_highlight(vec3 c, float threshold, float strength, float sat){
  if (strength <= 0.0) return c; float l = pc_luma(c);
  float amt = clamp((l - threshold) / max(1e-4, 1.0 - threshold), 0.0, 1.0) * strength;
  return clamp(c - amt * (c - threshold) * (1.0 - amt) * 0.5, 0.0, 1.0);
}
vec3 pc_shadow(vec3 c, float black_point, float compression, float saturation, float contrast){
  vec3 o = c;
  if (black_point > 0.0) o = (o - black_point) / max(1e-4, 1.0 - black_point);
  if (compression > 0.0){ float l = pc_luma(o); float m = clamp(1.0 - l, 0.0, 1.0); o = o + compression * m * (0.5 - o) * 0.5; }
  if (contrast != 1.0){ o = clamp((o - 0.5) * contrast + 0.5, -1.0, 1.0); }
  if (saturation != 1.0){ float l = pc_luma(o); o = clamp(l + (o - l) * saturation, 0.0, 1.0); }
  return clamp(o, 0.0, 1.0);
}
vec3 pc_hsl(vec3 c, sampler2D hslLut){
  vec3 hsv = pc_rgb2hsv(c); float hd = hsv.x * 360.0; int idx = pc_hue_index(hd);
  float u = (float(idx) + 0.5) / 7.0; vec3 pl = texture(hslLut, vec2(u, 0.5)).rgb;
  // encoding (lut3d.js bakeHslLUT): sat/light = value/4 (0..4), hue = deg/720 + 128/255
  // (byte 128 encodes hue 0 exactly — decode must use 128/255, not 0.5, else +1.4 deg bias)
  float h2 = fract(hsv.x + (pl.b - 0.50196078431) * 2.0);
  float s2 = clamp(hsv.y * pl.r * 4.0, 0.0, 1.0); float v2 = clamp(hsv.z * pl.g * 4.0, 0.0, 1.0);
  return clamp(pc_hsv2rgb(vec3(h2, s2, v2)), 0.0, 1.0);
}
float pc_film_ch(float x, float lo, float hi, float kt, float kh){
  if (x < kt){ float t = x/kt; return lo + (kt-lo)*t*t*(2.0-t); }
  if (x > kh){ float t2 = (x-kh)/(1.0-kh); return kh + (hi-kh)*t2*(1.0+t2-t2*t2); }
  return x;
}
vec3 pc_film(vec3 c, float floorv, float ceilv){
  if (floorv <= 0.0 && ceilv >= 255.0) return c;
  float lo = clamp(floorv/255.0,0.0,0.4); float hi = clamp(ceilv/255.0,0.6,1.0);
  float kt = clamp(lo+0.13,0.04,0.45); float kh = clamp(hi-0.17,0.55,0.97);
  return clamp(vec3(
    pc_film_ch(c.r, lo, hi, kt, kh),
    pc_film_ch(c.g, lo, hi, kt, kh),
    pc_film_ch(c.b, lo, hi, kt, kh)), 0.0, 1.0);
}
vec2 radial(vec2 uv){ return (uv - 0.5) * 2.0; }
vec3 sampleLens(vec2 uv){
  vec2 n = radial(uv); float r2 = dot(n, n);
  float f = 1.0 + 0.35 * u_lensDistort * r2;
  vec2 wuv = 0.5 + n * f * 0.5;
  vec3 o;
  o.r = texture(u_input, 0.5 + n * (f * (1.0 - 0.015 * u_lensCA)) * 0.5).r;
  o.g = texture(u_input, wuv).g;
  o.b = texture(u_input, 0.5 + n * (f * (1.0 + 0.015 * u_lensCA)) * 0.5).b;
  if (u_lensFalloff > 1e-4){
    float r = sqrt(r2) / 1.4142; float wgt = clamp(u_lensFalloff * 1.2 * clamp(r-0.25,0.0,1.0), 0.0, 0.85);
    vec3 blur = vec3(0.0); float wt = 0.0;
    for (int j=-1;j<=1;j++) for (int i=-1;i<=1;i++){
      float kw = (i==0&&j==0)?4.0:((i==0||j==0)?2.0:1.0);
      blur += texture(u_input, wuv + vec2(float(i),float(j))*u_texel*4.0).rgb * kw; wt += kw;
    }
    o = mix(o, blur/wt, wgt);
  }
  return clamp(o, 0.0, 1.0);
}
void main(){
  vec3 src = sampleLens(v_uv);
  if (u_lensVignette > 1e-4){
    vec2 n = radial(v_uv); float r = sqrt(dot(n,n))/1.4142;
    float fall = pow(clamp(r-0.3,0.0,1.0), 1.5);
    src *= (1.0 - u_lensVignette * 0.75 * fall);
  }
  vec3 c = src;
  if (u_useLut > 0.5) {
    // Learned 3D LUT (the trained look) -> film curve -> parametric chain as
    // the POST-LUT TUNING layer. Training writes identity params, so a fresh
    // profile renders exactly LUT->film; the sliders then act as live
    // fine-tuning on top. Synced with profile_renderer.render()'s LUT branch.
    // Coordinate remap: CPU lattice at c*(n-1) <=> GL texel centres at (i+.5)/n.
    vec3 coord = (clamp(src, 0.0, 1.0) * (u_lutN - 1.0) + 0.5) / u_lutN;
    c = texture(u_lut3d, coord).rgb;
    c = pc_film(c, u_film.x, u_film.y);
    c = pc_exposure(c, u_exposure);
    c = pc_white_balance(c, u_wb.x, u_wb.y);
    c = pc_color_matrix(c, u_colorMatrix);
    c = pc_highlight(c, u_highlight.x, u_highlight.y, u_highlight.z);
    c = pc_shadow(c, u_shadow.x, u_shadow.y, u_shadow.z, u_shadowContrast);
    c = pc_tone_curve(c, u_toneLut);
    c = pc_hsl(c, u_hslLut);
  } else {
  c = pc_exposure(c, u_exposure);
  c = pc_white_balance(c, u_wb.x, u_wb.y);
  c = pc_color_matrix(c, u_colorMatrix);
  c = pc_highlight(c, u_highlight.x, u_highlight.y, u_highlight.z);
  c = pc_shadow(c, u_shadow.x, u_shadow.y, u_shadow.z, u_shadowContrast);
  c = pc_film(c, u_film.x, u_film.y);
  c = pc_tone_curve(c, u_toneLut);
  c = pc_hsl(c, u_hslLut);
  }
  fragColor = vec4(c, 1.0);
}`;

  // ---- Pass 2: spatial layers (from shaders/*.glsl, bloom/halation/lens = bright-blur approx)
  const PASS2 = `#version 300 es
precision highp float;
in vec2 v_uv; out vec4 fragColor;
uniform sampler2D u_color;
uniform vec2 u_texel;
uniform vec3 u_vignette; uniform vec2 u_vignCenter;
uniform vec3 u_bloom;
uniform vec4 u_halation;
uniform vec3 u_lensBloom;
uniform vec3 u_lensFlare;
uniform vec3 u_grain; uniform vec2 u_noise;
uniform vec2 u_sharpen;
uniform float u_seed; uniform vec2 u_resolution;
${HELPERS}
vec3 pc_vignette(vec3 c, vec2 uv, vec2 center, float amount, float radius, float feather){
  if (amount <= 0.0) return c; vec2 d = uv - center;
  float dist = length(d) / (radius * 0.7071);
  float m = 1.0 - amount * clamp((dist - (1.0 - feather)) / max(1e-4, feather), 0.0, 1.0);
  return clamp(c * m, 0.0, 1.0);
}
vec3 pc_grain(vec3 c, float n, float amount, float size, float density){
  if (amount <= 0.0) return c; float scale = amount * 0.08 * density / max(0.5, size);
  return clamp(c + n * scale, 0.0, 1.0);
}
vec3 pc_noise(vec3 c, float nl, float ncr, float luma, float chroma){
  if (luma <= 0.0 && chroma <= 0.0) return c; vec3 o = c;
  if (luma > 0.0) o += nl * luma * 0.05;
  if (chroma > 0.0){ o.r += ncr * chroma * 0.04; o.b += ncr * chroma * 0.04; }
  return clamp(o, 0.0, 1.0);
}
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }
vec3 brightBlur(vec2 uv, float radius, float threshold){
  vec3 acc = vec3(0.0);
  for (int k=0;k<12;k++){
    float a = float(k)/12.0 * 6.2831853;
    float rr = radius * (1.0 + mod(float(k), 3.0));
    vec2 off = vec2(cos(a), sin(a)) * rr * u_texel;
    vec3 s = texture(u_color, uv + off).rgb;
    acc += clamp(s - threshold, 0.0, 1.0) / max(1e-4, 1.0 - threshold);
  }
  return acc / 12.0;
}
void main(){
  vec2 uv = v_uv; vec3 c = texture(u_color, uv).rgb;
  if (u_sharpen.x > 0.0){
    vec3 acc = vec3(0.0); float wt = 0.0;
    for (int j=-1;j<=1;j++) for (int i=-1;i<=1;i++){
      float kw = (i==0&&j==0)?4.0:((i==0||j==0)?2.0:1.0);
      acc += texture(u_color, uv + vec2(float(i),float(j))*u_texel*u_sharpen.y).rgb * kw; wt += kw;
    }
    c = clamp(c + u_sharpen.x * (c - acc/wt), 0.0, 1.0);
  }
  if (u_bloom.x > 0.0) c = clamp(c + u_bloom.x * brightBlur(uv, u_bloom.z, u_bloom.y), 0.0, 1.0);
  if (u_halation.x > 0.0){
    vec3 bl = brightBlur(uv, u_halation.z, u_halation.y); float g = pc_luma(bl);
    c.r += u_halation.x * g * u_halation.w; c.g += u_halation.x * g * 0.4;
    c.b += u_halation.x * g * (1.0/max(1e-4, u_halation.w)); c = clamp(c, 0.0, 1.0);
  }
  if (u_lensBloom.x > 0.0) c = clamp(c + u_lensBloom.x * brightBlur(uv, u_lensBloom.z, u_lensBloom.y), 0.0, 1.0);
  if (u_lensFlare.x > 0.0){
    float g = 0.0;
    for (int k=-8;k<=8;k++){ vec3 s = texture(u_color, vec2(uv.x + float(k)*u_texel.x*2.0, uv.y)).rgb; g += clamp(pc_luma(s)-u_lensFlare.y,0.0,1.0)/max(1e-4,1.0-u_lensFlare.y); }
    g /= 17.0; c = clamp(c + u_lensFlare.x * vec3(0.85,0.92,1.0) * g, 0.0, 1.0);
  }
  if (u_vignette.x > 0.0) c = pc_vignette(c, uv, u_vignCenter, u_vignette.x, u_vignette.y, u_vignette.z);
  vec2 p = uv * u_resolution;
  float n = hash(p + vec2(u_seed, u_seed)) - 0.5;
  c = pc_grain(c, n, u_grain.x, u_grain.y, u_grain.z);
  float nl = hash(p.yx + vec2(u_seed+1.3, u_seed+1.3)) - 0.5;
  float ncr = hash(p + vec2(u_seed+2.7, u_seed+2.7)) - 0.5;
  c = pc_noise(c, nl, ncr, u_noise.x, u_noise.y);
  fragColor = vec4(c, 1.0);
}`;

  function compile(gl, type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS))
      throw new Error(gl.getShaderInfoLog(s) || "shader compile error");
    return s;
  }
  function program(gl, fragSrc) {
    const vs = compile(gl, gl.VERTEX_SHADER, VERT);
    const fs = compile(gl, gl.FRAGMENT_SHADER, fragSrc);
    const p = gl.createProgram();
    gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS))
      throw new Error(gl.getProgramInfoLog(p) || "link error");
    return p;
  }
  function locs(gl, p, names) {
    const o = {};
    for (const n of names) o[n] = gl.getUniformLocation(p, n);
    return o;
  }

  function create(canvas) {
    const gl = canvas.getContext("webgl2", { alpha: false, antialias: false, preserveDrawingBuffer: true });
    if (!gl) return { ok: false };

    let prog1, prog2, fbo, texColor, texSrc, quad;
    try {
      prog1 = program(gl, PASS1);
      prog2 = program(gl, PASS2);
      quad = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      fbo = gl.createFramebuffer();
      texColor = gl.createTexture();
      texSrc = gl.createTexture();
    } catch (e) {
      console.error("[GLRender]", e);
      return { ok: false };
    }

    const L1 = locs(gl, prog1, [
      "u_input", "u_texel", "u_exposure", "u_wb", "u_colorMatrix", "u_toneLut",
      "u_highlight", "u_shadow", "u_shadowContrast", "u_film", "u_hslLut",
      "u_lut3d", "u_useLut", "u_lutN",
      "u_lensDistort", "u_lensCA", "u_lensFalloff", "u_lensVignette", "u_lensBloom", "u_lensFlare",
    ]);
    const L2 = locs(gl, prog2, [
      "u_color", "u_texel", "u_vignette", "u_vignCenter", "u_bloom", "u_halation",
      "u_lensBloom", "u_lensFlare", "u_grain", "u_noise", "u_sharpen", "u_seed", "u_resolution",
    ]);

    const toneTex = gl.createTexture();
    const hslTex = gl.createTexture();
    let lutTex3D = gl.createTexture();
    let N = 33;

    // Decode an embedded stylefit color_lut payload into a 3D texture.
    // Layout: uint8 RGB triplets, R fastest (r + n*(g + n*b)) — matches
    // stylefit.lut3d.to_payload / from_payload. Returns true on success.
    function setLut3D(payload) {
      try {
        if (!payload || !payload.data) return false;
        const n = payload.size || 33;
        const raw = atob(payload.data);
        const cells = n * n * n;
        if (raw.length !== cells * 3) return false;
        const rgba = new Uint8Array(cells * 4);
        for (let i = 0, j = 0, k = 0; i < cells; i++) {
          rgba[j++] = raw.charCodeAt(k++);
          rgba[j++] = raw.charCodeAt(k++);
          rgba[j++] = raw.charCodeAt(k++);
          rgba[j++] = 255;
        }
        gl.activeTexture(gl.TEXTURE3);
        gl.bindTexture(gl.TEXTURE_3D, lutTex3D);
        // UNPACK_FLIP_Y_WEBGL is illegal for texImage3D (INVALID_OPERATION ->
        // empty texture -> black output); setImage() leaves it enabled for 2D.
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGBA8, n, n, n, 0, gl.RGBA, gl.UNSIGNED_BYTE, rgba);
        gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE);
        N = n;
        return true;
      } catch (e) {
        return false;
      }
    }

    function uploadTex2D(tex, w, h, data, filter) {
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    }

    function setImage(src) {
      const w = src.naturalWidth || src.width || 1;
      const h = src.naturalHeight || src.height || 1;
      const cap = 2400;
      const sc = Math.min(1, cap / Math.max(w, h));
      canvas.width = Math.max(1, Math.round(w * sc));
      canvas.height = Math.max(1, Math.round(h * sc));
      gl.bindTexture(gl.TEXTURE_2D, texSrc);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      // (re)allocate FBO colour target
      gl.bindTexture(gl.TEXTURE_2D, texColor);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, canvas.width, canvas.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texColor, 0);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    }

    function setLUTs(profile) {
      const tone = LUT3D.bakeToneLUT(profile);
      const tbuf = new Uint8Array(1024 * 4);
      for (let i = 0; i < 1024; i++) {
        const v = Math.round(clamp01(tone[i]) * 255);
        tbuf[i * 4] = v; tbuf[i * 4 + 1] = v; tbuf[i * 4 + 2] = v; tbuf[i * 4 + 3] = 255;
      }
      uploadTex2D(toneTex, 1024, 1, tbuf, gl.LINEAR);
      const hsl = LUT3D.bakeHslLUT(profile);
      uploadTex2D(hslTex, 7, 1, hsl, gl.NEAREST);
    }

    const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);
    const num = (v, d) => (v == null ? d : v);

    function render(profile) {
      profile = profile || {};
      const ex = profile.exposure || {}, wb = profile.white_balance || {};
      const cm = (profile.color_matrix && profile.color_matrix.matrix_3x3) || null;
      const hr = profile.highlight_rolloff || {}, sh = profile.shadow || {};
      const fc = profile.film_curve || {};
      const vg = profile.vignette || {}, bl = profile.bloom || {}, hl = profile.halation || {};
      const gr = profile.grain || {}, ns = profile.noise || {}, sp = profile.sharpen || {};
      const lens = profile.lens || {};

      setLUTs(profile);

      const aPos1 = gl.getAttribLocation(prog1, "aPos");
      gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.enableVertexAttribArray(aPos1);
      gl.vertexAttribPointer(aPos1, 2, gl.FLOAT, false, 0, 0);

      // ---- Pass 1 -> FBO
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(prog1);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, texSrc); gl.uniform1i(L1.u_input, 0);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, toneTex); gl.uniform1i(L1.u_toneLut, 1);
      gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, hslTex); gl.uniform1i(L1.u_hslLut, 2);
      const hasLut = (profile.color_layer === "lut") && setLut3D(profile.color_lut);
      gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_3D, lutTex3D); gl.uniform1i(L1.u_lut3d, 3);
      gl.uniform1f(L1.u_useLut, hasLut ? 1.0 : 0.0);
      gl.uniform1f(L1.u_lutN, N);
      gl.uniform2f(L1.u_texel, 1 / canvas.width, 1 / canvas.height);
      gl.uniform1f(L1.u_exposure, num(ex.bias, 0));
      gl.uniform2f(L1.u_wb, num(wb.temperature_bias, 0), num(wb.tint_bias, 0));
      if (cm) gl.uniformMatrix3fv(L1.u_colorMatrix, false, [cm[0][0], cm[0][1], cm[0][2], cm[1][0], cm[1][1], cm[1][2], cm[2][0], cm[2][1], cm[2][2]]);
      else gl.uniformMatrix3fv(L1.u_colorMatrix, false, [1, 0, 0, 0, 1, 0, 0, 0, 1]);
      gl.uniform3f(L1.u_highlight, num(hr.threshold, 0.8), num(hr.strength, 0), num(hr.saturation, 1));
      gl.uniform3f(L1.u_shadow, num(sh.black_point, 0), num(sh.compression, 0), num(sh.saturation, 1));
      gl.uniform1f(L1.u_shadowContrast, num(sh.contrast, 1));
      gl.uniform2f(L1.u_film, num(fc.shadow_floor, 8), num(fc.highlight_ceiling, 248));
      gl.uniform1f(L1.u_lensDistort, num(lens.distortion, 0));
      gl.uniform1f(L1.u_lensCA, num(lens.chromatic_aberration, 0));
      gl.uniform1f(L1.u_lensFalloff, num(lens.sharpness_falloff, 0));
      gl.uniform1f(L1.u_lensVignette, num(lens.vignette, 0));
      gl.uniform1f(L1.u_lensBloom, num(lens.bloom, 0));
      gl.uniform1f(L1.u_lensFlare, num(lens.flare, 0));
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

      // ---- Pass 2 -> screen
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(prog2);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, texColor); gl.uniform1i(L2.u_color, 0);
      gl.uniform2f(L2.u_texel, 1 / canvas.width, 1 / canvas.height);
      const vc = vg.center || [0.5, 0.5];
      gl.uniform3f(L2.u_vignette, num(vg.amount, 0), num(vg.radius, 1), num(vg.feather, 0.5));
      gl.uniform2f(L2.u_vignCenter, num(vc[0], 0.5), num(vc[1], 0.5));
      gl.uniform3f(L2.u_bloom, num(bl.amount, 0), num(bl.threshold, 0.9), num(bl.radius, 1));
      gl.uniform4f(L2.u_halation, num(hl.amount, 0), num(hl.threshold, 0.9), num(hl.radius, 1), num(hl.warmth, 1));
      gl.uniform3f(L2.u_lensBloom, num(lens.bloom, 0), 0.82, 1);
      gl.uniform3f(L2.u_lensFlare, num(lens.flare, 0), 0.9, 1);
      gl.uniform3f(L2.u_grain, num(gr.amount, 0), num(gr.size, 1), num(gr.density, 1));
      gl.uniform2f(L2.u_noise, num(ns.luma, 0), num(ns.chroma, 0));
      gl.uniform2f(L2.u_sharpen, num(sp.amount, 0), num(sp.radius, 1));
      gl.uniform1f(L2.u_seed, Math.random() * 1000.0);
      gl.uniform2f(L2.u_resolution, canvas.width, canvas.height);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }

    return { ok: true, canvas, setImage, render };
  }

  global.GLRender = { create };
})(window);
