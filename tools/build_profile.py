#!/usr/bin/env python3
"""Offline Profile Builder driver — closes the PC pipeline.

Runs the full offline chain on a photographer dataset:
  analyze (Phase 3-13) -> AI generate (Phase 15) -> optimize (Phase 16, Loss Phase 17)
  -> validate on held-out test set (Phase 18) -> export profile_final.json

Usage:
  python tools/build_profile.py <images_root> --name "Photographer A" --out profiles
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from dataset_loader import discover_images  # noqa: E402
import style_analyzer  # noqa: E402
import ai_profile_generator  # noqa: E402
import profile_optimizer  # noqa: E402
import profile_validator  # noqa: E402


def load_images(root, limit=None, size=256):
    """Load dataset images. By default ALL discovered images are loaded — the
    old hard cap of 40 silently threw away everything past the first 40 files
    (alphabetical), which both starved the optimiser and shrank the held-out
    set to a token handful (400 photos -> n_test=6)."""
    paths = discover_images(root)
    n_total = len(paths)
    if limit:
        paths = paths[:limit]
    imgs = []
    n_load = len(paths)
    step = max(1, n_load // 10) if n_load >= 20 else n_load  # tiny sets: report only at completion
    for i, p in enumerate(paths, 1):
        im = Image.open(p).convert("RGB")
        arr = np.asarray(im, dtype=np.float32) / 255.0
        h, w = arr.shape[:2]
        if max(h, w) > size:
            s = size / max(h, w)
            arr = np.asarray(Image.fromarray((arr * 255).astype("uint8")).resize(
                (max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS), dtype=np.float32) / 255.0
        imgs.append(arr)
        if i % step == 0 or i == n_load:
            print(f"  优化集载入: {i}/{n_load} 张 ({i * 100 // n_load}%)", flush=True)
    print(f"  载入照片: {len(imgs)} 张（目录共发现 {n_total} 张"
          + (f"，上限 limit={limit}" if limit else "，全部载入") + "）")
    return imgs, n_total


def build(images_root: str, name: str, out_dir: str = "profiles", analysis_dir: str = "dataset/analysis"):
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(analysis_dir, exist_ok=True)

    print("[1/5] Style analysis (Phase 3-13) ...")
    style_analyzer.run(images_root, out_dir=analysis_dir)

    print("[2/5] AI profile generation (Phase 15) ...")
    prof_v1, gen_report = ai_profile_generator.generate_profile(analysis_dir, name)
    with open(os.path.join(out_dir, "profile_v1.json"), "w", encoding="utf-8") as f:
        json.dump(prof_v1, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "profile_generation_report.json"), "w", encoding="utf-8") as f:
        json.dump(gen_report, f, indent=2, ensure_ascii=False)

    print("[3/5] Split + optimize (Phase 16, Loss 17) ...")
    imgs, n_total = load_images(images_root)
    split = profile_validator.split_dataset(len(imgs), seed=42)
    train_val = [imgs[i] for i in (split["train"] + split["validation"])]
    test = [imgs[i] for i in split["test"]]
    print(f"  数据切分: 训练 {len(split['train'])} 张 + 验证 {len(split['validation'])} 张"
          f"（两者共同参与优化，共 {len(train_val)} 张）")
    print(f"  对照集（留出测试，不参与任何拟合）: {len(test)} 张")
    prof_final, opt_report = profile_optimizer.optimize(prof_v1, train_val)
    with open(os.path.join(out_dir, "optimization_report.json"), "w", encoding="utf-8") as f:
        json.dump(opt_report, f, indent=2, ensure_ascii=False)

    print("[4/5] Validate on held-out test set (Phase 18) ...")
    # NOTE: validate() stamps profile["validation_status"] in place, so the
    # profile MUST be written to disk AFTER this call, never before.
    val_report = profile_validator.validate(prof_final, test)
    # Surface the dataset accounting in the report so the Studio UI (and any
    # downstream audit) can see exactly how many photos did what.
    val_report["dataset"] = {
        "discovered": n_total,
        "loaded": len(imgs),
        "train": len(split["train"]),
        "validation": len(split["validation"]),
        "optimize_images": len(train_val),
        "test": len(test),
    }
    print(f"  参与优化的照片: {len(train_val)} 张 | 对照集: {len(test)} 张")
    with open(os.path.join(out_dir, "profile_final.json"), "w", encoding="utf-8") as f:
        json.dump(prof_final, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "validation_report.json"), "w", encoding="utf-8") as f:
        json.dump(val_report, f, indent=2, ensure_ascii=False)
    with open(os.path.join(out_dir, "regression_baseline.json"), "w", encoding="utf-8") as f:
        json.dump({"overall_test_loss": val_report["overall_test_loss"],
                   "components": val_report["components"]}, f, indent=2, ensure_ascii=False)

    print("[5/5] Done.")
    print(f"  profile_final.json test_loss = {val_report['overall_test_loss']} (n_test={val_report['n_test']})")
    print(f"  validation_status = {prof_final.get('validation_status')}")
    print(f"  outputs in: {out_dir}")
    return prof_final


def main(argv=None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="Offline Photographer Profile builder")
    p.add_argument("images_root")
    p.add_argument("--name", default="Photographer (auto)")
    p.add_argument("--out", default="profiles")
    p.add_argument("--analysis", default="dataset/analysis")
    args = p.parse_args(argv)
    build(args.images_root, args.name, args.out, args.analysis)
    return 0


if __name__ == "__main__":
    sys.exit(main())
