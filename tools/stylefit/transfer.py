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
TOP_QUANTILE_TRUST_SLACK = 1.5  # L*: curve may exceed T's measured quantiles by
                     # at most this in the highlights; None = legacy unclamped

# --- exposure-relative transfer (user directive 2026-10-16) ----------------
# Never impose an ABSOLUTE brighten/darken (that is what washes highlights to
# dead white / crushes shadows to dead black).  Like film: first EXPOSE the
# input toward the corpus key (multiplicative gain in linear light, capped),
# THEN apply the film tone shape.  Bright corpus (5207) exposes right and its
# shoulder absorbs the lift; dark corpus exposes left and its toe lifts.
EXPOSURE_EV_CAP = 0.8         # max stops the exposure pre-stage may shift
EXPOSURE_EV_DEADBAND = 0.05   # stop difference below which no exposure is applied
# The exposure pre-stage + key-elastic experiments DOUBLE count brightness in
# a static LUT (exposure lifts mids, then the quantile map lifts again ->
# whole-image washed grey, user-verified).  Default OFF: luminance uses the
# proven composition (affine level + capped shape + top-end quantile trust);
# ev is still measured and exported in the profile for a future App-side
# ADAPTIVE stage that exposes at capture time, before the LUT.
EXPOSURE_STAGE = False
# Film scans ALWAYS contain clipped atoms (dead white / dead black).  Small
# atoms are capture/scanner physics -> redistribute so the learned target
# shows a smooth shoulder/toe instead of a cliff into 100/0.  Atoms ABOVE
# ATOM_STYLE_MASS are treated as the profile's artistic character (blown
# highlights / crushed blacks style) and are kept verbatim — never protected
# away (user directive: 肩部压制不得写死).
ATOM_LOW, ATOM_HIGH = 1.0, 99.0
ATOM_REDISP_LO = (0.5, 3.5)
ATOM_REDISP_HI = (96.5, 99.5)
ATOM_STYLE_MASS = 0.05
# Hard end-protection of the final curve, strength scaled by how CLEAN T's
# own ends are (0 atoms -> full protection; T already clipped -> none):
SHADOW_FLOOR_RATIO = 0.75     # curve(v) >= ratio*v for v <= 20 L* (ratio*black_keep)
SHADOW_FLOOR_MAX_L = 20.0
HIGHLIGHT_CEIL_RATIO = 0.5    # ceiling distance ratio (scaled by white_keep)
HIGHLIGHT_CEIL_MIN_L = 80.0   # curve(v) <= 100 - ratio*(100-v) for v above this
# Key-anchored elastic suppression of the absolute level in the tone curve:
# displacement is zeroed AT the corpus key and restored smoothly toward the
# tonal extremes (kernel u/(u+w), w tied to the input IQR).
KEY_ELASTIC_MIN_W = 8.0       # L* floor of the elastic half-width
KEY_ELASTIC_MAX_W = 30.0
KEY_ELASTIC_IQR = 0.35        # half-width = this * input IQR (clamped)
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


def _lin_luma(rgb):
    """Rec.709 linear-light luminance Y in [0,1] — the physical quantity that
    exposure stops act on (C.luma is sRGB-encoded and NOT exposure-linear)."""
    c = np.clip(np.asarray(rgb, np.float32), 0.0, 1.0)
    lin = C.srgb_to_linear(c)
    w = np.array([0.2126, 0.7152, 0.0722], np.float32)
    return (lin @ w).astype(np.float64)


def _expose_rgb(rgb, gain):
    """Exposure pre-stage: multiplicative gain in LINEAR light, then back to
    sRGB.  Scene-proportional (not an additive absolute offset); only pixels
    pushed past 1.0 clip, and the film curve learned next handles those."""
    c = np.clip(np.asarray(rgb, np.float32), 0.0, 1.0)
    lin = np.clip(C.srgb_to_linear(c) * np.float32(gain), 0.0, 1.0)
    return C.linear_to_srgb(lin).astype(np.float32)


