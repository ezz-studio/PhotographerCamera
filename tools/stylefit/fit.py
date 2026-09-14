#!/usr/bin/env python3
"""stylefit v2 — learn a PhotographerProfile from graded reference photos.

WHY THE OLD PIPELINE PRODUCED GARBAGE
-------------------------------------
The old chain optimised

    loss( features(reference_i),  features(render(reference_i, profile)) )

i.e. it asked the profile to leave an *already graded* photo unchanged. The
global optimum of that objective is the identity transform, and every step
away from identity was fitting noise (Nelder-Mead over 12 bounded scalars with
a freshly-resampled evaluation subset at every call). Worse, the tone curve
was baked as the reference luminance CDF, so applying the profile to any other
photo performed a full histogram equalisation towards the reference histogram
— which is why results "looked nothing like the original".

WHAT THIS MODULE DOES INSTEAD
-----------------------------
1. Build a PAIRED training set. Every reference photo A is de-styled into A'
   (color.neutralize: grey-world WB, neutral chroma, neutral tone shape). A'
   keeps the content and drops the grade, so (A'_px, A_px) is a genuine
   "un-graded -> graded" pair.
2. Learn the colour layer as a 33^3 3-D LUT by binning/averaging those pairs,
   then smoothing + isotonic (PAVA) regularisation so the LUT can never invert
   tonal order. Style strength is a single linear blend towards identity.
3. Estimate the spatial layers (vignette, grain, bloom/halation) from geometry
   and residual measurements that the colour LUT provably cannot express.
4. Evaluate honestly on a HELD-OUT split (and on real un-graded photos when
   provided): report style match, content fidelity and ΔE so the numbers mean
   something.

Everything is deterministic, dependency-light (NumPy + Pillow) and every knob
has a documented meaning.
"""
from __future__ import annotations

import os
import sys
from typing import Callable, List, Optional

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from dataset_loader import discover_images, read_image  # noqa: E402
from profile_schema import default_profile, safe_clamp  # noqa: E402

from . import color as C  # noqa: E402
from . import lut3d as L3  # noqa: E402
from . import transfer as TR  # noqa: E402

FIT_MAX_SIDE = 512        # fitting resolution — colour statistics converge fast
FIT_PIXELS_PER_IMAGE = 24000
EVAL_MAX_SIDE = 384


