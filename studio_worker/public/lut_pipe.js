/* PhotographerCamera Studio — LUT profile 管线（浏览器端，Worker 版核心）。
 *
 * 与 tools/profile_renderer.py 逐函数对齐（对拍脚本 _diag/test_lut_pipe.js）：
 *   - decodeLutPayload / encodeLutPayload   ⇔ stylefit/lut3d.from_payload / to_payload
 *   - sampleLutGrid                          ⇔ stylefit/lut3d.sample（三线性 + clamp-to-edge）
 *   - colorPipelineCube                      ⇔ profile_renderer.color_pipeline_cube
 *     （新语义：LUT → film → 参数微调层；无 LUT 时参数链 + film 收尾）
 *   - neutralizeLutParams                    ⇔ profile_renderer.neutralize_lut_params
 *   - paramsAreIdentity                      ⇔ profile_renderer._params_are_identity
 *   - bakeParamsIntoLut                      ⇔ profile_renderer.bake_params_into_lut
 *     （HSL 被动过时重采样到 65³，规避色相硬分箱在 33³ 下的插值发散）
 *
 * 网格内存布局与 .cube / stylefit 一致：[b][g][r]，R 最快。
 */
(function (global) {
  "use strict";

  const clamp = (x, a, b) => (x < a ? a : x > b ? b : x);

  /* ----------------------------- base64 ----------------------------- */

  const b64decode = (s) => {
    if (typeof atob === "function") return atob(s);
    return Buffer.from(s, "base64").toString("binary"); // node 对拍用
  };
  const b64encode = (bin) => {
    if (typeof btoa === "function") return btoa(bin);
    return Buffer.from(bin, "binary").toString("base64");
  };

  /* ------------------------- payload ↔ grid ------------------------- */

  // payload → {grid: Float32Array(n³*3), n}；grid 为 [b][g][r] 平铺、0..1
  function decodeLutPayload(payload) {
    if (!payload || !payload.data) return null;
    const n = payload.size | 0;
    const scale = payload.scale || 255;
    const raw = b64decode(payload.data);
    const expect = n * n * n * 3;
    if (raw.length !== expect) return null;
    const grid = new Float32Array(expect);
    for (let i = 0; i < expect; i++) grid[i] = clamp(raw.charCodeAt(i) / scale, 0, 1);
    return { grid, n };
  }

  // grid → payload（uint8 量化 + base64），与 Python to_payload 一致：rint(x*255)
  function encodeLutPayload(grid, n) {
    const bytes = new Uint8Array(grid.length);
    for (let i = 0; i < grid.length; i++)
      bytes[i] = Math.round(clamp(grid[i], 0, 1) * 255);
    let bin = "";
    const CH = 0x8000;
    for (let i = 0; i < bytes.length; i += CH)
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
    return { size: n, dtype: "uint8", scale: 255, order: "r_fastest", data: b64encode(bin) };
  }

  /* ------------------------- 三线性采样 ------------------------- */

  // samples: Float32Array(m*3) 查询坐标（0..1）；返回新的 Float32Array(m*3)
  // 逐点对齐 Python lut3d.sample：lut[iz,iy,ix] = grid[((z*n)+y)*n+x]（x=r,y=g,z=b）
  function sampleLutGrid(grid, n, samples) {
    const m = samples.length / 3;
    const out = new Float32Array(m * 3);
    const n1 = n - 1;
    for (let p = 0; p < m; p++) {
      const r = clamp(samples[p * 3], 0, 1) * n1;
      const g = clamp(samples[p * 3 + 1], 0, 1) * n1;
      const b = clamp(samples[p * 3 + 2], 0, 1) * n1;
      let x0 = Math.floor(r), y0 = Math.floor(g), z0 = Math.floor(b);
      const fx = r - x0, fy = g - y0, fz = b - z0;
      let x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1;
      x0 = Math.min(x0, n1); y0 = Math.min(y0, n1); z0 = Math.min(z0, n1);
      x1 = Math.min(x1, n1); y1 = Math.min(y1, n1); z1 = Math.min(z1, n1);
      const gi = (z, y, x) => ((z * n + y) * n + x) * 3;
      for (let c = 0; c < 3; c++) {
        const c000 = grid[gi(z0, y0, x0) + c], c100 = grid[gi(z0, y0, x1) + c];
        const c010 = grid[gi(z0, y1, x0) + c], c110 = grid[gi(z0, y1, x1) + c];
        const c001 = grid[gi(z1, y0, x0) + c], c101 = grid[gi(z1, y0, x1) + c];
        const c011 = grid[gi(z1, y1, x0) + c], c111 = grid[gi(z1, y1, x1) + c];
        const c00 = c000 * (1 - fx) + c100 * fx;
        const c10 = c010 * (1 - fx) + c110 * fx;
        const c01 = c001 * (1 - fx) + c101 * fx;
        const c11 = c011 * (1 - fx) + c111 * fx;
        const c0 = c00 * (1 - fy) + c10 * fy;
        const c1 = c01 * (1 - fy) + c11 * fy;
        out[p * 3 + c] = clamp(c0 * (1 - fz) + c1 * fz, 0, 1);
      }
    }
    return out;
  }

  /* --------------------------- 身份参数 --------------------------- */

  const IDENTITY = {
    exposure: { bias: 0.0 },
    white_balance: { temperature_bias: 0.0, tint_bias: 0.0 },
    color_matrix: { matrix_3x3: [[1, 0, 0], [0, 1, 0], [0, 0, 1]] },
    highlight_rolloff: { threshold: 0.8, strength: 0.0, saturation: 1.0 },
    shadow: { black_point: 0.0, compression: 0.0, saturation: 1.0, contrast: 1.0 },
    tone_curve: { points: [[0.0, 0.0], [1.0, 1.0]] },
    hsl: {
      red: { saturation: 1, lightness: 1, hue_shift: 0 },
      orange: { saturation: 1, lightness: 1, hue_shift: 0 },
      yellow: { saturation: 1, lightness: 1, hue_shift: 0 },
      green: { saturation: 1, lightness: 1, hue_shift: 0 },
      cyan: { saturation: 1, lightness: 1, hue_shift: 0 },
      blue: { saturation: 1, lightness: 1, hue_shift: 0 },
      purple: { saturation: 1, lightness: 1, hue_shift: 0 },
    },
  };
  const LUT_PARAM_KEYS = ["exposure", "white_balance", "color_matrix",
    "highlight_rolloff", "shadow", "tone_curve", "hsl"];

  function isLutProfile(p) {
    return !!p && p.color_layer === "lut" &&
      !!p.color_lut && typeof p.color_lut === "object" && !!p.color_lut.data;
  }

  // 返回新 profile（深拷贝），参数层回 identity；非 LUT profile 返回 null
  function neutralizeLutParams(profile) {
    if (!isLutProfile(profile)) return null;
    const out = JSON.parse(JSON.stringify(profile));
    for (const k of LUT_PARAM_KEYS)
      if (k in out) out[k] = JSON.parse(JSON.stringify(IDENTITY[k]));
    return out;
  }

  function paramsAreIdentity(p) {
    if (!p) return false;
    if ((p.exposure && p.exposure.bias) || 0 !== 0) return false;
    const wb = p.white_balance || {};
    if ((wb.temperature_bias || 0) !== 0 || (wb.tint_bias || 0) !== 0) return false;
    const M = (p.color_matrix && p.color_matrix.matrix_3x3) || [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
    for (let r = 0; r < 3; r++)
      for (let c = 0; c < 3; c++)
        if (+M[r][c] !== (r === c ? 1 : 0)) return false;
    if ((p.highlight_rolloff && p.highlight_rolloff.strength) || 0 !== 0) return false;
    const sh = p.shadow || {};
    if ((sh.black_point || 0) !== 0 || (sh.compression || 0) !== 0) return false;
    if ((sh.contrast == null ? 1 : sh.contrast) !== 1) return false;
    const tint = sh.tint || [0, 0, 0];
    for (const v of tint) if (+v !== 0) return false;
    const pts = (p.tone_curve && p.tone_curve.points) || [[0, 0], [1, 1]];
    if (!(pts.length === 2 && +pts[0][0] === 0 && +pts[0][1] === 0 &&
        +pts[1][0] === 1 && +pts[1][1] === 1)) return false;
    const hsl = p.hsl || {};
    for (const k in hsl) {
      const ch = hsl[k];
      if (ch && (ch.saturation !== 1 || ch.lightness !== 1 || (ch.hue_shift || 0) !== 0)) {
        // 与 Python 一致：字段全为默认值视为 identity；任何非默认即非 identity
        if (ch.saturation !== undefined && ch.saturation !== 1) return false;
        if (ch.lightness !== undefined && ch.lightness !== 1) return false;
        if (ch.hue_shift !== undefined && ch.hue_shift !== 0) return false;
      }
    }
    return true;
  }

  /* --------------------- 完整色彩管线 → 立方体 --------------------- */

  // 返回 Float32Array(n³*3)，[b][g][r] 平铺（.cube 行序，R 最快），0..1。
  // LUT profile：LUT → film → exposure → wb → matrix → highlight → shadow → tone → hsl
  // 参数 profile：exposure → wb → matrix → highlight → shadow → film → tone → hsl
  function colorPipelineCube(profile, n) {
    const chain = global.LUT3D.chain;
    n = n || 33;
    const p = profile || {};
    const count = n * n * n;
    const grid = new Float32Array(count * 3);
    // 恒等查询网格（R 最快）
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
    const hasLut = isLutProfile(p);
    if (hasLut) {
      const dec = decodeLutPayload(p.color_lut);
      if (dec) {
        const sampled = sampleLutGrid(dec.grid, dec.n, grid);
        grid.set(sampled);
        const fc = p.film_curve || {};
        chain.applyFilmCurve(grid, fc.shadow_floor == null ? 8 : fc.shadow_floor,
          fc.highlight_ceiling == null ? 248 : fc.highlight_ceiling);
      } else {
        p._lut_broken = true; // 载荷损坏则退化为参数链
      }
    }
    chain.applyExposure(grid, p.exposure && p.exposure.bias);
    const wb = p.white_balance || {};
    chain.applyWB(grid, wb.temperature_bias || 0, wb.tint_bias || 0);
    chain.applyColorMatrix(grid, p.color_matrix && p.color_matrix.matrix_3x3);
    const hr = p.highlight_rolloff || {};
    chain.applyHighlight(grid, hr.threshold == null ? 0.8 : hr.threshold, hr.strength || 0);
    const sh = p.shadow || {};
    chain.applyShadow(grid, sh.black_point || 0, sh.compression || 0,
      sh.saturation == null ? 1 : sh.saturation, sh.contrast == null ? 1 : sh.contrast);
    if (!hasLut) {
      const fc = p.film_curve || {};
      chain.applyFilmCurve(grid, fc.shadow_floor == null ? 8 : fc.shadow_floor,
        fc.highlight_ceiling == null ? 248 : fc.highlight_ceiling);
    }
    chain.applyToneCurve(grid, p.tone_curve && p.tone_curve.points);
    chain.applyHSL(grid, p.hsl);
    for (let i = 0; i < grid.length; i++) grid[i] = clamp(grid[i], 0, 1);
    return grid;
  }

  /* ----------------------- 烘焙（保存/导出用） ----------------------- */

  // 把 film + 参数折进 3D LUT 并复位参数层；无 LUT 或参数已恒等返回 null。
  // 对齐 Python bake_params_into_lut（含 HSL→65³ 重采样）。
  function bakeParamsIntoLut(profile) {
    if (!isLutProfile(profile)) return null;
    if (paramsAreIdentity(profile)) return null;
    const dec = decodeLutPayload(profile.color_lut);
    if (!dec) return null;
    let { grid, n } = dec;
    const hslTweaked = Object.values(profile.hsl || {}).some((ch) => !!ch);
    if (hslTweaked && n < 65) {
      // 重采样到 65³：查询坐标即恒等网格本身（r 最快，与 Python 一致）
      const m = 65;
      const q = new Float32Array(m * m * m * 3);
      let k = 0;
      for (let b = 0; b < m; b++) {
        const bv = b / (m - 1);
        for (let g = 0; g < m; g++) {
          const gv = g / (m - 1);
          for (let r = 0; r < m; r++) {
            q[k++] = r / (m - 1); q[k++] = gv; q[k++] = bv;
          }
        }
      }
      grid = sampleLutGrid(grid, n, q);
      n = m;
    }
    const chain = global.LUT3D.chain;
    const fc0 = profile.film_curve || {};
    chain.applyFilmCurve(grid, fc0.shadow_floor == null ? 8 : fc0.shadow_floor,
      fc0.highlight_ceiling == null ? 248 : fc0.highlight_ceiling);
    chain.applyExposure(grid, profile.exposure && profile.exposure.bias);
    const wb = profile.white_balance || {};
    chain.applyWB(grid, wb.temperature_bias || 0, wb.tint_bias || 0);
    chain.applyColorMatrix(grid, profile.color_matrix && profile.color_matrix.matrix_3x3);
    const hr = profile.highlight_rolloff || {};
    chain.applyHighlight(grid, hr.threshold == null ? 0.8 : hr.threshold, hr.strength || 0);
    const sh = profile.shadow || {};
    chain.applyShadow(grid, sh.black_point || 0, sh.compression || 0,
      sh.saturation == null ? 1 : sh.saturation, sh.contrast == null ? 1 : sh.contrast);
    chain.applyToneCurve(grid, profile.tone_curve && profile.tone_curve.points);
    chain.applyHSL(grid, profile.hsl);
    for (let i = 0; i < grid.length; i++) grid[i] = clamp(grid[i], 0, 1);

    const out = JSON.parse(JSON.stringify(profile));
    const payload = encodeLutPayload(grid, n);
    payload.source = (profile.color_lut && profile.color_lut.source) || "";
    payload.strength = (profile.color_lut && profile.color_lut.strength != null)
      ? profile.color_lut.strength : 1.0;
    out.color_lut = payload;
    out.film_curve = { shadow_floor: 0, highlight_ceiling: 255 };
    const fresh = neutralizeLutParams(profile);
    for (const k of LUT_PARAM_KEYS) out[k] = fresh[k];
    return out;
  }

  global.LUTPIPE = {
    decodeLutPayload, encodeLutPayload, sampleLutGrid,
    isLutProfile, neutralizeLutParams, paramsAreIdentity,
    colorPipelineCube, bakeParamsIntoLut,
  };
})(typeof window !== "undefined" ? window : globalThis);
