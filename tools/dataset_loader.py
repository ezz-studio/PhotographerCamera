#!/usr/bin/env python3
"""Data-ready dataset loader for PhotographerCamera.

Scans a directory of photographer final images (JPG / JPEG / HEIC), reads pixels
and EXIF, generates a stable per-image ID, and emits ``dataset_metadata.json``.

Dependencies:
  - Pillow, NumPy  (required)
  - pillow-heif    (optional; enables HEIC decoding)
  - OpenCV / scikit-image (NOT required here; used later by feature analyzers)

This module is *data-ready*: point it at any compliant directory and it runs.
With no dataset available it still imports cleanly (heavy deps are lazy) so the
rest of the pipeline can be developed against its interface.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from typing import List

import numpy as np
from PIL import Image, ExifTags

try:
    import pillow_heif  # type: ignore

    pillow_heif.register_heif_opener()
    _HEIC_OK = True
except Exception:  # pragma: no cover - environment dependent
    _HEIC_OK = False

SUPPORTED_EXT = {".jpg", ".jpeg", ".heic"}

# EXIF tag id -> friendly key used in dataset metadata
_EXIF_MAP = {
    "Make": "camera_make",
    "Model": "camera_model",
    "LensModel": "lens_model",
    "DateTimeOriginal": "datetime_original",
    "ISOSpeedRatings": "iso",
    "FNumber": "aperture",
    "ExposureTime": "shutter",
    "FocalLength": "focal_length",
}


def discover_images(root: str) -> List[str]:
    """Recursively collect supported image paths, sorted for determinism."""
    out: List[str] = []
    for dirpath, _, files in os.walk(root):
        for fn in files:
            if os.path.splitext(fn)[1].lower() in SUPPORTED_EXT:
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def generate_image_id(path: str) -> str:
    """Stable 16-char id from path + file size (robust to renames)."""
    h = hashlib.sha1()
    h.update(path.encode("utf-8"))
    h.update(str(os.path.getsize(path)).encode("utf-8"))
    return h.hexdigest()[:16]


def read_image(path: str) -> np.ndarray:
    """Return RGB ``float32`` image normalized to [0, 1]."""
    img = Image.open(path)
    if img.mode != "RGB":
        img = img.convert("RGB")
    return np.asarray(img, dtype=np.float32) / 255.0


def _normalize_exif_value(v):
    # Rational encoded as (num, den) -> float
    if isinstance(v, tuple) and len(v) == 2 and v[1] != 0:
        return round(v[0] / v[1], 4)
    if isinstance(v, (list, tuple)):
        return list(v)
    return v


def read_exif(path: str) -> dict:
    """Extract dimensions, color mode, ICC presence and mapped EXIF fields."""
    out: dict = {}
    try:
        img = Image.open(path)
        out["width"], out["height"] = img.size
        out["mode"] = img.mode
        out["has_icc"] = img.info.get("icc_profile") is not None
        exif = img.getexif()
        for tag_id, val in exif.items():
            name = ExifTags.TAGS.get(tag_id, str(tag_id))
            if name in _EXIF_MAP:
                out[_EXIF_MAP[name]] = _normalize_exif_value(val)
    except Exception as e:  # pragma: no cover - corrupt file guard
        out["_error"] = str(e)
    return out


def build_metadata(images: List[str]) -> dict:
    """Build the dataset_metadata.json structure for a list of image paths."""
    records = []
    for p in images:
        records.append(
            {
                "id": generate_image_id(p),
                "path": p,
                "ext": os.path.splitext(p)[1].lower(),
                "exif": read_exif(p),
            }
        )
    return {
        "schema": "dataset_metadata/v1",
        "count": len(records),
        "heic_supported": _HEIC_OK,
        "images": records,
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Scan a photographer dataset -> dataset_metadata.json")
    p.add_argument("root", help="directory containing JPG/HEIC")
    p.add_argument("--out", default="dataset/dataset_metadata.json")
    args = p.parse_args(argv)

    imgs = discover_images(args.root)
    meta = build_metadata(imgs)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    print(f"Scanned {meta['count']} images -> {args.out} (HEIC supported: {_HEIC_OK})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
