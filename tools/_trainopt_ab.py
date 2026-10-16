#!/usr/bin/env python3
"""Controlled A/B: full stylefit training on identical data+seed, with the
top-end quantile trust OFF (legacy) vs ON (1.5 L*). Only the module constant
differs — everything else (sampling, iterative rounds, gates) is identical."""
import glob, sys
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import numpy as np
from PIL import Image
from stylefit import color as C, lut3d as L3
import stylefit.transfer as TR
from stylefit.fit import fit

refs = sorted(glob.glob("D:/下载/柯达5207/*"))
plains = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))

t = C.rgb_to_lab(np.concatenate([_px for _px in []])) if False else None
# T corpus top stats (for interpretation)
def _loadtop(paths, n=12, side=640, px=15000, seed=5):
    rng = np.random.default_rng(seed); out = []
    for p in sorted(paths)[:n]:
        im = Image.open(p).convert("RGB"); m = max(im.size)
        if m > side:
            im = im.resize((int(im.size[0]*side/m), int(im.size[1]*side/m)), Image.Resampling.LANCZOS)
        f = (np.asarray(im, np.float32)/255.0).reshape(-1, 3)
        out.append(f[rng.choice(f.shape[0], size=min(px, f.shape[0]), replace=False)])
    return np.concatenate(out)
Tpx = _loadtop(refs)
tL = C.rgb_to_lab(Tpx)[:, 0]
print(f"T语料: p99={np.percentile(tL,99):.1f} p99.9={np.percentile(tL,99.9):.1f} max={tL.max():.1f} "
      f"sRGB>0.99={(Tpx.max(-1)>0.99).mean()*100:.2f}%", flush=True)

def run(tag):
    profile, report = fit(refs, name=f"5207_ab_{tag}", plain_paths=plains, max_side=320)
    lut = L3.from_payload(profile["color_lut"])
    vals = []
    for v in [0.2, 0.5, 0.8]:
        out = L3.sample(lut, np.full((1, 1, 3), v, np.float32))[0, 0]
        base = C.rgb_to_lab(np.array([[v, v, v]], np.float32))[0]
        vals.append(float(C.rgb_to_lab(out[None, ...].astype(np.float32))[0, 0]) - float(base[0]))
    gl = float(np.mean(vals))
    acc = []
    for p in plains[:20]:
        im = Image.open(p).convert("RGB"); m = max(im.size)
        if m > 800:
            im = im.resize((int(im.size[0]*800/m), int(im.size[1]*800/m)), Image.Resampling.LANCZOS)
        a = np.asarray(im, np.float32)/255.0
        acc.append(np.clip(L3.sample(lut, a), 0, 1).reshape(-1, 3))
    r = np.concatenate(acc); L = C.rgb_to_lab(r)[:, 0]
    it = report.get("iterative", {})
    print(f"[{tag}] 灰轴{gl:+.2f}L* meanL={L.mean()/100:.3f} p50={np.percentile(L,50)/100:.3f} "
          f"p95={np.percentile(L,95)/100:.3f} | sRGB>0.99={(r.max(-1)>0.99).mean()*100:.2f}% "
          f"L*>99={(L>99).mean()*100:.2f}% L*>97={(L>97).mean()*100:.2f}% | "
          f"迭代接受{it.get('accepted_rounds')}轮", flush=True)
    return lut

TR.TOP_QUANTILE_TRUST_SLACK = None    # legacy behaviour
run("旧-顶端外推")
TR.TOP_QUANTILE_TRUST_SLACK = 1.5     # new behaviour
run("新-T分位信任")
print("AB-DONE", flush=True)
