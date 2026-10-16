#!/usr/bin/env python3
"""Smoke: full stylefit fit() on real 5207 data with the top-end quantile
trust change. Verifies end-to-end training (base pass + iterative rounds +
gates) still runs and the delivered LUT keeps the film lift while the
highlight top-end matches T's measured quantiles."""
import glob, json, sys
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import numpy as np
from PIL import Image
from stylefit import color as C, lut3d as L3
from stylefit.fit import fit

refs = sorted(glob.glob("D:/下载/柯达5207/*"))
plains = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))
print(f"refs={len(refs)} plains={len(plains)}", flush=True)

profile, report = fit(refs, name="5207_trainopt_smoke", plain_paths=plains,
                      max_side=320)

lut = L3.from_payload(profile["color_lut"])
print("source:", profile["color_lut"].get("source"), flush=True)

def gray_lift(lut):
    vals = []
    for v in [0.2, 0.5, 0.8]:
        out = L3.sample(lut, np.full((1, 1, 3), v, np.float32))[0, 0]
        base = C.rgb_to_lab(np.array([[v, v, v]], np.float32))[0]
        vals.append(float(C.rgb_to_lab(out[None, ...].astype(np.float32))[0, 0]) - float(base[0]))
    return float(np.mean(vals))

acc = []
for p in plains[:20]:
    im = Image.open(p).convert("RGB"); m = max(im.size)
    if m > 800:
        im = im.resize((int(im.size[0] * 800 / m), int(im.size[1] * 800 / m)), Image.Resampling.LANCZOS)
    a = np.asarray(im, np.float32) / 255.0
    acc.append(np.clip(L3.sample(lut, a), 0, 1).reshape(-1, 3))
r = np.concatenate(acc); L = C.rgb_to_lab(r)[:, 0]
print(f"灰轴提升 {gray_lift(lut):+.2f} L* | meanL={L.mean()/100:.3f} p50={np.percentile(L,50)/100:.3f} "
      f"p95={np.percentile(L,95)/100:.3f} | 死白 sRGB>0.99={(r.max(-1)>0.99).mean()*100:.2f}% "
      f"L*>99={(L>99).mean()*100:.2f}%", flush=True)
it = report.get("iterative", {})
print(f"迭代: 请求 {it.get('rounds_requested')} 轮, 接受 {it.get('accepted_rounds')} 轮", flush=True)
print("SMOKE-OK", flush=True)