# --------------------------------------------------------------------- loading
def _load(path, max_side=FIT_MAX_SIDE):
    img = Image.open(path)
    if img.mode not in ("RGB", "L"):
        img = img.convert("RGB")
    if img.mode == "L":
        img = img.convert("RGB")
    w, h = img.size
    m = max(w, h)
    if m > max_side:
        s = max_side / m
        img = img.resize((max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS)
    return np.asarray(img, dtype=np.float32) / 255.0


def _sample_pixels(rgb, n, rng):
    flat = rgb.reshape(-1, 3)
    if flat.shape[0] <= n:
        return flat
    idx = rng.choice(flat.shape[0], size=n, replace=False)
    return flat[idx]


# ------------------------------------------------------------- spatial layers
def _radial_profile(rgb, rings=12):
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx = (xx + 0.5) / w * 2.0 - 1.0
    ny = (yy + 0.5) / h * 2.0 - 1.0
    r = np.sqrt(nx * nx + ny * ny)
    lum = C.luma(rgb)
    out = np.zeros(rings, np.float32)
    for i in range(rings):
        m = (r >= i / rings) & (r < (i + 1) / rings)
        if m.any():
            out[i] = float(lum[m].mean())
    return out


def estimate_vignette(images: List[np.ndarray]) -> float:
    """Corner falloff measured against the centre, averaged over many frames.

    Content averages out across frames; a real optical/stylistic vignette does
    not. Scaled conservatively (0.8) and capped at 0.5 — an over-strong
    vignette is the single most obvious "preset" tell.
    """
    profs = []
    for im in images:
        p = _radial_profile(im)
        inner = p[:3].mean()
        if inner > 1e-3:
            profs.append(p / inner)
    if not profs:
        return 0.0
    mean = np.mean(np.stack(profs), axis=0)
    corner = float(mean[-2:].mean())
    return float(np.clip((1.0 - corner) * 0.8, 0.0, 0.5))


def estimate_grain(images: List[np.ndarray]) -> float:
    """Film grain from the high-frequency residual measured on FLAT areas only.

    Measuring global high-pass energy would count real detail (hair, foliage)
    as grain. We therefore sample only pixels whose local gradient is small,
    i.e. where any residual must be noise/grain.
    """
    sigmas = []
    for im in images:
        if min(im.shape[:2]) < 32:
            continue
        g = C.luma(im)
        k = np.array([[0, 1, 0], [1, -4, 1], [0, 1, 0]], np.float32) / 4.0
        pad = np.pad(g, 1, mode="reflect")
        hp = (
            k[0, 1] * pad[:-2, 1:-1] + k[1, 0] * pad[1:-1, :-2] + k[1, 1] * pad[1:-1, 1:-1]
            + k[1, 2] * pad[1:-1, 2:] + k[2, 1] * pad[2:, 1:-1]
        )
        blur = _box_blur(g, 5)
        flatness = np.abs(g - blur)
        thr = np.percentile(flatness, 25)
        m = flatness <= max(thr, 1e-4)
        if m.sum() < 200:
            continue
        # local std of the high-pass on flat areas ≈ grain sigma (Laplacian/4 of
        # white noise has std ≈ sigma * sqrt(1.5)); calibrated empirically below
        sigmas.append(float(np.std(hp[m])))
    if not sigmas:
        return 0.0
    sigma = float(np.median(sigmas))
    # renderer: out += N(0,1) * amount*0.08*density/size * mask(≈0.7 mean)
    amount = sigma / (0.08 * 0.7) / 1.22
    return float(np.clip(amount * 0.6, 0.0, 0.6))


def _box_blur(x, k):
    pad = k // 2
    p = np.pad(x, pad, mode="reflect")
    acc = np.zeros_like(x)
    for i in range(k):
        for j in range(k):
            acc += p[i:i + x.shape[0], j:j + x.shape[1]]
    return acc / float(k * k)


def estimate_glow(images: List[np.ndarray]) -> float:
    """Bloom/halation proxy: light spilling OUT of bright cores onto their
    surroundings. Measured as the mean positive excess around bright blobs."""
    vals = []
    for im in images:
        if min(im.shape[:2]) < 48:
            continue
        g = C.luma(im)
        bright = np.clip(g - 0.88, 0.0, 1.0) / 0.12
        if bright.max() <= 0:
            vals.append(0.0)
            continue
        near = _box_blur(bright, 7)
        far = _box_blur(bright, 21)
        ring = np.clip(far - near * 0.35, 0.0, None)
        vals.append(float(ring.mean()))
    if not vals:
        return 0.0
    v = float(np.median(vals))
    return float(np.clip(v * 2.2, 0.0, 0.35))


# ------------------------------------------------------------- param fallback
def _hsv_maps(rgb):
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    v = mx
    d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0.0).astype(np.float32)
    return s.astype(np.float32), v.astype(np.float32)


def _hue_deg(rgb):
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    d = np.maximum(mx - mn, 1e-6)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    h = np.where(mx == r, ((g - b) / d) % 6.0,
                 np.where(mx == g, (b - r) / d + 2.0, (r - g) / d + 4.0))
    return (h * 60.0).astype(np.float32)


_HUE_BINS = [("red", 345, 15), ("orange", 15, 45), ("yellow", 45, 70),
             ("green", 70, 160), ("cyan", 160, 200), ("blue", 200, 260),
             ("purple", 260, 345)]


def estimate_hsl(src_list, dst_list):
    """Per-hue saturation / lightness multipliers: dst relative to src."""
    out = {}
    s_src = np.concatenate([_hsv_maps(x)[0].ravel() for x in src_list])
    v_src = np.concatenate([_hsv_maps(x)[1].ravel() for x in src_list])
    s_dst = np.concatenate([_hsv_maps(x)[0].ravel() for x in dst_list])
    v_dst = np.concatenate([_hsv_maps(x)[1].ravel() for x in dst_list])
    h = np.concatenate([_hue_deg(x).ravel() for x in src_list])
    for name, lo, hi in _HUE_BINS:
        m = (h >= lo) & (h < hi) if lo < hi else ((h >= lo) | (h < hi))
        # ignore near-grey pixels: their saturation ratio is numerically wild
        m &= (s_src > 0.08)
        if m.sum() < 500:
            out[name] = {}
            continue
        sat = float(s_dst[m].mean() / max(s_src[m].mean(), 1e-3))
        lig = float(v_dst[m].mean() / max(v_src[m].mean(), 1e-3))
        out[name] = {
            "hue_shift": 0,
            "saturation": round(float(np.clip(sat, 0.5, 1.5)), 4),
            "lightness": round(float(np.clip(lig, 0.75, 1.25)), 4),
        }
    return out


def estimate_tone_curve(src_list, dst_list, points=17):
    """1-D luminance quantile map src -> dst (monotone by construction)."""
    s = np.concatenate([C.luma(x).ravel() for x in src_list])
    d = np.concatenate([C.luma(x).ravel() for x in dst_list])
    qs = np.linspace(0.001, 0.999, points, dtype=np.float64)
    xs = np.quantile(s, qs)
    ys = np.quantile(d, qs)
    xs = np.maximum.accumulate(xs)
    ys = np.maximum.accumulate(ys)
    xs = (xs - xs[0]) / max(1e-6, xs[-1] - xs[0])
    ys = (ys - ys[0]) / max(1e-6, ys[-1] - ys[0])
    return [[round(float(x), 4), round(float(y), 4)] for x, y in zip(xs, ys)]