def _expo_lstar_map(grid, gain, ev):
    """Exposure pre-stage as an UNCLIPPED CIE L* map with headroom taper.

    A naive sRGB-roundtrip exposure clips highlights at 1.0 BEFORE the film
    curve can compress them — the shoulder never sees the extra light and a
    wide input range slams into white.  Instead: full multiplicative gain in
    linear light where there is headroom (shadows/mids for ev>0), tapering
    smoothly to zero where there is none (highlights for ev>0, shadows for
    ev<0) — those ends are rendered by the film tone shape instead.  The map
    stays monotone for |ev| <= EXPOSURE_EV_CAP.
    """
    g = np.asarray(grid, np.float64)
    v = np.clip(g / 100.0, 0.0, 1.0)
    lin = np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
    l_full = np.maximum(116.0 * np.cbrt(np.clip(lin * gain, 0.0, None)) - 16.0, 0.0)
    t = np.clip((g - 60.0) / 35.0, 0.0, 1.0)
    taper_hi = t * t * (3.0 - 2.0 * t)          # 0 below L*60 -> 1 above L*95
    t2 = np.clip((g - 2.0) / 23.0, 0.0, 1.0)
    taper_lo = t2 * t2 * (3.0 - 2.0 * t2)       # 0 below L*2  -> 1 above L*25
    w = (1.0 - taper_hi) if ev > 0 else taper_lo
    return g + (l_full - g) * w


def _despike_l(L, rng, despike_white=True, despike_black=True):
    """Redistribute clipped scanner atoms over a narrow band below/above the
    clip point, so quantile estimation sees a smooth shoulder/toe.  Pass
    despike_white/black=False to keep a style atom verbatim."""
    L = np.asarray(L, np.float64).copy()
    if despike_white:
        hi = L > ATOM_HIGH
        if hi.any():
            L[hi] = rng.uniform(*ATOM_REDISP_HI, int(hi.sum()))
    if despike_black:
        lo = L < ATOM_LOW
        if lo.any():
            L[lo] = rng.uniform(*ATOM_REDISP_LO, int(lo.sum()))
    return L


