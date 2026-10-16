#!/usr/bin/env python3
"""Re-train a Studio LUT profile with the *relative-luma* fix.

For each profile:  T = the photographer's graded reference folder (style target)
                   S = the un-graded DNG originals (input distribution baseline)
The learned LUT keeps the film's colour + RELATIVE tone shape (toe lift,
S-curve, shoulder) but drops the absolute brightness level shift that caused
the "泛白 / 无暗部" wash (and, when neutralised, the lost film feel).

Usage:
  python tools/retrain_relative.py --all
  python tools/retrain_relative.py --name 柯达5207 --graded "D:/下载/柯达5207"
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "stylefit"))

import profile_renderer as R  # noqa: E402
from stylefit import fit as sfit  # noqa: E402
from stylefit import lut3d as L3, color as C  # noqa: E402

PLAIN_DIR = "D:/下载/DNG原图检测图/未命名导出"
STUDIO_DIR = os.path.join(ROOT, "dist/profiles/studio")
BACKUP_DIR = os.path.join(ROOT, "dist/profiles/_relative_retrain_backup")

PROFILES = {
    "柯达5207": "D:/下载/柯达5207",
    "理光GR3X": "D:/下载/理光GR3X",
    "哈苏X2D": "D:/下载/X2D",
    "GR4 HDF": "D:/下载/GR4 HDF",
    "Nickshih": "D:/下载/nickshih",
}

STRENGTH = 0.8
LEVEL_KEEP = 0.0  # 0.0 = fully relative; raise for films whose airiness is part of the look


def srgb_to_lin(c):
    c = np.asarray(c, np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def luma(rgb):
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def lstar(rgb):
    lin = srgb_to_lin(np.clip(np.asarray(rgb, np.float32), 0, 1))
    y = luma(lin)
    fy = np.where(y > 0.008856, np.cbrt(y), 7.787 * y + 16.0 / 116.0)
    return 116.0 * fy - 16.0


def gray_lift(lut, pts=(0.2, 0.35, 0.5, 0.65, 0.8)):
    ds = []
    for g in pts:
        cin = np.full((1, 1, 3), g, np.float32)
        out = L3.sample(lut, cin)[0, 0]
        ds.append(float(lstar(out) - lstar(np.array([g, g, g]))))
    return float(np.mean(ds))


def validate(prof, plain_paths, out_img, max_side=1000):
    lut3d = R._load_lut3d()
    lut = R._decode_lut(prof["color_lut"])
    paths = plain_paths[:40]
    nat, ren = [], []
    for p in paths:
        im = Image.open(p).convert("RGB")
        m = max(im.size)
        if m > max_side:
            s = max_side / m
            im = im.resize((max(1, int(im.size[0] * s)), max(1, int(im.size[1] * s))),
                           Image.Resampling.LANCZOS)
        im = np.asarray(im, np.float32) / 255.0
        nat.append(im)
        ren.append(np.clip(L3.sample(lut, im), 0.0, 1.0))
    nat = np.concatenate([x.reshape(-1, 3) for x in nat])
    ren = np.concatenate([x.reshape(-1, 3) for x in ren])
    ln, lr = luma(srgb_to_lin(nat)), luma(srgb_to_lin(ren))
    sn = np.mean(np.abs(nat - nat.mean(axis=0)), axis=1).mean()
    sr = np.mean(np.abs(ren - ren.mean(axis=0)), axis=1).mean()
    print(f"    DNG 验证集（{len(paths)} 张，已缩至≤{max_side}px 验证）:")
    print(f"      原生   meanL={ln.mean():.3f} p50={np.percentile(ln,50):.3f} "
          f"p90={np.percentile(ln,90):.3f} dark%={(ln<0.1).mean()*100:5.2f} sat≈{sn:.4f}")
    print(f"      相对LUT meanL={lr.mean():.3f} p50={np.percentile(lr,50):.3f} "
          f"p90={np.percentile(lr,90):.3f} dark%={(lr<0.1).mean()*100:5.2f} sat≈{sr:.4f}")
    de = float(np.mean([C.delta_e76(a, b)[0] for a, b in zip(nat, ren)]))
    print(f"      平均 ΔE76 = {de:.2f}  (胶片色+相对影调已施加，亮度随底片自身)")
    print(f"      灰轴平均亮度偏移 = {gray_lift(lut):+.2f} L*  (≈0 = 相对模式生效)")
    # visual: 3 representative panels native | relative
    sel = paths[:3]
    cols = []
    for p in sel:
        im = Image.open(p).convert("RGB")
        m = max(im.size)
        if m > 520:
            s = 520 / m
            im = im.resize((max(1, int(im.size[0] * s)), max(1, int(im.size[1] * s))),
                           Image.Resampling.LANCZOS)
        im = np.asarray(im, np.float32) / 255.0
        ap = np.clip(L3.sample(lut, im), 0.0, 1.0)
        cols.append(np.hstack([im, ap]))
    panel = (np.vstack(cols) * 255).astype("uint8")
    Image.fromarray(panel).save(out_img)
    print(f"      对照图已存: {out_img}")


def merge_keep_old(new_prof, old_prof):
    if not old_prof:
        return new_prof
    for k in ("icon", "display_name", "uid", "name", "category", "tags"):
        if k in old_prof:
            new_prof[k] = old_prof[k]
    return new_prof


def process_one(name, graded_dir, plain_dir, level_keep=LEVEL_KEEP):
    print(f"\n===== {name} =====")
    out_json = os.path.join(STUDIO_DIR, f"{name}.json")
    old_prof = None
    if os.path.exists(out_json):
        old_prof = json.load(open(out_json, encoding="utf-8"))
    refs = sorted(glob.glob(os.path.join(graded_dir, "*")))
    refs = [p for p in refs if os.path.splitext(p)[1].lower()
            in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff", ".heic"}]
    plains = sorted(glob.glob(os.path.join(plain_dir, "*")))
    plains = [p for p in plains if os.path.splitext(p)[1].lower()
              in {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff", ".heic"}]
    print(f"  参考片(T) {len(refs)} 张 | 未调色(S) {len(plains)} 张")
    prof, rep = sfit(refs, name=name, plain_paths=plains, strength=STRENGTH,
                     relative_luma=True, luma_level_keep=level_keep, seed=20240917)
    prof = merge_keep_old(prof, old_prof)
    prof["color_lut"]["source"] = ("stylefit/v3.1-relative-luma "
                                   f"(level_keep={level_keep})")
    prof["color_lut"]["strength"] = float(STRENGTH)
    os.makedirs(BACKUP_DIR, exist_ok=True)
    if old_prof:
        import shutil
        shutil.copy2(out_json, os.path.join(BACKUP_DIR, f"{name}.json"))
    json.dump(prof, open(out_json, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"  已写入: {out_json}")
    out_img = os.path.join(ROOT, "_diag/5207_cmp", f"retrain_{name}.jpg")
    validate(prof, plains, out_img)
    return prof


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name")
    ap.add_argument("--graded")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--plain", default=PLAIN_DIR)
    ap.add_argument("--level-keep", type=float, default=LEVEL_KEEP)
    a = ap.parse_args()
    if a.all:
        for name, g in PROFILES.items():
            process_one(name, g, a.plain, a.level_keep)
    elif a.name and a.graded:
        process_one(a.name, a.graded, a.plain, a.level_keep)
    else:
        ap.error("pass --all or --name + --graded")


if __name__ == "__main__":
    main()
