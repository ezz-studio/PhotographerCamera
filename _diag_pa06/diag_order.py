#!/usr/bin/env python3
"""PA06 read-only diagnosis: bisect the CPU vs GLSL numerical divergence.

Reproduces the three failing cases of tests/test_shader_equivalence.py and
localizes the FIRST stage that produces a significant deviation, by
  (a) proving per-stage math equivalence (lockstep, same input -> both fns),
  (b) swapping ONLY the stage ORDER in a local copy of the GLSL chain,
  (c) tracing the cumulative divergence stage by stage.

Writes nothing outside this directory. Run:
  C:/Users/70898/.workbuddy/binaries/python/envs/default/Scripts/python.exe _diag_pa06/diag_order.py
"""
import json
import os
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import profile_renderer as PR   # noqa: E402
import glsl_reference as GL     # noqa: E402

_HUES = ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]
TOL = 1e-5


def _rand_profile(rng):  # verbatim copy of the test's generator
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
        "vignette": {"amount": float(rng.uniform(0.05, 0.6)),
                     "radius": float(rng.uniform(0.7, 1.3)),
                     "feather": float(rng.uniform(0.2, 0.9)),
                     "center": [float(rng.uniform(0.35, 0.65)),
                                float(rng.uniform(0.35, 0.65))]},
        "film_curve": {"shadow_floor": float(rng.choice([0.0, rng.uniform(2, 32)])),
                       "highlight_ceiling": float(rng.choice([255.0, rng.uniform(210, 250)]))},
        "sharpen": {"amount": 0.0}, "bloom": {"amount": 0.0},
        "halation": {"amount": 0.0}, "grain": {"amount": 0.0},
        "noise": {"luma": 0.0, "chroma": 0.0},
    }


def _rand_img(rng, h=64, w=64):
    return rng.random((h, w, 3)).astype(np.float32)


def d(a, b):
    return float(np.abs(np.asarray(a, dtype=np.float64) - np.asarray(b, dtype=np.float64)).max())


# ---------------------------------------------------------------------------
# Chain builders. Both use ONLY the two modules' own stage functions; the only
# thing varied is the ORDER of the stage calls. No source file is modified.
# ---------------------------------------------------------------------------
def cpu_stages(p):
    """profile_renderer.render() order (parametric path, lens no-op w/o cv2)."""
    fc = p.get("film_curve", {}) or {}
    vg = p.get("vignette", {}) or {}
    return [
        ("exposure",  lambda c: PR.apply_exposure(c, p["exposure"]["bias"])),
        ("wb",        lambda c: PR.apply_white_balance(
                          c, p["white_balance"]["temperature_bias"], p["white_balance"]["tint_bias"])),
        ("matrix",    lambda c: PR.apply_color_matrix(c, p["color_matrix"]["matrix_3x3"])),
        ("highlight", lambda c: PR.apply_highlight_rolloff(
                          c, p["highlight_rolloff"]["threshold"], p["highlight_rolloff"]["strength"])),
        ("shadow",    lambda c: PR.apply_shadow(
                          c, p["shadow"]["black_point"], p["shadow"]["compression"],
                          None, p["shadow"]["saturation"], 1.0)),
        ("film",      lambda c: PR.apply_film_curve(
                          c, fc.get("shadow_floor", 8.0), fc.get("highlight_ceiling", 248.0))),
        ("tone",      lambda c: PR.apply_tone_curve(c, p["tone_curve"]["points"])),
        ("hsl",       lambda c: PR.apply_hsl(c, p["hsl"])),
        ("vignette",  lambda c: PR.apply_vignette(
                          c, vg.get("amount", 0.0), vg.get("radius", 1.0),
                          vg.get("feather", 0.5), vg.get("center", [0.5, 0.5]))),
    ]


