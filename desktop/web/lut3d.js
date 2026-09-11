/* PhotographerCamera Studio — client-side 3D LUT baker.
 *
 * Mirrors the COLOR layers of tools/profile_renderer.py::render() (the
 * parametric branch) so the in-browser preview is pixel-identical to the
 * server's CPU reference and to the exported .cube LUT:
 *
 *   exposure -> white_balance -> color_matrix -> highlight_rolloff -> shadow
 *   -> film_curve -> tone_curve -> hsl
 *
 * Spatial layers (vignette / bloom / halation / grain / noise / sharpen / lens)
 * are intentionally excluded — they require neighbour samples and cannot live
 * in a 3D LUT; the server export (render_file) keeps applying them. The studio
 * preview therefore shows the colour grade live & locally; the final server
 * export remains the authoritative full-fidelity pass.
 *
 * Baking 33^3 = 35937 grid points in JS takes a few ms; we re-bake on every
 * slider tick (already debounced 220ms) so live tuning is 100% local — no
 * server round-trip, no server CPU.
 */
(function (global) {
  "use strict";

  const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);

  // hue ranges in degrees (matches profile_renderer._HUE_RANGES)
  const HUE_RANGES = {
    orange: [15, 45], yellow: [45, 70], green: [70, 160],
    cyan: [160, 200], blue: [200, 260], purple: [260, 345],
  };

  function applyExposure(grid, bias) {
    if (!bias) return;
    const f = Math.pow(2, bias);
    for (let i = 0; i < grid.length; i++) grid[i] = clamp(grid[i] * f, 0, 1);
  }

  function applyWB(grid, temp, tint) {
    if (!temp && !tint) return;
    const n3 = grid.length / 3;
    const rT = 1 + temp * 0.2, bT = 1 - temp * 0.2;
    const rI = 1 + tint * 0.1, bI = 1 + tint * 0.1, gI = 1 - tint * 0.1;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      grid[b] = clamp(grid[b] * rT * rI, 0, 1);
      grid[b + 1] = clamp(grid[b + 1] * gI, 0, 1);
      grid[b + 2] = clamp(grid[b + 2] * bT * bI, 0, 1);
    }
  }

  function applyColorMatrix(grid, M) {
    if (!M || !M[0]) return;
    const n3 = grid.length / 3;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      const r = grid[b], g = grid[b + 1], bl = grid[b + 2];
      grid[b] = clamp(M[0][0] * r + M[0][1] * g + M[0][2] * bl, 0, 1);
      grid[b + 1] = clamp(M[1][0] * r + M[1][1] * g + M[1][2] * bl, 0, 1);
      grid[b + 2] = clamp(M[2][0] * r + M[2][1] * g + M[2][2] * bl, 0, 1);
    }
  }

  function applyHighlight(grid, threshold, strength) {
    if (!(strength > 0)) return;
    const n3 = grid.length / 3;
    const denom = Math.max(1e-4, 1 - threshold);
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      const lum = (grid[b] + grid[b + 1] + grid[b + 2]) / 3;
      const amt = clamp((lum - threshold) / denom, 0, 1) * strength;
      for (let c = 0; c < 3; c++)
        grid[b + c] = clamp(grid[b + c] - amt * (grid[b + c] - threshold) * (1 - amt) * 0.5, 0, 1);
    }
  }

  function applyShadow(grid, black_point, compression, saturation, contrast) {
    if (black_point <= 0 && compression <= 0 && saturation === 1 && contrast === 1) return;
    const n3 = grid.length / 3;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      let r = grid[b], g = grid[b + 1], bl = grid[b + 2];
      if (black_point > 0) {
        const d = Math.max(1e-4, 1 - black_point);
        r = (r - black_point) / d; g = (g - black_point) / d; bl = (bl - black_point) / d;
      }
      if (compression > 0) {
        const lum = (r + g + bl) / 3;
        const m = clamp(1 - lum, 0, 1);
        const k = compression * m * 0.5;
        r += k * (0.5 - r); g += k * (0.5 - g); bl += k * (0.5 - bl);
      }
      if (contrast !== 1) {
        r = clamp((r - 0.5) * contrast + 0.5, 0, 1);
        g = clamp((g - 0.5) * contrast + 0.5, 0, 1);
        bl = clamp((bl - 0.5) * contrast + 0.5, 0, 1);
      }
      if (saturation !== 1) {
        const lum = (r + g + bl) / 3;
        r = clamp(lum + (r - lum) * saturation, 0, 1);
        g = clamp(lum + (g - lum) * saturation, 0, 1);
        bl = clamp(lum + (bl - lum) * saturation, 0, 1);
      }
      grid[b] = r; grid[b + 1] = g; grid[b + 2] = bl;
    }
  }

  function applyFilmCurve(grid, shadow_floor, highlight_ceiling) {
    if (shadow_floor <= 0 && highlight_ceiling >= 255) return;
    const lo = clamp(shadow_floor / 255, 0, 0.4);
    const hi = clamp(highlight_ceiling / 255, 0.6, 1);
    const kt = clamp(lo + 0.13, 0.04, 0.45);
    const kh = clamp(hi - 0.17, 0.55, 0.97);
    const n3 = grid.length / 3;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      for (let c = 0; c < 3; c++) {
        let x = grid[b + c];
        if (x < kt) { const t = x / kt; x = lo + (kt - lo) * t * t * (2 - t); }
        else if (x > kh) { const t2 = (x - kh) / (1 - kh); x = kh + (hi - kh) * t2 * (1 + t2 - t2 * t2); }
        grid[b + c] = clamp(x, 0, 1);
      }
    }
  }

  // ---- tone curve: bake a 1D LUT at texel centers, then GL_LINEAR-style sample
  function interp(x, xs, ys) {
    if (x <= xs[0]) return ys[0];
    if (x >= xs[xs.length - 1]) return ys[ys.length - 1];
    for (let i = 0; i < xs.length - 1; i++) {
      if (x >= xs[i] && x <= xs[i + 1]) {
        const t = (x - xs[i]) / Math.max(1e-6, xs[i + 1] - xs[i]);
        return ys[i] + (ys[i + 1] - ys[i]) * t;
      }
    }
    return ys[ys.length - 1];
  }
  function toneLUT1d(points, size) {
    const order = points.map((_, i) => i).sort((a, b) => points[a][0] - points[b][0]);
    const xs = order.map((i) => points[i][0]);
    const ys = order.map((i) => points[i][1]);
    const lut = new Float32Array(size);
    for (let i = 0; i < size; i++) lut[i] = interp((i + 0.5) / size, xs, ys);
    return lut;
  }
  function sampleLUT1d(lut, x) {
    const size = lut.length;
    const t = clamp(clamp(x, 0, 1) * size - 0.5, 0, size - 1);
    const i0 = Math.floor(t), i1 = Math.min(i0 + 1, size - 1), f = t - i0;
    return lut[i0] * (1 - f) + lut[i1] * f;
  }
  function applyToneCurve(grid, points) {
    if (!points || (points.length === 2 && points[0][0] === 0 && points[0][1] === 0 &&
        points[1][0] === 1 && points[1][1] === 1)) return;
    const lut = toneLUT1d(points, 1024);
    const n3 = grid.length / 3;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      grid[b] = sampleLUT1d(lut, grid[b]);
      grid[b + 1] = sampleLUT1d(lut, grid[b + 1]);
      grid[b + 2] = sampleLUT1d(lut, grid[b + 2]);
    }
  }

  function applyHSL(grid, hsl) {
    if (!hsl) return;
    const n3 = grid.length / 3;
    for (let p = 0; p < n3; p++) {
      const b = p * 3;
      let r = grid[b], g = grid[b + 1], bl = grid[b + 2];
      const mx = Math.max(r, g, bl), mn = Math.min(r, g, bl), v = mx;
      const s = mx > 0 ? (mx - mn) / mx : 0;
      let h;
      if (mx === mn) h = 0;
      else {
        const diff = mx - mn;
        const rc = (mx - r) / diff, gc = (mx - g) / diff, bc = (mx - bl) / diff;
        if (mx === r) h = bc - gc;
        else if (mx === g) h = 2 + rc - bc;
        else h = 4 + gc - rc;
        h = (h / 6) % 1; if (h < 0) h += 1;
      }
      const hd = h * 360;
      let sat_f = 1, light_f = 1, hue_shift = 0;
      for (const name in hsl) {
        const params = hsl[name];
        if (!params) continue;
        let mask;
        if (name === "red") mask = hd >= 345 || hd < 15;
        else { const rng = HUE_RANGES[name]; if (!rng) continue; mask = hd >= rng[0] && hd < rng[1]; }
        if (!mask) continue;
        if (params.saturation !== undefined) sat_f *= params.saturation;
        if (params.lightness !== undefined) light_f *= params.lightness;
        if (params.hue_shift !== undefined) hue_shift += params.hue_shift / 360;
      }
      let h2 = (h + hue_shift) % 1; if (h2 < 0) h2 += 1;
      const s2 = clamp(s * sat_f, 0, 1), v2 = clamp(v * light_f, 0, 1);
      const i = Math.floor(h2 * 6), f = h2 * 6 - i;
      const pp = v2 * (1 - s2), q = v2 * (1 - f * s2), tt = v2 * (1 - (1 - f) * s2);
      let nr, ng, nb;
      switch (((i % 6) + 6) % 6) {
        case 0: nr = v2; ng = tt; nb = pp; break;
        case 1: nr = q; ng = v2; nb = pp; break;
        case 2: nr = pp; ng = v2; nb = tt; break;
        case 3: nr = pp; ng = q; nb = v2; break;
        case 4: nr = tt; ng = pp; nb = v2; break;
        default: nr = v2; ng = pp; nb = q; break;
      }
      grid[b] = nr; grid[b + 1] = ng; grid[b + 2] = nb;
    }
  }

  // Build the identity grid (R is the fastest-varying axis, matching .cube
  // "R varies fastest" convention) and push it through the colour chain.
  function bakeColorLUT(profile, n) {
    n = n || 33;
    profile = profile || {};
    const grid = new Float32Array(n * n * n * 3);
    let idx = 0;
    for (let b = 0; b < n; b++) {
      const bv = b / (n - 1);
      for (let g = 0; g < n; g++) {
        const gv = g / (n - 1);
        for (let r = 0; r < n; r++) {
          grid[idx++] = r / (n - 1);
          grid[idx++] = gv;
          grid[idx++] = bv;
        }
      }
    }
    applyExposure(grid, profile.exposure && profile.exposure.bias);
    const wb = profile.white_balance || {};
    applyWB(grid, wb.temperature_bias || 0, wb.tint_bias || 0);
    applyColorMatrix(grid, profile.color_matrix && profile.color_matrix.matrix_3x3);
    const hr = profile.highlight_rolloff || {};
    applyHighlight(grid, hr.threshold == null ? 0.8 : hr.threshold, hr.strength || 0);
    const sh = profile.shadow || {};
    applyShadow(grid, sh.black_point || 0, sh.compression || 0, sh.saturation == null ? 1 : sh.saturation);
    const fc = profile.film_curve || {};
    applyFilmCurve(grid, fc.shadow_floor == null ? 8 : fc.shadow_floor, fc.highlight_ceiling == null ? 248 : fc.highlight_ceiling);
    applyToneCurve(grid, profile.tone_curve && profile.tone_curve.points);
    applyHSL(grid, profile.hsl);

    const lut = new Uint8Array(n * n * n * 4);
    let o = 0;
    for (let i = 0; i < grid.length; i += 3) {
      lut[o++] = Math.round(clamp(grid[i], 0, 1) * 255);
      lut[o++] = Math.round(clamp(grid[i + 1], 0, 1) * 255);
      lut[o++] = Math.round(clamp(grid[i + 2], 0, 1) * 255);
      lut[o++] = 255;
    }
    return { lut, grid, n };
  }

  // Bake the tone curve into a 1024x1 1D LUT sampled at texel centres, matching
  // profile_renderer._tone_lut + tone_curve.glsl (so the GPU lookup == CPU curve).
  function bakeToneLUT(profile) {
    const points = (profile && profile.tone_curve && profile.tone_curve.points) || [];
    const size = 1024;
    let lut;
    if (!points.length) {
      lut = new Float32Array(size);
      for (let i = 0; i < size; i++) lut[i] = (i + 0.5) / size;
    } else {
      lut = toneLUT1d(points, size);
    }
    return lut; // Float32Array(size)
  }

  // Bake the 7-range HSL map into 7x1 RGBA8: R=sat, G=light, B=hueShift(deg).
  // Encoding: sat/light use range 0..4 (stored value/4*255) so legal multipliers
  // >1 (e.g. lightness 1.19) DON'T wrap the Uint8Array (the old *255 encoding
  // turned lightness 1.19 into byte 46 => 0.18x brightness => "black overlay").
  // Hue uses offset-binary: byte 128 = 0deg, range -360..+360.
  // Decode is in gl_lut.js pc_hsl (keep both sides in sync).
  const HSL_ORDER = ["red", "orange", "yellow", "green", "cyan", "blue", "purple"];
  function bakeHslLUT(profile) {
    const hsl = (profile && profile.hsl) || {};
    const out = new Uint8Array(7 * 4);
    for (let k = 0; k < 7; k++) {
      const p = hsl[HSL_ORDER[k]] || {};
      const sat = p.saturation == null ? 1 : p.saturation;
      const light = p.lightness == null ? 1 : p.lightness;
      const hue = p.hue_shift == null ? 0 : p.hue_shift; // degrees
      out[k * 4 + 0] = Math.round(clamp(sat, 0, 4) / 4 * 255);
      out[k * 4 + 1] = Math.round(clamp(light, 0, 4) / 4 * 255);
      out[k * 4 + 2] = Math.round(clamp(hue / 720, -0.5, 0.5) * 255 + 128);
      out[k * 4 + 3] = 255;
    }
    return out; // Uint8Array(28)
  }

  // Serialise the baked grid to an Adobe .cube 3D LUT (R fastest).
  function lut3dToCube(grid, n, title) {
    const lines = [
      `TITLE "${title || "PhotographerCamera profile LUT"}"`,
      "# color layers only; grain/vignette/bloom/halation are App-side live effects",
      `LUT_3D_SIZE ${n}`,
      "",
    ];
    for (let b = 0; b < n; b++)
      for (let g = 0; g < n; g++)
        for (let r = 0; r < n; r++) {
          const i = ((b * n + g) * n + r) * 3;
          lines.push(`${grid[i].toFixed(6)} ${grid[i + 1].toFixed(6)} ${grid[i + 2].toFixed(6)}`);
        }
    return lines.join("\n") + "\n";
  }

  global.LUT3D = { bakeColorLUT, lut3dToCube, bakeToneLUT, bakeHslLUT };
})(window);
