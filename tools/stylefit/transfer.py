#!/usr/bin/env python3
"""stylefit v3 — learn a photographic look from TWO photo sets. No guessing.

Setting
-------
    S = un-graded photographs  (the "before" corpus — ordinary photos)
    T = the photographer's graded photographs (the "after" corpus)

The two sets are NOT paired: we never pretend to know which un-graded pixel
became which graded pixel. The only thing that can be learned honestly is the
map between the two COLOUR DISTRIBUTIONS. Everything below is estimated from
pixel histograms of the two corpora; there is no "assume the scene was
exponentially lit", no "assume grey world", no "assume chroma p95 = 25".

Model (applied in CIELAB, in this order)
----------------------------------------
1. Luminance
       L' = lin(L) + clip(shape(L), ±shape_cap)
       lin(L)   = (L - mu_S) * (sd_T / sd_S) + mu_T        <- Reinhard/AdaIN
       shape(L) = Q_T(CDF_S(L)) - lin(L)                   <- bounded residual
   The *linear* part carries exposure and contrast (a well-behaved affine map).
   The *shape* part carries the stylistic tone signature (lifted blacks,
   S-curve, rolled-off highlights) and is hard-capped so it can never redraw
   the photograph. The sum is then re-monotonised on a 1024-point grid, so
   tonal order can never invert.

2. Hue/chroma — optimal linear transport of the (a,b) Gaussian:
       ab' = A (ab - mu_S) + mu_S + clip(mu_T - mu_S, ±mu_cap)
       A   = cov_T^{1/2} cov_S^{-1/2},   singular values clipped to [1/K, K]
   This single matrix carries white balance (mean), saturation (scale) and any
   global hue rotation (eigenvectors). The mean shift is capped because a mean
   (a,b) difference can also come from *content* (a corpus full of foliage vs
   a corpus full of skin), not only from style.

3. Per-hue residual (the HSL-ish part): after 1+2, for each of 24 hue bins the
   mean (a,b) of the transformed source still differs from the target's mean in
   that bin. That difference is applied as a circularly smoothed offset, capped.

4. Per-luminance residual (split toning): same construction over 6 L bands.

Steps 3 and 4 are fitted on the residual left by the previous steps, so the
whole thing is a bounded greedy (boosting) fit that cannot run away.

What is deliberately NOT transferred: the HUE distribution. Hue histograms are
content (grass, sky, skin, clothing), not style. Matching them would repaint
every photograph into the photographer's subject matter.

References
----------
  Reinhard, Ashikhmin, Gooch, Shirley (2001) "Color Transfer between Images"
      — mean/variance transfer in Lab.
  Huang & Belongie (2017) "Arbitrary Style Transfer in Real-time with Adaptive
      Instance Normalization" — the same affine statistic alignment, learned.
  scikit-image `exposure.match_histograms` — the canonical Q_T(CDF_S(x)) map.
  Zeng et al. (2020) "Learning Image-adaptive 3D Lookup Tables", TPAMI
      — why the result is *baked into* a 33^3 LUT instead of being re-evaluated
      per pixel at run time.
"""
from __future__ import annotations

import numpy as np

from . import color as C

_Q = np.linspace(0.0005, 0.9995, 512, dtype=np.float64)
_LGRID = 1024
HUE_BINS = 24
L_BANDS = 6

SHAPE_CAP = 8.0      # max L* the tone *shape* may move a pixel
RESID_CAP = 5.0      # max |ab| the per-hue residual may move a pixel
TINT_CAP = 4.0       # max |ab| the per-luma tint may move a pixel
MU_CAP = 4.0         # max |ab| global white-balance shift
GAIN_CAP = 1.8       # max/min singular value of the chroma transport map
# 4-fold CV (see _diag/stylefit_curve.py) showed full strength overshoots on
# held-out photos (ΔE 10.2); a mild shrink lands closer to the look AND keeps
# the photograph intact (ΔE ~6.3, structure corr 0.996). CV-optimal setting.
DEFAULT_STRENGTH = 0.8


# ------------------------------------------------------------------- helpers
def _smooth_circular(v, passes=2):
    out = np.asarray(v, np.float64).copy()
    for _ in range(passes):
        out = 0.25 * np.roll(out, 1, axis=0) + 0.5 * out + 0.25 * np.roll(out, -1, axis=0)
    return out.astype(np.float64)


def _smooth_bands(v, passes=1):
    out = np.asarray(v, np.float64).copy()
    for _ in range(passes):
        p = np.vstack([out[:1], out[:-1]])
        n = np.vstack([out[1:], out[-1:]])
        out = 0.25 * p + 0.5 * out + 0.25 * n
    return out.astype(np.float64)


