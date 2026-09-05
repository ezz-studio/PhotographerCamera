#!/usr/bin/env python3
"""Phase 22 — Desktop Profile Viewer (data-ready, zero extra GUI deps).

A lightweight, fully runnable viewer built on matplotlib (no Qt required for
early validation; can be migrated to PySide later). Capabilities:
  - load image + PhotographerProfile
  - Original / Simulated / Split-Before-After
  - RGB + luminance histogram
  - Tone curve overlay
  - live parameter sliders (exposure/wb/highlight/shadow/vignette/grain/sharpen)
  - save modified Profile
  - batch render + validation report

Usage:
  python tools/desktop_viewer.py --image img.jpg --profile profiles/photographer_a.json
  python tools/desktop_viewer.py --batch <dir> --profile P.json --out <dir>
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from profile_renderer import render, render_file  # noqa: E402
from profile_schema import load_schema, validate_profile, default_profile  # noqa: E402

SLIDERS = [
    ("exposure.bias", ("exposure", "bias"), -2.0, 2.0, 0.0),
    ("white_balance.temperature_bias", ("white_balance", "temperature_bias"), -1.0, 1.0, 0.0),
    ("white_balance.tint_bias", ("white_balance", "tint_bias"), -1.0, 1.0, 0.0),
    ("highlight_rolloff.strength", ("highlight_rolloff", "strength"), 0.0, 1.0, 0.0),
    ("shadow.black_point", ("shadow", "black_point"), 0.0, 0.2, 0.0),
    ("vignette.amount", ("vignette", "amount"), 0.0, 1.0, 0.0),
    ("sharpen.amount", ("sharpen", "amount"), 0.0, 1.0, 0.0),
    ("grain.amount", ("grain", "amount"), 0.0, 1.0, 0.0),
]


def _set(profile, keys, value):
    d = profile
    for k in keys[:-1]:
        d = d.setdefault(k, {})
    d[keys[-1]] = value


def _get(profile, keys, default):
    d = profile
    for k in keys:
        if not isinstance(d, dict) or k not in d:
            return default
        d = d[k]
    return d


def _load_rgb(path):
    img = Image.open(path).convert("RGB")
    return np.asarray(img, dtype=np.float32) / 255.0


def batch_render(input_dir: str, profile_path: str, out_dir: str) -> dict:
    from dataset_loader import discover_images

    with open(profile_path, "r", encoding="utf-8") as f:
        profile = json.load(f)
    ok, errors = validate_profile(profile)
    if not ok:
        raise SystemExit(f"Profile invalid: {errors}")
    os.makedirs(out_dir, exist_ok=True)
    paths = discover_images(input_dir)
    report = {"schema": "batch_render/v1", "profile": profile_path, "outputs": []}
    for p in paths:
        out = os.path.join(out_dir, "sim_" + os.path.basename(p))
        render_file(p, profile, out)
        report["outputs"].append({"source": p, "output": out})
    with open(os.path.join(out_dir, "batch_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"Batch rendered {len(paths)} images -> {out_dir}")
    return report


def interactive_viewer(image_path: str, profile_path: str) -> None:
    import matplotlib
    matplotlib.use("Agg")  # safe for headless; swap to TkAgg/QtAgg when GUI available
    import matplotlib.pyplot as plt
    from matplotlib.widgets import Slider, Button

    with open(profile_path, "r", encoding="utf-8") as f:
        profile = json.load(f)
    orig = _load_rgb(image_path)
    sim = render(orig, profile)

    fig = plt.figure(figsize=(13, 9))
    ax_orig = fig.add_axes([0.05, 0.55, 0.27, 0.35])
    ax_sim = fig.add_axes([0.37, 0.55, 0.27, 0.35])
    ax_hist = fig.add_axes([0.70, 0.55, 0.27, 0.35])
    ax_curve = fig.add_axes([0.05, 0.30, 0.40, 0.18])
    ax_split = fig.add_axes([0.55, 0.30, 0.40, 0.18])

    ax_orig.imshow(orig)
    ax_orig.set_title("Original")
    ax_orig.axis("off")
    im_sim = ax_sim.imshow(sim)
    ax_sim.set_title("Simulated")
    ax_sim.axis("off")

    def refresh():
        nonlocal sim
        sim = render(orig, profile)
        im_sim.set_data(sim)
        # histograms
        ax_hist.clear()
        for i, c in enumerate("rgb"):
            ax_hist.hist(sim[..., i].flatten(), bins=64, range=(0, 1), color=c, alpha=0.5, histtype="step")
        ax_hist.hist(sim.mean(axis=2).flatten(), bins=64, range=(0, 1), color="k", alpha=0.3, histtype="step")
        ax_hist.set_title("Simulated histogram")
        # tone curve
        ax_curve.clear()
        pts = profile.get("tone_curve", {}).get("points", [[0, 0], [1, 1]])
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        ax_curve.plot(xs, ys, "b-", marker="o")
        ax_curve.plot([0, 1], [0, 1], "k--", alpha=0.4)
        ax_curve.set_title("Tone curve")
        ax_curve.set_xlim(0, 1)
        ax_curve.set_ylim(0, 1)
        # split before/after (left original, right sim)
        ax_split.clear()
        combo = np.concatenate([orig, sim], axis=1)
        ax_split.imshow(combo)
        ax_split.set_title("Split: Original | Simulated")
        ax_split.axis("off")
        fig.canvas.draw_idle()

    refresh()

    # sliders
    slider_widgets = []
    for i, (label, keys, lo, hi, _def) in enumerate(SLIDERS):
        axs = fig.add_axes([0.30, 0.22 - i * 0.028, 0.45, 0.02])
        cur = _get(profile, keys, _def)
        s = Slider(axs, label, lo, hi, valinit=cur)
        s.on_changed(lambda val, k=keys: (_set(profile, k, float(val)), refresh()))
        slider_widgets.append(s)

    # save button
    ax_save = fig.add_axes([0.80, 0.10, 0.15, 0.05])
    btn = Button(ax_save, "Save Profile")

    def on_save(_):
        out = profile_path
        with open(out, "w", encoding="utf-8") as f:
            json.dump(profile, f, indent=2, ensure_ascii=False)
        print(f"Saved profile -> {out}")

    btn.on_clicked(on_save)

    # default profile button (reset)
    ax_reset = fig.add_axes([0.80, 0.17, 0.15, 0.05])
    btn_r = Button(ax_reset, "Reset to identity")

    def on_reset(_):
        nonlocal profile
        profile = default_profile(profile.get("name", "Photographer"))
        for (label, keys, lo, hi, _def), s in zip(SLIDERS, slider_widgets):
            s.set_val(_get(profile, keys, _def))
        refresh()

    btn_r.on_clicked(on_reset)

    plt.show()


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Phase 22 desktop profile viewer")
    p.add_argument("--image", help="single image to view")
    p.add_argument("--profile", required=True)
    p.add_argument("--batch", help="batch-render a directory of images")
    p.add_argument("--out", help="output dir for batch render")
    args = p.parse_args(argv)
    if args.batch:
        batch_render(args.batch, args.profile, args.out or (args.batch + "_sim"))
    elif args.image:
        interactive_viewer(args.image, args.profile)
    else:
        print("Provide --image <img> (interactive) or --batch <dir> (batch render).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
