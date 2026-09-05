"""Tests for tools/profile_renderer.py (CPU reference renderer)."""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
from profile_renderer import render  # noqa: E402
from profile_schema import default_profile  # noqa: E402


def _img():
    rng = np.random.default_rng(2)
    return rng.random((200, 300, 3)).astype(np.float32)


def test_identity_profile_is_nearly_passthrough():
    rgb = _img()
    p = default_profile("T")
    # disable the film_curve output-consistency stage to verify the REST of the
    # chain is identity (film_curve intentionally lifts shadows / rolls highlights).
    p["film_curve"] = {"shadow_floor": 0, "highlight_ceiling": 255}
    out = render(rgb, p)
    assert np.allclose(out, rgb, atol=1e-4)


def test_default_film_curve_bounds():
    """The default profile's film_curve (floor=8, ceiling=248) must hold the
    exported pixels inside [8/255, 248/255] — the CPU twin of the on-device
    pixel-statistics check for the film boundary (交接卡 next steps #2)."""
    rgb = _img()
    out = render(rgb, default_profile("T"))
    assert out.min() >= 8.0 / 255.0 - 1e-5
    assert out.max() <= 248.0 / 255.0 + 1e-5


def test_exposure_bias_brightens():
    rgb = np.clip(_img() * 0.5, 0, 1)
    p = default_profile("T")
    p["exposure"]["bias"] = 1.0
    out = render(rgb, p)
    assert out.mean() > rgb.mean()


def test_vignette_darkens_corners():
    rgb = np.ones((200, 200, 3), dtype=np.float32) * 0.8
    p = default_profile("T")
    p["vignette"]["amount"] = 0.8
    out = render(rgb, p)
    corner = out[5, 5].mean()
    center = out[100, 100].mean()
    assert corner < center


if __name__ == "__main__":
    test_identity_profile_is_nearly_passthrough()
    test_default_film_curve_bounds()
    test_exposure_bias_brightens()
    test_vignette_darkens_corners()
    print("test_renderer: PASS")