def estimate_white_balance(lut, n=33):
    """Read the LUT's response to mid grey -> temperature / tint bias."""
    grey = np.array([[0.18, 0.18, 0.18], [0.45, 0.45, 0.45], [0.75, 0.75, 0.75]], np.float32)
    out = L3.sample(lut, grey)
    lab_out = C.rgb_to_lab(out)
    lab_in = C.rgb_to_lab(grey)
    da = float(np.mean(lab_out[:, 1] - lab_in[:, 1]))
    db = float(np.mean(lab_out[:, 2] - lab_in[:, 2]))
    # renderer: temperature shifts R+/B- by 0.2 per unit; tint shifts R,B +0.1 / G -0.1
    temperature = float(np.clip(db / 6.0, -1.0, 1.0))
    tint = float(np.clip(-da / 6.0, -1.0, 1.0))
    return round(temperature, 4), round(tint, 4)


def estimate_exposure(lut):
    """EV shift implied by the LUT at the mid-tone anchor."""
    greys = np.linspace(0.05, 0.9, 24, dtype=np.float32).reshape(-1, 1)
    grey = np.repeat(greys, 3, axis=1)
    out = L3.sample(lut, grey)
    lin_in = C.srgb_to_linear(grey[:, 0] + 1e-4)
    lin_out = C.srgb_to_linear(np.clip(out[:, 0], 1e-4, 1.0) + 1e-4)
    ratio = np.median(lin_out / np.maximum(lin_in, 1e-5))
    bias = float(np.log2(np.clip(ratio, 1e-3, 1e3)))
    return round(float(np.clip(bias, -1.5, 1.5)), 4)


def estimate_color_matrix(lut, samples=4000, rng_seed=7):
    """Least-squares 3x3 that best approximates the LUT on low-chroma colours.

    Restricted to low chroma on purpose: a 3x3 can only express a linear map,
    so fitting it on saturated colours would trade accuracy where the linear
    model is actually valid.
    """
    rng = np.random.default_rng(rng_seed)
    x = rng.random((samples, 3)).astype(np.float32)
    x = 0.5 + (x - 0.5) * 0.55                      # keep near the neutral axis
    y = L3.sample(lut, x)
    # y = x @ M.T  ->  M = lstsq(x, y)
    M, *_ = np.linalg.lstsq(x.astype(np.float64), y.astype(np.float64), rcond=None)
    M = M.T.astype(np.float32)
    diag = np.clip(np.diag(M), 0.8, 1.2)
    off = np.clip(M - np.diag(np.diag(M)), -0.2, 0.2)
    M = np.diag(diag) + off
    return [[round(float(v), 4) for v in row] for row in M]


# ------------------------------------------------------------------ evaluation
def _content_fidelity(src: np.ndarray, out: np.ndarray) -> dict:
    """How much of the ORIGINAL is still legible after grading."""
    ls = C.luma(src).ravel()
    lo = C.luma(out).ravel()
    if ls.std() < 1e-6 or lo.std() < 1e-6:
        corr = 1.0
    else:
        corr = float(np.corrcoef(ls, lo)[0, 1])
    # gradient structure correlation — survives tone remapping, dies on smear
    def grad(x):
        gy, gx = np.gradient(C.luma(x))
        return np.concatenate([gx.ravel(), gy.ravel()])
    gs, go = grad(src), grad(out)
    gcorr = float(np.corrcoef(gs, go)[0, 1]) if gs.std() > 1e-6 else 1.0
    return {
        "luma_correlation": round(float(corr), 4),
        "structure_correlation": round(float(gcorr), 4),
    }


# perceptual scale of each statistic, so different units can be averaged
# NOTE: clip_high / clip_low are NOT style dimensions (they are binary
# near-zero clipping flags); including them with a tiny scale inflated the
# distance and made the panel look like the look "got worse". Excluded on
# purpose — they never enter style_distance.
_KEY_SCALE = {
    "L_mean": 100.0, "L_std": 100.0,
    "a_mean": 50.0, "b_mean": 50.0, "C_mean": 50.0, "C_p95": 50.0,
}


def _scale_for(k: str) -> float:
    if k in _KEY_SCALE:
        return _KEY_SCALE[k]
    if k.startswith("L_p"):
        return 100.0
    return 50.0


