#!/usr/bin/env python3
"""Phase 16 — Profile Optimizer (data-ready).

Fits an initial PhotographerProfile to a photographer's images by minimizing the
weighted Loss (Phase 17) between original and simulated features. Staged per the spec:
optimize Exposure/Tone/Color first, then Texture/Optical. Parameters are bounded and
clamped so the result stays within the valid Profile range.

Output: profile_final.json, optimization_report.json
"""
from __future__ import annotations

import copy
import json
import os
import sys
from typing import Callable, List, Tuple

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from profile_renderer import render  # noqa: E402
from loss_function import extract_features, loss  # noqa: E402
from profile_schema import validate_profile  # noqa: E402

try:
    from scipy.optimize import minimize, minimize_scalar  # type: ignore
except Exception:  # pragma: no cover
    minimize = None
    minimize_scalar = None


def _stage1_specs(p: dict) -> List[Tuple[str, Callable, Callable, Tuple[float, float]]]:
    return [
        ("exposure.bias", lambda: p["exposure"]["bias"],
         lambda v: p["exposure"].__setitem__("bias", v), (-2.0, 2.0)),
        ("white_balance.temperature_bias", lambda: p["white_balance"]["temperature_bias"],
         lambda v: p["white_balance"].__setitem__("temperature_bias", v), (-1.0, 1.0)),
        ("white_balance.tint_bias", lambda: p["white_balance"]["tint_bias"],
         lambda v: p["white_balance"].__setitem__("tint_bias", v), (-1.0, 1.0)),
        ("color_matrix[0][0]", lambda: p["color_matrix"]["matrix_3x3"][0][0],
         lambda v: p["color_matrix"]["matrix_3x3"][0].__setitem__(0, v), (0.5, 1.5)),
        ("color_matrix[1][1]", lambda: p["color_matrix"]["matrix_3x3"][1][1],
         lambda v: p["color_matrix"]["matrix_3x3"][1].__setitem__(1, v), (0.5, 1.5)),
        ("color_matrix[2][2]", lambda: p["color_matrix"]["matrix_3x3"][2][2],
         lambda v: p["color_matrix"]["matrix_3x3"][2].__setitem__(2, v), (0.5, 1.5)),
    ]


def _stage2_specs(p: dict) -> List[Tuple[str, Callable, Callable, Tuple[float, float]]]:
    return [
        ("highlight_rolloff.strength", lambda: p["highlight_rolloff"]["strength"],
         lambda v: p["highlight_rolloff"].__setitem__("strength", v), (0.0, 1.0)),
        ("shadow.black_point", lambda: p["shadow"]["black_point"],
         lambda v: p["shadow"].__setitem__("black_point", v), (0.0, 0.2)),
        ("vignette.amount", lambda: p["vignette"]["amount"],
         lambda v: p["vignette"].__setitem__("amount", v), (0.0, 1.0)),
        ("grain.amount", lambda: p["grain"]["amount"],
         lambda v: p["grain"].__setitem__("amount", v), (0.0, 1.0)),
        ("noise.luma", lambda: p["noise"]["luma"],
         lambda v: p["noise"].__setitem__("luma", v), (0.0, 1.0)),
        ("noise.chroma", lambda: p["noise"]["chroma"],
         lambda v: p["noise"].__setitem__("chroma", v), (0.0, 1.0)),
    ]


def _resize(rgb: np.ndarray, size=128) -> np.ndarray:
    from PIL import Image

    h, w = rgb.shape[:2]
    if max(h, w) <= size:
        return rgb
    scale = size / max(h, w)
    nh, nw = max(1, int(h * scale)), max(1, int(w * scale))
    arr = (np.clip(rgb, 0, 1) * 255).astype("uint8")
    small = np.asarray(Image.fromarray(arr).resize((nw, nh), Image.Resampling.LANCZOS), dtype=np.float32) / 255.0
    return small