def glsl_stages(p, order):
    """glsl_reference stage functions, invoked in the given order.
    order: 'asis'  = render_deterministic() order (tone early, film last)
           'fixed' = Android effect.frag order (highlight, shadow, film, tone)"""
    fc = p.get("film_curve", {}) or {}
    floor = float(fc.get("shadow_floor", 8.0))
    ceil = float(fc.get("highlight_ceiling", 248.0))
    lut_tone = GL.bake_tone_lut(p["tone_curve"]["points"])
    hsl_lut = GL.bake_hsl_lut(p["hsl"])
    seq = {
        "exposure":  lambda c: GL.pc_exposure(c, p["exposure"]["bias"]),
        "wb":        lambda c: GL.pc_white_balance(
                          c, p["white_balance"]["temperature_bias"], p["white_balance"]["tint_bias"]),
        "matrix":    lambda c: GL.pc_color_matrix(c, p["color_matrix"]["matrix_3x3"]),
        "tone":      lambda c: GL.pc_tone_curve(c, lut_tone),
        "highlight": lambda c: GL.pc_highlight(
                          c, p["highlight_rolloff"]["threshold"], p["highlight_rolloff"]["strength"], 1.0),
        "shadow":    lambda c: GL.pc_shadow(
                          c, p["shadow"]["black_point"], p["shadow"]["compression"], p["shadow"]["saturation"]),
        "hsl":       lambda c: GL.pc_hsl(c, hsl_lut),
        "film":      lambda c: GL.pc_film_curve(c, floor / 255.0, ceil / 255.0),
        "vignette":  None,  # needs image shape; filled by caller
    }
    if order == "asis":
        names = ["exposure", "wb", "matrix", "tone", "highlight", "shadow", "hsl", "vignette", "film"]
    else:  # Android effect.frag / profile_renderer order
        names = ["exposure", "wb", "matrix", "highlight", "shadow", "film", "tone", "hsl", "vignette"]
    return names, seq


def run_glsl(img, p, order):
    names, seq = glsl_stages(p, order)
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xx + 0.5) / w, (yy + 0.5) / h], axis=-1)
    vg = p.get("vignette", {}) or {}
    seq["vignette"] = (lambda c: GL.pc_vignette(c, uv, vg.get("center", [0.5, 0.5]),
                                                vg.get("amount", 0.0), vg.get("radius", 1.0),
                                                vg.get("feather", 0.5)))
    c = img.astype(np.float32)
    for n in names:
        c = seq[n](c)
    return np.clip(c, 0.0, 1.0)


def run_cpu_stages(img, p):
    c = img.astype(np.float32)
    for _n, fn in cpu_stages(p):
        c = fn(c)
    return np.clip(c, 0.0, 1.0)


