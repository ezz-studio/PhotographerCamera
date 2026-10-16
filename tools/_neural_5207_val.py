#!/usr/bin/env python3
"""Real-corpus validation: stylefit init -> neural refine -> gated metrics."""
import glob, sys, time
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import numpy as np
from PIL import Image
Image.MAX_IMAGE_PIXELS = None
from stylefit import color as C, lut3d as L3, neural_refine as NR
from stylefit.fit import _load, _sample_pixels, FIT_PIXELS_PER_IMAGE
import stylefit.transfer as TR

refs = sorted(glob.glob("D:/下载/柯达5207/*"))
plains = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))
rng = np.random.default_rng(20240917)
graded = [np.clip(_load(p, 320), 0, 1) for p in refs]
plains_im = [np.clip(_load(p, 320), 0, 1) for p in plains]
dst_px = np.concatenate([_sample_pixels(x, FIT_PIXELS_PER_IMAGE, rng) for x in graded])
src_px = np.concatenate([_sample_pixels(x, FIT_PIXELS_PER_IMAGE, rng) for x in plains_im])
print(f"corpus: refs={len(refs)} plains={len(plains)} px={src_px.shape[0]}", flush=True)

m, _ = TR.learn(src_px, dst_px, strength=0.8)
lut_init = TR.bake(m, 33)

import os
if os.path.exists("_diag/neural_ref_5207.npy"):
    lut_ref = np.load("_diag/neural_ref_5207.npy")
    print("reused cached refine result", flush=True)
else:
    t0 = time.time()
    lut_ref, rep = NR.refine_lut(lut_init, src_px, dst_px, steps=90, verbose=True)
    print(f"refine {time.time()-t0:.0f}s rep={rep}", flush=True)
    np.save("_diag/neural_ref_5207.npy", lut_ref)


def metrics(lut, tag):
    P = np.concatenate([L3.sample(lut, a).reshape(-1, 3) for a in plains_im[:20]])
    Q = dst_px
    r = np.random.default_rng(5)
    n = min(P.shape[0], Q.shape[0], 120000)
    P = P[r.choice(P.shape[0], n, replace=False)]
    Q = Q[r.choice(Q.shape[0], n, replace=False)]
    Lp = C.rgb_to_lab(P)[:, 0].astype(np.float64)
    Lt = C.rgb_to_lab(Q)[:, 0].astype(np.float64)
    Ap = C.rgb_to_lab(P)[:, 1].astype(np.float64)
    At = C.rgb_to_lab(Q)[:, 1].astype(np.float64)
    Bp = C.rgb_to_lab(P)[:, 2].astype(np.float64)
    Bt = C.rgb_to_lab(Q)[:, 2].astype(np.float64)
    lL, _ = NR._w2_1d(Lp, Lt)
    lAB, _, _ = NR._ab_band_w2(Lp, Ap, Bp, Lt, At, Bt)
    nd = P.max(-1) <= 0.99
    nb = P.min(-1) >= 0.01
    dw = (P.max(-1)[nd] > 0.99).mean() * 100
    db = (P.min(-1)[nb] < 0.01).mean() * 100
    gl = [float(C.rgb_to_lab(L3.sample(lut, np.full((1, 1, 3), v, np.float32)))[0, 0])
          - float(C.rgb_to_lab(np.full((1, 1, 3), v, np.float32))[0, 0]) for v in (0.2, 0.5, 0.8)]
    print(f"{tag:14s} W2(L)={lL:7.3f} W2(ab带)={lAB:7.3f} 死白={dw:5.2f}% 死黑={db:5.2f}% "
          f"灰轴={[round(g,2) for g in gl]}", flush=True)
    return lL, lAB


m0 = metrics(lut_init, "init(stylefit)")
m1 = metrics(lut_ref, "refined")
imp = (m0[0] - m1[0]) / max(m0[0], 1e-9) * 100
print(f"W2(L) 改善 {imp:+.1f}%", flush=True)
np.save("_diag/neural_ref_5207.npy", lut_ref)
np.save("_diag/neural_init_5207.npy", lut_init)
print("saved _diag/neural_ref_5207.npy / neural_init_5207.npy", flush=True)
