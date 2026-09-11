#!/usr/bin/env python3
"""CPU reference renderer for PhotographerProfile (data-ready).

Applies the 15-layer pipeline (same order as Android) on CPU with NumPy/OpenCV.
This is a *reference* implementation used by the Desktop Viewer and for PC-side
validation BEFORE the Android GPU renderer exists. It is not for production speed.

Order (mirrors docs/rendering_pipeline.md):
  Exposure -> White Balance -> Color Matrix -> Tone Curve -> Highlight Roll-off
  -> Shadow Response -> HSL Mapping -> Sharpen -> Bloom -> Halation
  -> Grain -> Noise -> Vignette
"""
from __future__ import annotations

import os

import numpy as np

try:
    import cv2  # type: ignore
except Exception:  # pragma: no cover
    cv2 = None


def _clip(a):
    return np.clip(a, 0.0, 1.0)


# ---------------------------------------------------------------- 3D LUT layer
_LUT_CACHE: dict = {}


def _load_lut3d():
    """Resolve the stylefit.lut3d module without dragging in the training stack.

    Importing the stylefit package runs __init__ -> fit -> scipy/skimage, which
    render-only deployments (server_web) may not have. lut3d.py itself only
    needs numpy, so fall back to loading it straight from its file.
    """
    try:
        from stylefit import lut3d
        return lut3d
    except Exception:
        pass
    import importlib.util
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stylefit", "lut3d.py")
    if not os.path.exists(p):
        return None
    try:
        spec = importlib.util.spec_from_file_location("_stylefit_lut3d", p)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod
    except Exception:
        return None


def _decode_lut(payload):
    """Decode an embedded colour LUT payload (cached — base64 is not free)."""
    if not isinstance(payload, dict) or not payload.get("data"):
        return None
    key = (payload.get("size"), payload.get("dtype"),
           len(payload.get("data", "")), payload.get("data", "")[:96])
    hit = _LUT_CACHE.get(key)
    if hit is not None:
        return hit
    lut3d = _load_lut3d()
    if lut3d is None:
        return None
    try:
        lut = lut3d.from_payload(payload)
    except Exception:
        return None
    if len(_LUT_CACHE) > 8:
        _LUT_CACHE.clear()
    _LUT_CACHE[key] = lut
    return lut


def profile_lut(profile, rgb=None):
    """Resolve the colour LUT for a profile (adaptive bank when present).

    Returns None when the profile carries no LUT — the caller then falls back
    to the parametric colour chain.
    """
    prof = profile or {}
    if prof.get("color_layer") != "lut":
        return None
    bank = prof.get("color_lut_bank")
    router = prof.get("color_lut_router")
    avg = _decode_lut(prof.get("color_lut"))
    if bank and router and rgb is not None:
        try:
            from stylefit import adaptive as A
            luts = [x for x in (_decode_lut(b) for b in bank) if x is not None]
            if len(luts) == len(bank) and len(luts) > 1:
                lut, _w = A.resolve_lut(rgb, luts, router.get("centers", []),
                                        router.get("sigma", 0.35),
                                        router.get("prior", 0.15),
                                        average_lut=avg)
                return lut
        except Exception:
            pass
    return avg


def apply_color_lut(rgb, lut):
    if lut is None:
        return rgb
    return _load_lut3d().sample(lut, rgb)


TONE_LUT_SIZE = 1024  # canonical 1D LUT width shared by CPU / GLSL / Kotlin loader


def _tone_lut(points, size=TONE_LUT_SIZE):
    """Bake control points into a 1D LUT sampled at TEXEL CENTERS ((i+0.5)/size).

    Texel-center baking is required so that a GL_LINEAR lookup at ``u = c``
    reproduces the continuous curve (see shaders/tone_curve.glsl).
    """
    xs = np.array([p[0] for p in points], dtype=np.float32)
    ys = np.array([p[1] for p in points], dtype=np.float32)
    order = np.argsort(xs)
    xs, ys = xs[order], ys[order]
    lut_x = (np.arange(size, dtype=np.float32) + 0.5) / size
    return np.interp(lut_x, xs, ys).astype(np.float32)


def apply_exposure(rgb, bias=0.0):
    if bias == 0.0:
        return rgb
    return _clip(rgb * (2.0 ** bias))