def main():
    print("=" * 78)
    print("PART 1 — reproduce test_shader_equivalence failures")
    print("=" * 78)

    # --- test 1, iter 0 (seed 123) ---
    rng = np.random.default_rng(123)
    for t in range(30):
        prof = _rand_profile(rng)
        img = _rand_img(rng)
        if t == 0:
            break
    cpu = PR.render(img, prof)
    gpu = GL.render_deterministic(img, prof)
    print(f"[test1 iter0] profile_renderer vs glsl_reference  max|diff| = {d(cpu, gpu):.6e}")
    print(f"[test1 iter0] film_curve in profile: floor={prof['film_curve']['shadow_floor']:.3f} "
          f"ceiling={prof['film_curve']['highlight_ceiling']:.3f}  "
          f"vignette amount={prof['vignette']['amount']:.3f}")

    # --- test 2, first of 6 (seed 2026, 47x83 + patches) ---
    rng = np.random.default_rng(2026)
    prof2 = _rand_profile(rng)
    img2 = _rand_img(rng, 47, 83)
    img2[:4, :, :] = 0.0
    img2[-4:, :, :] = 1.0
    img2[:, :3, :] = np.array([1.0, 0.0, 0.0], dtype=np.float32)
    cpu2 = PR.render(img2, prof2)
    gpu2 = GL.render_deterministic(img2, prof2)
    print(f"[test2 iter0] 47x83 + patches                    max|diff| = {d(cpu2, gpu2):.6e}")

    # --- test 3, demo profile, 5 iters (seed 7, 96x128) ---
    with open(os.path.join(ROOT, "profiles", "demo", "profile_final.json"), encoding="utf-8") as f:
        demo = json.load(f)
    for k in ("sharpen", "bloom", "halation", "grain"):
        demo[k] = {"amount": 0.0}
    demo["noise"] = {"luma": 0.0, "chroma": 0.0}
    rng = np.random.default_rng(7)
    worst3 = 0.0
    for _ in range(5):
        img3 = _rand_img(rng, 96, 128)
        worst3 = max(worst3, d(PR.render(img3, demo), GL.render_deterministic(img3, demo)))
    print(f"[test3 demo]  5x (96x128), vignette=0.6227        max|diff| = {worst3:.6e}")
    print(f"[test3 demo]  demo film_curve key present: {'film_curve' in demo} "
          f"(defaults 8.0/248.0 apply on BOTH sides)")

    print()
    print("=" * 78)
    print("PART 2 — per-stage math equivalence (same input, both implementations)")
    print("=" * 78)
    # lockstep: feed identical intermediate into each stage of both modules
    c = img.astype(np.float32)
    p = prof
    pairs = [
        ("exposure",  PR.apply_exposure(c, p["exposure"]["bias"]),
                     GL.pc_exposure(c, p["exposure"]["bias"])),
        ("wb",        PR.apply_white_balance(c, p["white_balance"]["temperature_bias"], p["white_balance"]["tint_bias"]),
                     GL.pc_white_balance(c, p["white_balance"]["temperature_bias"], p["white_balance"]["tint_bias"])),
        ("matrix",    PR.apply_color_matrix(c, p["color_matrix"]["matrix_3x3"]),
                     GL.pc_color_matrix(c, p["color_matrix"]["matrix_3x3"])),
        ("tone",      PR.apply_tone_curve(c, p["tone_curve"]["points"]),
                     GL.pc_tone_curve(c, GL.bake_tone_lut(p["tone_curve"]["points"]))),
        ("highlight", PR.apply_highlight_rolloff(c, p["highlight_rolloff"]["threshold"], p["highlight_rolloff"]["strength"]),
                     GL.pc_highlight(c, p["highlight_rolloff"]["threshold"], p["highlight_rolloff"]["strength"], 1.0)),
        ("shadow",    PR.apply_shadow(c, p["shadow"]["black_point"], p["shadow"]["compression"], None, p["shadow"]["saturation"], 1.0),
                     GL.pc_shadow(c, p["shadow"]["black_point"], p["shadow"]["compression"], p["shadow"]["saturation"])),
        ("film",      PR.apply_film_curve(c, p["film_curve"]["shadow_floor"], p["film_curve"]["highlight_ceiling"]),
                     GL.pc_film_curve(c, p["film_curve"]["shadow_floor"] / 255.0, p["film_curve"]["highlight_ceiling"] / 255.0)),
        ("hsl",       PR.apply_hsl(c, p["hsl"]),
                     GL.pc_hsl(c, GL.bake_hsl_lut(p["hsl"]))),
    ]
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xx + 0.5) / w, (yy + 0.5) / h], axis=-1)
    pairs.append(("vignette", PR.apply_vignette(c, p["vignette"]["amount"], p["vignette"]["radius"],
                                                p["vignette"]["feather"], p["vignette"]["center"]),
                  GL.pc_vignette(c, uv, p["vignette"]["center"], p["vignette"]["amount"],
                                 p["vignette"]["radius"], p["vignette"]["feather"])))
    for name, a, b in pairs:
        print(f"  stage {name:10s} same-input max|diff| = {d(a, b):.3e}")

    print()
    print("=" * 78)
    print("PART 3 — order bisect on test1 iter0 (only stage ORDER varies)")
    print("=" * 78)
    ref = run_cpu_stages(img, prof)
    asis = run_glsl(img, prof, "asis")
    fixed = run_glsl(img, prof, "fixed")
    print(f"  CPU (profile_renderer order)                 vs itself-check: {d(ref, cpu):.3e}")
    print(f"  GLSL as-is  (tone@4, film@9)                max|diff| = {d(asis, ref):.6e}")
    print(f"  GLSL fixed  (hl,sh,film@6, tone@7)          max|diff| = {d(fixed, ref):.6e}")

    # one-at-a-time isolation on the as-is chain
    names, seq = glsl_stages(prof, "asis")
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xx + 0.5) / w, (yy + 0.5) / h], axis=-1)
    vg = prof["vignette"]
    seq["vignette"] = (lambda c: GL.pc_vignette(c, uv, vg["center"],
                                                vg["amount"], vg["radius"], vg["feather"]))
    # parallel advance: both chains in THEIR OWN order, compare after k stages
    cpu_names = [n for n, _ in cpu_stages(prof)]
    print("  cumulative divergence (both chains in own order, aligned by stage count):")
    cg = img.astype(np.float32)
    cc = img.astype(np.float32)
    cpu_seq = dict(cpu_stages(prof))
    for k in range(len(names)):
        cg = seq[names[k]](cg)
        cc = cpu_seq[cpu_names[k]](cc)
        print(f"    after stage {k + 1} ({names[k]:>9s} vs {cpu_names[k]:<9s}): max|diff| = {d(cg, cc):.3e}")
    c = img.astype(np.float32)

    # isolate: only tone moved (film still last)
    names2 = ["exposure", "wb", "matrix", "highlight", "shadow", "tone", "hsl", "vignette", "film"]
    c = img.astype(np.float32)
    for n in names2:
        c = seq[n](c)
    print(f"  GLSL tone-moved-only (film still last)       max|diff| = {d(c, ref):.6e}")
    # isolate: only film moved (tone still early)
    names3 = ["exposure", "wb", "matrix", "tone", "highlight", "shadow", "film", "hsl", "vignette"]
    c = img.astype(np.float32)
    for n in names3:
        c = seq[n](c)
    print(f"  GLSL film-moved-only (tone still early)      max|diff| = {d(c, ref):.6e}")

    print()
    print("=" * 78)
    print("PART 4 — first divergence: same input, next stage differs")
    print("=" * 78)
    c = img.astype(np.float32)
    for fn in (PR.apply_exposure, ):
        pass
    c = PR.apply_exposure(c, prof["exposure"]["bias"])
    c = PR.apply_white_balance(c, prof["white_balance"]["temperature_bias"], prof["white_balance"]["tint_bias"])
    c = PR.apply_color_matrix(c, prof["color_matrix"]["matrix_3x3"])
    cpu_next = PR.apply_highlight_rolloff(c, prof["highlight_rolloff"]["threshold"], prof["highlight_rolloff"]["strength"])
    gpu_next = GL.pc_tone_curve(c, GL.bake_tone_lut(prof["tone_curve"]["points"]))
    print(f"  input: post-matrix image. CPU next=highlight, GLSL next=tone_curve")
    print(f"  FIRST significant deviation max|diff| = {d(cpu_next, gpu_next):.6e}")
    print(f"  (highlight output range [{cpu_next.min():.4f},{cpu_next.max():.4f}], "
          f"tone output range [{gpu_next.min():.4f},{gpu_next.max():.4f}])")

    print()
    print("=" * 78)
    print("PART 5 — fixed order vs the three failing scenarios")
    print("=" * 78)
    print(f"  [test1 iter0] as-is {d(asis, ref):.3e} -> fixed {d(fixed, ref):.3e}  (TOL {TOL:.0e})")
    f2 = run_glsl(img2, prof2, "fixed")
    print(f"  [test2 iter0] as-is {d(gpu2, cpu2):.3e} -> fixed {d(f2, cpu2):.3e}")
    worst3f = 0.0
    rng = np.random.default_rng(7)
    for _ in range(5):
        img3 = _rand_img(rng, 96, 128)
        worst3f = max(worst3f, d(PR.render(img3, demo), run_glsl(img3, demo, "fixed")))
    print(f"  [test3 demo ] as-is {worst3:.3e} -> fixed {worst3f:.3e}")

    # full 30-iteration test1 with fixed order
    rng = np.random.default_rng(123)
    worst = 0.0
    for t in range(30):
        pr = _rand_profile(rng)
        im = _rand_img(rng)
        worst = max(worst, d(PR.render(im, pr), run_glsl(im, pr, "fixed")))
    print(f"  [test1 full 30 iters] fixed-order worst max|diff| = {worst:.3e}")

    # vignette uv symmetry check on the non-square frame (both modules, same math)
    vg = prof2["vignette"]
    a = PR.apply_vignette(img2, vg["amount"], vg["radius"], vg["feather"], vg["center"])
    h, w = img2.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    uv = np.stack([(xx + 0.5) / w, (yy + 0.5) / h], axis=-1)
    b = GL.pc_vignette(img2, uv, vg["center"], vg["amount"], vg["radius"], vg["feather"])
    print(f"  [vignette uv] non-square 47x83 same-input max|diff| = {d(a, b):.3e} (uv convention identical)")


if __name__ == "__main__":
    main()
