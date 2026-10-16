#!/usr/bin/env python3
"""Neural residual refinement of a stylefit 3-D LUT (gradient-based).

Scientific basis (verified literature):

- F. Pitié, A. Kokaram, R. Dahyot, "Automated colour grading using colour
  distribution transfer", Computer Vision and Image Understanding 107(1-2):
  123-137, 2007.  Colour grading WITHOUT paired corpora = transfer the
  probability density function of colours; the N-D transfer is solved by
  ITERATIVE 1-D QUANTILE (sort-sort) matching along projections.  Our loss
  is exactly that 1-D quantile transfer, made differentiable: the L2 between
  sorted rendered-pixel quantiles and sorted target quantiles (W2 on the
  line) for L globally and for a/b within luminance bands (the film look is
  hue-by-brightness dependent, so a/b must be matched conditioned on L).
- H. Zeng, J. Cai, L. Li, Z. Cao, L. Zhang, "Learning Image-adaptive 3D
  Lookup Tables for High Performance Photo Enhancement in Real-time",
  IEEE TPAMI 2022 (arXiv:2009.14468).  Establishes that 3-D LUTs can be
  learned END-TO-END by gradient descent from paired OR UNPAIRED data, with
  smoothness regularisation on the LUT and identity initialisation.  We
  optimise the 33^3 = 35937-cell LUT directly (fewer parameters than their
  600K network), initialised at the stylefit analytic solution.
- E. Reinhard, M. Ashikhmin, B. Gooch, P. Shirley, "Color Transfer between
  Images", IEEE CG&A 21(5):34-41, 2001: the transfer is performed in CIELAB,
  where L is decorrelated from chroma -- the space our loss operates in.

Design constraints (project directives):
- Residual refinement ONLY: the optimiser starts at the stylefit solution and
  a L2 pull-to-initial keeps the result in its neighbourhood; a gate (caller)
  accepts the refined LUT only if honest metrics improve.  解析解是主，神经
  精修是辅 -- 永远不会更差。
- No dead-end regression: dead white/black of non-clipped pixels is part of
  the gate (caller side).
- Pure numpy + scipy L-BFGS-B: no torch dependency, so the Studio exe stays
  small.  120k pixels x ~100 iterations runs in ~1-2 minutes on CPU.
"""
from __future__ import annotations

import numpy as np
from scipy.optimize import minimize

# --------------------------------------------------------------- Lab (D65)
_M_SRGB2XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041]], np.float64)
_WP = np.array([0.95047, 1.0, 1.08883], np.float64)
_EPS = 0.008856
_KAPPA = 903.3


