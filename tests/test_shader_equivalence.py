"""Equivalence test: deterministic GLSL layer math == validated CPU reference.

Proves the GPU shader formulas (tools/glsl_reference.py, a numpy port of shaders/*.glsl)
reproduce tools/profile_renderer.py without a GPU. This validates the color-matrix
transpose convention and all LUT-based layers (tone curve, HSL).
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import profile_renderer  # noqa: E402
import glsl_reference  # noqa: E402

_HUES = ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]


def _rand_profile(rng):
    return {
        "exposure": {"bias": float(rng.uniform(-1.5, 1.5))},
        "white_balance": {"temperature_bias": float(rng.uniform(-0.8, 0.8)),
                           "tint_bias": float(rng.uniform(-0.8, 0.8))},
        "color_matrix": {"matrix_3x3": [
            [1 + rng.uniform(-0.2, 0.2), rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1)],
            [rng.uniform(-0.1, 0.1), 1 + rng.uniform(-0.2, 0.2), rng.uniform(-0.1, 0.1)],
            [rng.uniform(-0.1, 0.1), rng.uniform(-0.1, 0.1), 1 + rng.uniform(-0.2, 0.2)]]},
        "tone_curve": {"points": [
            [0.0, 0.0], [0.25, rng.uniform(0.1, 0.35)], [0.5, rng.uniform(0.4, 0.6)],
            [0.75, rng.uniform(0.65, 0.9)], [1.0, 1.0]]},
        "highlight_rolloff": {"threshold": float(rng.uniform(0.6, 0.9)),
                              "strength": float(rng.uniform(0.1, 0.9)), "saturation": 1.0},
        "shadow": {"black_point": float(rng.uniform(0.0, 0.15)),
                   "compression": float(rng.uniform(0.0, 0.6)),
                   "saturation": float(rng.uniform(0.8, 1.4))},
        "hsl": {n: {"hue_shift": float(rng.uniform(-20, 20)),
                    "saturation": float(rng.uniform(0.7, 1.4)),
                    "lightness": float(rng.uniform(0.8, 1.3))} for n in _HUES},
        # asymmetric centres on purpose: they exercise the uv convention
        # (texel centres, y counted from the TOP row) that a flip would break.
        "vignette": {"amount": float(rng.uniform(0.05, 0.6)),
                     "radius": float(rng.uniform(0.7, 1.3)),
                     "feather": float(rng.uniform(0.2, 0.9)),
                     "center": [float(rng.uniform(0.35, 0.65)),
                                float(rng.uniform(0.35, 0.65))]},
        # film curve (LAST pass): random floor/ceiling across the schema range,
        # including (floor=0, ceiling=255) so the disabled path is exercised too.
        "film_curve": {"shadow_floor": float(rng.choice([0.0, rng.uniform(2, 32)])),
                       "highlight_ceiling": float(rng.choice([255.0, rng.uniform(210, 250)]))},
        # blur / procedural layers zeroed (GPU-approximated, validated on-device)
        "sharpen": {"amount": 0.0}, "bloom": {"amount": 0.0},
        "halation": {"amount": 0.0}, "grain": {"amount": 0.0},
        "noise": {"luma": 0.0, "chroma": 0.0},
    }


def _rand_img(rng, h=64, w=64):
    return rng.random((h, w, 3)).astype(np.float32)


# float32 unit-roundoff level: the two implementations are the SAME math, so the
# only admissible residual is floating-point ordering noise.
TOL = 1e-5


def test_glsl_matches_cpu_reference():
    rng = np.random.default_rng(123)
    worst = 0.0
    for t in range(30):
        prof = _rand_profile(rng)
        img = _rand_img(rng)
        cpu = profile_renderer.render(img, prof)
        gpu = glsl_reference.render_deterministic(img, prof)
        diff = float(np.abs(cpu - gpu).max())
        worst = max(worst, diff)
        assert diff < TOL, f"iter {t}: max abs diff {diff:.2e} exceeds {TOL:.0e}"
    assert worst < TOL


def test_non_square_and_extreme_images():
    """Non-square frames + saturated/black patches (vignette uv + clamp paths)."""
    rng = np.random.default_rng(2026)
    for _ in range(6):
        prof = _rand_profile(rng)
        img = _rand_img(rng, 47, 83)
        img[:4, :, :] = 0.0
        img[-4:, :, :] = 1.0
        img[:, :3, :] = np.array([1.0, 0.0, 0.0], dtype=np.float32)
        cpu = profile_renderer.render(img, prof)
        gpu = glsl_reference.render_deterministic(img, prof)
        assert float(np.abs(cpu - gpu).max()) < TOL


def test_film_curve_boundaries():
    """film_curve guarantees: pixels never exceed the highlight ceiling nor fall
    below the shadow floor (both 0-255 display levels / 255), and both CPU
    references (profile_renderer vs glsl_reference) agree on the remap."""
    rng = np.random.default_rng(99)
    for floor, ceil in [(8.0, 248.0), (0.0, 248.0), (8.0, 255.0), (0.0, 255.0), (16.0, 230.0)]:
        prof = {
            "exposure": {"bias": 0.0},
            "white_balance": {"temperature_bias": 0.0, "tint_bias": 0.0},
            "color_matrix": {"matrix_3x3": [[1, 0, 0], [0, 1, 0], [0, 0, 1]]},
            "tone_curve": {"points": [[0.0, 0.0], [1, 1]]},
            "highlight_rolloff": {"threshold": 0.8, "strength": 0.0},
            "shadow": {"black_point": 0.0, "compression": 0.0, "saturation": 1.0},
            "hsl": {n: {} for n in _HUES},
            "vignette": {"amount": 0.0},
            "film_curve": {"shadow_floor": floor, "highlight_ceiling": ceil},
        }
        img = _rand_img(rng)
        img[0, :, :] = 0.0    # pure black input
        img[1, :, :] = 1.0    # pure white input
        cpu = profile_renderer.render(img, prof)
        gpu = glsl_reference.render_deterministic(img, prof)
        assert float(np.abs(cpu - gpu).max()) < TOL
        # only the film_curve stage guarantees these bounds; with the rest of
        # the chain at identity the output IS the film curve of the input.
        if floor > 0.0:
            assert cpu.min() >= floor / 255.0 - 1e-5, \
                f"floor {floor}: min {cpu.min():.5f} below floor"
        if ceil < 255.0:
            assert cpu.max() <= ceil / 255.0 + 1e-5, \
                f"ceiling {ceil}: max {cpu.max():.5f} above ceiling"


def test_identity_passthrough():
    rng = np.random.default_rng(1)
    prof = {
        "exposure": {"bias": 0.0},
        "white_balance": {"temperature_bias": 0.0, "tint_bias": 0.0},
        "color_matrix": {"matrix_3x3": [[1, 0, 0], [0, 1, 0], [0, 0, 1]]},
        "tone_curve": {"points": [[0.0, 0.0], [1, 1]]},
        "highlight_rolloff": {"threshold": 0.8, "strength": 0.0},
        "shadow": {"black_point": 0.0, "compression": 0.0, "saturation": 1.0},
        "hsl": {n: {} for n in _HUES},
        "vignette": {"amount": 0.0},
    }
    img = _rand_img(rng)
    cpu = profile_renderer.render(img, prof)
    gpu = glsl_reference.render_deterministic(img, prof)
    assert float(np.abs(cpu - gpu).max()) < 1e-5


def test_demo_profile_equivalence():
    here = os.path.dirname(__file__)
    demo = os.path.join(here, "..", "profiles", "demo", "profile_final.json")
    if not os.path.exists(demo):
        return
    with open(demo, "r", encoding="utf-8") as f:
        prof = json.load(f)
    # only the blur/procedural (GPU-approximated) layers are neutralised;
    # the real vignette of the shipped profile stays active.
    prof["sharpen"] = {"amount": 0.0}
    prof["bloom"] = {"amount": 0.0}
    prof["halation"] = {"amount": 0.0}
    prof["grain"] = {"amount": 0.0}
    prof["noise"] = {"luma": 0.0, "chroma": 0.0}
    rng = np.random.default_rng(7)
    for _ in range(5):
        img = _rand_img(rng, 96, 128)
        cpu = profile_renderer.render(img, prof)
        gpu = glsl_reference.render_deterministic(img, prof)
        assert float(np.abs(cpu - gpu).max()) < TOL


if __name__ == "__main__":
    test_glsl_matches_cpu_reference()
    test_non_square_and_extreme_images()
    test_film_curve_boundaries()
    test_identity_passthrough()
    test_demo_profile_equivalence()
    print("test_shader_equivalence: PASS")
