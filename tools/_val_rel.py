#!/usr/bin/env python3
import json, glob, sys
import numpy as np
from PIL import Image
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import profile_renderer as R
from stylefit import lut3d as L3, color as C

def s2l(c):
    c = np.asarray(c, np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)

def luma(rgb):
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]

def lstar(rgb):
    y = luma(s2l(np.clip(rgb, 0, 1)))
    fy = np.where(y > 0.008856, np.cbrt(y), 7.787 * y + 16.0 / 116.0)
    return 116.0 * fy - 16.0

name = sys.argv[1] if len(sys.argv) > 1 else "柯达5207"
prof = json.load(open(f"dist/profiles/studio/{name}.json", encoding="utf-8"))
lut = R._decode_lut(prof["color_lut"])
print(f"[{name}] source={prof['color_lut'].get('source')} strength={prof['color_lut'].get('strength')}", flush=True)
print("  灰轴 dL*:")
for g in [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
    o = L3.sample(lut, np.full((1, 1, 3), g, np.float32))[0, 0]
    print(f"    gray {g}: inL*={lstar([g,g,g]):.1f} outL*={lstar(o):.1f} dL*={lstar(o)-lstar([g,g,g]):+.1f}", flush=True)
plains = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))[:20]
nat, ren = [], []
for p in plains:
    im = Image.open(p).convert("RGB")
    m = max(im.size)
    if m > 800:
        s = 800 / m
        im = im.resize((max(1, int(im.size[0]*s)), max(1, int(im.size[1]*s))), Image.Resampling.LANCZOS)
    im = np.asarray(im, np.float32) / 255.0
    nat.append(im); ren.append(np.clip(L3.sample(lut, im), 0, 1))
nat = np.concatenate([x.reshape(-1, 3) for x in nat])
ren = np.concatenate([x.reshape(-1, 3) for x in ren])
ln, lr = luma(s2l(nat)), luma(s2l(ren))
print(f"  DNG集 原生  meanL={ln.mean():.3f} p50={np.percentile(ln,50):.3f} p90={np.percentile(lr,90):.3f} dark%={(ln<0.1).mean()*100:5.2f}", flush=True)
print(f"  DNG集 {name}相对 meanL={lr.mean():.3f} p50={np.percentile(lr,50):.3f} p90={np.percentile(lr,90):.3f} dark%={(lr<0.1).mean()*100:5.2f}", flush=True)
de = float(np.mean([C.delta_e76(a, b)[0] for a, b in zip(nat, ren)]))
print(f"  平均ΔE76={de:.2f}", flush=True)
sel = plains[:3]; cols = []
for p in sel:
    im = Image.open(p).convert("RGB"); m = max(im.size)
    if m > 520:
        s = 520 / m
        im = im.resize((max(1, int(im.size[0]*s)), max(1, int(im.size[1]*s))), Image.Resampling.LANCZOS)
    im = np.asarray(im, np.float32) / 255.0
    ap = np.clip(L3.sample(lut, im), 0, 1)
    cols.append(np.hstack([im, ap]))
Image.fromarray((np.vstack(cols) * 255).astype("uint8")).save(f"_diag/5207_cmp/retrain_{name}.jpg")
print(f"  panel -> _diag/5207_cmp/retrain_{name}.jpg", flush=True)