def _sqrtm_psd(M):
    w, V = np.linalg.eigh(M)
    w = np.clip(w, 1e-9, None)
    return (V * np.sqrt(w)) @ V.T


def _invsqrtm_psd(M):
    w, V = np.linalg.eigh(M)
    w = np.clip(w, 1e-9, None)
    return (V * (1.0 / np.sqrt(w))) @ V.T


def _hue_deg(a, b):
    return np.degrees(np.arctan2(b, a)) % 360.0


def _min_count(n):
    return int(max(500, 0.0005 * n))


# ---------------------------------------------------------------------- learn
def learn(src_rgb, dst_rgb, strength=DEFAULT_STRENGTH, shape_cap=SHAPE_CAP, resid_cap=RESID_CAP,
          tint_cap=TINT_CAP, mu_cap=MU_CAP, gain_cap=GAIN_CAP):
    """Learn the S -> T colour map.  src_rgb / dst_rgb are (N,3) float 0..1."""
    s = C.rgb_to_lab(np.asarray(src_rgb, np.float32)).astype(np.float64)
    t = C.rgb_to_lab(np.asarray(dst_rgb, np.float32)).astype(np.float64)
    sL, sa, sb = s[:, 0], s[:, 1], s[:, 2]
    tL, ta, tb = t[:, 0], t[:, 1], t[:, 2]
    ns, nt = s.shape[0], t.shape[0]
    mc = _min_count(min(ns, nt))
    strength = float(np.clip(strength, 0.0, 1.0))

    # ---------------------------------------------------------- 1. luminance
    mu_s, sd_s = float(sL.mean()), float(sL.std())
    mu_t, sd_t = float(tL.mean()), float(tL.std())
    k = float(np.clip(sd_t / max(sd_s, 1e-6), 0.5, 2.0))
    xs = np.maximum.accumulate(np.quantile(sL, _Q))
    ys = np.maximum.accumulate(np.quantile(tL, _Q))
    grid = np.linspace(0.0, 100.0, _LGRID)
    lin_grid = (grid - mu_s) * k + mu_t
    qmap = np.interp(grid, xs, ys)
    shape = np.clip(qmap - lin_grid, -shape_cap, shape_cap)
    curve = (1.0 - strength) * grid + strength * (lin_grid + shape)
    curve = np.maximum.accumulate(np.clip(curve, 0.0, 100.0))
    sL2 = np.interp(sL, grid, curve)

    # ------------------------------------------- 2. chroma (linear transport)
    muS = np.array([sa.mean(), sb.mean()])
    muT = np.array([ta.mean(), tb.mean()])
    covS = np.cov(np.stack([sa, sb]))
    covT = np.cov(np.stack([ta, tb]))
    A = _sqrtm_psd(covT) @ _invsqrtm_psd(covS)
    U, sv, Vt = np.linalg.svd(A)
    sv = np.clip(sv, 1.0 / gain_cap, gain_cap)
    A = U @ np.diag(sv) @ Vt
    dmu = np.clip(muT - muS, -mu_cap, mu_cap)
    M = (1.0 - strength) * np.eye(2) + strength * A
    c = strength * (muS + dmu - muS @ A.T)

    ab = np.stack([sa, sb], axis=1) @ M.T + c           # (N,2)

    # --------------------------------------------------- 3. per-hue residual
    thue = _hue_deg(ta, tb)
    hue = _hue_deg(ab[:, 0], ab[:, 1])
    binw = 360.0 / HUE_BINS
    hue_delta = np.zeros((HUE_BINS, 2), np.float64)
    hue_count = np.zeros(HUE_BINS, np.int64)
    for i in range(HUE_BINS):
        lo, hi = i * binw, (i + 1) * binw
        ms = (hue >= lo) & (hue < hi)
        mt = (thue >= lo) & (thue < hi)
        hue_count[i] = min(int(ms.sum()), int(mt.sum()))
        if ms.sum() < mc or mt.sum() < mc:
            continue
        hue_delta[i] = [ta[mt].mean() - ab[ms, 0].mean(),
                        tb[mt].mean() - ab[ms, 1].mean()]
    hue_delta = np.clip(hue_delta, -resid_cap, resid_cap)
    hue_delta = _smooth_circular(hue_delta, passes=2)

    idx = np.clip((hue / binw).astype(np.int64), 0, HUE_BINS - 1)
    ab2 = ab + hue_delta[idx] * strength

    # ------------------------------------------- 4. per-luminance split tone
    edges = np.linspace(0.0, 100.0, L_BANDS + 1)
    bandw = 100.0 / L_BANDS
    tint = np.zeros((L_BANDS, 2), np.float64)
    for i in range(L_BANDS):
        ms = (sL2 >= edges[i]) & (sL2 < edges[i + 1])
        mt = (tL >= edges[i]) & (tL < edges[i + 1])
        if ms.sum() < mc or mt.sum() < mc:
            continue
        tint[i] = [ta[mt].mean() - ab2[ms, 0].mean(),
                   tb[mt].mean() - ab2[ms, 1].mean()]
    tint = np.clip(tint, -tint_cap, tint_cap)
    tint = _smooth_bands(tint, passes=1)

    model = {
        "version": 3,
        "strength": strength,
        "L_grid": grid.astype(np.float32),
        "L_curve": curve.astype(np.float32),
        "M": M.astype(np.float32),
        "c": c.astype(np.float32),
        "hue_delta": hue_delta.astype(np.float32),
        "hue_count": hue_count.tolist(),
        "tint": tint.astype(np.float32),
        "caps": {"shape": shape_cap, "resid": resid_cap, "tint": tint_cap,
                 "mu": mu_cap, "gain": gain_cap},
        "n_pixels": {"source": int(ns), "target": int(nt)},
    }
    return model, _describe(model, mu_s, sd_s, mu_t, sd_t, k, dmu, A)


