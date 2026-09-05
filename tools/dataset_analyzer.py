#!/usr/bin/env python3
"""Phase 1 — Photographer dataset analyzer (data-ready).

Reads JPG/JPEG/HEIC final images and emits:
  - dataset_metadata.json : full per-image metadata (EXIF + file + id)
  - dataset_manifest.json : lightweight manifest (id, path, dims, key exif)

Depends on: Pillow, NumPy, pillow-heif (HEIC). OpenCV/scikit-image NOT required.

JPEG quality is not recoverable from a decoded file; we report a *proxy*
(compression ratio and a rough quality estimate) and label it clearly.
"""
from __future__ import annotations

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
except Exception:  # pragma: no cover
    _HEIC_OK = False

sys.path.insert(0, os.path.dirname(__file__))
from dataset_loader import discover_images, generate_image_id  # noqa: E402

# EXIF tag id -> friendly key
_EXIF_MAP = {
    "Make": "camera_make",
    "Model": "camera_model",
    "LensModel": "lens_model",
    "DateTimeOriginal": "datetime_original",
    "ISOSpeedRatings": "iso",
    "FNumber": "aperture",
    "ExposureTime": "shutter",
    "FocalLength": "focal_length",
    "Orientation": "orientation",
}

# Common ICC profile descriptions -> color space label
_COLORSPACE_HINTS = {
    "sRGB": "sRGB",
    "Adobe RGB": "AdobeRGB",
    "Display P3": "DisplayP3",
    "ProPhoto": "ProPhoto",
}


def _ratio(v):
    if isinstance(v, tuple) and len(v) == 2 and v[1] != 0:
        return round(v[0] / v[1], 4)
    if isinstance(v, (list, tuple)):
        return list(v)
    return v


def _estimate_quality(width: int, height: int, size_bytes: int) -> dict:
    """Proxy for JPEG quality (NOT exact). Based on bytes-per-pixel."""
    bpp = size_bytes / max(1, width * height)
    # heuristic: bpp ~ 0.25 => ~quality 95; bpp ~ 1.0 => ~quality 80; bpp ~ 4 => ~quality 50
    # Map bpp in [0.2, 6] to quality in [98, 35] (rough, linear in log).
    import math

    quality = int(round(98 - max(0.0, math.log2(max(bpp, 0.01)) - math.log2(0.2)) * 14))
    quality = max(35, min(98, quality))
    return {"bytes_per_pixel": round(bpp, 4), "estimated_quality": quality}


def read_full_exif(path: str) -> dict:
    out: dict = {}
    try:
        img = Image.open(path)
        w, h = img.size
        out["width"] = w
        out["height"] = h
        out["mode"] = img.mode
        out["format"] = img.format
        out["has_icc"] = img.info.get("icc_profile") is not None
        # color space from ICC description if present
        icc = img.info.get("icc_profile")
        if icc:
            desc = ""
            try:
                # minimal: search for known strings in ICC bytes
                blob = icc
                for k in _COLORSPACE_HINTS:
                    if k.encode("ascii", "ignore") in blob:
                        desc = k
                        break
            except Exception:
                desc = ""
            out["color_space"] = desc or "ICC (unknown)"
        else:
            out["color_space"] = "sRGB*" if img.mode in ("RGB", "RGBA") else img.mode
        exif = img.getexif()
        for tag_id, val in exif.items():
            name = ExifTags.TAGS.get(tag_id, str(tag_id))
            if name in _EXIF_MAP:
                out[_EXIF_MAP[name]] = _ratio(val)
    except Exception as e:  # pragma: no cover
        out["_error"] = str(e)
    return out


def analyze_dataset(root: str, out_dir: str = "dataset") -> dict:
    images = discover_images(root)
    metadata_records = []
    manifest_records = []
    for p in images:
        size = os.path.getsize(p)
        exif = read_full_exif(p)
        rec = {
            "id": generate_image_id(p),
            "path": p,
            "ext": os.path.splitext(p)[1].lower(),
            "size_bytes": size,
            "exif": exif,
        }
        if "width" in exif and "height" in exif:
            rec["quality_proxy"] = _estimate_quality(exif["width"], exif["height"], size)
        metadata_records.append(rec)
        manifest_records.append({
            "id": rec["id"],
            "path": p,
            "ext": rec["ext"],
            "width": exif.get("width"),
            "height": exif.get("height"),
            "camera_model": exif.get("camera_model"),
            "lens_model": exif.get("lens_model"),
            "iso": exif.get("iso"),
            "aperture": exif.get("aperture"),
            "shutter": exif.get("shutter"),
            "focal_length": exif.get("focal_length"),
            "datetime_original": exif.get("datetime_original"),
        })

    os.makedirs(out_dir, exist_ok=True)
    meta = {
        "schema": "dataset_metadata/v1",
        "count": len(metadata_records),
        "heic_supported": _HEIC_OK,
        "images": metadata_records,
    }
    manifest = {
        "schema": "dataset_manifest/v1",
        "count": len(manifest_records),
        "images": manifest_records,
    }
    meta_path = os.path.join(out_dir, "dataset_metadata.json")
    mani_path = os.path.join(out_dir, "dataset_manifest.json")
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, ensure_ascii=False)
    with open(mani_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"Analyzed {len(images)} images -> {meta_path}, {mani_path}")
    return meta


def main(argv=None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="Phase 1 dataset analyzer")
    p.add_argument("root")
    p.add_argument("--out", default="dataset")
    args = p.parse_args(argv)
    analyze_dataset(args.root, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
