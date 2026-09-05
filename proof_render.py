#!/usr/bin/env python3
"""Generate authoritative before/after renders of the 6 film presets.

Uses the project's own CPU reference renderer (tools/profile_renderer.py, which
is the GLSL-equivalent reference) so the look shown here matches what the Android
GL chain produces. Renders a neutral (identity) image + each preset, then prints
per-output statistics that prove the filter is non-identity and presets differ.
"""
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "tools"))
import profile_renderer as pr  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "proof_out")
ASSETS = os.path.join(os.path.dirname(__file__), "android", "app", "src", "main", "assets", "profiles")
os.makedirs(OUT, exist_ok=True)

W, H = 720, 480
img = np.zeros((H, W, 3), dtype=np.float32)

# ---- top band: hue rainbow (full sat/val) to expose colour + saturation shifts
def hsv_to_rgb(h, s, v):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p = v * (1 - s)
    q = v * (1 - f * s)
    t = v * (1 - (1 - f) * s)
    return {(0): (v, t, p), (1): (q, v, p), (2): (p, v, t),
            (3): (p, q, v), (4): (t, p, v), (5): (v, p, q)}[i]

for x in range(W):
    h = x / W
    r, g, b = hsv_to_rgb(h, 1.0, 1.0)
    img[0:H // 4, x, 0] = r
    img[0:H // 4, x, 1] = g
    img[0:H // 4, x, 2] = b

# ---- second band: 10 vertical bars (8 hues + white + black)
bar_cols = [(1, 0, 0), (1, 0.5, 0), (1, 1, 0), (0, 1, 0), (0, 1, 1),
            (0, 0, 1), (0.5, 0, 1), (1, 0, 1), (1, 1, 1), (0, 0, 0)]
bw = W // len(bar_cols)
for bi, col in enumerate(bar_cols):
    x0 = bi * bw
    img[H // 4: H // 2, x0:x0 + bw] = col

# ---- bottom half: left->right luminance ramp (tone curve / contrast) + patches
for x in range(W):
    v = x / (W - 1)
    img[H // 2:H, x] = (v, v, v)
# patches on the right of the ramp: skin / sky / green
img[H // 2 + 10:H // 2 + 70, W - 220:W - 160] = (0.85, 0.6, 0.45)   # skin
img[H // 2 + 10:H // 2 + 70, W - 150:W - 90] = (0.4, 0.6, 0.95)    # sky
img[H // 2 + 10:H // 2 + 70, W - 80:W - 20] = (0.3, 0.7, 0.3)      # green

inp = os.path.join(OUT, "test_input.png")
(Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), "RGB")).save(inp)

identity = {
    "exposure": {"bias": 0.0},
    "white_balance": {"temperature_bias": 0.0, "tint_bias": 0.0},
    "color_matrix": {"matrix_3x3": [[1, 0, 0], [0, 1, 0], [0, 0, 1]]},
    "hsl": {c: {"hue_shift": 0, "saturation": 1.0, "lightness": 1.0}
            for c in ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]},
    "tone_curve": {"points": [[0, 0], [1, 1]]},
    "highlight_rolloff": {"threshold": 0.8, "strength": 0.0, "saturation": 1.0},
    "shadow": {"black_point": 0.0, "compression": 0.0, "tint": [0, 0, 0],
               "saturation": 1.0, "contrast": 1.0},
    "lens": {"vignette": 0.0, "chromatic_aberration": 0.0, "sharpness_falloff": 0.0,
             "distortion": 0.0, "bloom": 0.0, "flare": 0.0},
    "grain": {"amount": 0.0, "size": 1.0, "density": 1.0},
    "noise": {"luma": 0.0, "chroma": 0.0},
    "halation": {"amount": 0.0, "radius": 1.0, "threshold": 0.9, "warmth": 1.0},
    "bloom": {"amount": 0.0, "radius": 1.0, "threshold": 0.9},
    "vignette": {"amount": 0.0, "radius": 1.0, "feather": 0.5, "center": [0.5, 0.5]},
    "sharpen": {"amount": 0.0, "radius": 1.0},
}

names = ["VINTAGE 400", "ACROS 100", "CINEMA 80", "MONO 400", "FRESH 200", "CLASSIC 320"]


def stats(arr):
    a = arr.astype(np.float32) / 255.0
    mr = a[..., 0].mean() * 255
    mg = a[..., 1].mean() * 255
    mb = a[..., 2].mean() * 255
    mx = a.max(axis=2)
    mn = a.min(axis=2)
    sat = (mx - mn) / (mx + 1e-6)
    sat_mean = sat.mean()
    luma = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    contrast = luma.std()
    return mr, mg, mb, sat_mean, contrast


print(f"{'Profile':<14}{'R':>7}{'G':>7}{'B':>7}{'Sat':>8}{'Contrast':>10}")
rows = []
# neutral
neutral_path = os.path.join(OUT, "neutral.png")
Image.fromarray((np.clip(pr.render(np.asarray(Image.open(inp).convert('RGB'), dtype=np.float32) / 255.0, identity), 0, 1) * 255).astype(np.uint8), "RGB").save(neutral_path)
mr, mg, mb, sat, con = stats(np.asarray(Image.open(neutral_path).convert("RGB")))
print(f"{'NEUTRAL':<14}{mr:7.1f}{mg:7.1f}{mb:7.1f}{sat:8.3f}{con:10.3f}")
rows.append(("NEUTRAL", neutral_path))

for n in names:
    with open(os.path.join(ASSETS, n + ".json"), encoding="utf-8") as f:
        prof = json.load(f)
    out_path = os.path.join(OUT, n + ".png")
    pr.render_file(inp, prof, out_path, seed=7)
    mr, mg, mb, sat, con = stats(np.asarray(Image.open(out_path).convert("RGB")))
    print(f"{n:<14}{mr:7.1f}{mg:7.1f}{mb:7.1f}{sat:8.3f}{con:10.3f}")
    rows.append((n, out_path))

print("\nOutputs in:", OUT)
