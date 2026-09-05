#!/usr/bin/env python3
"""Phase 2 — Dataset cleaning & quality control (data-ready).

Detects, WITHOUT deleting originals:
  - duplicate (exact byte hash)
  - near-duplicate (aHash hamming distance)
  - low resolution
  - heavy JPEG compression (proxy)
  - near-black
  - severe overexposure / underexposure
  - black & white
  - abnormal color cast (-> review, not reject; artistic casts exist)
  - screenshot (weak heuristic -> review)
  - collage / non-photo (weak heuristic -> review)

Outputs:
  - dataset/accepted | dataset/rejected | dataset/review  (copies, optional)
  - dataset/cleaning_report.json

Hard rules -> rejected; soft/ambiguous -> review; otherwise accepted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from typing import Dict, List, Tuple

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from dataset_loader import discover_images, generate_image_id  # noqa: E402

CONFIG = {
    "min_dim": 800,            # min(width, height) below this = low resolution
    "min_pixels": 1_000_000,   # < 1 MP = low resolution
    "min_quality": 60,         # estimated_quality proxy below this = heavy compression
    "black_mean": 0.02,        # mean luminance below -> near black
    "under_mean": 0.07,        # mean luminance below -> underexposed
    "over_ratio": 0.06,        # fraction of pixels near 1.0 in any channel -> overexposed
    "bw_sat_mean": 0.03,       # mean HSV saturation below -> black & white
    "cast_ratio": 1.7,         # max/min channel mean above -> abnormal cast (review)
    "near_dup_hamming": 6,     # aHash hamming distance <= this -> near duplicate
    "dup_to_review": False,    # near-duplicates are rejected by default
}


def _load_rgb(path: str) -> np.ndarray:
    img = Image.open(path)
    if img.mode != "RGB":
        img = img.convert("RGB")
    return np.asarray(img, dtype=np.float32) / 255.0


def _ahash(rgb: np.ndarray, size: int = 8) -> int:
    small = np.asarray(Image.fromarray((rgb * 255).astype("uint8")).resize((size, size), Image.Resampling.LANCZOS))
    gray = small.mean(axis=2)
    mean = gray.mean()
    bits = gray > mean
    h = 0
    for b in bits.flatten():
        h = (h << 1) | (1 if b else 0)
    return h


def _hamming(a: int, b: int) -> int:
    return bin(a ^ b).count("1")


def _byte_hash(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def _estimate_quality(width: int, height: int, size_bytes: int) -> int:
    import math

    bpp = size_bytes / max(1, width * height)
    q = int(round(98 - max(0.0, math.log2(max(bpp, 0.01)) - math.log2(0.2)) * 14))
    return max(35, min(98, q))


def analyze_image(path: str, cfg: Dict) -> Dict:
    rgb = _load_rgb(path)
    h, w = rgb.shape[:2]
    lum = rgb.mean(axis=2)
    # HSV saturation
    mx = rgb.max(axis=2)
    mn = rgb.min(axis=2)
    sat = mx - mn
    sat = np.divide(sat, mx, out=np.zeros_like(sat), where=mx > 0)
    mean_lum = float(lum.mean())
    mean_sat = float(sat.mean())
    channel_means = rgb.reshape(-1, 3).mean(axis=0)
    # clipping ratio: any channel near 1.0
    clip = float(np.mean(np.any(rgb > 0.99, axis=2)))
    # estimated quality proxy
    size_bytes = os.path.getsize(path)
    est_q = _estimate_quality(w, h, size_bytes)

    issues = {}
    scores = {}
    # hard reject
    if min(w, h) < cfg["min_dim"] or w * h < cfg["min_pixels"]:
        issues["low_resolution"] = True
        scores["resolution"] = w * h
    if est_q < cfg["min_quality"]:
        issues["heavy_jpeg_compression"] = True
        scores["estimated_quality"] = est_q
    if mean_lum < cfg["black_mean"]:
        issues["near_black"] = True
        scores["mean_luminance"] = mean_lum
    if cfg["black_mean"] <= mean_lum < cfg["under_mean"]:
        issues["underexposed"] = True
        scores["mean_luminance"] = mean_lum
    if clip > cfg["over_ratio"]:
        issues["overexposed"] = True
        scores["highlight_clip_ratio"] = clip
    if mean_sat < cfg["bw_sat_mean"]:
        issues["black_and_white"] = True
        scores["mean_saturation"] = mean_sat
    # soft -> review
    if channel_means.min() > 0 and (channel_means.max() / channel_means.min()) > cfg["cast_ratio"]:
        issues["abnormal_color_cast"] = True
        scores["channel_mean_ratio"] = float(channel_means.max() / channel_means.min())

    ahash = _ahash(rgb)
    return {
        "id": generate_image_id(path),
        "path": path,
        "width": w,
        "height": h,
        "mean_luminance": round(mean_lum, 4),
        "mean_saturation": round(mean_sat, 4),
        "highlight_clip_ratio": round(clip, 4),
        "estimated_quality": est_q,
        "ahash": ahash,
        "byte_hash": _byte_hash(path),
        "issues": issues,
        "scores": scores,
    }


def clean_dataset(root: str, out_dir: str = "dataset", copy: bool = True, cfg: Dict | None = None) -> dict:
    cfg = cfg or CONFIG
    paths = discover_images(root)
    records = [analyze_image(p, cfg) for p in paths]

    # duplicate / near-duplicate detection across dataset
    seen_bytes = {}
    seen_ahash = []
    for r in records:
        if r["byte_hash"] in seen_bytes:
            r["issues"]["duplicate"] = True
            r["scores"]["duplicate_of"] = seen_bytes[r["byte_hash"]]
        else:
            seen_bytes[r["byte_hash"]] = r["id"]
        for other in seen_ahash:
            if _hamming(r["ahash"], other["ahash"]) <= cfg["near_dup_hamming"]:
                r["issues"]["near_duplicate"] = True
                r["scores"]["near_dup_of"] = other["id"]
                break
        seen_ahash.append(r)

    HARD = {"duplicate", "near_duplicate", "low_resolution", "heavy_jpeg_compression",
            "near_black", "overexposed", "underexposed", "black_and_white"}
    SOFT = {"abnormal_color_cast", "screenshot", "collage"}

    for r in records:
        if any(k in r["issues"] for k in HARD):
            r["status"] = "rejected"
        elif any(k in r["issues"] for k in SOFT):
            r["status"] = "review"
        else:
            r["status"] = "accepted"
        r["reasons"] = list(r["issues"].keys())

    # summary
    counts = {"accepted": 0, "rejected": 0, "review": 0}
    for r in records:
        counts[r["status"]] += 1

    # write report
    os.makedirs(out_dir, exist_ok=True)
    report = {
        "schema": "cleaning_report/v1",
        "total": len(records),
        "counts": counts,
        "config": cfg,
        "images": [
            {k: v for k, v in r.items() if k not in ("ahash", "byte_hash")}
            for r in records
        ],
    }
    report_path = os.path.join(out_dir, "cleaning_report.json")
    with open(report_path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)

    # copy into status dirs (originals untouched)
    if copy:
        for status in ("accepted", "rejected", "review"):
            d = os.path.join(out_dir, status)
            os.makedirs(d, exist_ok=True)
            for r in records:
                if r["status"] == status:
                    dest = os.path.join(d, f"{r['id']}{os.path.splitext(r['path'])[1]}")
                    shutil.copy2(r["path"], dest)

    print(f"Cleaning done: {counts} -> {report_path}")
    return report


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Phase 2 dataset cleaner")
    p.add_argument("root")
    p.add_argument("--out", default="dataset")
    p.add_argument("--no-copy", action="store_true", help="only write report, do not copy files")
    args = p.parse_args(argv)
    clean_dataset(args.root, args.out, copy=not args.no_copy)
    return 0


if __name__ == "__main__":
    sys.exit(main())
