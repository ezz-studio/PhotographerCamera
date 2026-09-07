#!/usr/bin/env python3
"""Phase 18 — Profile Validator & Regression (data-ready).

Validates a PhotographerProfile on a held-out TEST set that did NOT participate in
parameter tuning. Compares original vs simulated features and reports per-component
errors plus an overall loss. Produces a regression baseline so future Profile changes
can be checked for regressions (test metrics must not degrade beyond tolerance).

Output: validation_report.json, regression_baseline.json
"""
from __future__ import annotations

import json
import os
import sys
from typing import Dict, List

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from profile_renderer import render  # noqa: E402
from loss_function import extract_features, loss  # noqa: E402


def split_dataset(n: int, train=0.7, val=0.15, test=0.15, seed=0) -> Dict[str, List[int]]:
    rng = np.random.default_rng(seed)
    idx = rng.permutation(n)
    nt = int(round(n * train))
    nv = int(round(n * val))
    return {
        "train": idx[:nt].tolist(),
        "validation": idx[nt:nt + nv].tolist(),
        "test": idx[nt + nv:].tolist(),
    }


def _aggregate(records: List[dict]) -> Dict:
    out = {}
    keys = records[0].keys() if records else []
    for k in keys:
        if k == "total":
            out[k] = round(float(np.mean([r[k] for r in records])), 5)
        else:
            out[k] = round(float(np.mean([r[k] for r in records])), 5)
    return out


def average_features(records: List[dict]) -> Dict:
    """Mean-pool a list of extract_features() dicts into a single style centroid.

    Scalars are averaged directly; the 'hist' field is a fixed-length array and is
    averaged element-wise. Used to build the held-out validation TARGET from the
    photographer's GRADED reference set, so validation measures whether the profile
    correctly imposes the style on *un-graded* photos instead of double-grading an
    already-graded image (which spuriously inflates the loss).
    """
    if not records:
        raise ValueError("cannot average an empty feature list")
    keys = [k for k in records[0].keys() if k != "hist"]
    out = {k: float(np.mean([r[k] for r in records])) for k in keys}
    hists = [np.asarray(r["hist"], dtype=np.float64) for r in records]
    out["hist"] = np.mean(hists, axis=0)
    return out


# AGENTS.md gate: a profile may only ship to the Android renderer when its
# held-out test loss is below this threshold (mirrors the test-suite assertion).
VALIDATED_LOSS_THRESHOLD = 1.0


def validate(profile: dict, test_imgs: List[np.ndarray], weights: dict | None = None,
             loss_threshold: float = VALIDATED_LOSS_THRESHOLD, verbose: bool = True,
             target_features: dict | None = None) -> Dict:
    """Validate *profile* on a held-out *test_imgs* set.

    target_features: when provided (recommended), every test image is rendered and
    compared against this FIXED style centroid — the proper "does the profile impose
    the right look on neutral content" check. The test set should then be ordinary,
    UN-GRADED photos, so render() is not applied on top of an already-graded image
    (which would double-grade and inflate the loss). When None (legacy mode), the
    target is each image's own features (original-vs-simulated), retained for
    backwards compatibility with the CLI / regression tests.
    """
    if not test_imgs:
        profile["validation_status"] = "pending"
        return {
            "schema": "validation_report/v1",
            "validation_target": "style-centroid" if target_features is not None else "legacy-self",
            "n_test": 0, "overall_test_loss": None, "loss_threshold": loss_threshold,
            "validation_status": "pending", "components": {}, "per_image": [],
        }
    per_image = []
    n = len(test_imgs)
    mode = "style-centroid" if target_features is not None else "legacy-self"
    if verbose and n:
        print(f"  留出验证: 共 {n} 张对照照片（目标模式={mode}）", flush=True)
    # milestone progress every ~20% for meaningful-size sets; single line for tiny sets
    step = max(1, n // 5) if n >= 10 else n
    for i, img in enumerate(test_imgs, 1):
        tgt = target_features if target_features is not None else extract_features(img)
        sim = extract_features(render(img, profile))
        _l, brk = loss(tgt, sim, weights)
        per_image.append(brk)
        if verbose and (i % step == 0 or i == n):
            print(f"  留出验证: {i}/{n} 张 ({i * 100 // n}%)", flush=True)
    summary = _aggregate(per_image)
    overall = summary.get("total")
    profile["validation_status"] = (
        "validated" if overall is not None and overall < loss_threshold else "pending"
    )
    return {
        "schema": "validation_report/v1",
        "validation_target": mode,
        "n_test": len(test_imgs),
        "overall_test_loss": overall,
        "loss_threshold": loss_threshold,
        "validation_status": profile["validation_status"],
        "components": {k: v for k, v in summary.items() if k != "total"},
        "per_image": per_image,
    }


def regression_test(profile: dict, test_imgs: List[np.ndarray], baseline: dict,
                    tolerance: float = 0.05, weights: dict | None = None) -> Dict:
    rep = validate(profile, test_imgs, weights, verbose=False)
    base_loss = baseline.get("overall_test_loss")
    cur_loss = rep["overall_test_loss"]
    passed = base_loss is None or (cur_loss is not None and cur_loss <= base_loss * (1 + tolerance))
    return {
        "schema": "regression_test/v1",
        "baseline_loss": base_loss,
        "current_loss": cur_loss,
        "tolerance": tolerance,
        "passed": bool(passed),
        "detail": rep["components"],
    }


def main(argv=None) -> int:
    import argparse

    from dataset_loader import discover_images  # noqa: E402
    from PIL import Image  # noqa: E402

    p = argparse.ArgumentParser(description="Phase 18 profile validator")
    p.add_argument("profile")
    p.add_argument("images_root")
    p.add_argument("--out", default="profiles")
    p.add_argument("--tolerance", type=float, default=0.05)
    args = p.parse_args(argv)

    with open(args.profile, "r", encoding="utf-8") as f:
        prof = json.load(f)
    imgs = []
    for path in discover_images(args.images_root):
        im = Image.open(path).convert("RGB")
        imgs.append(np.asarray(im, dtype=np.float32) / 255.0)
    test_idx = split_dataset(len(imgs))["test"]
    if not test_idx:
        test_idx = list(range(len(imgs)))
    test_imgs = [imgs[i] for i in test_idx]

    rep = validate(prof, test_imgs)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "validation_report.json"), "w", encoding="utf-8") as f:
        json.dump(rep, f, indent=2, ensure_ascii=False)
    with open(os.path.join(args.out, "regression_baseline.json"), "w", encoding="utf-8") as f:
        json.dump({"overall_test_loss": rep["overall_test_loss"], "components": rep["components"]},
                  f, indent=2, ensure_ascii=False)
    print(f"Validation: test_loss={rep['overall_test_loss']} (n_test={rep['n_test']}) -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
