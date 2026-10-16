#!/usr/bin/env python3
"""域外安全重精修：从旧版(studio)解析 LUT 出发，用升级版神经精修
（中性轴色度帽 + TV 5×）重做 5 个 profile，替换 studio_test_neural。

背景（2026-10-16 用户反馈「颜色杂乱」）：旧神经精修 (TV 2e-4, 无中性约束)
把中性轴染色放大 1.3~3 倍、栅格粗糙度 +20~50%、低饱和色相突跳 32°，
普通图片（灰天空/皮肤/白墙）出现洋红/绿色杂斑。

门控：分布距离改善 + 死端不回归 + 域外安全（中性染色/粗糙度不回归）。
任一门不过 → 输出旧版 LUT（宁缺毋滥）。
"""
import json
import os
import sys
import time

sys.path.insert(0, "tools")
sys.path.insert(0, "tools/stylefit")
import numpy as np
from stylefit import lut3d as L3
from stylefit import color as C
from stylefit import neural_refine as NR
from stylefit.neural_refine import refine_lut, _w2_1d, _ab_band_w2
from stylefit.fit import discover_images, _load, _sample_from_images

PLAIN = "D:/下载/DNG原图检测图/未命名导出"
OLD = "dist/profiles/studio"
OUT = "dist/profiles/studio_test_neural"
PROFILES = {
    "柯达5207": "D:/下载/柯达5207",
    "理光gr3x": "D:/下载/理光GR3X",
    "GR4 HDF": "D:/下载/GR4 HDF",
    "Nickshih": "D:/下载/nickshih",
    "哈苏X2D": "D:/下载/X2D",
}
MAX_SIDE = 320


def lowsat_ring_chroma(lut):
    """s=0.06 低饱和色相环的输出色度峰值（域外杂乱探针）。"""
    h = np.arange(360) / 360.0
    i = ((h * 6).astype(int)) % 6
    f = h * 6 - np.floor(h * 6)
    v = np.full(360, 0.5, np.float32)
    s = np.full(360, 0.06, np.float32)
    p = v * (1 - s); q = v * (1 - f * s); t = v * (1 - (1 - f) * s)
    ring = np.zeros((360, 3), np.float32)
    for k, tri in enumerate([np.stack([v, t, p], -1), np.stack([q, v, p], -1),
                             np.stack([p, v, t], -1), np.stack([p, q, v], -1),
                             np.stack([t, p, v], -1), np.stack([v, p, q], -1)]):
        ring[i == k] = tri[i == k]
    o = L3.sample(lut, ring)
    return float((o.max(-1) - o.min(-1)).max())


def main():
    os.makedirs(OUT, exist_ok=True)
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, ref_dir in PROFILES.items():
        if only and name != only:
            continue
        t0 = time.time()
        print(f"\n{'=' * 60}\n[{name}] refs={ref_dir}", flush=True)
        try:
            old_prof = json.load(open(f"{OLD}/{name}.json", encoding="utf-8"))
            lut0 = L3.from_payload(old_prof["color_lut"])

            refs = discover_images(ref_dir)
            plains = discover_images(PLAIN)
            graded = [np.clip(_load(p, MAX_SIDE), 0.0, 1.0) for p in refs]
            plain_fit = []
            for p in plains:
                try:
                    plain_fit.append(np.clip(_load(p, MAX_SIDE), 0.0, 1.0))
                except Exception:
                    pass
            rng = np.random.default_rng(20240917)
            if len(graded) > 260:
                idx = np.sort(rng.choice(len(graded), 260, replace=False))
                graded = [graded[int(i)] for i in idx]
            dst_px = _sample_from_images(graded, 8000, rng)
            src_px = _sample_from_images(plain_fit, 60000, rng)
            print(f"[{name}] 语料: refs={len(graded)} plains={len(plain_fit)} "
                  f"dst={dst_px.shape} src={src_px.shape} ({time.time()-t0:.0f}s)", flush=True)

            lut_nr, rep = refine_lut(lut0, src_px, dst_px, steps=90, verbose=False)
            print(f"[{name}] refine done iters={rep['iters']} ({time.time()-t0:.0f}s)", flush=True)

            # ---- 门控
            evals = plain_fit[:40]
            def _dead(lx):
                P = np.concatenate([L3.sample(lx, a).reshape(-1, 3) for a in evals])
                nd = P.max(-1) <= 0.99
                nb = P.min(-1) >= 0.01
                return ((P.max(-1)[nd] > 0.99).mean(), (P.min(-1)[nb] < 0.01).mean())
            def _dist(lx):
                P = np.concatenate([L3.sample(lx, a).reshape(-1, 3) for a in evals])
                r = np.random.default_rng(5)
                nsub = min(P.shape[0], dst_px.shape[0], 120000)
                P = P[r.choice(P.shape[0], nsub, replace=False)]
                Q = dst_px[r.choice(dst_px.shape[0], nsub, replace=False)]
                lp, ap, bp = (C.rgb_to_lab(P)[:, k].astype(np.float64) for k in range(3))
                lq, aq, bq = (C.rgb_to_lab(Q)[:, k].astype(np.float64) for k in range(3))
                l1, _ = _w2_1d(lp, lq)
                l2, _, _ = _ab_band_w2(lp, ap, bp, lq, aq, bq)
                return float(l1 + l2)

            dw0, db0 = _dead(lut0); dw1, db1 = _dead(lut_nr)
            s0, s1 = _dist(lut0), _dist(lut_nr)
            n0, n1 = NR._neutral_chroma(lut0), NR._neutral_chroma(lut_nr)
            r0, r1 = NR._lattice_roughness(lut0), NR._lattice_roughness(lut_nr)
            g0, g1 = lowsat_ring_chroma(lut0), lowsat_ring_chroma(lut_nr)
            dead_ok = dw1 <= dw0 + 5e-4 and db1 <= db0 + 5e-4
            domain_ok = (n1 <= n0 + 0.01) and (r1 <= r0 * 1.25 + 5e-4) and (g1 <= g0 + 0.02)
            dist_ok = s1 < s0 * 0.995
            print(f"[{name}] 门控: dist {s0:.3f}->{s1:.3f} ({'OK' if dist_ok else 'X'}) "
                  f"dead ({dw0*100:.2f},{db0*100:.2f})->({dw1*100:.2f},{db1*100:.2f}) "
                  f"({'OK' if dead_ok else 'X'}) 中性 {n0:.3f}->{n1:.3f} "
                  f"粗糙 {r0:.4f}->{r1:.4f} 色环 {g0:.3f}->{g1:.3f} "
                  f"({'OK' if domain_ok else 'X'})", flush=True)

            if dead_ok and domain_ok and dist_ok:
                lut, tag, why = lut_nr, "+neural2", "refined"
            else:
                lut, tag, why = lut0, "+neural2-gated-out", "revert-to-analytic"
            out = json.loads(json.dumps(old_prof))
            payload = L3.to_payload(lut)
            payload["source"] = payload.get("source", "") + tag
            payload["strength"] = old_prof["color_lut"].get("strength", 0.8)
            out["color_lut"] = payload
            with open(f"{OUT}/{name}.json", "w", encoding="utf-8") as f:
                json.dump(out, f, ensure_ascii=False)
            print(f"[{name}] saved ({why}) {OUT}/{name}.json 总用时 {time.time()-t0:.0f}s", flush=True)
        except Exception as e:
            import traceback
            print(f"[{name}] FAILED: {e}\n{traceback.format_exc()}", flush=True)
    print("\nALL-DONE", flush=True)


if __name__ == "__main__":
    main()