def apply_white_balance(rgb, temperature_bias=0.0, tint_bias=0.0):
    if temperature_bias == 0.0 and tint_bias == 0.0:
        return rgb
    out = rgb.copy()
    # temperature: + = warmer (more R, less B)
    out[..., 0] *= (1.0 + temperature_bias * 0.2)
    out[..., 2] *= (1.0 - temperature_bias * 0.2)
    # tint: + = magenta (more R/B, less G)
    out[..., 0] *= (1.0 + tint_bias * 0.1)
    out[..., 2] *= (1.0 + tint_bias * 0.1)
    out[..., 1] *= (1.0 - tint_bias * 0.1)
    return _clip(out)


def apply_color_matrix(rgb, matrix_3x3=None):
    if not matrix_3x3:
        return rgb
    M = np.array(matrix_3x3, dtype=np.float32)
    flat = rgb.reshape(-1, 3) @ M.T
    return _clip(flat.reshape(rgb.shape))


def sample_lut1d_linear(lut, x):
    """Emulate a GL_LINEAR / GL_CLAMP_TO_EDGE 1D texture fetch at u = clamp(x,0,1).

    Kept identical to glsl_reference.pc_tone_curve on purpose: the CPU reference
    must predict what the GPU actually computes, not an idealised curve. The
    equivalence test (tests/test_shader_equivalence.py) guards the two copies.
    """
    size = len(lut)
    t = np.clip(np.clip(x, 0.0, 1.0) * size - 0.5, 0.0, size - 1.0)
    i0 = np.floor(t).astype(np.int32)
    i1 = np.minimum(i0 + 1, size - 1)
    f = (t - i0).astype(np.float32)
    return (lut[i0] * (1.0 - f) + lut[i1] * f).astype(np.float32)


def apply_tone_curve(rgb, points):
    """Tone curve via the same texel-centre 1D LUT the GPU uses (TONE_LUT_SIZE)."""
    if not points or (len(points) == 2 and points[0] == [0, 0] and points[1] == [1, 1]):
        return rgb
    return sample_lut1d_linear(_tone_lut(points), rgb)


def apply_highlight_rolloff(rgb, threshold=0.8, strength=0.0, saturation=1.0):
    if strength <= 0.0:
        return rgb
    out = rgb.copy()
    lum = rgb.mean(axis=2, keepdims=True)
    amt = np.clip((lum - threshold) / max(1e-4, 1.0 - threshold), 0, 1) * strength
    out = rgb - amt * (rgb - threshold) * (1 - amt) * 0.5
    return _clip(out)


def apply_shadow(rgb, black_point=0.0, compression=0.0, tint=None, saturation=1.0, contrast=1.0):
    out = rgb.copy()
    if black_point > 0.0:
        out = (out - black_point) / max(1e-4, 1.0 - black_point)
    if compression > 0.0:
        lum = out.mean(axis=2, keepdims=True)
        shadow_mask = np.clip(1.0 - lum, 0, 1)
        out = out + compression * shadow_mask * (0.5 - out) * 0.5
    if saturation != 1.0:
        lum = out.mean(axis=2, keepdims=True)
        out = _clip(lum + (out - lum) * saturation)
    return _clip(out)


_HUE_RANGES = {
    "red": (345, 360), "orange": (15, 45), "yellow": (45, 70), "green": (70, 160),
    "cyan": (160, 200), "blue": (200, 260), "purple": (260, 345),
}


