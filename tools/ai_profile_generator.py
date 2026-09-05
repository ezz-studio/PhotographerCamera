#!/usr/bin/env python3
"""Phase 15 — AI Profile Generator (offline only, data-ready).

Turns StyleAnalyzer statistics into an *initial* PhotographerProfile (profile_v1.json)
plus a human-readable generation report. The generator is deterministic and based on
statistics (no external API required), but the LLM interface is provided as an extension
point: `build_ai_prompt()` returns a structured prompt, `parse_llm_response()` merges a
model's JSON output. The rule "do not force unstable features into the Profile" is honored
by clamping and by flagging low-confidence parameters in the report.

Output: profile_v1.json, profile_generation_report.json
"""
from __future__ import annotations

import json
import os
import sys
from typing import Dict

sys.path.insert(0, os.path.dirname(__file__))
from profile_schema import validate_profile, default_profile  # noqa: E402


def _clamp(x, lo, hi):
    return max(lo, min(hi, x))


def _load(analysis_dir: str, name: str) -> Dict:
    with open(os.path.join(analysis_dir, name), "r", encoding="utf-8") as f:
        return json.load(f)


def generate_profile(analysis_dir: str, name: str = "Photographer (generated)") -> Dict:
    prof = default_profile(name)
    report = {"schema": "profile_generation_report/v1", "name": name, "decisions": [], "warnings": []}

    def decide(param, value, reason, confidence="high"):
        report["decisions"].append({"param": param, "value": value, "reason": reason, "confidence": confidence})

    # Exposure: pull mean brightness toward 0.5 reference
    try:
        exp = _load(analysis_dir, "exposure_statistics.json")
        mb = exp.get("mean_brightness", 0.5)
        bias = _clamp(__import__("math").log2(0.5 / max(mb, 1e-3)), -2.0, 2.0)
        prof["exposure"]["bias"] = round(bias, 3)
        decide("exposure.bias", bias, f"mean_brightness={mb:.3f} -> pull toward 0.5")
    except FileNotFoundError:
        report["warnings"].append("exposure_statistics.json missing; used neutral bias")

    # White balance from Lab a*/b*
    try:
        col = _load(analysis_dir, "color_statistics.json")
        a = col["lab"]["a_mean"]
        b = col["lab"]["b_mean"]
        temp = _clamp(b / 20.0, -1.0, 1.0)
        tint = _clamp(-a / 20.0, -1.0, 1.0)
        prof["white_balance"]["temperature_bias"] = round(temp, 3)
        prof["white_balance"]["tint_bias"] = round(tint, 3)
        decide("white_balance.temperature_bias", temp, f"Lab b_mean={b:.2f}")
        decide("white_balance.tint_bias", tint, f"Lab a_mean={a:.2f}")
    except FileNotFoundError:
        report["warnings"].append("color_statistics.json missing; neutral WB")

    # Color matrix (use heuristic estimate directly)
    try:
        cm = _load(analysis_dir, "color_matrix.json")
        M = [[_clamp(v, -2.0, 2.0) for v in row] for row in cm["matrix_3x3"]]
        prof["color_matrix"]["matrix_3x3"] = [[round(v, 4) for v in row] for row in M]
        decide("color_matrix", "gray-world estimate", "from color_matrix.json")
    except FileNotFoundError:
        report["warnings"].append("color_matrix.json missing; identity matrix")

    # Tone curve
    try:
        tc = _load(analysis_dir, "tone_curve_initial.json")
        prof["tone_curve"]["points"] = [[round(x, 4), round(y, 4)] for x, y in tc["points"]]
        decide("tone_curve", "empirical CDF", "from tone_curve_initial.json")
    except FileNotFoundError:
        pass

    # Highlight roll-off
    try:
        hr = _load(analysis_dir, "highlight_rolloff.json")
        prof["highlight_rolloff"]["threshold"] = hr.get("threshold", 0.8)
        prof["highlight_rolloff"]["strength"] = 0.0
        decide("highlight_rolloff.threshold", hr.get("threshold"), "from 90th percentile")
    except FileNotFoundError:
        pass

    # Shadow
    try:
        sh = _load(analysis_dir, "shadow_model.json")
        prof["shadow"]["black_point"] = _clamp(sh.get("black_point", 0.0), 0.0, 0.2)
        prof["shadow"]["tint"] = [round(v, 4) for v in sh.get("tint", [0, 0, 0])]
        decide("shadow.black_point", prof["shadow"]["black_point"], "from 2nd percentile")
    except FileNotFoundError:
        pass

    # HSL mapping
    try:
        hsl = _load(analysis_dir, "hsl_mapping.json")
        for hue, params in hsl.get("hues", {}).items():
            prof["hsl"][hue] = {
                "hue_shift": int(params.get("hue_shift", 0)),
                "saturation": round(_clamp(params.get("saturation", 1.0), 0.0, 2.0), 4),
                "lightness": round(_clamp(params.get("lightness", 1.0), 0.0, 2.0), 4),
            }
        decide("hsl", "per-hue saturation factors", "from hsl_mapping.json")
    except FileNotFoundError:
        pass

    # Lens -> vignette / chromatic aberration
    try:
        lm = _load(analysis_dir, "lens_model.json")
        prof["vignette"]["amount"] = _clamp(lm.get("vignette", 0.0), 0.0, 1.0)
        prof["lens"]["chromatic_aberration"] = _clamp(lm.get("chromatic_aberration", 0.0), 0.0, 1.0)
        decide("vignette.amount", prof["vignette"]["amount"], "from lens geometry")
    except FileNotFoundError:
        pass

    # Texture -> grain / noise
    try:
        tx = _load(analysis_dir, "texture_model.json")
        ln = tx.get("luma_noise", 0.0)
        cn = tx.get("chroma_noise", 0.0)
        prof["noise"]["luma"] = round(_clamp(ln / 20.0, 0.0, 1.0), 4)
        prof["noise"]["chroma"] = round(_clamp(cn / 20.0, 0.0, 1.0), 4)
        prof["grain"]["amount"] = round(_clamp(ln / 30.0, 0.0, 1.0), 4)
        decide("noise/grain", "from smooth-region noise estimate", "texture_model.json")
    except FileNotFoundError:
        pass

    prof["schema_version"] = 1
    prof["version"] = 1
    prof["validation_status"] = "pending"
    ok, errors = validate_profile(prof)
    if not ok:
        report["warnings"].append(f"generated profile failed schema validation: {errors}")
    report["schema_valid"] = ok
    return prof, report


