#!/usr/bin/env python3
"""APP 端成片管线(LutImageProcessor 链) vs 桌面 server 管线(profile_renderer.render)一致性验证.

App 侧按真实成片 GLSL + ProfileToRecipeMapper 映射逐层复现(确定性层):
  recipe: exposure(0=跳过) -> ShadowsHighlights(0=恒等) -> contrast(1=恒等)
          -> applyToneCurve(toe=-blackPoint/0.2, shoulder=0, pivot=0)   [ACTIVE]
          -> BasicTone(0=关) -> WB(0=跳过) -> saturation(1=恒等)
          -> vibrance/LCH/grading/fade/bleach/flash(全 0=恒等)
          -> vignette(profile 桌面公式, LUT 之前)                        [ACTIVE]
          -> noise(0)
  curve  : master/red/green/blue 恒等, film(8/248) 烘焙 256 点 8-bit 纹理,
           GL_LINEAR 采样 = film(x)                                      [ACTIVE]
  LUT    : sRGB 域整数栅格三线性, mix(strength 已烘焙进栅格, intensity=1.0) [ACTIVE]
  末级   : TPDF ±1 LSB 抖动(随机层, 对比中排除, 界 ±1/255)
空间层 grain/bloom/halation/sharpen 是 GPU 近似(双侧置零, 真机 QA 范畴)。

server 侧 = desktop/server.py::_render_jpeg 的精确实现(profile_renderer.render,
输入 _load_rgb(max_side=1600)), 仅将空间层置零以隔离确定性层。
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import profile_renderer as R  # noqa: E402
from stylefit import lut3d  # noqa: E402

IMG_PATH = r"C:\Program Files\Netease\GameViewer\Download\20873.jpg"
PROFILE_DIR = os.path.join(ROOT, "dist", "profiles", "studio_test_neural")
OUT_DIR = os.path.join(ROOT, "_diag", "app_vs_server")
MAX_SIDE = 1600


# ------------------------------------------------------------------ 输入(= server _load_rgb)
def load_rgb(path: str, max_side: int) -> np.ndarray:
    img = Image.open(path).convert("RGB")
    w, h = img.size
    m = max(w, h)
    if m > max_side:
        s = max_side / m
        img = img.resize((max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS)
    return np.asarray(img, dtype=np.float32) / 255.0


# ----------------------------------------------------- App GLSL: applyToneCurve 忠实复现
def app_tone_curve(rgb: np.ndarray, toe: float, shoulder: float, pivot: float) -> np.ndarray:
    if abs(toe) < 1e-3 and abs(shoulder) < 1e-3 and abs(pivot) < 1e-3:
        return rgb
    nonneg = np.maximum(rgb, 0.0)
    cs = np.clip(nonneg, 0.0, 1.0)
    luma = cs[..., 0] * 0.2126 + cs[..., 1] * 0.7152 + cs[..., 2] * 0.0722
    peak = cs.max(axis=-1)
    tone_signal = 0.35 * luma + 0.65 * peak          # mix(luma, peak, 0.65)

    pivot_point = float(np.clip(0.5 + pivot * 0.12, 0.2, 0.8))
    toe_amount = min(abs(toe), 1.0)
    shoulder_amount = min(abs(shoulder), 1.0)
    toe_gamma = (1.0 + (0.68 - 1.0) * toe_amount) if toe >= 0 else (1.0 + (1.85 - 1.0) * toe_amount)
    shoulder_gamma = (1.0 + (0.72 - 1.0) * shoulder_amount) if shoulder >= 0 \
        else (1.0 + (1.85 - 1.0) * shoulder_amount)

    seg_low = np.clip(tone_signal / max(pivot_point, 1e-4), 0.0, 1.0)
    low_out = np.power(seg_low, toe_gamma) * pivot_point
    seg_high = np.clip((tone_signal - pivot_point) / max(1.0 - pivot_point, 1e-4), 0.0, 1.0)
    high_out = 1.0 - np.power(np.maximum(0.0, 1.0 - seg_high), shoulder_gamma) * (1.0 - pivot_point)
    curved = np.where(tone_signal <= pivot_point, low_out, high_out)

    ratio = np.clip(curved / np.maximum(tone_signal, 1e-4), 0.0, 16.0)
    scaled = nonneg * ratio[..., None]
    out = 0.04 * curved[..., None] + 0.96 * scaled   # mix(vec3(curved), scaled, 0.96)
    return np.clip(out, 0.0, 1.0)


# ------------------------------------------------- CurveUtils: film curve 256 点 8-bit 纹理
def build_film_curve_lut_u8(shadow_floor: float, highlight_ceiling: float) -> np.ndarray | None:
    if shadow_floor <= 0 and highlight_ceiling >= 255:
        return None
    lo = float(np.clip(shadow_floor / 255.0, 0.0, 0.4))
    hi = float(np.clip(highlight_ceiling / 255.0, 0.6, 1.0))
    kt = float(np.clip(lo + 0.13, 0.04, 0.45))
    kh = float(np.clip(hi - 0.17, 0.55, 0.97))
    x = np.arange(256, dtype=np.float32) / 255.0
    out = x.copy()
    below = x < kt
    t = x[below] / max(kt, 1e-4)
    out[below] = np.clip(lo + (kt - lo) * t * t * (2.0 - t), 0.0, 1.0)
    above = x > kh
    t2 = (x[above] - kh) / max(1.0 - kh, 1e-4)
    out[above] = np.clip(kh + (hi - kh) * t2 * (1.0 + t2 - t2 * t2), 0.0, 1.0)
    # toByteUnsigned: (f*255+0.5).toInt() — 8-bit 纹理量化
    return np.floor(np.clip(out, 0.0, 1.0) * 255.0 + 0.5).astype(np.float32) / 255.0


def sample_1d_texture(rgb: np.ndarray, lut_u8: np.ndarray) -> np.ndarray:
    """GL_LINEAR / GL_CLAMP_TO_EDGE 256 宽纹理采样(u = clamp(c,0,1)), 同 pc_tone_curve."""
    size = len(lut_u8)
    t = np.clip(np.clip(rgb, 0.0, 1.0) * size - 0.5, 0.0, size - 1.0)
    i0 = np.floor(t).astype(np.int32)
    i1 = np.minimum(i0 + 1, size - 1)
    f = (t - i0).astype(np.float32)
    return lut_u8[i0] * (1.0 - f) + lut_u8[i1] * f


# ------------------------------------------------------------------- App 端确定性成片链
def app_render(rgb: np.ndarray, profile: dict, *, use_toe: bool = True,
               use_film: bool = True) -> np.ndarray:
    p = profile or {}
    c = rgb.astype(np.float32)

    # -- recipe 阶段(exposure/WB/matrix/highlight/compression/saturation/HSL 均恒等, 跳过)
    sh = p.get("shadow", {})
    hr = p.get("highlight_rolloff", {})
    toe = -float(sh.get("black_point", 0.0)) / 0.2          # ProfileToRecipeMapper:87
    shoulder = float(hr.get("strength", 0.0)) * (1.0 - float(hr.get("threshold", 0.8)))
    pivot = (float(sh.get("contrast", 1.0)) - 1.0) * 0.1
    if use_toe:
        c = app_tone_curve(c, toe, shoulder, pivot)

    # -- vignette(profile 桌面公式, 位于 curve/LUT 之前 — App 实际链序)
    vg = p.get("vignette", {})
    c = R.apply_vignette(c, vg.get("amount", 0.0), vg.get("radius", 1.0),
                         vg.get("feather", 0.5), vg.get("center", [0.5, 0.5]))

    # -- curve 纹理: film(8/248) 烘焙 256 点 8-bit, master/channel 恒等 → 组合 = film(x)
    fc = p.get("film_curve", {})
    if use_film:
        film = build_film_curve_lut_u8(float(fc.get("shadow_floor", 8.0)),
                                       float(fc.get("highlight_ceiling", 248.0)))
        if film is not None:
            c = sample_1d_texture(c, film)

    # -- 3D LUT: sRGB 域, 整数栅格, mix=1.0(strength 0.8 已烘焙在栅格内)
    lut = lut3d.from_payload(p["color_lut"])
    c = lut3d.sample(lut, c)

    # -- TPDF ±1 LSB 抖动: 随机层, 确定性对比中排除(界 ±1/255)
    return np.clip(c, 0.0, 1.0)


# ---------------------------------------------------------------------- server 端(置零空间层)
def server_render(rgb: np.ndarray, profile: dict) -> np.ndarray:
    p = json.loads(json.dumps(profile))  # deep copy
    for k in ("grain", "bloom", "halation", "sharpen"):
        if k in p:
            p[k] = {**p[k], "amount": 0.0}
    return R.render(rgb, p, seed=0)


# ------------------------------------------------------------------------------ 指标
def metrics(a: np.ndarray, b: np.ndarray) -> dict:
    d = np.abs(a - b) * 255.0
    mse = float((d ** 2).mean())
    return {
        "mean": float(d.mean()),
        "p95": float(np.percentile(d, 95)),
        "p99": float(np.percentile(d, 99)),
        "max": float(d.max()),
        "psnr": float(10 * np.log10(255 ** 2 / mse)) if mse > 0 else float("inf"),
        "gt1": float((d > 1.0).mean() * 100),
        "gt3": float((d > 3.0).mean() * 100),
    }


def main() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    rgb = load_rgb(IMG_PATH, MAX_SIDE)
    profiles = sorted(f for f in os.listdir(PROFILE_DIR) if f.endswith(".json"))

    rows = []
    for name in profiles:
        with open(os.path.join(PROFILE_DIR, name), encoding="utf-8") as fh:
            prof = json.load(fh)
        base = os.path.splitext(name)[0]

        srv = server_render(rgb, prof)
        app = app_render(rgb, prof)
        app_no_toe = app_render(rgb, prof, use_toe=False)
        app_no_film = app_render(rgb, prof, use_film=False)

        m_full = metrics(app, srv)
        m_toe = metrics(app_no_toe, srv)
        m_film = metrics(app_no_film, srv)
        rows.append((base, m_full, m_toe, m_film))

        # 交付图: server / app 模拟 / 差异热图
        srv8 = (np.clip(srv, 0, 1) * 255 + 0.5).astype(np.uint8)
        app8 = (np.clip(app, 0, 1) * 255 + 0.5).astype(np.uint8)
        heat = np.abs(app - srv).mean(axis=-1) * 255.0
        heat_n = np.clip(heat / max(1e-3, float(np.percentile(heat, 99.5))), 0, 1)
        heat_rgb = np.stack([heat_n * 255, heat_n * 90, (1 - heat_n) * 60], -1).astype(np.uint8)
        Image.fromarray(srv8).save(os.path.join(OUT_DIR, f"{base}_server.jpg"), quality=92)
        Image.fromarray(app8).save(os.path.join(OUT_DIR, f"{base}_app_sim.jpg"), quality=92)
        Image.fromarray(heat_rgb).save(os.path.join(OUT_DIR, f"{base}_diff_heat.jpg"), quality=92)
        np.save(os.path.join(OUT_DIR, f"{base}_diffmap.npy"), heat.astype(np.float32))

    # ---- 汇总表
    lines = ["profile | 全链 mean/p95/p99/max (0-255) | PSNR | >1LSB% | 去toe mean | 去film mean"]
    for base, m, m2, m3 in rows:
        lines.append(
            f"{base} | {m['mean']:.2f}/{m['p95']:.2f}/{m['p99']:.2f}/{m['max']:.2f} "
            f"| {m['psnr']:.1f}dB | {m['gt1']:.1f}% | {m2['mean']:.2f} | {m3['mean']:.2f}")
    report = "\n".join(lines)
    print(report)
    with open(os.path.join(OUT_DIR, "metrics.txt"), "w", encoding="utf-8") as fh:
        fh.write(report + "\n")


if __name__ == "__main__":
    main()
