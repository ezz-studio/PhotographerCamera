#!/usr/bin/env python3
"""Apply the film-feel-preserving wash fix to the LUT profiles.

Keep the film's colour + (most of) its tonal lift -- the "film feel" -- but:
  * reduce the absolute lift slightly (k = keep fraction, 1 = original/washy), and
  * add a highlight SHOULDER so the brightest pixels roll off instead of clipping
    (the actual "泛白" cause).
Source LUT = the ORIGINAL (backup if present, else the live studio file which was
never modified for GR4 HDF / Nickshih). Result is written back to studio/*.
"""
from __future__ import annotations
import argparse, glob, json, os, sys
import numpy as np
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import profile_renderer as R
from stylefit import lut3d as L3

STUDIO = "dist/profiles/studio"
BACKUP = "dist/profiles/_luma_neutral_backup"
# profiles that have a positive absolute lift -> wash-prone -> fix them.
PROFILES = ["柯达5207", "理光gr3x", "闪光灯人像", "GR4 HDF", "Nickshih"]

# True ORIGINAL LUT sources (these were never the buggy filmfix run):
#  * 3 are in _luma_neutral_backup (pre-neutralization originals)
#  * Nickshih original lives in studio_session/imports
#  * GR4 HDF original lives in a studio_* training run (v3, never washy: -1.11 lift)
ORIGINS = {
    "柯达5207":   "dist/profiles/_luma_neutral_backup/柯达5207.json",
    "理光gr3x":   "dist/profiles/_luma_neutral_backup/理光gr3x.json",
    "闪光灯人像": "dist/profiles/_luma_neutral_backup/闪光灯人像.json",
    "Nickshih":   "dist/studio_session/imports/Nickshih.json",
    "GR4 HDF":    "dist/profiles/studio_1790736121215/profile_final.json",
}


def s2l(c):
    c = np.asarray(c, np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def l2s(c):
    c = np.clip(np.asarray(c, np.float32), 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * (c ** (1/2.4)) - 0.055)
def luma(rgb): return 0.2126*rgb[...,0]+0.7152*rgb[...,1]+0.0722*rgb[...,2]
def lstar(rgb):
    y=luma(s2l(np.clip(rgb,0,1))); fy=np.where(y>0.008856,np.cbrt(y),7.787*y+16/116); return 116*fy-16


def build(lut, k, shoulder):
    n = lut.shape[0]
    ax = np.linspace(0, 1, n, dtype=np.float32)
    B,G,R = np.meshgrid(ax,ax,ax,indexing="ij")
    inp = np.stack([R,G,B],-1).astype(np.float32)
    lin_in = s2l(inp); lin_out = s2l(np.clip(lut,0,1))
    # Keep fraction k of the film LUT's transformation in LINEAR light
    # (k=1 -> original/washy, k=0 -> identity). Preserves film colour + tonal
    # character at strength k; the old kk=tgt/Lin multiply re-amplified lift.
    blended = (1.0 - k) * lin_in + k * lin_out
    if shoulder > 0:
        s = float(shoulder)
        Lb = luma(blended)
        m = Lb > s
        if np.any(m):
            # hue-preserving highlight SHOULDER: compress luma above s back down
            comp = s + (1.0 - s) * np.clip((Lb[m] - s) / (1.0 - s), 0.0, 1.0) ** 2.4
            f = comp / Lb[m]
            blended[m] = blended[m] * f[..., None]
    return np.clip(l2s(np.clip(blended, 0, 1)), 0, 1).astype(np.float32)


def gray_lift(lut):
    return float(np.mean([lstar(L3.sample(lut,np.full((1,1,3),g,np.float32))[0,0])-lstar([g,g,g])
                         for g in [0.2,0.5,0.8]]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--k", type=float, default=1.0,
                    help="keep fraction of film transform; 1.0 = keep 100%% film feel (recommended), "
                         "<1 also weakens the lift (user rejected darkening -> keep 1.0)")
    ap.add_argument("--shoulder", type=float, default=0.82)
    a = ap.parse_args()
    for name in PROFILES:
        live = os.path.join(STUDIO, f"{name}.json")
        src_path = ORIGINS[name]                           # TRUE ORIGINAL lut
        base = json.load(open(live, encoding="utf-8"))     # has icon/name
        src = json.load(open(src_path, encoding="utf-8"))
        lut = R._decode_lut(src["color_lut"])
        new = build(lut, a.k, a.shoulder)
        payload = L3.to_payload(new)
        base["color_lut"]["data"] = payload["data"]
        base["color_lut"]["size"] = payload.get("size", base["color_lut"].get("size"))
        base["color_lut"]["source"] = (f"stylefit/v3.1-filmfix k={a.k} shoulder={a.shoulder}")
        base["color_lut"]["strength"] = float(base["color_lut"].get("strength", 0.8))
        base.pop("luma_neutralized", None)
        json.dump(base, open(live, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        print(f"{name:10s} 原版灰轴偏移≈{gray_lift(lut):+.2f}L* -> 修复后≈{gray_lift(new):+.2f}L* "
              f" | shoulder={a.shoulder} k={a.k} | 已写回 {live}")


if __name__ == "__main__":
    main()
