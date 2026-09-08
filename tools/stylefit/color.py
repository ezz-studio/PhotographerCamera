#!/usr/bin/env python3
"""Colour-space helpers for the stylefit v2 pipeline.

Everything here is dependency-free (NumPy only) and deterministic.

Why Lab and not RGB:
  Style is a *perceptual* quantity. Matching RGB statistics distorts hue
  relationships (a channel-wise stretch rotates hues), while CIELAB gives an
  approximately perceptually-uniform space where mean/variance transfer
  (Reinhard et al. 2001) and quantile transfer behave predictably.

Functions
  srgb<->linear, linear<->xyz(D65), xyz<->lab, lab<->xyz->linear->srgb
  quantile_of / quantile_map        — monotone 1-D distribution transfer
  neutral_luminance_prior()         — "what this frame would look like un-graded"
  neutralize()                      — build the paired input A' from graded A
"""
from __future__ import annotations

import numpy as np

# ---------------------------------------------------------------- transfer fn
def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb(x):
    x = np.asarray(x, dtype=np.float32)
    x = np.clip(x, 0.0, 1.0)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * (x ** (1.0 / 2.4)) - 0.055).astype(np.float32)


# sRGB (D65) matrices — IEC 61966-2-1
_M_RGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
], dtype=np.float32)
_M_XYZ2RGB = np.array([
    [3.2404542, -1.5371385, -0.4985314],
    [-0.9692660, 1.8760108, 0.0415560],
    [0.0556434, -0.2040259, 1.0572252],
], dtype=np.float32)
_WHITE_D65 = np.array([0.95047, 1.00000, 1.08883], dtype=np.float32)


def _flat(x):
    a = np.asarray(x, dtype=np.float32)
    return a.reshape(-1, 3)


def rgb_to_lab(rgb):
    rgb = _flat(rgb)
    lin = srgb_to_linear(rgb)
    xyz = lin @ _M_RGB2XYZ.T
    xyz = xyz / _WHITE_D65
    eps, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    f = np.where(xyz > eps, np.cbrt(xyz), (kappa * xyz + 16.0) / 116.0)
    L = 116.0 * f[..., 1] - 16.0
    a = 500.0 * (f[..., 0] - f[..., 1])
    b = 200.0 * (f[..., 1] - f[..., 2])
    return np.stack([L, a, b], axis=-1).astype(np.float32)


def lab_to_rgb(lab):
    lab = _flat(lab)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx = fy + a / 500.0
    fz = fy - b / 200.0
    eps, kappa = 216.0 / 24389.0, 24389.0 / 27.0
    f3 = np.stack([fx, fy, fz], axis=-1) ** 3
    xyz = np.where(f3 > eps, f3, (116.0 * np.stack([fx, fy, fz], axis=-1) - 16.0) / kappa)
    xyz = xyz * _WHITE_D65
    lin = xyz @ _M_XYZ2RGB.T
    return np.clip(linear_to_srgb(lin), 0.0, 1.0).astype(np.float32)


def luma(rgb):
    """Rec.709 luma of sRGB-encoded values (perceptual-ish, cheap)."""
    rgb = np.asarray(rgb, dtype=np.float32)
    return (0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]).astype(np.float32)


# ------------------------------------------------------------------ quantiles
_Q_GRID = np.linspace(0.0005, 0.9995, 512, dtype=np.float32)


def quantile_curve(values, qs=_Q_GRID):
    """Monotone quantile function of *values* sampled on a fixed q grid."""
    v = np.asarray(values, dtype=np.float32).ravel()
    if v.size == 0:
        return np.zeros_like(qs)
    return np.quantile(v, qs).astype(np.float32)


def quantile_map(src_values, dst_values, qs=_Q_GRID):
    """Return f(x) mapping the src distribution onto the dst distribution.

    f = Q_dst ∘ CDF_src, evaluated on a dense x grid built from the src
    quantiles. Monotone by construction — no tone inversion possible.
    """
    xs = quantile_curve(src_values, qs)
    ys = quantile_curve(dst_values, qs)
    # enforce strict monotonicity so np.interp never goes backwards
    xs = np.maximum.accumulate(xs)
    ys = np.maximum.accumulate(ys)
    return xs, ys


def apply_quantile_map(x, xs, ys):
    return np.interp(np.asarray(x, dtype=np.float32), xs, ys).astype(np.float32)


