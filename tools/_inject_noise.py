#!/usr/bin/env python3
"""为 studio_test_neural 的 5 个 profile 注入真实 noise 估计（2026-10-16 指令）。

旧 profile 的 noise 字段是 0.10.x 归零值；本脚本从各训练语料重新估计
亮度/色度高频残差并写入 noise.luma / noise.chroma（schema 0..1）。
只改 noise 两个字段，不动 LUT 与其他层。
"""
import json
import os
import sys

sys.path.insert(0, "tools")
sys.path.insert(0, "tools/stylefit")
import numpy as np
from stylefit.fit import discover_images, _load, estimate_noise

PLAIN_UNUSED = None
PROFILES = {
    "柯达5207": "D:/下载/柯达5207",
    "理光gr3x": "D:/下载/理光GR3X",
    "GR4 HDF": "D:/下载/GR4 HDF",
    "Nickshih": "D:/下载/nickshih",
    "哈苏X2D": "D:/下载/X2D",
}
OUT = "dist/profiles/studio_test_neural"
MAX_SIDE = 320


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, ref_dir in PROFILES.items():
        if only and name != only:
            continue
        fp = os.path.join(OUT, f"{name}.json")
        if not os.path.exists(fp):
            print(f"[{name}] MISSING {fp}")
            continue
        refs = discover_images(ref_dir)
        graded = []
        for p in refs:
            try:
                graded.append(np.clip(_load(p, MAX_SIDE), 0.0, 1.0))
            except Exception:
                pass
        n_luma, n_chroma = estimate_noise(graded[:150])
        prof = json.load(open(fp, encoding="utf-8"))
        old = (prof.get("noise") or {})
        prof["noise"] = {"luma": round(float(n_luma), 4), "chroma": round(float(n_chroma), 4)}
        with open(fp, "w", encoding="utf-8") as f:
            json.dump(prof, f, ensure_ascii=False)
        print(f"[{name}] noise {old} -> luma={n_luma:.4f} chroma={n_chroma:.4f}", flush=True)
    print("ALL-DONE")


if __name__ == "__main__":
    main()
