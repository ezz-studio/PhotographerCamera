"""量化误差 vs 风格差异 的量级对照实验。

目的：回答"用 8bit sRGB 做验证集，会不会因色彩信息少而引入显著误差"。

做法（对每张真实照片 X，float32，视为真值）：
  f0      = features(X)                         基准
  f_q     = features(X + U(-0.5/255, 0.5/255))  8bit 量化误差上界（±0.5 LSB）
  f_jpeg  = features(JPEG q=92 往返后)          8bit + 压缩伪影
  f_style = features(render(X, demo_profile))   一个真实风格带来的特征变化
分别算 loss(f_*, f0)，比较量级。
"""
from __future__ import annotations

import glob
import io
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "tools"))

import loss_function as L  # noqa: E402
import profile_renderer as R  # noqa: E402


def load(path, max_side=512):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    s = max_side / max(w, h)
    if s < 1:
        im = im.resize((max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS)
    return np.asarray(im, dtype=np.float32) / 255.0


def jpeg_roundtrip(x, q=92):
    buf = io.BytesIO()
    Image.fromarray((np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8)).save(buf, "JPEG", quality=q)
    buf.seek(0)
    return np.asarray(Image.open(buf).convert("RGB"), dtype=np.float32) / 255.0


def main():
    src_dir = os.path.join(ROOT, "studio_session", "selection")
    paths = sorted(glob.glob(os.path.join(src_dir, "*.jpg")))[:8]
    if not paths:
        print("no images"); return 1

    prof_path = os.path.join(ROOT, "profiles", "demo", "profile_final.json")
    profile = None
    if os.path.isfile(prof_path):
        import json
        with open(prof_path, encoding="utf-8") as f:
            profile = json.load(f)
        print(f"profile: {prof_path}")
    else:
        print("no demo profile -> skip style baseline")

    rng = np.random.default_rng(0)
    print(f"\n{'image':<12}{'quant(±0.5LSB)':>18}{'jpeg q92':>14}{'jpeg q75':>14}{'style':>14}{'ratio%':>10}")
    print("-" * 82)

    rows = []
    for p in paths:
        x = load(p)
        f0 = L.extract_features(x)

        dq = L.loss(f0, L.extract_features(np.clip(x + rng.uniform(-0.5 / 255, 0.5 / 255, x.shape), 0, 1)))[0]
        dj92 = L.loss(f0, L.extract_features(jpeg_roundtrip(x, 92)))[0]
        dj75 = L.loss(f0, L.extract_features(jpeg_roundtrip(x, 75)))[0]
        ds = L.loss(f0, L.extract_features(R.render(x, profile or {}, seed=0)))[0] if profile else float("nan")

        ratio = (dq / ds * 100) if (ds and ds > 0) else float("nan")
        rows.append((dq, dj92, dj75, ds))
        print(f"{os.path.basename(p)[:10]:<12}{dq:>18.6f}{dj92:>14.6f}{dj75:>14.6f}{ds:>14.6f}{ratio:>9.2f}%")

    a = np.array(rows, dtype=np.float64)
    print("-" * 82)
    print(f"{'MEAN':<12}{a[:,0].mean():>18.6f}{a[:,1].mean():>14.6f}{a[:,2].mean():>14.6f}{a[:,3].mean():>14.6f}"
          f"{a[:,0].mean()/a[:,3].mean()*100:>9.2f}%")
    print("\n说明：quant = 8bit 量化误差引入的 loss 上界；style = 一个真实风格带来的 loss。")
    print("若 quant 比 style 小 1~2 个数量级，则 8bit 精度不是误差瓶颈。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
