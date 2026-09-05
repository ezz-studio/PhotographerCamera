#!/usr/bin/env python3
"""NumPy reference of the DETERMINISTIC PhotographerCamera GLSL layers.

Purpose: prove the GPU shader math equals the validated CPU reference
(`tools/profile_renderer.py`) WITHOUT a GPU. The Android renderer uploads
`matrix_3x3` transposed (see shaders/color_matrix.glsl), so `pc_color_matrix`
here uses `c @ M.T`, which reproduces both the GLSL `m * c` and the CPU `v @ M.T`.

Scope: only the seed-free, blur-free layers are bit-exact comparable:
  Exposure, White Balance, Color Matrix, Tone Curve (LUT), Highlight,
  Shadow, HSL (LUT), Vignette.
Sharpen / Bloom / Halation (Gaussian blur) and Grain / Noise (procedural RNG)
are GPU-approximated and validated by on-device visual QA (Phase 32), not here.
"""
from __future__ import annotations

import numpy as np

_HUE_RANGES = {
    "red": (345, 360), "orange": (15, 45), "yellow": (45, 70), "green": (70, 160),
    "cyan": (160, 200), "blue": (200, 260), "purple": (260, 345),
}

# Texel order of the baked HSL LUT. MUST match pc_hue_index() in shaders/common.glsl.
HUE_ORDER = ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]

TONE_LUT_SIZE = 1024  # canonical tone-curve LUT width (GL_LINEAR)
HSL_LUT_SIZE = len(HUE_ORDER)  # 7 texels, category-indexed (GL_NEAREST)


def _luma(c: np.ndarray) -> np.ndarray:
    return c.mean(axis=2, keepdims=True)


# ---------------------------------------------------------------------------
# Layer functions (mirror shaders/*.glsl)
# ---------------------------------------------------------------------------
def pc_exposure(c, bias):
    if bias == 0.0:
        return c
    return np.clip(c * (2.0 ** bias), 0.0, 1.0)


def pc_white_balance(c, temp, tint):
    if temp == 0.0 and tint == 0.0:
        return c
    o = c.copy()
    o[..., 0] *= (1.0 + temp * 0.2)
    o[..., 2] *= (1.0 - temp * 0.2)
    o[..., 0] *= (1.0 + tint * 0.1)
    o[..., 2] *= (1.0 + tint * 0.1)
    o[..., 1] *= (1.0 - tint * 0.1)
    return np.clip(o, 0.0, 1.0)


def pc_color_matrix(c, matrix_3x3):
    # GLSL uploads the transpose; GLSL m*c == c @ M.T == CPU v @ M.T
    M = np.array(matrix_3x3, dtype=np.float32)
    return np.clip(c.reshape(-1, 3) @ M.T, 0.0, 1.0).reshape(c.shape)


def bake_tone_lut(points, size=TONE_LUT_SIZE):
    """Bake control points at TEXEL CENTERS so GL_LINEAR at u=c is the curve itself."""
    xs = np.array([p[0] for p in points], dtype=np.float32)
    ys = np.array([p[1] for p in points], dtype=np.float32)
    order = np.argsort(xs)
    xs, ys = xs[order], ys[order]
    lut_x = (np.arange(size, dtype=np.float32) + 0.5) / size
    return np.interp(lut_x, xs, ys).astype(np.float32)


_tone_lut = bake_tone_lut  # backwards-compatible alias


def pc_tone_curve(c, lut):
    """Model GL_LINEAR / GL_CLAMP_TO_EDGE sampling of a 1D LUT at u = clamp(c,0,1)."""
    size = len(lut)
    t = np.clip(np.clip(c, 0.0, 1.0) * size - 0.5, 0.0, size - 1.0)
    i0 = np.floor(t).astype(np.int32)
    i1 = np.minimum(i0 + 1, size - 1)
    f = (t - i0).astype(np.float32)
    return lut[i0] * (1.0 - f) + lut[i1] * f


def pc_highlight(c, threshold, strength, sat):
    if strength <= 0.0:
        return c
    l = _luma(c)
    amt = np.clip((l - threshold) / max(1e-4, 1.0 - threshold), 0, 1) * strength
    o = c - amt * (c - threshold) * (1.0 - amt) * 0.5
    return np.clip(o, 0.0, 1.0)