def style_distance(stats_a: dict, stats_b: dict):
    """Mean normalised |Δ| between two statistic dicts, expressed in percent."""
    keys = [k for k in stats_a if k in stats_b]
    if not keys:
        return None
    d = [abs(float(stats_a[k]) - float(stats_b[k])) / _scale_for(k) for k in keys]
    return round(float(np.mean(d)) * 100.0, 3)


def evaluate(apply_fn, plain_images: List[np.ndarray], ref_stats: dict,
             holdout_pairs=None) -> dict:
    """Does the look land, and does the photo survive?

    *apply_fn* is a callable rgb -> rgb (the baked LUT, or the renderer).
    Two numbers matter and they pull in opposite directions:

      style_distance      — how far the rendered photos still are from the
                            photographer's measured statistics (lower = the
                            look was reproduced). Compared against
                            `style_distance_before` (the un-graded photos as
                            they are) so the improvement is attributable.
      content_fidelity    — how much of the original photograph is still there
                            (ΔE in a sane band, structure correlation high).
    """
    rep: dict = {"style_match": {}, "content_fidelity": {}, "holdout": {}}
    if plain_images:
        rendered = [np.clip(np.asarray(apply_fn(im), np.float32), 0.0, 1.0)
                    for im in plain_images]
        r_stats = [C.stats(x) for x in rendered]
        p_stats = [C.stats(x) for x in plain_images]
        mean_r = {k: float(np.mean([s[k] for s in r_stats])) for k in r_stats[0]}
        mean_p = {k: float(np.mean([s[k] for s in p_stats])) for k in p_stats[0]}
        rep["rendered_stats"] = {k: round(v, 4) for k, v in mean_r.items()}
        rep["plain_stats"] = {k: round(v, 4) for k, v in mean_p.items()}
        rep["style_match"] = {k: round(abs(mean_r.get(k, 0.0) - v), 4)
                              for k, v in ref_stats.items()}
        rep["style_match_before"] = {k: round(abs(mean_p.get(k, 0.0) - v), 4)
                                     for k, v in ref_stats.items()}
        rep["style_distance"] = style_distance(mean_r, ref_stats)
        rep["style_distance_before"] = style_distance(mean_p, ref_stats)
        if rep["style_distance"]:
            rep["style_improvement"] = round(
                (rep["style_distance_before"] or 0.0) / max(rep["style_distance"], 1e-6), 3)

        de_mean = float(np.mean([C.delta_e76(a, b)[0] for a, b in zip(plain_images, rendered)]))
        de_p95 = float(np.mean([C.delta_e76(a, b)[1] for a, b in zip(plain_images, rendered)]))
        fid = [_content_fidelity(a, b) for a, b in zip(plain_images, rendered)]
        rep["content_fidelity"] = {
            "deltaE_mean": round(de_mean, 3),
            "deltaE_p95": round(de_p95, 3),
            "luma_correlation": round(float(np.mean([f["luma_correlation"] for f in fid])), 4),
            "structure_correlation": round(float(np.mean([f["structure_correlation"] for f in fid])), 4),
            "n_images": len(plain_images),
        }
        ok = (4.0 <= de_mean <= 25.0) and rep["content_fidelity"]["structure_correlation"] > 0.90
        rep["content_fidelity"]["verdict"] = "ok" if ok else (
            "too_strong" if de_mean > 25.0 else
            "too_weak" if de_mean < 4.0 else "structure_damaged")

    if holdout_pairs:
        rec = [C.delta_e76(apply_fn(src), dst)[0] for src, dst in holdout_pairs]
        rep["holdout"] = {"deltaE_mean": round(float(np.mean(rec)), 3),
                          "n_images": len(holdout_pairs)}
    return rep


