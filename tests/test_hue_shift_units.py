"""PA-05 regression: profile hue_shift is specified in DEGREES (normalized as
hue_shift / 360.0) on BOTH the Studio CPU reference and the on-device HSL-LUT
stage (assets/shaders/hsl.glsl:19 / effect.frag:283, `pl.b / 360.0`).

These tests lock that contract so a future "fix" that multiplies by 20.0 (the
SCALE of the SEPARATE Android LCH color-mixer stage, uLchHueAdjustments *
radians(20.0)) cannot silently diverge the Studio HSL-LUT path from the real
shader. The LCH mixer is a distinct, band-weighted OKLCH grade and is NOT the
hue_shift field's render path.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import profile_renderer  # noqa: E402
import glsl_reference  # noqa: E402

# float32 unit-roundoff level: the two implementations are the SAME math.
TOL = 1e-5

_HUES = ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]


def _rgb_hue_deg(rgb):
    r, g, b = float(rgb[0]), float(rgb[1]), float(rgb[2])
    mx, mn = max(r, g, b), min(r, g, b)
    if mx - mn < 1e-9:
        return 0.0
    d = mx - mn
    if mx == r:
        h = ((g - b) / d) % 6.0
    elif mx == g:
        h = (b - r) / d + 2.0
    else:
        h = (r - g) / d + 4.0
    return h * 60.0


def _norm_deg(a):
    return a % 360.0


def _hue_shift_only(hue_name, deg):
    return {n: ({"hue_shift": float(deg), "saturation": 1.0, "lightness": 1.0}
                if n == hue_name else {}) for n in _HUES}


def test_hue_shift_is_degrees_not_20x():
    """A red pixel (hue 0) with red hue_shift = +30 must land at hue 30 deg,
    NOT at (0 + 30*20) % 360 = 240 deg. This is the direct guard against the
    previous '18-20x mismatch' misread being 'corrected' the wrong way."""
    red = np.array([[[1.0, 0.0, 0.0]]], dtype=np.float32)
    out = profile_renderer.apply_hsl(red, _hue_shift_only("red", 30.0))
    hue = _rgb_hue_deg(out[0, 0])
    assert abs(_norm_deg(hue - 30.0)) < 0.5, f"expected ~30deg, got {hue:.3f}"
    # explicitly forbid the 20x interpretation
    assert abs(_norm_deg(hue - 240.0)) > 30.0, \
        f"hue_shift was scaled by ~20x (got {hue:.3f}), contract violated"


def test_hue_shift_rotates_known_primaries():
    cases = [
        ("red", 0.0, 30.0),    # 0   -> 30
        ("green", 120.0, 30.0), # 120 -> 150
        ("blue", 240.0, 30.0),  # 240 -> 270
        ("red", 0.0, -30.0),   # wrap: 0 -> 330
    ]
    px = {
        "red": np.array([[[1.0, 0.0, 0.0]]], dtype=np.float32),
        "green": np.array([[[0.0, 1.0, 0.0]]], dtype=np.float32),
        "blue": np.array([[[0.0, 0.0, 1.0]]], dtype=np.float32),
    }
    for name, in_hue, shift in cases:
        out = profile_renderer.apply_hsl(px[name], _hue_shift_only(name, shift))
        hue = _rgb_hue_deg(out[0, 0])
        expected = _norm_deg(in_hue + shift)
        diff = abs(_norm_deg(hue - expected))
        diff = min(diff, 360.0 - diff)
        assert diff < 0.5, f"{name} shift {shift}: expected {expected}deg, got {hue:.3f}"


def test_hue_shift_out_of_range_pixel_unchanged():
    """A hue_shift set only on the 'green' range must NOT move a red pixel."""
    red = np.array([[[1.0, 0.0, 0.0]]], dtype=np.float32)
    out = profile_renderer.apply_hsl(red, _hue_shift_only("green", 45.0))
    assert np.allclose(out, red, atol=1e-6), f"out-of-range pixel changed: {out[0,0]}"


def test_apply_hsl_matches_pc_hsl_reference():
    """The two Studio references (CPU renderer vs numpy GLSL port) must agree on
    the HSL stage for arbitrary hue_shift values, including the schema extremes."""
    rng = np.random.default_rng(7)
    for _ in range(20):
        hsl = {n: {"hue_shift": float(rng.uniform(-30, 30)),
                   "saturation": float(rng.uniform(0.7, 1.4)),
                   "lightness": float(rng.uniform(0.8, 1.3))} for n in _HUES}
        img = rng.random((16, 16, 3)).astype(np.float32)
        a = profile_renderer.apply_hsl(img, hsl)
        b = glsl_reference.pc_hsl(img, glsl_reference.bake_hsl_lut(hsl))
        assert float(np.abs(a - b).max()) < TOL, \
            f"apply_hsl vs pc_hsl max diff {np.abs(a - b).max():.2e}"


def test_zero_hue_shift_is_identity():
    rng = np.random.default_rng(11)
    img = rng.random((8, 8, 3)).astype(np.float32)
    hsl = {n: {"hue_shift": 0.0, "saturation": 1.0, "lightness": 1.0} for n in _HUES}
    out = profile_renderer.apply_hsl(img, hsl)
    assert np.allclose(out, img, atol=1e-6), "zero hue_shift not identity"