def pc_shadow(c, black_point, compression, saturation):
    o = c.copy()
    if black_point > 0.0:
        o = (o - black_point) / max(1e-4, 1.0 - black_point)
    if compression > 0.0:
        l = _luma(o)
        mask = np.clip(1.0 - l, 0.0, 1.0)
        o = o + compression * mask * (0.5 - o) * 0.5
    if saturation != 1.0:
        l = _luma(o)
        o = np.clip(l + (o - l) * saturation, 0.0, 1.0)
    return np.clip(o, 0.0, 1.0)


def _hue_index_vec(hd):
    idx = np.full(hd.shape, 6, dtype=int)
    idx = np.where((hd >= 345) | (hd < 15), 0, idx)
    idx = np.where((hd >= 15) & (hd < 45), 1, idx)
    idx = np.where((hd >= 45) & (hd < 70), 2, idx)
    idx = np.where((hd >= 70) & (hd < 160), 3, idx)
    idx = np.where((hd >= 160) & (hd < 200), 4, idx)
    idx = np.where((hd >= 200) & (hd < 260), 5, idx)
    return idx


def bake_hsl_lut(hsl_dict):
    """Bake the 7 hue-range constants into a (7,3) CATEGORY-indexed LUT.

    Texel k holds the parameters of ``HUE_ORDER[k]``: R=saturation, G=lightness,
    B=hue_shift (degrees). shaders/hsl.glsl samples it with GL_NEAREST at
    ``u = (pc_hue_index(hd) + 0.5) / 7``, which resolves exactly to texel k, so
    the per-range CONSTANT factors are reproduced without any quantisation of the
    hue-range boundaries (a degree-indexed LUT would blur them).
    """
    lut = np.tile(np.array([1.0, 1.0, 0.0], dtype=np.float32), (HSL_LUT_SIZE, 1))
    for k, name in enumerate(HUE_ORDER):
        p = (hsl_dict or {}).get(name)
        if not p:
            continue
        lut[k, 0] = float(p.get("saturation", 1.0))
        lut[k, 1] = float(p.get("lightness", 1.0))
        lut[k, 2] = float(p.get("hue_shift", 0.0))
    return lut


def _rgb2hsv(c):
    mx = c.max(axis=2)
    mn = c.min(axis=2)
    v = mx
    s = np.where(mx > 0, (mx - mn) / np.where(mx == 0, 1, mx), 0.0)
    diff = mx - mn
    rc = (mx - c[..., 0]) / np.where(diff == 0, 1, diff)
    gc = (mx - c[..., 1]) / np.where(diff == 0, 1, diff)
    bc = (mx - c[..., 2]) / np.where(diff == 0, 1, diff)
    h = np.where(mx == c[..., 0], bc - gc, np.where(mx == c[..., 1], 2 + rc - bc, 4 + gc - rc))
    h = (h / 6.0) % 1.0
    return h, s, v


def _hsv2rgb(h, s, v):
    i = (np.floor(h * 6).astype(int)) % 6
    f = h * 6 - np.floor(h * 6)
    p = v * (1 - s)
    q = v * (1 - f * s)
    t = v * (1 - (1 - f) * s)
    r = np.zeros_like(v); g = np.zeros_like(v); b = np.zeros_like(v)
    m0 = i == 0; r[m0], g[m0], b[m0] = v[m0], t[m0], p[m0]
    m1 = i == 1; r[m1], g[m1], b[m1] = q[m1], v[m1], p[m1]
    m2 = i == 2; r[m2], g[m2], b[m2] = p[m2], v[m2], t[m2]
    m3 = i == 3; r[m3], g[m3], b[m3] = p[m3], q[m3], v[m3]
    m4 = i == 4; r[m4], g[m4], b[m4] = t[m4], p[m4], v[m4]
    m5 = i == 5; r[m5], g[m5], b[m5] = v[m5], p[m5], q[m5]
    return np.stack([r, g, b], axis=-1)


def pc_hsl(c, hsl_lut):
    h, s, v = _rgb2hsv(c)
    hd = h * 360.0
    idx = _hue_index_vec(hd)
    # GL_NEAREST on a 7x1 texture sampled at u=(idx+0.5)/7 resolves to texel idx.
    u = (idx + 0.5) / float(len(hsl_lut))
    li = np.clip(np.floor(u * len(hsl_lut)).astype(np.int32), 0, len(hsl_lut) - 1)
    pl = hsl_lut[li]
    sat_f = pl[..., 0]; light_f = pl[..., 1]; hue_shift = pl[..., 2] / 360.0
    h2 = (h + hue_shift) % 1.0
    s2 = np.clip(s * sat_f, 0, 1)
    v2 = np.clip(v * light_f, 0, 1)
    return np.clip(_hsv2rgb(h2, s2, v2), 0.0, 1.0)


