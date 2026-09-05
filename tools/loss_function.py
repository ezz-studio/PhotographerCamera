#!/usr/bin/env python3
"""Phase 17 — Loss Function (data-ready).

Quantifies the difference between a photographer's original image features and the
features of the simulated (profile-rendered) image. Weighted sum per the project spec:

  Loss = w1*ColorError + w2*ToneError + w3*HistogramError
       + w4*HSLColorError + w5*HighlightError + w6*ShadowError + w7*TextureError

All components are normalized to comparable scales so weights are meaningful.
"""
from __future__ import annotations

import numpy as np


def _hsv_sat(rgb):
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    sat = mx - mn
    return np.divide(sat, mx, out=np.zeros_like(sat), where=mx > 0)


def extract_features(rgb: np.ndarray) -> dict:
    rgb = np.asarray(rgb, dtype=np.float32)
    ch = [rgb[..., i] for i in range(3)]
    lum = rgb.mean(axis=2)
    sat = _hsv_sat(rgb)
    flat = rgb.reshape(-1, 3)
    corr = np.corrcoef(flat, rowvar=False)
    hist, _ = np.histogram(lum, bins=32, range=(0, 1))
    hist = hist / max(1, hist.sum())
    # high-frequency (texture) proxy: laplacian std
    gray = (lum * 255).astype(np.float32)
    lap = gray[1:-1, 1:-1] - 0.25 * (gray[2:, 1:-1] + gray[:-2, 1:-1] + gray[1:-1, 2:] + gray[1:-1, :-2])
    return {
        "mean_r": float(ch[0].mean()), "mean_g": float(ch[1].mean()), "mean_b": float(ch[2].mean()),
        "lum_mean": float(lum.mean()), "lum_p5": float(np.percentile(lum, 5)),
        "lum_p50": float(np.percentile(lum, 50)), "lum_p95": float(np.percentile(lum, 95)),
        "sat_mean": float(sat.mean()), "clip_high": float(np.mean(np.any(rgb > 0.99, axis=2))),
        "corr_rg": float(corr[0, 1]), "corr_rb": float(corr[0, 2]), "corr_gb": float(corr[1, 2]),
        "hist": hist,
        "texture": float(np.std(lap)),
    }


def feature_vector(f: dict) -> np.ndarray:
    return np.array([
        f["mean_r"], f["mean_g"], f["mean_b"], f["lum_mean"], f["lum_p5"],
        f["lum_p50"], f["lum_p95"], f["sat_mean"], f["clip_high"],
        f["corr_rg"], f["corr_rb"], f["corr_gb"],
    ], dtype=np.float32)


_DEFAULT_WEIGHTS = {
    "color": 1.0, "tone": 1.0, "histogram": 0.5, "hsl": 0.5,
    "highlight": 1.0, "shadow": 1.0, "texture": 0.3,
}


def loss(target: dict, sim: dict, weights: dict | None = None) -> tuple:
    w = weights or _DEFAULT_WEIGHTS
    color_err = (abs(target["mean_r"] - sim["mean_r"]) +
                 abs(target["mean_g"] - sim["mean_g"]) +
                 abs(target["mean_b"] - sim["mean_b"]) +
                 abs(target["corr_rg"] - sim["corr_rg"]) * 0.5 +
                 abs(target["corr_rb"] - sim["corr_rb"]) * 0.5) / 4.0
    tone_err = (abs(target["lum_p50"] - sim["lum_p50"]) +
                abs(target["lum_p5"] - sim["lum_p5"]) +
                abs(target["lum_p95"] - sim["lum_p95"])) / 3.0
    hist_err = float(np.mean(np.abs(target["hist"] - sim["hist"])))
    hsl_err = abs(target["sat_mean"] - sim["sat_mean"])
    high_err = abs(target["clip_high"] - sim["clip_high"])
    shadow_err = abs(target["lum_p5"] - sim["lum_p5"])
    # Bounded symmetric relative error: |t-s| / (|t|+|s|+eps) in [0,1].
    # Avoids the divide-by-tiny blow-up when the original image is smooth (texture ~ 0).
    tex_err = abs(target["texture"] - sim["texture"]) / (abs(target["texture"]) + abs(sim["texture"]) + 1e-3)
    total = (w["color"] * color_err + w["tone"] * tone_err + w["histogram"] * hist_err +
             w["hsl"] * hsl_err + w["highlight"] * high_err + w["shadow"] * shadow_err +
             w["texture"] * tex_err)
    return total, {
        "color": color_err, "tone": tone_err, "histogram": hist_err, "hsl": hsl_err,
        "highlight": high_err, "shadow": shadow_err, "texture": tex_err, "total": total,
    }
