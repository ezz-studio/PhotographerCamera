#!/usr/bin/env python3
"""Visual A/B panel: original | old studio LUT (absolute+shoulder filmfix)
| NEW exposure-relative trained LUT.  Rows 1-3 dark (as-is), rows 4-6 x1.6."""
import glob, json, sys
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import numpy as np
from PIL import Image
from stylefit import color as C, lut3d as L3
from stylefit.fit import _load, _sample_pixels, FIT_PIXELS_PER_IMAGE
import stylefit.transfer as TR

refs = sorted(glob.glob("D:/下载/柯达5207/*"))
plains = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))
rng = np.random.default_rng(20240917)
graded = [np.clip(_load(p, 320), 0, 1) for p in refs]
plains_im = [np.clip(_load(p, 320), 0, 1) for p in plains]
dst_px = np.concatenate([_sample_pixels(x, FIT_PIXELS_PER_IMAGE, rng) for x in graded])
src_px = np.concatenate([_sample_pixels(x, FIT_PIXELS_PER_IMAGE, rng) for x in plains_im])
m, _ = TR.learn(src_px, dst_px, strength=0.8)
lut_new = TR.bake(m, 33)

old = json.load(open("dist/profiles/_luma_neutral_backup/柯达5207.json", encoding="utf-8"))
lut_old = L3.from_payload(old["color_lut"])   # 真原版（未肩压）= 旧行为的代表

sel = plains[:6]
rows = []
def to360(a):
    return np.asarray(Image.fromarray((np.clip(a, 0, 1) * 255).astype("uint8"))
                      .resize((360, 360), Image.Resampling.LANCZOS), np.float32) / 255
for i, p in enumerate(sel):
    im = Image.open(p).convert("RGB"); mm = max(im.size)
    if mm > 800:
        im = im.resize((int(im.size[0] * 800 / mm), int(im.size[1] * 800 / mm)),
                       Image.Resampling.LANCZOS)
    a = np.asarray(im, np.float32) / 255.0
    src = np.clip(a * 1.6, 0, 1) if i >= 3 else a
    rows.append(np.hstack([to360(src), to360(L3.sample(lut_old, src)),
                           to360(L3.sample(lut_new, src))]))
Image.fromarray((np.vstack(rows) * 255).astype("uint8")).save(
    "_diag/5207_cmp/exporel_AB.jpg", quality=90)
print("panel -> _diag/5207_cmp/exporel_AB.jpg (列=原图|旧LUT|新训练; 行1-3暗图,4-6=x1.6)")
