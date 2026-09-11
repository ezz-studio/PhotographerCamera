#!/usr/bin/env python3
"""stylefit v3.1 — iterative residual refinement ("train -> compare -> retrain").

Why
---
A single pass of the v3 distribution transfer leaves a measurable residual on
the table: the per-hue / per-luma corrections are ONE greedy boosting step over
a source distribution that is still far from the target. Running the same
estimator again on its own output — damped, capped, and gated by held-out
validation — closes more of that residual and improves cross-photo consistency
(style_distance on photos the model never saw), without repainting them.

Safety against overfitting / colour collapse (the whole point of this module)
-----------------------------------------------------------------------------
1. DAMPED ROUNDS. Round r learns with strength gamma_r = strength * DAMP^r and
   every cap scaled by the same factor. Each round can move a pixel only a
   fraction of what the previous round moved: the total transform is a
   contraction, it cannot run away no matter how many rounds are requested.
2. HELD-OUT GATE. Every candidate round is baked into a LUT and scored the
   same way the final profile is scored (style_distance, deltaE, structure
   correlation). A round is accepted ONLY if style_distance improves by at
   least MIN_REL_IMPROVE *relative* AND content fidelity does not degrade.
   A rejected round is discarded entirely — never averaged in, never "kept
   for later". When no honest held-out set exists (too few un-graded photos
   to split), the gate switches to K-fold cross-validation over the un-graded
   corpus, so the decision is never made on data the round was trained on.
3. BOUNDED TOTAL CHROMA GAIN. Singular values of the COMPOSED chroma matrix
   M_k...M_1 are tracked; a round that would push total saturation gain past
   TOTAL_GAIN_CAP is rejected outright. Oversaturation is the visible form of
   "colour collapse"; this makes it structurally impossible.
4. MONOTONE TONE. Every stage's L-curve is monotone by construction and the
   composition of monotone maps is monotone — tonal order can never invert,
   in any round, ever.
5. HUE DISTRIBUTION IS NEVER MATCHED. Hue histograms are content (grass, sky,
   skin), not style — unchanged from v3, and the residual rounds measure the
   same bounded per-hue mean offsets, not hue redistribution.

Accepted-round trace is returned so the caller (Studio report panel) can show
the round-by-round comparison the user asked for.
"""
from __future__ import annotations

import numpy as np

from . import transfer as TR
from . import lut3d as L3

# ---- round schedule -------------------------------------------------------
DAMP = 0.55             # gamma_r = strength * DAMP^r  (contraction factor)
MIN_GAMMA = 0.10        # residual rounds never learn stronger than this
RETRIES = 1             # extra attempts per round at a shrunken step
RETRY_SHRINK = 0.4      # rejected round retries once with gamma * 0.4
# ---- caps -----------------------------------------------------------------
CAP_FLOOR = {"shape": 3.0, "resid": 1.0, "tint": 1.0, "mu": 0.5}
TOTAL_GAIN_CAP = 2.2    # max singular value of the COMPOSED chroma matrix
# ---- acceptance gate ------------------------------------------------------
MIN_REL_IMPROVE = 0.02  # round must cut style_distance by >= 2% (relative)
STRUCT_MIN = 0.90       # structure correlation hard floor
STRUCT_TOL = 0.004      # structure corr may not drop more than this vs best
DE_MIN, DE_MAX = 4.0, 25.0   # deltaE sanity band (v3 semantics: 4=too weak)