def optimize(profile_v1: dict, train_imgs: List[np.ndarray], weights: dict | None = None,
            size: int = 128, maxiter: int = 80, eval_subset: int = 64,
            rng_seed: int = 42) -> Tuple[dict, dict]:
    """Fit profile scalars against *train_imgs*.

    eval_subset: when the training set is larger than this, each objective
    evaluation renders a fresh random sample of that many images instead of the
    whole set. This keeps the wall-clock bounded on large datasets (hundreds of
    photos) while every image still participates across the many evaluations.
    Set to 0 (or >= len) to evaluate on the full set every call.
    """
    if minimize is None:
        raise RuntimeError("scipy.optimize required for optimizer")
    wp = copy.deepcopy(profile_v1)
    targets = [extract_features(_resize(img, size)) for img in train_imgs]
    train_small = [_resize(img, size) for img in train_imgs]

    n_train = len(train_imgs)
    sampling = bool(eval_subset and 0 < eval_subset < n_train)
    rng = np.random.default_rng(rng_seed)
    print(f"  优化照片: {n_train} 张 @ {size}px (maxiter={maxiter})"
          + (f"，每次评估随机抽样 {eval_subset} 张 (seed={rng_seed})" if sampling else "，全量评估"))
    evals = {"n": 0}

    report = {"schema": "optimization_report/v1", "stages": [], "weights": weights or {},
              "n_train_images": n_train, "eval_subset": eval_subset if sampling else None}

    def run_stage(specs):
        history = []

        def objective(x):
            for (name, _g, s, b), val in zip(specs, x):
                s(float(np.clip(val, b[0], b[1])))
            if sampling:
                idx = rng.choice(n_train, size=eval_subset, replace=False)
                pairs = [(train_small[i], targets[i]) for i in idx]
            else:
                pairs = list(zip(train_small, targets))
            total = 0.0
            for img, tgt in pairs:
                sim = extract_features(render(img, wp))
                l, _ = loss(tgt, sim, weights)
                total += l
            total /= max(1, len(pairs))
            evals["n"] += 1
            if not np.isfinite(total):
                # corrcoef divide-by-zero on near-constant channels can emit
                # NaN; a NaN objective poisons Nelder-Mead -> large finite
                # penalty instead.
                return 1e6
            if evals["n"] % 100 == 0:
                print(f"    优化进度: 第 {evals['n']} 次评估, loss={total:.5f}")
            history.append(round(float(total), 5))
            return total

        x0 = np.array([g() for (_n, g, _s, _b) in specs], dtype=float)
        bounds = [b for (_n, _g, _s, b) in specs]
        if minimize is not None:
            minimize(objective, x0, method="Nelder-Mead",
                     options={"maxiter": maxiter, "xatol": 1e-3, "fatol": 1e-4})
        # Coordinate-descent refinement: Nelder-Mead can get stuck at bounds / in
        # flat regions, leaving an obviously-wrong parameter un-fit. A per-parameter
        # bounded 1-D minimization reliably drives each param to its local optimum
        # (e.g. noise/chroma -> 0 when the original has no such texture).
        if minimize_scalar is not None:
            x = np.array([g() for (_n, g, _s, _b) in specs], dtype=float)
            for i, (name, _g, s, b) in enumerate(specs):
                def f(v):
                    xv = x.copy()
                    xv[i] = v
                    return objective(xv)
                try:
                    r = minimize_scalar(f, bounds=b, method="bounded")
                    x[i] = float(np.clip(r.x, b[0], b[1]))
                except Exception:
                    pass
            for (name, _g, s, b), val in zip(specs, x):
                s(float(np.clip(val, b[0], b[1])))
        report["stages"].append({
            "params": [n for (n, _g, _s, _b) in specs],
            "initial_loss": history[0] if history else None,
            "final_loss": history[-1] if history else None,
            "loss_history": history,
        })

    run_stage(_stage1_specs(wp))
    run_stage(_stage2_specs(wp))

    wp["validation_status"] = "pending"
    wp["version"] = max(1, int(profile_v1.get("version", 1)) + 1)
    ok, errors = validate_profile(wp)
    report["final_schema_valid"] = ok
    if not ok:
        report["final_schema_errors"] = errors
    return wp, report


def main(argv=None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="Phase 16 profile optimizer")
    p.add_argument("profile_v1", help="initial profile JSON")
    p.add_argument("images_root", help="directory of training images")
    p.add_argument("--out", default="profiles")
    args = p.parse_args(argv)

    from dataset_loader import discover_images  # noqa: E402

    with open(args.profile_v1, "r", encoding="utf-8") as f:
        prof = json.load(f)
    imgs = []
    for path in discover_images(args.images_root)[:12]:
        from PIL import Image

        im = Image.open(path).convert("RGB")
        imgs.append(np.asarray(im, dtype=np.float32) / 255.0)
    final, rep = optimize(prof, imgs)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "profile_final.json"), "w", encoding="utf-8") as f:
        json.dump(final, f, indent=2, ensure_ascii=False)
    with open(os.path.join(args.out, "optimization_report.json"), "w", encoding="utf-8") as f:
        json.dump(rep, f, indent=2, ensure_ascii=False)
    print(f"Optimized -> profile_final.json (final_loss={rep['stages'][-1]['final_loss']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
