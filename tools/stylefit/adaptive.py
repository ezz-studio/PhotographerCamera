#!/usr/bin/env python3
"""Image-adaptive LUT routing — the "dynamic" part of a profile.

Design goal (from HuiZeng et al., *Learning Image-adaptive 3D Lookup Tables*,
TPAMI 2020): instead of one fixed LUT, learn a small BANK of basis LUTs plus a
tiny router that predicts the mixing weights from a low-dimensional summary of
the input image. We deliberately do NOT ship a CNN:

  * the router input is a 7-D vector of global colour statistics,
  * the router is an RBF kernel — a few dozen multiply-adds,
  * it is deterministic, auditable, and trivially portable to Kotlin/GLSL.

Feature vector (scale & resolution independent):
    f = [ L_mean, L_std, L_p10, L_p90, a_mean, b_mean, C_mean ]  (normalised)

Weights:
    w_k ∝ exp( -||f - c_k||^2 / (2 sigma^2) )
    w   = (1 - prior) * softmax-ish normalise + prior * (1/K)

The uniform prior keeps the blend near the AVERAGE look when an input lands far
outside anything seen in training — the failure mode is "slightly generic",
never "wildly wrong".

Portability note: everything below is plain arithmetic on a length-7 vector.
`route()` is the exact function to re-implement on Android.
"""
from __future__ import annotations

import numpy as np

FEATURE_NAMES = ["L_mean", "L_std", "L_p10", "L_p90", "a_mean", "b_mean", "C_mean"]
_FEAT_SCALE = np.array([100.0, 100.0, 100.0, 100.0, 50.0, 50.0, 50.0], np.float32)

DEFAULT_PRIOR = 0.15


def image_features(rgb: np.ndarray) -> np.ndarray:
    """7-D normalised global statistic of an RGB image (float 0..1)."""
    from . import color as C

    rgb = np.asarray(rgb, dtype=np.float32)
    lab = C.rgb_to_lab(rgb).reshape(-1, 3)
    L, a, b = lab[:, 0], lab[:, 1], lab[:, 2]
    ch = np.sqrt(a * a + b * b)
    f = np.array([
        float(L.mean()), float(L.std()),
        float(np.percentile(L, 10)), float(np.percentile(L, 90)),
        float(a.mean()), float(b.mean()), float(ch.mean()),
    ], np.float32)
    return (f / _FEAT_SCALE).astype(np.float32)


def route(features: np.ndarray, centers, sigma: float, prior: float = DEFAULT_PRIOR) -> np.ndarray:
    """Mixing weights for one feature vector. Pure arithmetic — port as-is."""
    f = np.asarray(features, dtype=np.float32).ravel()
    C = np.asarray(centers, dtype=np.float32)
    if C.ndim == 1:
        C = C.reshape(1, -1)
    K = C.shape[0]
    d2 = np.sum((C - f[None, :]) ** 2, axis=1)
    w = np.exp(-d2 / max(2.0 * float(sigma) ** 2, 1e-9)).astype(np.float32)
    s = float(w.sum())
    if s <= 1e-9:                       # numerically dead: fall back to uniform
        w = np.ones(K, np.float32)
        s = float(K)
    w = w / s
    w = (1.0 - float(prior)) * w + float(prior) / K
    return (w / float(w.sum())).astype(np.float32)


def blend_luts(luts, weights) -> np.ndarray:
    """Weighted sum of basis LUTs -> a single LUT (all same shape)."""
    luts = np.asarray(luts, dtype=np.float32)
    w = np.asarray(weights, dtype=np.float32).ravel()
    out = np.zeros(luts.shape[1:], np.float32)
    for wi, l in zip(w, luts):
        out += wi * l
    return np.clip(out, 0.0, 1.0).astype(np.float32)


def resolve_lut(rgb: np.ndarray, bank, centers, sigma: float,
                prior: float = DEFAULT_PRIOR, average_lut=None):
    """Pick/build the LUT for this image. Returns (lut, weights)."""
    if not bank:
        return (average_lut, np.zeros(0, np.float32))
    bank_arr = np.asarray(bank, dtype=np.float32)
    if bank_arr.shape[0] == 1:
        return (bank_arr[0], np.ones(1, np.float32))
    f = image_features(rgb)
    w = route(f, centers, sigma, prior)
    return blend_luts(bank_arr, w), w


# ------------------------------------------------------------------ clustering
def kmeans(X: np.ndarray, k: int, iters: int = 40, seed: int = 7):
    """Minimal deterministic k-means (no sklearn dependency)."""
    X = np.asarray(X, dtype=np.float32)
    n = X.shape[0]
    k = int(max(1, min(k, n)))
    rng = np.random.default_rng(seed)
    # k-means++ style seeding, deterministic
    centers = [X[rng.integers(0, n)]]
    for _ in range(k - 1):
        d = np.min(((X[:, None, :] - np.array(centers)[None, :, :]) ** 2).sum(-1), axis=1)
        probs = d / max(d.sum(), 1e-9)
        centers.append(X[int(np.searchsorted(np.cumsum(probs), rng.random())) % n])
    C = np.array(centers, np.float32)
    labels = np.zeros(n, np.int32)
    for _ in range(iters):
        d = ((X[:, None, :] - C[None, :, :]) ** 2).sum(-1)
        new = d.argmin(axis=1).astype(np.int32)
        if (new == labels).all():
            break
        labels = new
        for j in range(k):
            m = labels == j
            if m.any():
                C[j] = X[m].mean(axis=0)
    # drop empty clusters
    keep = [j for j in range(k) if (labels == j).sum() > 0]
    return C[keep], labels, keep


def suggested_k(n_images: int, k_max: int = 4) -> int:
    """Basis count: 1 LUT for tiny sets, up to k_max for large ones.

    Every basis needs enough photos behind it or the LUT becomes a portrait of
    one frame rather than of the style.
    """
    if n_images < 8:
        return 1
    return int(max(1, min(k_max, n_images // 8)))


def sigma_from_centers(centers) -> float:
    """RBF width from the spread of the cluster centres.

    Using the mean pairwise distance keeps the router responsive without
    collapsing to a hard nearest-neighbour assignment.
    """
    C = np.asarray(centers, dtype=np.float32)
    if C.shape[0] < 2:
        return 0.35
    d = np.sqrt(((C[:, None, :] - C[None, :, :]) ** 2).sum(-1))
    iu = np.triu_indices(C.shape[0], 1)
    m = float(d[iu].mean())
    return float(np.clip(m * 0.9, 0.08, 1.0))