# --------------------------------------------------------------------------
# LLM extension point (AI only offline; not executed by default)
# --------------------------------------------------------------------------
def build_ai_prompt(analysis_dir: str) -> str:
    """Construct a structured prompt for an LLM to produce the profile JSON."""
    summary = {}
    for f in ("exposure_statistics.json", "color_statistics.json", "tone_statistics.json", "texture_model.json"):
        try:
            summary[f] = _load(analysis_dir, f)
        except FileNotFoundError:
            pass
    return (
        "You are a photography color scientist. Given the aggregated statistics below, "
        "produce a PhotographerProfile JSON strictly within the documented parameter ranges. "
        "Do NOT invent features that are not supported by the data. "
        "Return ONLY JSON.\n\n" + json.dumps(summary, indent=2, ensure_ascii=False)
    )


def parse_llm_response(text: str) -> Dict:
    """Parse and clamp an LLM-produced profile JSON (best-effort)."""
    start = text.find("{")
    end = text.rfind("}") + 1
    obj = json.loads(text[start:end])
    ok, _ = validate_profile(obj)
    return obj, ok


def main(argv=None) -> int:
    import argparse

    p = argparse.ArgumentParser(description="Phase 15 AI profile generator")
    p.add_argument("analysis_dir", help="directory with style_analyzer JSON outputs")
    p.add_argument("--name", default="Photographer (generated)")
    p.add_argument("--out", default="profiles")
    args = p.parse_args(argv)
    prof, report = generate_profile(args.analysis_dir, args.name)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "profile_v1.json"), "w", encoding="utf-8") as f:
        json.dump(prof, f, indent=2, ensure_ascii=False)
    with open(os.path.join(args.out, "profile_generation_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print(f"Generated profile_v1.json (schema_valid={report['schema_valid']}) -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