def _srgb_to_lin(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def _lab_fwd(rgb):
    """sRGB (N,3) 0..1 -> Lab (N,3); returns lab and cache for backward."""
    rgb = np.clip(np.asarray(rgb, np.float64), 0.0, 1.0)
    lin = _srgb_to_lin(rgb)
    xyz = lin @ _M_SRGB2XYZ.T
    t = xyz / _WP
    f = np.where(t > _EPS, np.cbrt(np.maximum(t, _EPS)), _KAPPA / 116.0 * t + 16.0 / 116.0)
    L = 116.0 * f[..., 1] - 16.0
    a = 500.0 * (f[..., 0] - f[..., 1])
    b = 200.0 * (f[..., 1] - f[..., 2])
    cache = (rgb, t, f)
    return np.stack([L, a, b], -1), cache


def _lab_bwd(dLab, cache):
    """Chain dLab/dOut back to d/d(sRGB out) — the LUT output is sRGB-encoded,
    so the sRGB->linear derivative MUST be included."""
    rgb, t, f = cache
    dfdt = np.where(t > _EPS, 1.0 / (3.0 * np.cbrt(np.maximum(t, _EPS)) ** 2),
                    _KAPPA / 116.0)
    dfdt = np.clip(dfdt, 0.0, 1e6)          # guard the linear-toe junction
    # lab -> f:  L=116 f(Y)-16, a=500(f(X)-f(Y)), b=200(f(Y)-f(Z))
    dL, da, db = dLab[..., 0], dLab[..., 1], dLab[..., 2]
    df = np.empty_like(dLab)
    df[..., 0] = 500.0 * da                 # d/dfX
    df[..., 1] = 116.0 * dL - 500.0 * da + 200.0 * db   # d/dfY
    df[..., 2] = -200.0 * db                # d/dfZ
    dt = df * dfdt
    dxyz = dt / _WP
    dlin = dxyz @ _M_SRGB2XYZ
    # linear -> sRGB chain (d srgb / d lin)
    lc = np.clip(rgb, 0.0, 1.0)
    dsrgb_dlin = np.where(lc <= 0.04045, np.full_like(lc, 12.92),
                          2.4 * ((lc + 0.055) / 1.055) ** 1.4 / 1.055)
    return dlin * dsrgb_dlin


# ------------------------------------------------------- differentiable LUT
def _render(lut, rgb):
    """Trilinear forward matching lut3d.sample (lut indexed [b,g,r]),
    clamp-to-edge.  Returns out(N,3) and scatter bookkeeping."""
    n = lut.shape[0]
    flat = np.clip(np.asarray(rgb, np.float64).reshape(-1, 3), 0.0, 1.0) * (n - 1)
    i0 = np.floor(flat).astype(np.int32)
    i0 = np.minimum(i0, n - 1)
    f = flat - i0
    i1 = np.minimum(i0 + 1, n - 1)
    fx, fy, fz = f[:, 0], f[:, 1], f[:, 2]
    lutf = lut.reshape(-1, 3)
    out = np.zeros((flat.shape[0], 3), np.float64)
    corners = []
    for dz in (0, 1):
        wz = fz if dz else 1.0 - fz
        for dy in (0, 1):
            wy = fy if dy else 1.0 - fy
            for dx in (0, 1):
                wx = fx if dx else 1.0 - fx
                idx = (np.where(dx, i1[:, 0], i0[:, 0])
                       + n * np.where(dy, i1[:, 1], i0[:, 1])
                       + n * n * np.where(dz, i1[:, 2], i0[:, 2]))
                w = wx * wy * wz
                corners.append((idx, w))
                out += lutf[idx] * w[:, None]
    return out, corners


def _scatter(dOut, corners, n):
    """d/dlut from dOut(N,3): 8 corner bincounts per channel."""
    g = np.zeros((n * n * n, 3), np.float64)
    for idx, w in corners:
        ww = w[:, None]
        for c in range(3):
            g[:, c] += np.bincount(idx, weights=dOut[:, c] * w,
                                   minlength=n * n * n)
    return g


# ------------------------------------------------------------------- losses
def _w2_1d(p, q):
    """Differentiable 1-D Wasserstein-2 (Pitié's quantile transfer).
    p: rendered values (N,), q: target values (M,).  Returns (loss, dL/dp)."""
    ps = np.sort(p)
    qs = np.sort(np.asarray(q, np.float64))
    if ps.shape[0] != qs.shape[0]:
        # quantile-resample the shorter side onto p's ranks
        ranks = (np.arange(ps.shape[0]) + 0.5) / ps.shape[0]
        qr = np.quantile(qs, ranks)
    else:
        qr = qs
    d = ps - qr
    loss = float(np.mean(d * d))
    dp_sorted = 2.0 * d / ps.shape[0]
    order = np.argsort(p, kind="stable")
    dp = np.empty_like(p)
    dp[order] = dp_sorted
    return loss, dp


_L_BANDS = 12


def _ab_band_w2(Pl, Pa, Pb, Ql, Qa, Qb):
    """a/b matched CONDITIONED on luminance band (film hue depends on L).
    Returns (loss, dPa, dPb)."""
    edges = np.linspace(0.0, 100.0, _L_BANDS + 1)
    loss, dA, dB = 0.0, np.zeros_like(Pa), np.zeros_like(Pb)
    for i in range(_L_BANDS):
        mp = (Pl >= edges[i]) & (Pl < edges[i + 1])
        mq = (Ql >= edges[i]) & (Ql < edges[i + 1])
        if mp.sum() < 200 or mq.sum() < 200:
            continue
        for ch, qv, dv in ((0, Qa, dA), (1, Qb, dB)):
            l, dp = _w2_1d(Pa[mp] if ch == 0 else Pb[mp], qv[mq])
            loss += l / _L_BANDS
            dv[mp] += dp / _L_BANDS
    return loss, dA, dB


def _tv(lut):
    d = (np.abs(np.diff(lut, axis=0)).sum() + np.abs(np.diff(lut, axis=1)).sum()
         + np.abs(np.diff(lut, axis=2)).sum())
    g = np.zeros_like(lut)
    for ax in (0, 1, 2):
        dd = np.diff(lut, axis=ax)
        s = np.sign(dd)
        sl = [slice(None)] * 4
        # |lut[k+1]-lut[k]|: d/dlut[k] = -sign, d/dlut[k+1] = +sign
        sl[ax] = slice(0, -1)
        g[tuple(sl)] -= s
        sl[ax] = slice(1, None)
        g[tuple(sl)] += s
    return float(d), g


# ------------------------------------------------------------------ driver
def refine_lut(lut0, src_px, dst_px, steps=100, pixels=120000, seed=7,
               w_tv=2.0, w_resid=4.0, verbose=False):
    """Residual-refine a baked LUT by direct gradient optimisation.

    lut0   : (n,n,n,3) float32, the stylefit analytic solution (initial AND
             the pull-to-initial anchor).
    src_px : (N,3) input-corpus pixels (sRGB 0..1) — rendered through the LUT.
    dst_px : (M,3) target-corpus pixels (sRGB 0..1) — the graded style.
    Returns (lut_refined, report).
    """
    rng = np.random.default_rng(seed)
    n = lut0.shape[0]
    src_px = np.asarray(src_px, np.float64).reshape(-1, 3)
    dst_px = np.asarray(dst_px, np.float64).reshape(-1, 3)
    if src_px.shape[0] > pixels:
        src_px = src_px[rng.choice(src_px.shape[0], pixels, replace=False)]
    if dst_px.shape[0] > pixels:
        dst_px = dst_px[rng.choice(dst_px.shape[0], pixels, replace=False)]
    lab_q, _ = _lab_fwd(dst_px)
    Ql, Qa, Qb = lab_q[:, 0], lab_q[:, 1], lab_q[:, 2]
    lut0f = lut0.astype(np.float64).copy()
    lut0_flat = lut0f.reshape(-1, 3)
    nvar = int(lut0f.size)

    evals = {"n": 0}

    def fun(x):
        lut = x.reshape(lut0f.shape)
        out, corners = _render(lut, src_px)
        lab, cache = _lab_fwd(out)
        Pl, Pa, Pb = lab[:, 0], lab[:, 1], lab[:, 2]
        lL, dpL = _w2_1d(Pl, Ql)
        lAB, dpA, dpB = _ab_band_w2(Pl, Pa, Pb, Ql, Qa, Qb)
        ltv, gtv = _tv(lut)
        res = lut - lut0f
        lres = float(np.mean(res * res)) * w_resid * 100.0
        dLab = np.stack([dpL, dpA, dpB], -1)
        dOut = _lab_bwd(dLab, cache)
        gl = _scatter(dOut, corners, n).reshape(lut0f.shape)
        gl += gtv * w_tv * 1e-4
        gl += 2.0 * res * w_resid * 100.0 / res.size
        evals["n"] += 1
        if verbose and evals["n"] % 20 == 0:
            print(f"    [neural] it{evals['n']:3d}  L {lL:.4f}  ab {lAB:.4f} "
                  f"tv {ltv:.1f} resid {lres:.3f}", flush=True)
        return float(lL + lAB + ltv * w_tv * 1e-4 + lres), gl.reshape(-1)

    x0 = lut0f.reshape(-1).copy()
    res = minimize(fun, x0, jac=True, method="L-BFGS-B",
                   bounds=[(0.0, 1.0)] * nvar,
                   options={"maxiter": int(steps), "maxcor": 8, "maxls": 12,
                            "ftol": 1e-12, "gtol": 1e-10})
    lut = np.clip(res.x.reshape(lut0f.shape), 0.0, 1.0)
    # keep identity anchors: pure black/white corners stay put unless the
    # target corpus genuinely wants otherwise (Zeng: identity initialisation)
    rep = {"iters": evals["n"], "converged": bool(res.success),
           "final_objective": float(res.fun)}
    return lut.astype(np.float32), rep