# --------------------------------------------------------------------- model
class CompositeModel:
    """A sequence of v3 transfer models applied one after another.

    Behaves like a single model for the two operations the pipeline needs:
    apply(rgb) and bake(n) -> 33^3 LUT with 100% coverage.
    """

    def __init__(self, models):
        self.models = [m for m in models]

    def __len__(self):
        return len(self.models)

    def extended(self, model) -> "CompositeModel":
        return CompositeModel(self.models + [model])

    def apply(self, rgb):
        out = np.asarray(rgb, np.float32)
        for m in self.models:
            out = TR.apply(m, out)
        return out

    def bake(self, n=33):
        axis = np.linspace(0.0, 1.0, n, dtype=np.float32)
        B, G, R = np.meshgrid(axis, axis, axis, indexing="ij")
        grid = np.stack([R, G, B], axis=-1).reshape(-1, 3)
        out = self.apply(grid)
        return out.reshape(n, n, n, 3).astype(np.float32)

    def total_gain(self) -> float:
        """Largest singular value of the composed chroma map (saturation
        amplification the whole chain applies to the (a,b) plane)."""
        M = np.eye(2)
        for m in self.models:
            M = np.asarray(m["M"], np.float64) @ M
        return float(np.linalg.svd(M, compute_uv=False).max())

    # -- composed 1-D luminance curve (independent of a,b at every stage) ----
    @property
    def L_grid(self):
        return self.models[0]["L_grid"].astype(np.float64)

    @property
    def L_curve(self):
        grid = self.L_grid
        y = grid.copy()
        for m in self.models:
            y = np.interp(y, np.asarray(m["L_grid"], np.float64),
                          np.asarray(m["L_curve"], np.float64))
        return np.maximum.accumulate(y)

    def as_curve_model(self):
        """Minimal dict with just L_grid/L_curve — enough for the parametric
        tone-curve fallback in fit._tone_curve_from_model."""
        return {"L_grid": self.L_grid.astype(np.float32),
                "L_curve": self.L_curve.astype(np.float32)}


# ------------------------------------------------------------------ scoring
def _score_lut(lut, eval_images, ref_stats):
    """Score a baked LUT exactly the way the final profile is scored.
    NOTE: import the functions, not `from . import fit` — the package
    re-binds the name `fit` to the fit() *function* in __init__, which would
    shadow the module here (lazy import also avoids the load-time cycle)."""
    from .fit import evaluate as _evaluate
    rep = _evaluate(lambda im: L3.sample(lut, im), eval_images, ref_stats)
    cf = rep.get("content_fidelity", {})
    return {
        "style_distance": rep.get("style_distance"),
        "deltaE": cf.get("deltaE_mean"),
        "structure": cf.get("structure_correlation"),
    }