def pc_vignette(c, uv, center, amount, radius, feather):
    if amount <= 0.0:
        return c
    d = uv - np.array(center, dtype=np.float32)
    dist = np.sqrt((d ** 2).sum(axis=-1)) / (radius * 0.7071)
    m = 1.0 - amount * np.clip((dist - (1.0 - feather)) / max(1e-4, feather), 0, 1)
    return np.clip(c * m[..., None], 0.0, 1.0)


def pc_film_curve(c, shadow_floor=8.0 / 255.0, highlight_ceiling=248.0 / 255.0):
    """Mirror shaders/film_curve.frag (LAST chain pass): C1-continuous soft-knee
    remap toward the shadow floor / highlight ceiling. Inputs are 0..1 display
    levels; matches GpuParams.kt coercion (lo to [0,0.4], hi to [0.6,1]) and the
    filmEnabled early-out (floor<=0 AND ceiling>=255 -> pass skipped)."""
    if shadow_floor <= 0.0 and highlight_ceiling >= 1.0:
        return c
    lo = float(np.clip(shadow_floor, 0.0, 0.4))
    hi = float(np.clip(highlight_ceiling, 0.6, 1.0))
    kt = float(np.clip(lo + 0.13, 0.04, 0.45))  # shadow knee
    kh = float(np.clip(hi - 0.17, 0.55, 0.97))  # highlight knee
    o = c.copy()
    below = o < kt
    t = o[below] / kt
    o[below] = lo + (kt - lo) * t * t * (2.0 - t)           # flat at lo, slope 1 at kt
    above = o > kh
    t2 = (o[above] - kh) / (1.0 - kh)
    o[above] = kh + (hi - kh) * t2 * (1.0 + t2 - t2 * t2)   # slope 1 at kh, flat at hi
    return np.clip(o, 0.0, 1.0)


# ---------------------------------------------------------------------------
# Ordered deterministic chain (excludes blur/random layers)
# ---------------------------------------------------------------------------
def render_deterministic(rgb: np.ndarray, profile: dict) -> np.ndarray:
    p = profile or {}
    c = rgb.astype(np.float32)
    c = pc_exposure(c, p.get("exposure", {}).get("bias", 0.0))
    wb = p.get("white_balance", {})
    c = pc_white_balance(c, wb.get("temperature_bias", 0.0), wb.get("tint_bias", 0.0))
    c = pc_color_matrix(c, p.get("color_matrix", {}).get("matrix_3x3"))
    points = p.get("tone_curve", {}).get("points", [])
    if points and not (len(points) == 2 and points[0] == [0, 0] and points[1] == [1, 1]):
        c = pc_tone_curve(c, bake_tone_lut(points))
    hr = p.get("highlight_rolloff", {})
    c = pc_highlight(c, hr.get("threshold", 0.8), hr.get("strength", 0.0), hr.get("saturation", 1.0))
    sh = p.get("shadow", {})
    c = pc_shadow(c, sh.get("black_point", 0.0), sh.get("compression", 0.0), sh.get("saturation", 1.0))
    c = pc_hsl(c, bake_hsl_lut(p.get("hsl", {})))
    vg = p.get("vignette", {})
    if vg.get("amount", 0.0) > 0.0:
        h, w = c.shape[:2]
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        # v_uv at a fragment centre = ((x+0.5)/w, (y+0.5)/h). The frame texture is
        # uploaded row-0-first, so v_uv.y counts image rows from the TOP (no flip).
        uv = np.stack([(xx + 0.5) / w, (yy + 0.5) / h], axis=-1)
        c = pc_vignette(c, uv, vg.get("center", [0.5, 0.5]), vg.get("amount", 0.0),
                        vg.get("radius", 1.0), vg.get("feather", 0.5))
    # film curve LAST (same position as ProfileRenderer's film pass).
    fc = p.get("film_curve", {})
    floor = float(fc.get("shadow_floor", 8.0))
    ceil = float(fc.get("highlight_ceiling", 248.0))
    if floor > 0.0 or ceil < 255.0:
        c = pc_film_curve(c, floor / 255.0, ceil / 255.0)
    return np.clip(c, 0.0, 1.0)