def _describe(model, mu_s, sd_s, mu_t, sd_t, k, dmu, A):
    g = model["L_grid"].astype(np.float64)
    f = model["L_curve"].astype(np.float64)
    probe = [5, 25, 50, 75, 95]
    return {
        "exposure_luma": {"source_mean": round(mu_s, 2), "target_mean": round(mu_t, 2),
                          "source_std": round(sd_s, 2), "target_std": round(sd_t, 2),
                          "contrast_gain": round(k, 4)},
        "tone_curve_probe": {f"L{p}": round(float(np.interp(p, g, f)) - p, 2) for p in probe},
        "white_balance_ab_shift": {"a": round(float(dmu[0]), 2), "b": round(float(dmu[1]), 2)},
        "chroma_gain": [round(float(x), 3) for x in np.linalg.svd(A, compute_uv=False)],
        "hue_residual_max": round(float(np.abs(model["hue_delta"]).max()), 3),
        "tint_max": round(float(np.abs(model["tint"]).max()), 3),
    }


# ---------------------------------------------------------------------- apply
def apply(model, rgb):
    """Apply the learned map to an RGB image (H,W,3) float 0..1."""
    arr = np.asarray(rgb, np.float32)
    shape = arr.shape
    lab = C.rgb_to_lab(arr).reshape(shape).astype(np.float64)
    L = lab[..., 0]
    a = lab[..., 1]
    b = lab[..., 2]

    s = float(model["strength"])
    L2 = np.interp(L, model["L_grid"].astype(np.float64), model["L_curve"].astype(np.float64))
    ab = np.stack([a, b], axis=-1) @ model["M"].astype(np.float64).T + model["c"].astype(np.float64)

    binw = 360.0 / HUE_BINS
    hue = _hue_deg(ab[..., 0], ab[..., 1])
    idx = np.clip((hue / binw).astype(np.int64), 0, HUE_BINS - 1)
    ab = ab + model["hue_delta"].astype(np.float64)[idx] * s

    bandw = 100.0 / L_BANDS
    bidx = np.clip((L2 / bandw).astype(np.int64), 0, L_BANDS - 1)
    ab = ab + model["tint"].astype(np.float64)[bidx] * s

    out = np.stack([L2, ab[..., 0], ab[..., 1]], axis=-1).astype(np.float32)
    return C.lab_to_rgb(out).reshape(shape).astype(np.float32)


# ----------------------------------------------------------------------- bake
def bake(model, n=33):
    """Evaluate the model on the identity grid -> an (n,n,n,3) 3-D LUT.

    Coverage is 100 % by construction: every term of the model is defined for
    any input colour, so no cell is ever filled by interpolation guesswork.
    """
    axis = np.linspace(0.0, 1.0, n, dtype=np.float32)
    B, G, R = np.meshgrid(axis, axis, axis, indexing="ij")
    grid = np.stack([R, G, B], axis=-1).reshape(-1, 3)
    out = apply(model, grid)
    return out.reshape(n, n, n, 3).astype(np.float32)


def cubic_identity_check(lut, n=33):
    """Fraction of cells whose output luminance breaks input-luminance order."""
    from . import lut3d as L3
    return L3.luminance_inversion_rate(lut) if hasattr(L3, "luminance_inversion_rate") else None