# ---------------------------------------------------------------------- learn
def learn(src_rgb, dst_rgb, strength=DEFAULT_STRENGTH, shape_cap=SHAPE_CAP, resid_cap=RESID_CAP,
          tint_cap=TINT_CAP, mu_cap=MU_CAP, gain_cap=GAIN_CAP,
          relative_luma=False, luma_level_keep=0.0):
    """Learn the S -> T colour map.  src_rgb / dst_rgb are (N,3) float 0..1.

    relative_luma : optional legacy mode (default off).  The PRIMARY level
        mechanism is now the exposure pre-stage (EXPOSURE_EV_*) + exposure-
        neutral tone shape; see the constants block.
    luma_level_keep : only used with relative_luma=True.
    """
    # ------------------------------------------------- 0. exposure pre-stage
    # Exposure difference measured as stop distance between robust linear-
    # luma medians.  With EXPOSURE_STAGE=False (default) this is MEASURED AND
    # EXPORTED ONLY (profile param for a future App-side adaptive stage) —
    # the pixels are NOT exposed: a static LUT cannot stack an exposure on
    # top of a quantile-map without double counting brightness (verified:
    # whole-image washed grey).  With EXPOSURE_STAGE=True the input is
    # exposed toward T FIRST (向右/向左), then the film tone shape follows.
    medY_s = float(np.median(_lin_luma(src_rgb)))
    medY_t = float(np.median(_lin_luma(dst_rgb)))
    ev_raw = float(np.log2(max(medY_t, 1e-6) / max(medY_s, 1e-6)))
    ev = 0.0
    if abs(ev_raw) >= EXPOSURE_EV_DEADBAND:
        ev = float(np.clip(ev_raw, -EXPOSURE_EV_CAP, EXPOSURE_EV_CAP))
    gain = 2.0 ** ev
    src2 = (_expose_rgb(src_rgb, gain)
            if (ev and EXPOSURE_STAGE) else np.asarray(src_rgb, np.float32))

    s0 = C.rgb_to_lab(np.asarray(src_rgb, np.float32)).astype(np.float64)  # original
    s = C.rgb_to_lab(src2).astype(np.float64)                              # exposed
    t = C.rgb_to_lab(np.asarray(dst_rgb, np.float32)).astype(np.float64)
    sL0, sa, sb = s0[:, 0], s[:, 1], s[:, 2]
    sL, tL, ta, tb = s[:, 0], t[:, 0], t[:, 1], t[:, 2]
    ns, nt = s.shape[0], t.shape[0]
    mc = _min_count(min(ns, nt))
    strength = float(np.clip(strength, 0.0, 1.0))

    # ------------------------------------------- 1. luminance (film SHAPE)
    # Atom handling (adaptive, per user directive): S is always a photo side,
    # its clipped atoms are capture physics -> always despike for robust
    # quantile anchoring.  T's atoms are style-ambiguous: below
    # ATOM_STYLE_MASS they are scanner clipping (despike -> smooth shoulder/
    # toe); above, blown-white / crushed-black IS the profile's character and
    # the cliff is kept verbatim.
    rng = np.random.default_rng(20261016)
    atom_s = (float((sL > ATOM_HIGH).mean()), float((sL < ATOM_LOW).mean()))
    atom_t = (float((tL > ATOM_HIGH).mean()), float((tL < ATOM_LOW).mean()))
    sL_d = _despike_l(sL, rng)
    tL_d = _despike_l(tL, rng,
                      despike_white=atom_t[0] < ATOM_STYLE_MASS,
                      despike_black=atom_t[1] < ATOM_STYLE_MASS)

    mu_s, sd_s = float(sL.mean()), float(sL.std())
    mu_t, sd_t = float(tL.mean()), float(tL.std())
    k = float(np.clip(sd_t / max(sd_s, 1e-6), 0.5, 2.0))
    xs = np.maximum.accumulate(np.quantile(sL_d, _Q))
    ys = np.maximum.accumulate(np.quantile(tL_d, _Q))
    grid = np.linspace(0.0, 100.0, _LGRID)

    # Proven-look luminance synthesis (default).  This is the composition the
    # user visually approved: affine absolute level (S->T key, the film's
    # characteristic lift) + capped relative shape (toe/contrast/shoulder).
    # The experimental exposure-prestage + key-elastic variants below DOUBLE
    # count brightness when folded into a static LUT (exposure lifts mids
    # +17 L*, then the quantile map adds its displacement again -> whole-image
    # washed-out grey); they are kept behind flags for the future App-side
    # ADAPTIVE exposure stage only, where the pre-stage is applied at capture
    # time and the LUT never stacks on top of it.
    qmap = np.interp(grid, xs, ys)
    if EXPOSURE_STAGE:
        # Absolute (despiked) quantile map, shrunk by strength.
        film = (1.0 - strength) * grid + strength * qmap
        key_e = float(np.median(sL))                 # exposed-domain key
        q25, q75 = np.quantile(sL, [0.25, 0.75])
        w_el = float(np.clip(KEY_ELASTIC_IQR * (q75 - q25),
                             KEY_ELASTIC_MIN_W, KEY_ELASTIC_MAX_W))
        u_abs = np.abs(grid - key_e)
        gamma = u_abs / (u_abs + w_el)               # 0 at key -> 1 far away
        curve = np.maximum.accumulate(np.clip(grid + (film - grid) * gamma,
                                              0.0, 100.0))
        if ev:
            expo = _expo_lstar_map(grid, gain, ev)
            curve_o = np.interp(expo, grid, curve)
            qmap_o = np.interp(np.clip(expo, 0.0, 100.0), xs, ys)
        else:
            expo = grid
            curve_o = curve.copy()
            qmap_o = qmap
    else:
        lin_grid = (grid - mu_s) * k + mu_t
        shape = np.clip(qmap - lin_grid, -shape_cap, shape_cap)
        curve_o = (1.0 - strength) * grid + strength * (lin_grid + shape)
        expo = grid
        qmap_o = qmap

    # -- end protections, ORIGINAL domain, strength ADAPTIVE to T's ends -----
    # Top-end quantile trust: clamp the curve to T's measured quantiles +
    # slack (when T keeps a style atom, its quantile is ~100 and this cap is
    # a no-op automatically).  Shadow floor / highlight ceiling protect only
    # as much as T itself is clean: black_keep/white_keep -> 1 for a clean
    # corpus, -> 0 when T's own character includes crushed blacks / blown
    # whites (then NO artificial protection is applied).
    white_keep = float(np.clip(1.0 - atom_t[0] / ATOM_STYLE_MASS, 0.0, 1.0))
    black_keep = float(np.clip(1.0 - atom_t[1] / ATOM_STYLE_MASS, 0.0, 1.0))
    top_slack = TOP_QUANTILE_TRUST_SLACK
    if top_slack is not None:
        cap_hi = qmap_o + float(top_slack)
        over = curve_o > cap_hi
        if np.any(over):
            curve_o[over] = cap_hi[over]
    lo_ratio = SHADOW_FLOOR_RATIO * black_keep
    if lo_ratio > 0.0:
        lo_zone = grid <= SHADOW_FLOOR_MAX_L
        curve_o[lo_zone] = np.maximum(curve_o[lo_zone], lo_ratio * grid[lo_zone])
    hi_strength = HIGHLIGHT_CEIL_RATIO * white_keep
    if hi_strength > 0.0:
        hi_zone = grid >= HIGHLIGHT_CEIL_MIN_L
        curve_o[hi_zone] = np.minimum(curve_o[hi_zone],
                                      100.0 - hi_strength * (100.0 - grid[hi_zone]))

    if relative_luma:
        # Optional legacy mode (default off): additionally remove the whole
        # level component at the key instead of relying on the elastic
        # suppression + exposure pre-stage.
        off = float(np.interp(mu_s, grid, curve_o) - mu_s)
        curve_o = curve_o - (1.0 - float(luma_level_keep)) * off
        curve_o = np.clip(curve_o, 0.0, 100.0)

    curve_o = np.maximum.accumulate(np.clip(curve_o, 0.0, 100.0))

    sL2 = np.interp(sL0, grid, curve_o)

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
        "relative_luma": bool(relative_luma),
        "luma_level_keep": float(luma_level_keep),
        "exposure_ev": float(ev),
        "exposure_ev_raw": round(ev_raw, 4),
        "atom_mass": {"source_white": round(atom_s[0], 5), "source_black": round(atom_s[1], 5),
                      "target_white": round(atom_t[0], 5), "target_black": round(atom_t[1], 5)},
        "key_anchor": round(float(np.median(sL)), 3) if EXPOSURE_STAGE else None,
        "key_elastic_width": None if not EXPOSURE_STAGE else
        float(np.clip(KEY_ELASTIC_IQR * (np.quantile(sL, 0.75) - np.quantile(sL, 0.25)),
                      KEY_ELASTIC_MIN_W, KEY_ELASTIC_MAX_W)),
        "end_protection": {"white_keep": round(white_keep, 3),
                           "black_keep": round(black_keep, 3)},
        "L_grid": grid.astype(np.float32),
        "L_curve": curve_o.astype(np.float32),   # ORIGINAL domain (exposure folded)
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
                          "contrast_gain": round(k, 4),
                          "exposure_ev_stops": round(float(model.get("exposure_ev", 0.0)), 3),
                          "exposure_ev_raw": round(float(model.get("exposure_ev_raw", 0.0)), 3),
                          "key_anchor": model.get("key_anchor"),
                          "key_elastic_width": model.get("key_elastic_width"),
                          "end_protection": model.get("end_protection", {}),
                          "atom_mass": model.get("atom_mass", {})},
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
