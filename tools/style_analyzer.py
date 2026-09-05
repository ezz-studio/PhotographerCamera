#!/usr/bin/env python3
"""Phase 3-13 — Style Feature Engine (data-ready).

Aggregates statistics and *initial* models from a set of photographer final images.
Produces one JSON per Phase deliverable under --out:

  rgb_statistics.json, luminance_statistics.json, color_statistics.json,
  tone_statistics.json, tone_curve_initial.json, exposure_statistics.json,
  photographer_color_model.json, color_matrix.json, hsl_mapping.json,
  highlight_rolloff.json, shadow_model.json, lens_model.json, texture_model.json

NOTE: values marked *_initial / heuristic are first estimates derived from data.
They are intentionally conservative (neutral where uncertain) and are meant to be
refined by the Profile Optimizer (Phase 16-19), NOT trusted as final style.
This is consistent with the rule: do not force unstable features into the Profile.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Dict, List

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from dataset_loader import discover_images  # noqa: E402

HUE_RANGES = {
    "red": (345, 360),
    "orange": (15, 45),
    "yellow": (45, 70),
    "green": (70, 160),
    "cyan": (160, 200),
    "blue": (200, 260),
    "purple": (260, 345),
}
STAT_SIZE = 256  # downscale longest side for fast, stable statistics


def _load(path: str) -> np.ndarray:
    img = Image.open(path)
    if img.mode != "RGB":
        img = img.convert("RGB")
    arr = np.asarray(img, dtype=np.float32) / 255.0
    h, w = arr.shape[:2]
    if max(h, w) > STAT_SIZE:
        scale = STAT_SIZE / max(h, w)
        nh, nw = max(1, int(h * scale)), max(1, int(w * scale))
        arr = np.asarray(Image.fromarray((arr * 255).astype("uint8")).resize((nw, nh), Image.Resampling.LANCZOS), dtype=np.float32) / 255.0
    return arr


def _rgb_to_hsv(rgb: np.ndarray):
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    v = mx
    s = np.where(mx > 0, (mx - mn) / mx, 0.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        diff = mx - mn
        rc = (mx - rgb[..., 0]) / np.where(diff == 0, 1, diff)
        gc = (mx - rgb[..., 1]) / np.where(diff == 0, 1, diff)
        bc = (mx - rgb[..., 2]) / np.where(diff == 0, 1, diff)
        h = np.where(mx == rgb[..., 0], bc - gc,
                     np.where(mx == rgb[..., 1], 2 + rc - bc, 4 + gc - rc))
        h = (h / 6.0) % 1.0
    return h, s, v


def _rgb_to_lab(rgb: np.ndarray) -> np.ndarray:
    """Pure-numpy sRGB -> CIE Lab (D65), matching skimage.color.rgb2lab conventions.

    Avoids the heavy scikit-image dependency for the data-ready analysis pipeline.
    Input: float array in [0,1] with an arbitrary leading shape; output has the same
    leading shape with a trailing 3 (L, a, b).
    """
    arr = np.clip(rgb, 0.0, 1.0)
    # sRGB gamma decode -> linear RGB
    lin = np.where(arr <= 0.04045, arr / 12.92, ((arr + 0.055) / 1.055) ** 2.4)
    # linear RGB -> CIE XYZ (D65)
    X = lin[..., 0] * 0.4124564 + lin[..., 1] * 0.3575761 + lin[..., 2] * 0.1804375
    Y = lin[..., 0] * 0.2126729 + lin[..., 1] * 0.7151522 + lin[..., 2] * 0.0721750
    Z = lin[..., 0] * 0.0193339 + lin[..., 1] * 0.1191920 + lin[..., 2] * 0.9503041
    Xn, Yn, Zn = 0.95047, 1.0, 1.08883
    Xr, Yr, Zr = X / Xn, Y / Yn, Z / Zn
    d = 6.0 / 29.0
    f = lambda t: np.where(t > d ** 3, np.cbrt(t), (t / (3 * d * d)) + (4.0 / 29.0))
    fx, fy, fz = f(Xr), f(Yr), f(Zr)
    L = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return np.stack([L, a, b], axis=-1)


def _hue_region(hue_deg: float) -> str:
    for name, (a, b) in HUE_RANGES.items():
        if a <= hue_deg < b or (name == "red" and (hue_deg >= 345 or hue_deg < 15)):
            return name
    return "red"


def _hist(values: np.ndarray, bins=64, lo=0.0, hi=1.0) -> List[float]:
    counts, _ = np.histogram(values, bins=bins, range=(lo, hi))
    total = counts.sum()
    return (counts / total).round(5).tolist() if total > 0 else [0.0] * bins


def _percentiles(arr: np.ndarray, qs=(10, 25, 50, 75, 90, 95)) -> Dict:
    return {f"p{q}": round(float(np.percentile(arr, q)), 4) for q in qs}


# ---------------------------------------------------------------------------
# Phase 3 — RGB statistics
# ---------------------------------------------------------------------------
def rgb_statistics(imgs: List[np.ndarray]) -> Dict:
    means = []
    perc = {c: [] for c in "rgb"}
    corr_acc = np.zeros((3, 3))
    for rgb in imgs:
        ch = [rgb[..., i].flatten() for i in range(3)]
        means.append([float(c.mean()) for c in ch])
        for i, c in enumerate("rgb"):
            perc[c].append(_percentiles(ch[i]))
        c = np.stack(ch, axis=1)
        corr_acc += np.corrcoef(c, rowvar=False)
    n = len(imgs)
    out = {"schema": "rgb_statistics/v1", "n_images": n, "channels": {}}
    for i, c in enumerate("rgb"):
        out["channels"][c] = {
            "mean": round(float(np.mean([m[i] for m in means])), 4),
            "p10": round(float(np.mean([p["p10"] for p in perc[c]])), 4),
            "p25": round(float(np.mean([p["p25"] for p in perc[c]])), 4),
            "p50": round(float(np.mean([p["p50"] for p in perc[c]])), 4),
            "p75": round(float(np.mean([p["p75"] for p in perc[c]])), 4),
            "p90": round(float(np.mean([p["p90"] for p in perc[c]])), 4),
            "p95": round(float(np.mean([p["p95"] for p in perc[c]])), 4),
            "histogram": _hist(np.concatenate([rgb[..., i].flatten() for rgb in imgs]), bins=64),
        }
    out["channel_correlation"] = np.round(corr_acc / n, 4).tolist()
    return out


def luminance_statistics(imgs: List[np.ndarray]) -> Dict:
    lums = [rgb.mean(axis=2).flatten() for rgb in imgs]
    all_l = np.concatenate(lums)
    clip = float(np.mean([np.mean(np.any(rgb > 0.99, axis=2)) for rgb in imgs]))
    return {
        "schema": "luminance_statistics/v1",
        "n_images": len(imgs),
        "mean": round(float(all_l.mean()), 4),
        "median": round(float(np.median(all_l)), 4),
        **_percentiles(all_l),
        "clipping_ratio": round(clip, 4),
        "histogram": _hist(all_l, bins=64),
    }


# ---------------------------------------------------------------------------
# Phase 4 — HSV / HSL / Lab
# ---------------------------------------------------------------------------
def color_statistics(imgs: List[np.ndarray]) -> Dict:
    hue_all, sat_all, light_all = [], [], []
    lab_all = []
    region_stats = {name: {"count": 0, "sat_sum": 0.0, "a_sum": 0.0, "b_sum": 0.0} for name in HUE_RANGES}
    for rgb in imgs:
        h, s, v = _rgb_to_hsv(rgb)
        hue_all.append(h.flatten())
        sat_all.append(s.flatten())
        light_all.append(v.flatten())
        lab = _rgb_to_lab(rgb)
        lab_all.append(lab.reshape(-1, 3))
        # per-hue region aggregation (use hue in degrees)
        hd = (h * 360.0).flatten()
        a = lab[..., 1].flatten()
        b = lab[..., 2].flatten()
        ss = s.flatten()
        for pix_h, pix_s, pix_a, pix_b in zip(hd, ss, a, b):
            name = _hue_region(pix_h)
            rs = region_stats[name]
            rs["count"] += 1
            rs["sat_sum"] += pix_s
            rs["a_sum"] += pix_a
            rs["b_sum"] += pix_b
    hue = np.concatenate(hue_all)
    sat = np.concatenate(sat_all)
    light = np.concatenate(light_all)
    lab = np.concatenate(lab_all)
    regions = {}
    for name, rs in region_stats.items():
        cnt = max(1, rs["count"])
        regions[name] = {
            "pixel_fraction": round(rs["count"] / max(1, sum(r["count"] for r in region_stats.values())), 4),
            "mean_saturation": round(rs["sat_sum"] / cnt, 4),
            "mean_a": round(rs["a_sum"] / cnt, 4),
            "mean_b": round(rs["b_sum"] / cnt, 4),
        }
    return {
        "schema": "color_statistics/v1",
        "n_images": len(imgs),
        "hue_distribution": _hist(hue, bins=64, lo=0, hi=1),
        "saturation_distribution": _hist(sat, bins=64),
        "lightness_distribution": _hist(light, bins=64),
        "lab": {
            "L_mean": round(float(lab[:, 0].mean()), 4),
            "a_mean": round(float(lab[:, 1].mean()), 4),
            "b_mean": round(float(lab[:, 2].mean()), 4),
            "a_std": round(float(lab[:, 1].std()), 4),
            "b_std": round(float(lab[:, 2].std()), 4),
        },
        "hue_regions": regions,
    }


# ---------------------------------------------------------------------------
# Phase 5 — Tone curve
# ---------------------------------------------------------------------------
def _tone_curve_from_luminance(lum: np.ndarray, n=17) -> List[List[float]]:
    xs = np.linspace(0, 1, n)
    # mapping input luminance percentile -> output rank (empirical CDF)
    ys = np.array([np.mean(lum <= x) for x in xs])
    return [[round(float(x), 4), round(float(y), 4)] for x, y in zip(xs, ys)]


def tone_statistics(imgs: List[np.ndarray]):
    lum_list = [rgb.mean(axis=2).flatten() for rgb in imgs]
    all_l = np.concatenate(lum_list)
    from scipy.ndimage import laplace  # local contrast proxy

    local = []
    for rgb in imgs:
        gray = (rgb.mean(axis=2) * 255).astype(np.float32)
        lap = laplace(gray)
        local.append(float(np.std(lap)))
    tone = {
        "schema": "tone_statistics/v1",
        "n_images": len(imgs),
        "black_point": round(float(np.percentile(all_l, 2)), 4),
        "shadow": round(float(np.percentile(all_l, 25)), 4),
        "midtone": round(float(np.percentile(all_l, 50)), 4),
        "highlight": round(float(np.percentile(all_l, 75)), 4),
        "white_point": round(float(np.percentile(all_l, 98)), 4),
        "global_contrast": round(float(np.percentile(all_l, 95) - np.percentile(all_l, 5)), 4),
        "local_contrast": round(float(np.mean(local)), 4),
        "highlight_compression": round(float(np.mean([np.mean(rgb.mean(axis=2) > 0.9) for rgb in imgs])), 4),
        "shadow_compression": round(float(np.mean([np.mean(rgb.mean(axis=2) < 0.1) for rgb in imgs])), 4),
    }
    curve = _tone_curve_from_luminance(all_l)
    return tone, {"schema": "tone_curve_initial/v1", "n_images": len(imgs), "points": curve,
                  "note": "empirical CDF of luminance; initial estimate, refine in optimizer"}


# ---------------------------------------------------------------------------
# Phase 6 — Exposure
# ---------------------------------------------------------------------------
def exposure_statistics(imgs: List[np.ndarray], manifest_exif: List[dict] | None = None) -> Dict:
    lums = [rgb.mean(axis=2).flatten() for rgb in imgs]
    all_l = np.concatenate(lums)
    clip = float(np.mean([np.mean(np.any(rgb > 0.99, axis=2)) for rgb in imgs]))
    shadow = float(np.mean([np.mean(rgb.mean(axis=2) < 0.1) for rgb in imgs]))
    out = {
        "schema": "exposure_statistics/v1",
        "n_images": len(imgs),
        "mean_brightness": round(float(all_l.mean()), 4),
        "median_brightness": round(float(np.median(all_l)), 4),
        "highlight_ratio": round(clip, 4),
        "shadow_ratio": round(shadow, 4),
        "clipping_ratio": round(clip, 4),
        "estimated_black_level": round(float(np.percentile(all_l, 1)), 4),
        "estimated_white_level": round(float(np.percentile(all_l, 99)), 4),
        "exposure_distribution": _hist(all_l, bins=32),
        "exif_distribution": None,
    }
    # EXIF-based distributions if provided (Phase 1 manifest)
    if manifest_exif:
        dist = {}
        for key in ("iso", "aperture", "shutter", "focal_length"):
            vals = [e.get(key) for e in manifest_exif if isinstance(e.get(key), (int, float))]
            if vals:
                dist[key] = {"min": min(vals), "max": max(vals),
                             "mean": round(float(np.mean(vals)), 4), "count": len(vals)}
        out["exif_distribution"] = dist
    return out


# ---------------------------------------------------------------------------
# Phase 7 — Photographer color model
# ---------------------------------------------------------------------------
def photographer_color_model(imgs: List[np.ndarray]) -> Dict:
    per_region = {name: {"r": [], "g": [], "b": [], "a": [], "b_lab": []} for name in HUE_RANGES}
    # neutral / skin / sky / plant / highlight / shadow aggregates
    cats = {"neutral": [], "skin": [], "sky": [], "plant": [], "highlight": [], "shadow": []}
    for rgb in imgs:
        h, s, v = _rgb_to_hsv(rgb)
        lab = _rgb_to_lab(rgb)
        hd = (h * 360.0).flatten()
        hh = h.flatten()
        for pix_h, pix_s, pix_v, pix_a, pix_b in zip(hd, s.flatten(), v.flatten(), lab[..., 1].flatten(), lab[..., 2].flatten()):
            name = _hue_region(pix_h)
            per_region[name]["a"].append(pix_a)
            per_region[name]["b_lab"].append(pix_b)
        # category level (use masks)
        srgb = rgb.reshape(-1, 3)
        vflat = v.flatten()
        sflat = s.flatten()
        # skin: orange-ish, mid sat, mid-light
        skin = ((hd >= 15) & (hd < 45) & (sflat > 0.15) & (sflat < 0.6) & (vflat > 0.3) & (vflat < 0.85))
        # sky: blue/cyan, low-mid sat, high light
        sky = (((hd >= 160) & (hd < 260)) & (sflat > 0.05) & (vflat > 0.4))
        # plant: green, mid-high sat
        plant = ((hd >= 70) & (hd < 160) & (sflat > 0.1))
        neutral = (sflat < 0.05)
        hl = (vflat > 0.85)
        sh = (vflat < 0.12)
        for key, mask in (("skin", skin), ("sky", sky), ("plant", plant), ("neutral", neutral), ("highlight", hl), ("shadow", sh)):
            if mask.any():
                cats[key].append(srgb[mask].mean(axis=0))
    region_out = {}
    for name, d in per_region.items():
        if d["a"]:
            region_out[name] = {
                "mean_a": round(float(np.mean(d["a"])), 4),
                "mean_b": round(float(np.mean(d["b_lab"])), 4),
                "n_samples": len(d["a"]),
            }
    cat_out = {}
    for key, lst in cats.items():
        if lst:
            arr = np.array(lst)
            cat_out[key] = {"mean_rgb": np.round(arr.mean(axis=0), 4).tolist()}
    return {"schema": "photographer_color_model/v1", "n_images": len(imgs),
            "hue_regions": region_out, "semantic_regions": cat_out,
            "note": "stable features only; variance across images not used to force params"}


# ---------------------------------------------------------------------------
# Phase 8 — Color matrix (initial / heuristic)
# ---------------------------------------------------------------------------
def color_matrix_estimate(imgs: List[np.ndarray]) -> Dict:
    # Gray-world style initial diagonal: pull channel means toward equalization,
    # with tiny off-diagonal from channel correlation. Conservative & in-range.
    ch_means = np.zeros(3)
    n = 0
    for rgb in imgs:
        ch_means += rgb.reshape(-1, 3).mean(axis=0)
        n += 1
    ch_means /= max(1, n)
    ref = ch_means.mean()
    ref = ref if ref > 0 else 1.0
    diag = np.clip(ref / (ch_means + 1e-6), 0.5, 1.5)  # gentle scaling
    M = np.diag(diag).astype(float)
    # keep within [-2,2]; it's a diagonal -> safe
    return {
        "schema": "color_matrix/v1",
        "n_images": len(imgs),
        "matrix_3x3": np.round(M, 4).tolist(),
        "input_gamut": "sRGB",
        "output_gamut": "sRGB",
        "note": "gray-world initial estimate; refine via optimizer with bounds",
    }


# ---------------------------------------------------------------------------
# Phase 9 — HSL mapping (initial)
# ---------------------------------------------------------------------------
def hsl_mapping_estimate(imgs: List[np.ndarray]) -> Dict:
    region_sat = {name: [] for name in HUE_RANGES}
    global_sat = []
    for rgb in imgs:
        h, s, _ = _rgb_to_hsv(rgb)
        global_sat.append(s.flatten().mean())
        hd = (h * 360.0).flatten()
        sflat = s.flatten()
        for pix_h, pix_s in zip(hd, sflat):
            region_sat[_hue_region(pix_h)].append(pix_s)
    g = float(np.mean(global_sat)) if global_sat else 1.0
    out = {"schema": "hsl_mapping/v1", "n_images": len(imgs), "hues": {}}
    for name in HUE_RANGES:
        vals = region_sat[name]
        mean_s = float(np.mean(vals)) if vals else g
        # saturation factor relative to global; neutral=1; clamp to [0.7,1.4]
        factor = float(np.clip(mean_s / (g + 1e-6), 0.7, 1.4))
        out["hues"][name] = {
            "hue_shift": 0,
            "saturation": round(factor, 4),
            "lightness": 1.0,
        }
    out["note"] = "saturation factor vs global mean; hue_shift/lightness neutral until optimizer runs"
    return out


# ---------------------------------------------------------------------------
# Phase 10 — Highlight roll-off (initial)
# ---------------------------------------------------------------------------
def highlight_rolloff_estimate(imgs: List[np.ndarray]) -> Dict:
    th = float(np.mean([np.percentile(rgb.mean(axis=2).flatten(), 90) for rgb in imgs]))
    return {
        "schema": "highlight_rolloff/v1",
        "n_images": len(imgs),
        "threshold": round(min(0.95, max(0.7, th)), 4),
        "strength": 0.0,
        "saturation": 1.0,
        "note": "threshold from 90th percentile; strength left 0 for optimizer",
    }


# ---------------------------------------------------------------------------
# Phase 11 — Shadow model (initial)
# ---------------------------------------------------------------------------
def shadow_model_estimate(imgs: List[np.ndarray]) -> Dict:
    black = float(np.mean([np.percentile(rgb.mean(axis=2).flatten(), 2) for rgb in imgs]))
    # shadow tint: average color of darkest 10% pixels
    tints = []
    for rgb in imgs:
        lum = rgb.mean(axis=2)
        mask = lum < np.percentile(lum.flatten(), 10)
        if mask.any():
            tints.append(rgb.reshape(-1, 3)[mask.flatten()].mean(axis=0))
    tint = np.mean(tints, axis=0) if tints else np.zeros(3)
    tint = np.clip(tint / (tint.max() + 1e-6) - 0.33, -0.3, 0.3)  # relative tint
    return {
        "schema": "shadow_model/v1",
        "n_images": len(imgs),
        "black_point": round(min(0.2, max(0.0, black)), 4),
        "compression": 0.0,
        "tint": np.round(tint, 4).tolist(),
        "saturation": 1.0,
        "contrast": 1.0,
        "note": "initial; refine via optimizer",
    }


# ---------------------------------------------------------------------------
# Phase 12 — Lens model (P1, initial/estimated)
# ---------------------------------------------------------------------------
def lens_model_estimate(imgs: List[np.ndarray]) -> Dict:
    # vignette estimate: corner brightness vs center brightness (downscaled)
    vig = []
    ca = []
    for rgb in imgs:
        h, w = rgb.shape[:2]
        gray = rgb.mean(axis=2)
        cy, cx = h // 2, w // 2
        center = gray[cy - h // 8:cy + h // 8, cx - w // 8:cx + w // 8].mean()
        corner = (gray[0:h // 8, 0:w // 8].mean() + gray[-h // 8:, -w // 8:].mean()) / 2
        if center > 0:
            vig.append(float(min(1.0, max(0.0, 1 - corner / center))))
        # crude CA: shift red vs blue at right edge
        edge_r = rgb[h // 3:2 * h // 3, -w // 12:, 0].mean()
        edge_b = rgb[h // 3:2 * h // 3, -w // 12:, 2].mean()
        ca.append(float(np.clip(abs(edge_r - edge_b), 0, 1)))
    return {
        "schema": "lens_model/v1",
        "n_images": len(imgs),
        "vignette": round(float(np.mean(vig)), 4) if vig else 0.0,
        "chromatic_aberration": round(float(np.mean(ca)), 4) if ca else 0.0,
        "sharpness_falloff": 0.0,
        "distortion": 0.0,
        "bloom": 0.0,
        "flare": 0.0,
        "note": "vignette/CA estimated from geometry; others left 0 pending optimizer",
    }


# ---------------------------------------------------------------------------
# Phase 13 — Texture (grain / noise) model
# ---------------------------------------------------------------------------
def texture_model_estimate(imgs: List[np.ndarray]) -> Dict:
    from scipy.ndimage import laplace

    luma_n, chroma_n = [], []
    for rgb in imgs:
        gray = (rgb.mean(axis=2) * 255).astype(np.float32)
        lap = laplace(gray)
        # noise ~ std of high-freq residual in smooth (low-gradient) areas
        grad = np.abs(np.gradient(gray)[0]) + np.abs(np.gradient(gray)[1])
        smooth = grad < np.percentile(grad, 30)
        if smooth.any():
            luma_n.append(float(np.std(lap[smooth])))
        chroma_n.append(float(np.std((rgb[..., 0] - rgb[..., 2]) * 255)))
    return {
        "schema": "texture_model/v1",
        "n_images": len(imgs),
        "luma_noise": round(float(np.mean(luma_n)), 4) if luma_n else 0.0,
        "chroma_noise": round(float(np.mean(chroma_n)), 4) if chroma_n else 0.0,
        "noise_frequency": "high",
        "grain_size": 1.0,
        "grain_density": 1.0,
        "shadow_grain": 1.0,
        "midtone_grain": 1.0,
        "highlight_grain": 1.0,
        "note": "noise estimated in smooth regions to separate from JPEG edges; grain size/density neutral",
    }


# ---------------------------------------------------------------------------
def run(root: str, out_dir: str = "dataset/analysis", manifest: str | None = None) -> Dict:
    paths = discover_images(root)
    if not paths:
        raise SystemExit(f"No images found under {root}")
    n = len(paths)
    print(f"  发现照片 {n} 张，开始载入（每 10% 报一次进度）", flush=True)
    step = max(1, n // 10) if n >= 20 else n  # tiny sets: report only at completion
    imgs = []
    for i, p in enumerate(paths, 1):
        imgs.append(_load(p))
        if i % step == 0 or i == n:
            print(f"  风格分析: 载入 {i}/{n} 张 ({i * 100 // n}%)", flush=True)
    manifest_exif = None
    if manifest and os.path.exists(manifest):
        with open(manifest, "r", encoding="utf-8") as f:
            manifest_exif = json.load(f).get("images", [])

    os.makedirs(out_dir, exist_ok=True)
    jobs = [
        ("rgb_statistics.json", lambda: rgb_statistics(imgs)),
        ("luminance_statistics.json", lambda: luminance_statistics(imgs)),
        ("color_statistics.json", lambda: color_statistics(imgs)),
        ("exposure_statistics.json", lambda: exposure_statistics(imgs, manifest_exif)),
        ("photographer_color_model.json", lambda: photographer_color_model(imgs)),
        ("color_matrix.json", lambda: color_matrix_estimate(imgs)),
        ("hsl_mapping.json", lambda: hsl_mapping_estimate(imgs)),
        ("highlight_rolloff.json", lambda: highlight_rolloff_estimate(imgs)),
        ("shadow_model.json", lambda: shadow_model_estimate(imgs)),
        ("lens_model.json", lambda: lens_model_estimate(imgs)),
        ("texture_model.json", lambda: texture_model_estimate(imgs)),
    ]
    results = {}
    for k, (name, fn) in enumerate(jobs, 1):
        print(f"  风格分析: 统计聚合 {k}/{len(jobs)} — {name}", flush=True)
        results[name] = fn()
    tone, curve = tone_statistics(imgs)
    results["tone_statistics.json"] = tone
    results["tone_curve_initial.json"] = curve

    for name, data in results.items():
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
            json.dump(_native(data), f, indent=2, ensure_ascii=False)
    print(f"Style analysis complete: {len(results)} files -> {out_dir}")
    return results


def _native(o):
    """Recursively convert numpy scalars/arrays to native Python types for JSON."""
    if isinstance(o, dict):
        return {k: _native(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_native(v) for v in o]
    if isinstance(o, np.ndarray):
        return _native(o.tolist())
    if isinstance(o, np.floating):
        return float(o)
    if isinstance(o, np.integer):
        return int(o)
    return o


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Phase 3-13 style feature engine")
    p.add_argument("root")
    p.add_argument("--out", default="dataset/analysis")
    p.add_argument("--manifest", default=None, help="optional dataset_manifest.json for EXIF distributions")
    args = p.parse_args(argv)
    run(args.root, args.out, args.manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())