# ------------------------------------------------------------------- cross-val
def cross_validate(graded_images: List[np.ndarray], plain_images: List[np.ndarray],
                   k: int = 4, strength: float = 0.8, lut_size: int = 33,
                   progress=None, seed: int = 20240917, cv_max_plain: int = 150) -> Optional[dict]:
    """Honest generalisation score via K-fold on the UNGRADED set.

    The delivered LUT is trained on ALL ungraded photos, so measuring it on the
    same corpus it was trained on is optimistic. This routine instead trains K
    separate LUTs, each on K-1 folds of ungraded, and measures how well each
    lands on the held-out fold. Averaging over folds removes the optimism and
    yields the real "does the look generalise to a fresh photo?" number that
    the Studio panel should display.

    Returns None if there are too few ungraded photos to fold.
    """
    def say(m):
        (progress or (lambda x: None))(m)

    graded = [np.clip(np.asarray(x, np.float32), 0.0, 1.0) for x in graded_images]
    plains = [np.clip(np.asarray(x, np.float32), 0.0, 1.0) for x in plain_images]
    if len(graded) < 3 or len(plains) < k:
        return None

    rng = np.random.default_rng(seed)
    ref_stats = {kk: float(np.mean([C.stats(x)[kk] for x in graded]))
                 for kk in C.stats(graded[0])}
    # Target distribution T is fixed across folds → sample graded pixels once.
    g_idx = np.sort(rng.choice(len(graded), min(260, len(graded)), replace=False))
    dst = _sample_from_images([graded[int(i)] for i in g_idx], FIT_PIXELS_PER_IMAGE, rng)

    order = rng.permutation(len(plains))
    folds = np.array_split(order, k)
    dists_b, dists_a, imps, de = [], [], [], []
    for fi in range(k):
        test_idx = [int(i) for i in folds[fi]]
        train_idx = [int(i) for f in range(k) if f != fi for i in folds[f]]
        if not train_idx or not test_idx:
            continue
        # cap ungraded used per fold so CV cost stays bounded on huge datasets
        if len(train_idx) > cv_max_plain:
            train_idx = [int(i) for i in np.sort(
                rng.choice(train_idx, cv_max_plain, replace=False))]
        src = _sample_from_images([plains[i] for i in train_idx], FIT_PIXELS_PER_IMAGE, rng)
        model, _ = TR.learn(src, dst, strength=strength)
        lut = TR.bake(model, lut_size)
        test = [plains[i] for i in test_idx]
        rep = evaluate(lambda im: L3.sample(lut, im), test, ref_stats)
        if rep.get("style_distance"):
            dists_a.append(rep["style_distance_before"])
            dists_b.append(rep["style_distance"])
            imps.append(rep["style_improvement"])
            de.append(rep["content_fidelity"].get("deltaE_mean", 0.0))
    if not imps:
        return None
    sd_after = float(np.mean(dists_b))
    sd_before = float(np.mean(dists_a))
    return {
        "k": k,
        "seed": seed,
        "style_distance_before": round(sd_before, 3),
        "style_distance_after": round(sd_after, 3),
        "style_improvement": round(float(np.mean(imps)), 3),
        "style_similarity": round(max(0.0, 100.0 - sd_after), 2),
        "content_deltaE_mean": round(float(np.mean(de)), 3),
    }


# ------------------------------------------------------- parametric fallback
def _tone_curve_from_model(model, points=17):
    g = np.asarray(model["L_grid"], np.float64)
    f = np.asarray(model["L_curve"], np.float64)
    xs = np.linspace(0.0, 100.0, points)
    ys = np.interp(xs, g, f) / 100.0
    return [[round(float(a / 100.0), 4), round(float(b), 4)] for a, b in zip(xs, ys)]


def _hsv_to_rgb(h, s, v):
    h = np.mod(np.asarray(h, np.float64), 360.0) / 60.0
    i = np.floor(h).astype(np.int64) % 6
    f = h - np.floor(h)
    p = v * (1.0 - s)
    q = v * (1.0 - s * f)
    t = v * (1.0 - s * (1.0 - f))
    R = np.choose(i, [v, q, p, p, t, v])
    G = np.choose(i, [t, v, v, q, p, p])
    B = np.choose(i, [p, p, t, v, v, q])
    return np.stack([R, G, B], axis=-1).astype(np.float32)


def _hsv_flat(rgb):
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0.0)
    return s.astype(np.float32), mx.astype(np.float32)