def apply_hsl(rgb, hsl: dict):
    # hsl: {hue_name: {hue_shift, saturation, lightness}}
    if not hsl:
        return rgb
    out = rgb.copy()
    mx = out.max(axis=2)
    mn = out.min(axis=2)
    v = mx
    s = np.where(mx > 0, (mx - mn) / np.where(mx == 0, 1, mx), 0.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        diff = mx - mn
        rc = (mx - out[..., 0]) / np.where(diff == 0, 1, diff)
        gc = (mx - out[..., 1]) / np.where(diff == 0, 1, diff)
        bc = (mx - out[..., 2]) / np.where(diff == 0, 1, diff)
        h = np.where(mx == out[..., 0], bc - gc,
                     np.where(mx == out[..., 1], 2 + rc - bc, 4 + gc - rc))
        h = (h / 6.0) % 1.0
    hd = h * 360.0
    # precompute per-pixel factors
    sat_f = np.ones_like(v)
    light_f = np.ones_like(v)
    hue_shift = np.zeros_like(h)
    for name, params in hsl.items():
        if not params:
            continue
        # build mask for this hue range
        if name == "red":
            mask = (hd >= 345) | (hd < 15)
        else:
            a, b = _HUE_RANGES[name]
            mask = (hd >= a) & (hd < b)
        sat_f = np.where(mask, params.get("saturation", 1.0), sat_f)
        light_f = np.where(mask, params.get("lightness", 1.0), light_f)
        hue_shift = np.where(mask, params.get("hue_shift", 0) / 360.0, hue_shift)
    h2 = (h + hue_shift) % 1.0
    s2 = np.clip(s * sat_f, 0, 1)
    v2 = np.clip(v * light_f, 0, 1)
    # back to rgb
    i = np.floor(h2 * 6).astype(int)
    f = h2 * 6 - i
    p = v2 * (1 - s2)
    q = v2 * (1 - f * s2)
    t = v2 * (1 - (1 - f) * s2)
    rgb_new = np.zeros_like(out)
    for k in range(6):
        m = i % 6 == k
        if k == 0:
            rgb_new[m] = np.stack([v2[m], t[m], p[m]], axis=-1)
        elif k == 1:
            rgb_new[m] = np.stack([q[m], v2[m], p[m]], axis=-1)
        elif k == 2:
            rgb_new[m] = np.stack([p[m], v2[m], t[m]], axis=-1)
        elif k == 3:
            rgb_new[m] = np.stack([p[m], q[m], v2[m]], axis=-1)
        elif k == 4:
            rgb_new[m] = np.stack([t[m], p[m], v2[m]], axis=-1)
        else:
            rgb_new[m] = np.stack([v2[m], p[m], q[m]], axis=-1)
    return _clip(rgb_new)


def _rel_scale(rgb) -> float:
    """Resolution-relative kernel scale.

    The GPU chain expresses effect radii in *texels* (uv-relative), so a
    radius=1 looks identical at any preview size. The CPU kernels are in
    pixels - scale them by image size (400px reference) so slider response
    is resolution-independent and visible at 1600px previews.
    """
    return float(min(6.0, max(1.0, max(rgb.shape[0], rgb.shape[1]) / 400.0)))


def apply_sharpen(rgb, amount=0.0, radius=1.0):
    if amount <= 0.0 or cv2 is None:
        return rgb
    sigma = max(0.5, 0.6 * radius * _rel_scale(rgb))
    ksz = max(3, int(round(sigma * 2.5)) | 1)
    blur = np.zeros_like(rgb)
    for c in range(3):
        blur[..., c] = cv2.GaussianBlur(rgb[..., c], (ksz, ksz), sigma)
    return _clip(rgb + amount * (rgb - blur))


def apply_bloom(rgb, amount=0.0, threshold=0.9, radius=1.0):
    if amount <= 0.0 or cv2 is None:
        return rgb
    bright = np.clip(rgb - threshold, 0, 1) / max(1e-4, 1 - threshold)
    ksz = max(3, int(round(6 * radius)) | 1)
    bloom = np.zeros_like(rgb)
    for c in range(3):
        small = cv2.GaussianBlur(bright[..., c], (ksz, ksz), 0)
        bloom[..., c] = cv2.GaussianBlur(small, (ksz, ksz), 0)
    return _clip(rgb + amount * bloom)


def apply_halation(rgb, amount=0.0, threshold=0.9, radius=1.0, warmth=1.0):
    if amount <= 0.0 or cv2 is None:
        return rgb
    lum = rgb.mean(axis=2)
    bright = np.clip(lum - threshold, 0, 1) / max(1e-4, 1 - threshold)
    ksz = max(3, int(round(8 * radius * _rel_scale(rgb))) | 1)
    glow = cv2.GaussianBlur(bright, (ksz, ksz), 0)
    glow = cv2.GaussianBlur(glow, (ksz, ksz), 0)
    out = rgb.copy()
    # warm/red glow
    out[..., 0] += amount * glow * warmth
    out[..., 1] += amount * glow * 0.4
    out[..., 2] += amount * glow * (1.0 / max(1e-4, warmth))
    return _clip(out)


def apply_grain(rgb, amount=0.0, size=1.0, density=1.0, seed=None):
    if amount <= 0.0:
        return rgb
    rng = np.random.default_rng(seed)
    noise = rng.standard_normal(rgb.shape[:2]).astype(np.float32)
    # finer grain with smaller size (more high-freq); approximate with scaling
    scale = amount * 0.08 * density / max(0.5, size)
    # LIGHT-AWARE distribution (v0.3.0, matches effect.frag::pc_grain):
    # the only adaptive term in the engine. Dense grain in shadows, flat
    # 0.4 floor in mids, gentle lift over strong highlights. The amount/
    # size/density parameters stay constant - only the per-pixel mask varies.
    lum = rgb.mean(axis=2)
    shadow_w = 1.0 - 0.6 * _smoothstep(0.0, 0.45, lum)
    high_w = 0.4 + 0.45 * _smoothstep(0.72, 0.97, lum)
    mask = np.maximum(shadow_w, high_w)
    return _clip(rgb + noise[..., None] * scale * mask[..., None])


def _smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(1e-6, e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def apply_noise(rgb, luma=0.0, chroma=0.0, seed=None):
    if luma <= 0.0 and chroma <= 0.0:
        return rgb
    rng = np.random.default_rng(seed)
    out = rgb.copy()
    if luma > 0:
        out += rng.standard_normal(rgb.shape[:2])[:, :, None].astype(np.float32) * luma * 0.05
    if chroma > 0:
        out[..., 0] += rng.standard_normal(rgb.shape[:2]).astype(np.float32) * chroma * 0.04
        out[..., 2] += rng.standard_normal(rgb.shape[:2]).astype(np.float32) * chroma * 0.04
    return _clip(out)


def apply_vignette(rgb, amount=0.0, radius=1.0, feather=0.5, center=(0.5, 0.5)):
    """Radial darkening.

    UV convention (must match shaders/vignette.frag): texel centers in image
    space, ``uv = ((x + 0.5) / w, (y + 0.5) / h)`` with y counted from the TOP
    row. The GL texture is uploaded row-0-first, so ``v_uv`` is identical.
    """
    if amount <= 0.0:
        return rgb
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    xx = (xx + 0.5) / w - center[0]
    yy = (yy + 0.5) / h - center[1]
    dist = np.sqrt(xx ** 2 + yy ** 2) / (radius * 0.7071)
    mask = 1.0 - amount * np.clip((dist - (1 - feather)) / max(1e-4, feather), 0, 1)
    return _clip(rgb * mask[..., None])


def _radial_maps(h, w):
    """Normalized radial coordinate maps centered on the optical axis."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    nx = (xx + 0.5) / w * 2.0 - 1.0
    ny = (yy + 0.5) / h * 2.0 - 1.0
    return nx, ny


def apply_lens(rgb, lens: dict):
    """Optical lens simulation stage (schema `lens.*`).

    Physically-first stage: happens BEFORE all stylization, like light
    passing through glass before it hits the sensor. Desktop-reference
    implementation; the Android GPU chain needs an equivalent stage
    (see docs/HANDOFF_android_lens.md).
      distortion        barrel (+) / pincushion (-) radial warp
      chromatic_aberration  radial per-channel magnification split (R in, B out)
      sharpness_falloff radial blur mix towards corners
      vignette          natural optical corner falloff (pre-style, multiplicative)
      bloom             soft optical glow around highlights (wide, low-gain)
      flare             horizontal streak + warm glow from the brightest spots
    """
    lens = lens or {}
    if not any((lens.get(k) or 0.0) != 0.0 for k in
               ("distortion", "chromatic_aberration", "sharpness_falloff",
                "vignette", "bloom", "flare")):
        return rgb
    h, w = rgb.shape[:2]
    cx, cy = w / 2.0, h / 2.0

    # 1. distortion - radial warp (barrel + / pincushion -)
    k = float(lens.get("distortion") or 0.0)
    if abs(k) > 1e-4 and cv2 is not None:
        nx, ny = _radial_maps(h, w)
        r2 = nx * nx + ny * ny
        f = 1.0 + 0.35 * k * r2
        map_x = nx * f * cx + cx
        map_y = ny * f * cy + cy
        rgb = cv2.remap(rgb, map_x, map_y, cv2.INTER_LINEAR,
                        borderMode=cv2.BORDER_REFLECT)

    # 2. chromatic aberration - R pulled in, B pushed out radially
    ca = float(lens.get("chromatic_aberration") or 0.0)
    if ca > 1e-4 and cv2 is not None:
        nx, ny = _radial_maps(h, w)
        out = np.empty_like(rgb)
        for ch, sc in ((0, 1.0 - 0.015 * ca), (2, 1.0 + 0.015 * ca)):
            mx = nx * sc * cx + cx
            my = ny * sc * cy + cy
            out[..., ch] = cv2.remap(rgb[..., ch], mx, my, cv2.INTER_LINEAR,
                                     borderMode=cv2.BORDER_REFLECT)
        out[..., 1] = rgb[..., 1]
        rgb = out

    # 3. sharpness falloff - corners go soft
    fo = float(lens.get("sharpness_falloff") or 0.0)
    if fo > 1e-4 and cv2 is not None:
        nx, ny = _radial_maps(h, w)
        r = np.sqrt(nx * nx + ny * ny) / 1.4142
        wgt = np.clip(fo * 1.2 * np.clip(r - 0.25, 0, 1), 0, 0.85)
        wgt = wgt[..., None].astype(np.float32)
        ksz = max(3, int(round(4 * _rel_scale(rgb))) | 1)
        blur = np.empty_like(rgb)
        for c in range(3):
            blur[..., c] = cv2.GaussianBlur(rgb[..., c], (ksz, ksz), 0)
        rgb = rgb * (1.0 - wgt) + blur * wgt

    # 4. optical vignette - natural falloff (multiplicative, pre-style)
    vg = float(lens.get("vignette") or 0.0)
    if vg > 1e-4:
        nx, ny = _radial_maps(h, w)
        r = np.sqrt(nx * nx + ny * ny) / 1.4142
        fall = np.clip(r - 0.3, 0, 1) ** 1.5
        rgb = rgb * (1.0 - vg * 0.75 * fall)[..., None]

    # 5. lens bloom - wide soft glow around highlights
    lb = float(lens.get("bloom") or 0.0)
    if lb > 1e-4 and cv2 is not None:
        bright = np.clip(rgb - 0.82, 0, 1) / 0.18
        ksz = max(3, int(round(14 * _rel_scale(rgb))) | 1)
        glow = np.empty_like(rgb)
        for c in range(3):
            glow[..., c] = cv2.GaussianBlur(bright[..., c], (ksz, ksz), 0)
        rgb = _clip(rgb + lb * 0.8 * glow)

    # 6. flare - horizontal anamorphic streak + warm core glow
    fl = float(lens.get("flare") or 0.0)
    if fl > 1e-4 and cv2 is not None:
        lum = rgb.mean(axis=2)
        bright = np.clip(lum - 0.9, 0, 1) / 0.1
        ksz_s = max(3, int(round(4 * _rel_scale(rgb))) | 1)
        ksz_l = max(3, int(round(36 * _rel_scale(rgb))) | 1)
        glow = cv2.GaussianBlur(bright, (ksz_s, ksz_s), 0)
        streak = cv2.GaussianBlur(bright, (ksz_l, ksz_s), 0)
        rgb = _clip(rgb + fl * (0.55 * glow[..., None]
                                * np.array([1.0, 0.92, 0.8], np.float32)
                                + 0.45 * streak[..., None]
                                * np.array([0.85, 0.92, 1.0], np.float32)))
    return _clip(rgb)


def apply_film_curve(rgb, shadow_floor=8.0, highlight_ceiling=248.0):
    """Output-consistency stage (LAST pass, mirrors shaders/film_curve.frag).

    ``shadow_floor`` / ``highlight_ceiling`` are 0-255 DISPLAY levels. The knees
    derive from floor/ceiling (clamped to sane mid-range positions) with
    C1-continuous soft-knee toes/shoulders, so every photo saved with a profile
    sharing the same lo/hi gets one ceiling, one floor and one curve signature.
    Matches GpuParams.kt: lo = floor/255 coerced to [0, 0.4],
    hi = ceiling/255 coerced to [0.6, 1].
    """
    if shadow_floor <= 0.0 and highlight_ceiling >= 255.0:
        return rgb  # disabled, same early-out as ProfileRenderer (filmEnabled)
    lo = min(max(shadow_floor / 255.0, 0.0), 0.4)
    hi = min(max(highlight_ceiling / 255.0, 0.6), 1.0)
    kt = min(max(lo + 0.13, 0.04), 0.45)   # shadow knee
    kh = min(max(hi - 0.17, 0.55), 0.97)   # highlight knee
    out = rgb.copy()
    below = out < kt
    t = out[below] / kt
    out[below] = lo + (kt - lo) * t * t * (2.0 - t)          # flat at lo, slope 1 at kt
    above = out > kh
    t2 = (out[above] - kh) / (1.0 - kh)
    out[above] = kh + (hi - kh) * t2 * (1.0 + t2 - t2 * t2)  # slope 1 at kh, flat at hi
    return _clip(out)


def render(rgb: np.ndarray, profile: dict, seed: int | None = 0) -> np.ndarray:
    """Apply full PhotographerProfile pipeline (CPU reference).

    Chain order synced with effect.frag v0.3.0 (Unified Image Engine, the
    user-defined authoritative order):
      lens (optical, desktop reference) -> exposure -> WB -> color matrix
      -> highlight -> shadow -> film curve (Contrast/BW)
      -> tone curve (independent) -> HSL
      -> vignette -> bloom -> halation
      -> grain (light-aware) -> noise -> sharpen (always LAST)
    The 3D LUT stage is Android-only (sampler3D; no CPU equivalent here).
    """
    rgb = rgb.astype(np.float32)
    p = profile or {}
    # -- Stage 0: optical lens simulation (before any stylization) --
    rgb = apply_lens(rgb, p.get("lens", {}))
    # -- Colour layer: a learned 3D LUT carries the trained look; the
    # parametric chain runs AFTER it as the post-LUT tuning layer (training
    # writes identity params, so a fresh profile is exactly LUT -> film and
    # the Studio sliders act as live fine-tuning). Profiles without a LUT
    # fall back to the parametric chain alone.
    lut = profile_lut(p, rgb)
    if lut is not None:
        rgb = apply_color_lut(rgb, lut)
        fc = p.get("film_curve", {})
        rgb = apply_film_curve(rgb, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))
    rgb = apply_exposure(rgb, p.get("exposure", {}).get("bias", 0.0))
    wb = p.get("white_balance", {})
    rgb = apply_white_balance(rgb, wb.get("temperature_bias", 0.0), wb.get("tint_bias", 0.0))
    rgb = apply_color_matrix(rgb, p.get("color_matrix", {}).get("matrix_3x3"))
    hr = p.get("highlight_rolloff", {})
    rgb = apply_highlight_rolloff(rgb, hr.get("threshold", 0.8), hr.get("strength", 0.0), hr.get("saturation", 1.0))
    sh = p.get("shadow", {})
    rgb = apply_shadow(rgb, sh.get("black_point", 0.0), sh.get("compression", 0.0),
                       sh.get("tint"), sh.get("saturation", 1.0), sh.get("contrast", 1.0))
    if lut is None:
        fc = p.get("film_curve", {})
        rgb = apply_film_curve(rgb, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))
    rgb = apply_tone_curve(rgb, p.get("tone_curve", {}).get("points", []))
    rgb = apply_hsl(rgb, p.get("hsl", {}))
    vg = p.get("vignette", {})
    rgb = apply_vignette(rgb, vg.get("amount", 0.0), vg.get("radius", 1.0), vg.get("feather", 0.5), vg.get("center", [0.5, 0.5]))
    bl = p.get("bloom", {})
    rgb = apply_bloom(rgb, bl.get("amount", 0.0), bl.get("threshold", 0.9), bl.get("radius", 1.0))
    hl = p.get("halation", {})
    rgb = apply_halation(rgb, hl.get("amount", 0.0), hl.get("threshold", 0.9), hl.get("radius", 1.0), hl.get("warmth", 1.0))
    gr = p.get("grain", {})
    rgb = apply_grain(rgb, gr.get("amount", 0.0), gr.get("size", 1.0), gr.get("density", 1.0), seed)
    ns = p.get("noise", {})
    rgb = apply_noise(rgb, ns.get("luma", 0.0), ns.get("chroma", 0.0), seed)
    # sharpen LAST (final detail stage) - matches the Android GPU chain.
    sp = p.get("sharpen", {})
    rgb = apply_sharpen(rgb, sp.get("amount", 0.0), sp.get("radius", 1.0))
    return _clip(rgb)


def render_file(in_path: str, profile: dict, out_path: str, seed: int | None = 0) -> None:
    from PIL import Image

    img = Image.open(in_path).convert("RGB")
    rgb = np.asarray(img, dtype=np.float32) / 255.0
    out = (render(rgb, profile, seed) * 255).astype(np.uint8)
    Image.fromarray(out, "RGB").save(out_path)


# ----------------------------------------------------- LUT profile helpers
_LUT_PARAM_KEYS = ("exposure", "white_balance", "color_matrix",
                   "highlight_rolloff", "shadow", "tone_curve", "hsl")


def _is_lut_profile(profile: dict) -> bool:
    p = profile or {}
    return (p.get("color_layer") == "lut"
            and isinstance(p.get("color_lut"), dict)
            and bool(p["color_lut"].get("data")))


def neutralize_lut_params(profile: dict):
    """Reset the parametric colour layer to identity for LUT profiles.

    Profiles trained before the identity-params change carry an inverse-solved
    approximation of the LUT in their params; under the post-LUT tuning
    semantic those values would style the image a second time. Returns a NEW
    profile dict, or None when there is nothing to neutralize.
    """
    import copy
    from profile_schema import default_profile
    if not isinstance(profile, dict) or not _is_lut_profile(profile):
        return None
    fresh = default_profile(profile.get("name", ""))
    out = copy.deepcopy(profile)
    for k in _LUT_PARAM_KEYS:
        if k in out:
            out[k] = copy.deepcopy(fresh.get(k, out[k]))
    return out


def _params_are_identity(profile: dict) -> bool:
    p = profile or {}
    if float(p.get("exposure", {}).get("bias", 0.0) or 0.0) != 0.0:
        return False
    wb = p.get("white_balance", {})
    if float(wb.get("temperature_bias", 0.0) or 0.0) != 0.0: return False
    if float(wb.get("tint_bias", 0.0) or 0.0) != 0.0: return False
    M = p.get("color_matrix", {}).get("matrix_3x3") or [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    if [[float(v) for v in row] for row in M] != [[1.0, 0, 0], [0, 1.0, 0], [0, 0, 1.0]]:
        return False
    if float(p.get("highlight_rolloff", {}).get("strength", 0.0) or 0.0) != 0.0:
        return False
    sh = p.get("shadow", {})
    if (float(sh.get("black_point", 0.0) or 0.0) != 0.0
            or float(sh.get("compression", 0.0) or 0.0) != 0.0
            or float(sh.get("contrast", 1.0) or 1.0) != 1.0
            or list(sh.get("tint") or [0, 0, 0]) != [0, 0, 0]):
        return False
    pts = p.get("tone_curve", {}).get("points") or [[0, 0], [1, 1]]
    if [[float(a), float(b)] for a, b in pts] not in ([[0.0, 0.0], [1.0, 1.0]], [[0, 0], [1, 1]]):
        return False
    for ch in (p.get("hsl") or {}).values():
        if ch:
            return False
    return True


def bake_params_into_lut(profile: dict):
    """Fold film curve + tuned params into the 3D LUT; reset them to neutral.

    After baking, the lattice holds P(film(LUT(v))) for each grid colour v and
    film_curve is set to the identity window (0/255), so EVERY engine —
    desktop GL/Python, server_web, Android sampler3D — renders exactly what
    the Studio preview showed, regardless of whether it applies the
    parametric chain. Returns a NEW profile dict, or None when there is
    nothing to bake (no LUT / params already identity).
    """
    import copy
    if not isinstance(profile, dict) or not _is_lut_profile(profile):
        return None
    if _params_are_identity(profile):
        # film curve is part of the trained look for stylefit profiles — only
        # fold it in when there are tuned params to bake alongside it
        return None
    lut3d = _load_lut3d()
    if lut3d is None:
        return None
    payload = profile["color_lut"]
    lut = _decode_lut(payload)
    if lut is None:
        return None
    n = lut.shape[0]
    # Hue rotation (HSL) is strongly nonlinear: baked into the native 33^3
    # lattice it diverges from the true chain under trilinear interpolation
    # (p99 error ~13/255). Android accepts up to 65, so re-grid to 65^3 when
    # HSL carries tweaks — interpolation error then drops with grid density.
    hsl_tweaked = any(ch for ch in (profile.get("hsl") or {}).values())
    if hsl_tweaked and n < 65:
        m = 65
        axis = np.linspace(0.0, 1.0, m, dtype=np.float32)
        b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
        query = np.stack([r.ravel(), g.ravel(), b.ravel()], axis=1)
        lut = lut3d.sample(lut, query).reshape(m, m, m, 3).astype(np.float32)
        n = m
    grid = lut.reshape(1, -1, 3).astype(np.float32)
    fc = profile.get("film_curve", {})
    grid = apply_film_curve(grid, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))
    grid = apply_exposure(grid, profile.get("exposure", {}).get("bias", 0.0))
    wb = profile.get("white_balance", {})
    grid = apply_white_balance(grid, wb.get("temperature_bias", 0.0), wb.get("tint_bias", 0.0))
    grid = apply_color_matrix(grid, profile.get("color_matrix", {}).get("matrix_3x3"))
    hr = profile.get("highlight_rolloff", {})
    grid = apply_highlight_rolloff(grid, hr.get("threshold", 0.8), hr.get("strength", 0.0), hr.get("saturation", 1.0))
    sh = profile.get("shadow", {})
    grid = apply_shadow(grid, sh.get("black_point", 0.0), sh.get("compression", 0.0),
                        sh.get("tint"), sh.get("saturation", 1.0), sh.get("contrast", 1.0))
    grid = apply_tone_curve(grid, profile.get("tone_curve", {}).get("points", []))
    grid = apply_hsl(grid, profile.get("hsl", {}))
    grid = np.clip(grid, 0.0, 1.0).reshape(lut.shape)

    out = copy.deepcopy(profile)
    new_payload = lut3d.to_payload(np.ascontiguousarray(grid, dtype=np.float32))
    new_payload["source"] = payload.get("source", "")
    new_payload["strength"] = payload.get("strength", 1.0)
    out["color_lut"] = new_payload
    out["film_curve"] = {"shadow_floor": 0, "highlight_ceiling": 255}
    fresh_params = neutralize_lut_params(profile)
    for k in _LUT_PARAM_KEYS:
        out[k] = fresh_params[k]
    return out


def color_pipeline_cube(profile: dict, n: int = 33) -> np.ndarray:
    """Sample the profile's full COLOUR pipeline on an n^3 identity grid.

    Returns (n**3, 3) float RGB in .cube row order (RED fastest). LUT
    profiles: 3D LUT -> film -> post-LUT param chain — exactly render()'s
    colour stages. Parametric profiles: the chain alone. Spatially-varying
    layers (grain/vignette/bloom/halation/sharpen) are excluded — a .cube
    cannot express them.
    """
    p = profile or {}
    axes = np.linspace(0.0, 1.0, n, dtype=np.float32)
    b, g, r = np.meshgrid(axes, axes, axes, indexing="ij")  # r fastest
    rgb = np.stack([r, g, b], axis=-1).reshape(1, -1, 3).astype(np.float32)
    lut = profile_lut(p, rgb)
    if lut is not None:
        rgb = apply_color_lut(rgb, lut)
        fc = p.get("film_curve", {})
        rgb = apply_film_curve(rgb, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))
    rgb = apply_exposure(rgb, p.get("exposure", {}).get("bias", 0.0))
    wb = p.get("white_balance", {})
    rgb = apply_white_balance(rgb, wb.get("temperature_bias", 0.0), wb.get("tint_bias", 0.0))
    rgb = apply_color_matrix(rgb, p.get("color_matrix", {}).get("matrix_3x3"))
    hr = p.get("highlight_rolloff", {})
    rgb = apply_highlight_rolloff(rgb, hr.get("threshold", 0.8), hr.get("strength", 0.0), hr.get("saturation", 1.0))
    sh = p.get("shadow", {})
    rgb = apply_shadow(rgb, sh.get("black_point", 0.0), sh.get("compression", 0.0),
                       sh.get("tint"), sh.get("saturation", 1.0), sh.get("contrast", 1.0))
    if lut is None:
        fc = p.get("film_curve", {})
        rgb = apply_film_curve(rgb, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))
    rgb = apply_tone_curve(rgb, p.get("tone_curve", {}).get("points", []))
    rgb = apply_hsl(rgb, p.get("hsl", {}))
    return np.clip(rgb, 0.0, 1.0).reshape(-1, 3)