# ------------------------------------------------- "un-graded" reconstruction
def neutral_luminance_prior(L_grad, lo=0.5, hi=99.5):
    """Estimate the luminance distribution a *neutral* render would have.

    Model (documented assumption): scene linear luminance of an un-graded
    photograph is approximately exponentially distributed — a standard
    first-order model for natural-image luminance (long-tailed, mode near the
    low end). We pin the scale λ to the *median* luminance actually present,
    so the photographer's exposure intent survives; only the tone *shape*
    (S-curve, black/white point styling) is removed.

    Returns (x_src, y_neutral): a monotone mapping L_graded -> L_neutral.
    """
    L = np.asarray(L_grad, dtype=np.float32).ravel()
    if L.size == 0:
        return np.array([0.0, 1.0], np.float32), np.array([0.0, 1.0], np.float32)

    qs = np.linspace(lo / 100.0, hi / 100.0, 256, dtype=np.float32)
    Qg = np.quantile(L, qs).astype(np.float32)            # graded L quantiles

    # Lab L -> relative luminance Y in [0,1]
    Y = ((Qg + 16.0) / 116.0) ** 3
    Y = np.clip(Y, 1e-6, 1.0)
    Y_med = float(np.median(Y))
    lam = float(np.log(2.0) / max(Y_med, 1e-6))           # Exp(λ) with median = Y_med

    # neutral linear-luminance quantiles, then back to Lab L
    Yn = -np.log(np.maximum(1.0 - qs, 1e-6)) / max(lam, 1e-6)
    Yn = np.clip(Yn, 1e-6, 1.0)
    Ln = (116.0 * np.cbrt(Yn) - 16.0).astype(np.float32)
    Ln = np.clip(Ln, 0.0, 100.0)
    Ln = np.maximum.accumulate(Ln)

    x = np.maximum.accumulate(Qg)
    return x.astype(np.float32), Ln.astype(np.float32)


def neutralize(rgb, chroma_target_p95=25.0, wb_strength=1.0, tone_strength=1.0):
    """Build A' — the same photograph with the photographer's grade removed.

    Three *removable* components are taken out, each with a strength knob so
    the pipeline stays auditable:

      1. white balance  — grey-world in Lab (a,b shifted to zero mean)
      2. saturation     — chroma rescaled so P95(chroma) hits a neutral level
      3. tone shape     — luminance quantiles remapped onto the neutral prior

    Everything that is *content* (composition, local contrast, sharpness,
    geometry, grain) is preserved — so the pair (A', A) is a genuinely paired
    training set for the mapping "neutral photo -> photographer's look".
    """
    rgb = np.asarray(rgb, dtype=np.float32)
    shape = rgb.shape
    lab = rgb_to_lab(rgb).reshape(shape)
    L = lab[..., 0]
    a = lab[..., 1]
    b = lab[..., 2]

    # 1. grey-world white balance
    if wb_strength > 0:
        a = a - float(np.mean(a)) * wb_strength
        b = b - float(np.mean(b)) * wb_strength

    # 2. neutral saturation
    C = np.sqrt(a * a + b * b)
    p95 = float(np.percentile(C, 95))
    if p95 > 1e-3 and chroma_target_p95 > 0:
        s = chroma_target_p95 / p95
        s = 1.0 + (s - 1.0) * wb_strength
        a = a * s
        b = b * s

    # 3. neutral tone shape
    if tone_strength > 0:
        xs, ys = neutral_luminance_prior(L)
        Ln = apply_quantile_map(L, xs, ys)
        L = L + (Ln - L) * tone_strength

    out = np.stack([L, a, b], axis=-1).astype(np.float32)
    return lab_to_rgb(out).reshape(shape)


# ---------------------------------------------------------------------- stats
def stats(rgb):
    """Compact perceptual statistics used for both fitting and evaluation."""
    rgb = np.asarray(rgb, dtype=np.float32)
    lab = rgb_to_lab(rgb).reshape(rgb.shape)
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.sqrt(a * a + b * b)
    qs = (1, 5, 25, 50, 75, 95, 99)
    d = {
        "L_mean": float(L.mean()), "L_std": float(L.std()),
        "a_mean": float(a.mean()), "b_mean": float(b.mean()),
        "C_mean": float(C.mean()), "C_p95": float(np.percentile(C, 95)),
        "clip_high": float(np.mean(rgb > 0.995)),
        "clip_low": float(np.mean(rgb < 0.005)),
    }
    for q in qs:
        d[f"L_p{q}"] = float(np.percentile(L, q))
    return d


def delta_e76(rgb_a, rgb_b):
    """Mean / p95 CIE76 colour difference between two RGB images."""
    la = rgb_to_lab(rgb_a).reshape(-1, 3)
    lb = rgb_to_lab(rgb_b).reshape(-1, 3)
    d = np.sqrt(np.sum((la - lb) ** 2, axis=1))
    return float(d.mean()), float(np.percentile(d, 95))


def histogram_l1(rgb_a, rgb_b, bins=32):
    """L1 distance of luminance histograms (0 = identical, 2 = disjoint)."""
    ha, _ = np.histogram(luma(rgb_a), bins=bins, range=(0, 1))
    hb, _ = np.histogram(luma(rgb_b), bins=bins, range=(0, 1))
    ha = ha / max(1, ha.sum())
    hb = hb / max(1, hb.sum())
    return float(np.abs(ha - hb).sum())