def _hue_deg_flat(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = rgb.max(axis=-1)
    mn = rgb.min(axis=-1)
    d = np.maximum(mx - mn, 1e-6)
    h = np.where(mx == r, ((g - b) / d) % 6.0,
                 np.where(mx == g, (b - r) / d + 2.0, (r - g) / d + 4.0))
    return (h * 60.0).astype(np.float32)


def estimate_hsl_from_lut(lut, per_bin=1024, seed=3):
    """Per-hue saturation / lightness / hue behaviour measured off the LUT.

    Probes the baked LUT with synthetic colours inside each hue bin, so the
    fallback parameters describe what the LUT actually does (instead of re-using
    a distribution estimate made in a different colour space).
    """
    rng = np.random.default_rng(seed)
    out = {}
    for name, lo, hi in _HUE_BINS:
        span = (hi - lo) % 360.0 or 360.0
        h = (lo + rng.random(per_bin) * span) % 360.0
        s = rng.uniform(0.12, 0.90, per_bin)
        v = rng.uniform(0.15, 0.95, per_bin)
        rgb = _hsv_to_rgb(h, s, v)
        o = L3.sample(lut, rgb)
        s2, v2 = _hsv_flat(o)
        keep = s > 0.05
        if keep.sum() < 50:
            continue
        sat = float(np.mean(s2[keep]) / max(float(np.mean(s[keep])), 1e-3))
        lig = float(np.mean(v2) / max(float(np.mean(v)), 1e-3))
        h2 = _hue_deg_flat(o)
        dh = h2 - h
        dh = (dh + 180.0) % 360.0 - 180.0
        out[name] = {
            "hue_shift": round(float(np.clip(np.mean(dh), -12.0, 12.0)), 3),
            "saturation": round(float(np.clip(sat, 0.5, 1.5)), 4),
            "lightness": round(float(np.clip(lig, 0.75, 1.25)), 4),
        }
    return out


def _shrink(im, max_side=320):
    h, w = im.shape[:2]
    m = max(h, w)
    if m <= max_side:
        return im
    s = int(np.ceil(m / max_side))
    return np.ascontiguousarray(im[::s, ::s])


def _sample_from_images(images, per_image, rng):
    parts = [_sample_pixels(x, per_image, rng) for x in images]
    return np.concatenate(parts) if parts else np.zeros((0, 3), np.float32)


# --------------------------------------------------------------- main fitting
def fit_arrays(graded_images: List[np.ndarray], plain_images: List[np.ndarray],
               name: str = "My Look", strength: float = 0.8, lut_size: int = 33,
               eval_images: Optional[List[np.ndarray]] = None,
               progress: Optional[Callable[[str], None]] = None,
               seed: int = 20240917, max_ref_images: int = 260,
               px_per_ref: int = 8000,                px_per_plain: int = 60000,
               rounds: int = 12,
               **_ignored) -> tuple:
    """Learn the look from two corpora of images (already decoded, 0..1 float).

    graded_images : the photographer's delivered photos (the STYLE target)
    plain_images  : un-graded photos that define the INPUT distribution — the
                    "before" side. Without it there is nothing to measure the
                    style against, so it is required, not optional.
    eval_images   : photos used only for validation (never for fitting).
    rounds        : iterative residual refinement — SAFETY CEILING, not a
                    fixed count. DEFAULT 12. The loop runs at most this many
                    extra rounds, but the gate is the real stop condition:
                    as soon as a round fails to beat the best honest score
                    (hold-out / K-fold CV) at every step size, the WHOLE
                    chain terminates early — 防过拟合，宁拒不冒进. So on small
                    / clean data it typically accepts far fewer than 12 and
                    stops; the ceiling only prevents runaway loops.
                    (2026-09-11 晚曾临时回退 rounds=1，后经查爆色根因是预览
                    层双叠加而非训练算法，已重新默认启用 rounds=12 动态上限；
                    见 12735dd / 2026-09-14。)
    """
    def say(msg):
        (progress or (lambda m: print(m, flush=True)))(msg)

    graded = [np.clip(np.asarray(x, np.float32), 0.0, 1.0) for x in graded_images]
    plains = [np.clip(np.asarray(x, np.float32), 0.0, 1.0) for x in (plain_images or [])]
    if len(graded) < 3:
        raise ValueError("至少需要 3 张已调色参考照片")
    if len(plains) < 3:
        raise ValueError("至少需要 3 张未调色原图：风格 = 已调色分布 ÷ 未调色分布，"
                         "缺少未调色的一侧就只能靠假设去猜，本项目禁止猜测")

    rng = np.random.default_rng(seed)
    if len(graded) > max_ref_images:
        idx = np.sort(rng.choice(len(graded), max_ref_images, replace=False))
        graded_fit = [graded[int(i)] for i in idx]
    else:
        graded_fit = graded

    say(f"  采样像素：已调色 {len(graded_fit)}/{len(graded)} 张 × {px_per_ref} | "
        f"未调色 {len(plains)} 张 × {px_per_plain}")
    dst_px = _sample_from_images(graded_fit, px_per_ref, rng)
    src_px = _sample_from_images(plains, px_per_plain, rng)

    say("  学习分布迁移（Lab：亮度分位 + 色度最优传输 + 分色相/分亮度残差）…")
    model, desc = TR.learn(src_px, dst_px, strength=strength)
    lut = TR.bake(model, lut_size)
    say(f"    LUT {lut_size}³ 覆盖率 100%（解析式烘焙，无空洞、无插值猜测）")

    # ------------------------- v3.1 iterative residual refinement ----------------
    # Held-out evaluation inputs are needed both by the round gate and by the
    # final report, so compute them once here (moved up from the eval section).
    ref_stats = {k: float(np.mean([C.stats(x)[k] for x in graded]))
                 for k in C.stats(graded[0])}
    evals = [np.clip(np.asarray(x, np.float32), 0.0, 1.0)
             for x in ((eval_images if eval_images is not None else plains)[:40])]

    curve_model = model
    itrace = None
    rounds = int(rounds)
    if rounds > 1:
        from . import iterative as IT
        say(f"  迭代残差训练（安全上限 {rounds} 个额外轮次，"
            "门控未通过即提前终止；实际接受轮数由门控决定）…")
        chain, lut, itrace = IT.refine(
            model, src_px, dst_px,
            eval_images=(eval_images if eval_images is not None and
                         len(eval_images) >= 3 else None),
            plains=plains, ref_stats=ref_stats,
            strength=strength, rounds=rounds, lut_size=lut_size,
            progress=say, seed=seed)
        if len(chain) > 1:
            # the composed monotone curve replaces the single-pass curve in
            # the parametric fallback so both descriptions agree
            curve_model = chain.as_curve_model()
        say(f"    最终 LUT 已更新（复合层数 {len(chain)}，"
            f"组合色度增益 {chain.total_gain():.2f}）")

    # ---------------------------------------------------------- spatial layers
    say("  估计空间层（暗角 / 颗粒 / 光晕）…")
    small = [_shrink(x, 320) for x in graded[:150]]
    vig = estimate_vignette(small)
    grain = estimate_grain(small)
    glow = estimate_glow(small)

    # --------------------------------------- inverse-solved diagnostic params
    # The 3D LUT is the ONLY colour stage of the profile. We still solve the
    # classic parametric approximation below, but purely as a diagnostic
    # written to the report — writing it into the profile used to invite
    # engines that ignore the bypass rule into double-grading (the 2026-09-11
    # web blowout). Identity params make every engine agree by construction.
    say("  反解诊断参数（仅写入报告，不进 profile）…")
    curve = _tone_curve_from_model(curve_model)
    wb_t, wb_i = estimate_white_balance(lut)
    bias = estimate_exposure(lut)
    M = estimate_color_matrix(lut)
    hsl = estimate_hsl_from_lut(lut)

    # profile keeps the schema-neutral (identity) parametric defaults from
    # default_profile(); only spatial layers are estimated below.
    profile = default_profile(name)
    profile["vignette"]["amount"] = round(float(vig), 4)
    profile["grain"]["amount"] = round(float(grain), 4)
    profile["grain"]["size"] = 1.0
    profile["grain"]["density"] = 1.0
    profile["halation"]["amount"] = round(float(glow), 4)
    profile["bloom"]["amount"] = round(float(glow * 0.8), 4)
    profile["sharpen"]["amount"] = 0.0
    profile["noise"]["luma"] = 0.0
    profile["noise"]["chroma"] = 0.0
    profile["highlight_rolloff"]["strength"] = 0.0
    profile["shadow"]["black_point"] = round(float(np.clip(
        max(0.0, (np.median([C.stats(x)["L_p1"] for x in graded]) - 2.0) / 100.0), 0.0, 0.1)), 4)
    profile["film_curve"]["shadow_floor"] = 8
    profile["film_curve"]["highlight_ceiling"] = 248

    profile["color_layer"] = "lut"
    profile["color_adaptive"] = False
    payload = L3.to_payload(lut)
    _acc = (int(sum(1 for t in (itrace or [])
                    if t.get("round", 0) > 0 and t.get("accepted")))
            if itrace is not None else 0)
    payload["source"] = ("stylefit/v3.1-iterative-distribution-transfer" if _acc > 0
                         else "stylefit/v3-distribution-transfer")
    payload["strength"] = float(strength)
    profile["color_lut"] = payload
    profile = safe_clamp(profile)

    # ------------------------------------------------------------ evaluation
    say("  评估 …")

    def _render(im):
        return L3.sample(lut, im)

    report = evaluate(_render, evals, ref_stats)
    report["version"] = 3
    report["method"] = ("unpaired CIELAB distribution transfer: luminance "
                        "quantile map (linear + bounded shape), optimal linear "
                        "transport of the (a,b) covariance, per-hue and "
                        "per-luma residual corrections; baked into one 3D LUT")
    report["model"] = desc
    if itrace is not None:
        report["iterative"] = {
            "rounds_requested": rounds,
            "accepted_rounds": int(sum(1 for t in itrace
                                       if t.get("round", 0) > 0 and t.get("accepted"))),
            "gate": ("held-out" if eval_images is not None and len(eval_images) >= 3
                     else "kfold"),
            "trace": itrace,
        }
    report["lut"] = {"size": lut_size, "cells": int(lut_size ** 3),
                     "coverage": 1.0, "adaptive": False,
                     "strength": float(strength)}
    report["reference_stats"] = {k: round(v, 4) for k, v in ref_stats.items()}
    report["dataset"] = {
        "graded_total": len(graded),
        "graded_sampled": len(graded_fit),
        "plain_total": len(plains),
        "plain_eval": len(evals),
        "eval_held_out": eval_images is not None,
        "pixels_source": int(src_px.shape[0]),
        "pixels_target": int(dst_px.shape[0]),
    }
    report["estimated"] = {
        "vignette": round(float(vig), 4),
        "grain": round(float(grain), 4),
        "glow": round(float(glow), 4),
        "exposure_bias": bias,
        "white_balance": {"temperature_bias": wb_t, "tint_bias": wb_i},
        "color_matrix": M,
        "tone_curve": curve,
        "hsl": hsl,
    }
    return profile, report


fit_from_arrays = fit_arrays


def fit(reference_paths: List[str], name: str = "My Look",
        plain_paths: Optional[List[str]] = None, max_side: int = 320,
        plain_fit_fraction: float = 1.0, **kw):
    """Learn a profile from photo paths.

    reference_paths : the photographer's graded photos (style target)
    plain_paths     : un-graded photos. They define the INPUT distribution S;
                      the profile is trained on ALL of them (they are scarce,
                      so we do not waste half as a hold-out) and the reported
                      style_distance is measured on the same corpus. For an
                      honest generalisation number, run a K-fold CV
                      (see _diag/stylefit_curve.py) instead.
    """
    refs = list(reference_paths)
    plains = list(plain_paths or [])
    if len(refs) < 3:
        raise ValueError(f"已调色参考照片不足 3 张（只找到 {len(refs)} 张）")
    if len(plains) < 4:
        raise ValueError(
            f"未调色原图不足 4 张（只找到 {len(plains)} 张）。\n"
            "风格 = 已调色分布 ÷ 未调色分布：未调色这一侧是测量的基准，不是可选项。\n"
            "请提供至少 4 张、建议 15 张以上「同一相机直出 / 未调色」的普通照片；"
            "其中一半用于测量输入分布，另一半留作验证。")

    seed = int(kw.get("seed", 20240917))
    rng = np.random.default_rng(seed)
    order = rng.permutation(len(plains))
    n_fit = int(round(len(plains) * plain_fit_fraction))
    n_fit = max(3, min(n_fit, len(plains) - 1)) if len(plains) > 4 else max(3, len(plains) - 1)
    fit_idx = sorted(order[:n_fit].tolist())
    eval_idx = sorted(order[n_fit:].tolist()) or fit_idx

    graded_all, skipped = [], []
    for p in refs:
        try:
            graded_all.append(np.clip(_load(p, max_side), 0.0, 1.0))
        except Exception as e:
            skipped.append((os.path.basename(p), str(e)))
    if len(graded_all) < 3:
        raise ValueError(f"可解码的已调色照片不足 3 张：共 {len(refs)} 张，"
                         f"仅 {len(graded_all)} 张能打开"
                         + (f"。无法解码示例：{skipped[0][0]} ({skipped[0][1]})" if skipped else ""))

    # Load every ungraded photo once (at fit resolution) so both the delivered
    # fit AND the honest K-fold cross-validation receive real numpy arrays, not
    # paths. plain_fit / plain_eval are disjoint index-based subsets of this list.
    plain_imgs = []
    for p in plains:
        try:
            plain_imgs.append(np.clip(_load(p, max_side), 0.0, 1.0))
        except Exception:
            plain_imgs.append(None)
    plain_fit = [plain_imgs[i] for i in fit_idx if plain_imgs[i] is not None]
    plain_eval = [plain_imgs[i] for i in eval_idx if plain_imgs[i] is not None
                  and i not in set(fit_idx)]
    if len(plain_fit) < 3:
        raise ValueError("未调色原图中可解码的不足 3 张，无法测量输入分布")

    prof, rep = fit_arrays(graded_all, plain_fit, name=name,
                           eval_images=plain_eval or None, **kw)
    # Honest generalisation score: K-fold on the ungraded set (never measures
    # the fit on the corpus it was trained on). Kept separate from the
    # (optimistic) in-report style_distance that reuses the same corpus.
    cv_strength = float(kw.get("strength", 0.8))
    try:
        cv = cross_validate(graded_all, plain_imgs, k=4, strength=cv_strength)
        if cv:
            rep["cv"] = cv
    except Exception as e:  # CV is a reporting aid only — never block delivery
        rep["cv_error"] = str(e)
    rep["dataset"]["plain_fit_used"] = len(plain_fit)
    rep["dataset"]["plain_eval_used"] = len(plain_eval)
    rep["dataset"]["graded_skipped"] = len(skipped)
    if skipped:
        rep["skipped_files"] = [{"name": a, "error": b} for a, b in skipped[:20]]
    return prof, rep


def fit_directory(ref_dir: str, plain_dir: str, name="My Look", **kw):
    return fit(discover_images(ref_dir), name=name,
               plain_paths=discover_images(plain_dir), **kw)