def _cv_score(chain_factory, plains, ref_stats, k, seed, lut_size, fit_px):
    """K-fold honest score of a round schedule over the un-graded corpus.

    chain_factory(train_px) -> callable/ModelLike with .bake(); for each fold
    the     chain is re-trained on K-1 folds and scored on the held-out fold, so
    the number measures generalisation, exactly like fit.cross_validate.
    """
    from . import color as C
    rng = np.random.default_rng(seed)
    n = len(plains)
    if n < k:
        k = max(2, n // 2) if n >= 4 else 0
    if k < 2:
        return None
    order = rng.permutation(n)
    folds = np.array_split(order, k)
    sd, de, st = [], [], []
    for fi in range(k):
        test_idx = [int(i) for i in folds[fi]]
        train_idx = [int(i) for f in range(k) if f != fi for i in folds[f]]
        if not train_idx or not test_idx:
            continue
        parts = []
        for im in (plains[i] for i in train_idx):
            flat = im.reshape(-1, 3)
            if flat.shape[0] > fit_px:
                sel = rng.choice(flat.shape[0], fit_px, replace=False)
                flat = flat[sel]
            parts.append(flat)
        train_px = np.concatenate(parts)
        chain = chain_factory(train_px, rng)
        if chain is None:
            return None
        lut = chain.bake(lut_size)
        test = [plains[i] for i in test_idx]
        s = _score_lut(lut, test, ref_stats)
        if s["style_distance"] is None:
            return None
        sd.append(s["style_distance"]); de.append(s["deltaE"] or 0.0)
        st.append(s["structure"] or 1.0)
    if not sd:
        return None
    return {"style_distance": float(np.mean(sd)), "deltaE": float(np.mean(de)),
            "structure": float(np.mean(st))}


def _round_params(strength: float, r: int):
    """Damped learning rate + caps for round r (contraction schedule)."""
    gamma = max(MIN_GAMMA, strength * (DAMP ** r))
    caps = {}
    for key, floor in CAP_FLOOR.items():
        base = {"shape": TR.SHAPE_CAP, "resid": TR.RESID_CAP, "tint": TR.TINT_CAP,
                "mu": TR.MU_CAP}[key]
        caps[key] = max(floor, base * (DAMP ** (r - 1)))
    return gamma, caps


def _gate_ok(score, best):
    """The anti-overfit acceptance rule. Conservative by design: when in
    doubt, reject — the previous chain is already a valid profile."""
    s, b = score.get("style_distance"), best.get("style_distance")
    if s is None or b is None:
        return False, "no-score"
    if not s <= float(b) * (1.0 - MIN_REL_IMPROVE):
        return False, f"style_distance {s} vs best {b} (<{MIN_REL_IMPROVE:.0%} gain)"
    st = score.get("structure")
    stb = best.get("structure")
    if st is None or float(st) < STRUCT_MIN:
        return False, f"structure {st} < {STRUCT_MIN}"
    if stb is not None and float(st) < float(stb) - STRUCT_TOL:
        return False, f"structure dropped {stb} -> {st}"
    de = score.get("deltaE")
    if de is None or not (DE_MIN <= float(de) <= DE_MAX):
        return False, f"deltaE {de} outside [{DE_MIN}, {DE_MAX}]"
    return True, "ok"


def _finish(chain, lut, trace, gate_desc, rounds, say):
    say(f"  迭代训练完成：接受 {len(chain) - 1} 个残差轮次"
        f"（门控={gate_desc}，组合色度增益 {chain.total_gain():.2f}）")
    return chain, lut, trace


# -------------------------------------------------------------------- refine
def refine(model0, src_px, dst_px, eval_images, plains, ref_stats,
           strength: float = 0.8, rounds: int = 3, lut_size: int = 33,
           progress=None, seed: int = 20240917, fit_px: int = 60000):
    """Iteratively refine the round-0 transfer model.

    model0   : the v3 model learned by transfer.learn(src_px, dst_px)
    src_px   : (N,3) un-graded source pixels (the round-0 input distribution)
    dst_px   : (N,3) graded target pixels
    eval_images : HELD-OUT un-graded images for the direct gate (may be None)
    plains   : un-graded fit-side images for the K-fold gate fallback
    ref_stats: target statistics dict (fit computes it from the graded set)
    rounds   : max additional rounds (1 = legacy single-pass behaviour)

    Returns (chain, lut, trace). `chain` is a CompositeModel even when no
    extra round is accepted, so downstream code has one shape to handle.
    """
    say = progress or (lambda m: None)
    rounds = max(1, int(rounds))

    chain = CompositeModel([model0])
    lut = chain.bake(lut_size)

    trace = []

    def note(r, accepted, score, reason="", extra=None):
        row = {"round": r, "accepted": bool(accepted), "reason": reason}
        if score:
            row.update({k: (round(v, 4) if isinstance(v, float) else v)
                        for k, v in score.items()})
        if extra:
            row.update(extra)
        trace.append(row)
        say(f"    轮次 {r}: {'接受' if accepted else '拒绝'}"
            + (f" | style_distance={score.get('style_distance')}"
               f" ΔE={score.get('deltaE')} 结构相关={score.get('structure')}"
               if score else f" | {reason}"))

    # ---------------- gate selection ----------------
    # Direct gate needs >=3 held-out images; with fewer the score would be
    # decided by one or two frames, which is exactly how overfitting sneaks
    # in. Fall back to 4-fold CV over the un-graded corpus instead.
    direct_eval = ([np.clip(np.asarray(x, np.float32), 0.0, 1.0)
                    for x in eval_images] if eval_images is not None else [])
    use_direct = len(direct_eval) >= 3
    if use_direct:
        best = _score_lut(lut, direct_eval, ref_stats)
        note(0, True, best, "base")

        def score_candidate(cand_lut):
            return _score_lut(cand_lut, direct_eval, ref_stats)
        gate_desc = "held-out"
    else:
        best = None
        gate_desc = "kfold"
        say("    验证集不足 3 张：轮次门控切换为对未调色集的 4 折交叉验证")

    for r in range(1, rounds + 1):
        base_gamma, base_caps = _round_params(strength, r)
        attempt = 0
        while True:                                   # one retry at a smaller step
            gamma = base_gamma * (RETRY_SHRINK ** attempt)
            caps = {k: max(CAP_FLOOR[k], v * (RETRY_SHRINK ** attempt))
                    for k, v in base_caps.items()}
            if gamma <= 0:
                break

        # -- candidate construction ------------------------------------
            def build_candidate(src_pixels):
                m, _ = TR.learn(src_pixels, dst_px, strength=gamma,
                                shape_cap=caps["shape"], resid_cap=caps["resid"],
                                tint_cap=caps["tint"], mu_cap=caps["mu"])
                return chain.extended(m)

        # -- gate --------------------------------------------------------
            if use_direct:
                cand = build_candidate(chain.apply(src_px))
                gain = cand.total_gain()
                if gain > TOTAL_GAIN_CAP:
                    note(r, False, None, f"组合色度增益 {gain:.2f} 超过上限 {TOTAL_GAIN_CAP}（防颜色崩坏）")
                    return _finish(chain, lut, trace, gate_desc, rounds, say)
                cand_lut = cand.bake(lut_size)
                score = score_candidate(cand_lut)
                ok, reason = _gate_ok(score, best)
                note(r, ok, score, reason, {"gamma": round(gamma, 3),
                                            "total_gain": round(gain, 3)})
                if ok:
                    chain, lut, best = cand, cand_lut, score
                    break
            else:
                # K-fold gate: score the WHOLE schedule (base + rounds 1..r)
                # per fold, honestly, then decide once for the round.
                def chain_factory(train_px, rng):
                    m0, _ = TR.learn(train_px, dst_px, strength=strength)
                    c = CompositeModel([m0])
                    for j in range(1, r):         # replay already-accepted rounds
                        g_j, caps_j = _round_params(strength, j)
                        s = c.apply(train_px)
                        mm, _ = TR.learn(s, dst_px, strength=g_j,
                                         shape_cap=caps_j["shape"], resid_cap=caps_j["resid"],
                                         tint_cap=caps_j["tint"], mu_cap=caps_j["mu"])
                        c = c.extended(mm)
                    s = c.apply(train_px)
                    mm, _ = TR.learn(s, dst_px, strength=gamma,
                                     shape_cap=caps["shape"], resid_cap=caps["resid"],
                                     tint_cap=caps["tint"], mu_cap=caps["mu"])
                    return c.extended(mm)

                score = _cv_score(chain_factory, plains, ref_stats, k=4, seed=seed,
                                  lut_size=lut_size, fit_px=fit_px)
                if score is None:
                    note(r, False, None, "交叉验证不可用（未调色图太少）")
                    return _finish(chain, lut, trace, gate_desc, rounds, say)
                if best is None:      # round-0 baseline needs a CV score too
                    def base_factory(train_px, rng):
                        m0, _ = TR.learn(train_px, dst_px, strength=strength)
                        return CompositeModel([m0])
                    best = _cv_score(base_factory, plains, ref_stats, k=4, seed=seed,
                                     lut_size=lut_size, fit_px=fit_px)
                    if best is None:
                        note(r, False, None, "基线交叉验证不可用")
                        return _finish(chain, lut, trace, gate_desc, rounds, say)
                    say(f"    基线（第 0 轮）交叉验证：style_distance={best['style_distance']:.4f}"
                        f" ΔE={best['deltaE']:.3f} 结构相关={best['structure']:.4f}")
                gain_estimate = chain.total_gain() * (1.0 + gamma * 0.8)
                if gain_estimate > TOTAL_GAIN_CAP:
                    note(r, False, None, f"预计组合色度增益 {gain_estimate:.2f} 超上限（防颜色崩坏）")
                    return _finish(chain, lut, trace, gate_desc, rounds, say)
                ok, reason = _gate_ok(score, best)
                note(r, ok, score, reason, {"gamma": round(gamma, 3), "gate": "kfold"})
                if ok:
                    cand = build_candidate(chain.apply(src_px))
                    if cand.total_gain() > TOTAL_GAIN_CAP:
                        note(r, False, None,
                             f"组合色度增益 {cand.total_gain():.2f} 超上限（防颜色崩坏）")
                        return _finish(chain, lut, trace, gate_desc, rounds, say)
                    chain = cand
                    lut = chain.bake(lut_size)
                    best = score
                    break

            # rejected: retry once at a much smaller step before giving up
            attempt += 1
            if attempt > RETRIES:
                # a full round failed at every step size: the schedule has
                # converged (or would overfit) — stop refining entirely
                return _finish(chain, lut, trace, gate_desc, rounds, say)

    return _finish(chain, lut, trace, gate_desc, rounds, say)
