#!/usr/bin/env python3
"""End-to-end test with the user's REAL corpora: 5 training dirs + DNG plain dir.
Runs the full Studio generate chain (fit(): 12-round iterative + gated neural
refine) per profile, saves profiles to dist/profiles/studio_test_neural/."""
import glob, json, os, sys, time
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import numpy as np
from stylefit import lut3d as L3  # noqa: F401  (package __init__ rebinds `fit`)
import importlib
_fit_mod = importlib.import_module("stylefit.fit")  # the MODULE, not fit()

PLAIN = "D:/下载/DNG原图检测图/未命名导出"
PROFILES = {
    "柯达5207": "D:/下载/柯达5207",
    "理光gr3x": "D:/下载/理光GR3X",
    "GR4 HDF": "D:/下载/GR4 HDF",
    "Nickshih": "D:/下载/nickshih",
    "哈苏X2D": "D:/下载/X2D",
}
OUT = "dist/profiles/studio_test_neural"
os.makedirs(OUT, exist_ok=True)

only = sys.argv[1] if len(sys.argv) > 1 else None
for name, ref_dir in PROFILES.items():
    if only and name != only:
        continue
    t0 = time.time()
    if os.path.exists(f"{OUT}/{name}.json") and not only:
        print(f"[{name}] skip (exists)", flush=True)
        continue
    print(f"\n{'='*60}\n[{name}] refs={ref_dir}\n{'='*60}", flush=True)
    try:
        prof, rep = _fit_mod.fit_directory(ref_dir, PLAIN, name=name,
                                           plain_fit_fraction=1.0, seed=20240917)
        src = prof.get("color_lut", {}).get("source", "?")
        sd = rep.get("style_distance")
        cv = rep.get("cv", {})
        print(f"[{name}] DONE {time.time()-t0:.0f}s  source={src}  "
              f"style_distance={sd}  cv={cv.get('style_distance_mean', cv)}", flush=True)
        with open(f"{OUT}/{name}.json", "w", encoding="utf-8") as f:
            json.dump(prof, f, ensure_ascii=False)
        print(f"[{name}] saved {OUT}/{name}.json", flush=True)
    except Exception as e:
        import traceback
        print(f"[{name}] FAILED: {e}\n{traceback.format_exc()}", flush=True)
print("\nALL-DONE", flush=True)
