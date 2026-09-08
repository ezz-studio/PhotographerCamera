#!/usr/bin/env python3
"""Pure-python PhotographerProfile validator + default generator.

No third-party dependencies required. Single source of truth for structure =
``profiles/schema/photographer_profile.schema.json`` (JSON Schema draft-07).

Implements a small but sufficient subset of JSON Schema validation:
object required keys, type checks, numeric min/max, string enum, array
min/max items. This keeps the skeleton runnable without installing jsonschema.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "..", "profiles", "schema", "photographer_profile.schema.json")


def _schema_candidates() -> list:
    """Candidate schema locations, ordered.

    Source tree: tools/../profiles/schema (script or package import).
    Frozen bundle: <_MEIPASS>/profiles/schema — the plain-module import puts
    this file at the bundle root, so the ../ prefix would escape the bundle.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    return [
        SCHEMA_PATH,
        os.path.join(here, "profiles", "schema", "photographer_profile.schema.json"),
        os.path.join(os.getcwd(), "profiles", "schema", "photographer_profile.schema.json"),
    ]


def load_schema(path: str = None) -> dict:
    if path is None:
        cands = _schema_candidates()
        path = next((c for c in cands if os.path.isfile(c)), cands[0])
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _resolve(ref: str, root: dict):
    """Resolve a local ``#/definitions/...`` reference."""
    parts = ref.lstrip("#/").split("/")
    node = root
    for p in parts:
        node = node[p]
    return node


def _validate_node(value, schema: dict, root: dict, path_str: str, errors: list) -> None:
    if "$ref" in schema:
        schema = _resolve(schema["$ref"], root)
    t = schema.get("type")

    if t == "object":
        if not isinstance(value, dict):
            errors.append(f"{path_str}: expected object, got {type(value).__name__}")
            return
        for k in schema.get("required", []):
            if k not in value:
                errors.append(f"{path_str}.{k}: missing required key")
        props = schema.get("properties", {})
        for k, v in value.items():
            if k in props:
                _validate_node(v, props[k], root, f"{path_str}.{k}", errors)
        return

    if t == "array":
        if not isinstance(value, list):
            errors.append(f"{path_str}: expected array, got {type(value).__name__}")
            return
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path_str}: too few items ({len(value)} < {schema['minItems']})")
        if "maxItems" in schema and len(value) > schema["maxItems"]:
            errors.append(f"{path_str}: too many items ({len(value)} > {schema['maxItems']})")
        items = schema.get("items")
        if items is not None:
            for i, item in enumerate(value):
                _validate_node(item, items, root, f"{path_str}[{i}]", errors)
        return

    if t in ("number", "integer"):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            errors.append(f"{path_str}: expected {t}, got {type(value).__name__}")
            return
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path_str}: {value} < min {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path_str}: {value} > max {schema['maximum']}")
        return

    if t == "string":
        if not isinstance(value, str):
            errors.append(f"{path_str}: expected string, got {type(value).__name__}")
            return
        if "enum" in schema and value not in schema["enum"]:
            errors.append(f"{path_str}: {value!r} not in {schema['enum']}")
        return


def validate_profile(profile: dict, schema: dict | None = None) -> tuple[bool, list]:
    """Return ``(ok, errors)`` for a candidate PhotographerProfile dict."""
    schema = schema or load_schema()
    errors: list = []
    _validate_node(profile, schema, schema, "profile", errors)
    return (len(errors) == 0, errors)


def default_profile(name: str = "Photographer A") -> dict:
    """Identity profile: no style applied (all parameters at neutral defaults)."""
    return {
        "version": 1,
        "schema_version": 1,
        "name": name,
        "validation_status": "pending",
        "exposure": {"bias": 0.0},
        "white_balance": {"temperature_bias": 0.0, "tint_bias": 0.0},
        "color_matrix": {
            "matrix_3x3": [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
            "input_gamut": "sRGB",
            "output_gamut": "sRGB",
        },
        "hsl": {c: {} for c in ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]},
        "tone_curve": {"points": [[0.0, 0.0], [1.0, 1.0]]},
        "highlight_rolloff": {"threshold": 0.8, "strength": 0.0, "saturation": 1.0},
        "shadow": {"black_point": 0.0, "compression": 0.0, "tint": [0, 0, 0], "saturation": 1.0, "contrast": 1.0},
        "lens": {"vignette": 0.0, "chromatic_aberration": 0.0, "sharpness_falloff": 0.0,
                 "distortion": 0.0, "bloom": 0.0, "flare": 0.0},
        "grain": {"amount": 0.0, "size": 1.0, "density": 1.0},
        "noise": {"luma": 0.0, "chroma": 0.0},
        "halation": {"amount": 0.0, "radius": 1.0, "threshold": 0.9, "warmth": 1.0},
        "bloom": {"amount": 0.0, "radius": 1.0, "threshold": 0.9},
        "vignette": {"amount": 0.0, "radius": 1.0, "feather": 0.5, "center": [0.5, 0.5]},
        "sharpen": {"amount": 0.0, "radius": 1.0},
        "film_curve": {"shadow_floor": 8, "highlight_ceiling": 248},
    }


# --------------------------------------------------------------------------- safe bands
def _nget(d, path):
    cur = d
    for k in path.split("."):
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    return cur


def _nset(d, path, v):
    cur = d
    parts = path.split(".")
    for k in parts[:-1]:
        if not isinstance(cur.get(k), dict):
            cur[k] = {}
        cur = cur[k]
    cur[parts[-1]] = v


# Safe operating bands — tighter than the schema's absolute *validity* limits.
# The empirical generation stages (tone curve baked from a luminance CDF,
# color-matrix off-diagonals from channel correlation, film_curve.shadow_floor
# from shadow statistics) are NOT bounded by the optimiser, so they can
# over-reach and re-expose 8-bit read/JPEG noise (see 1.3.3 noise/banding
# diagnosis). These bands keep creative headroom without the artifacts and are
# enforced at generator output, Studio load/save/export, and the sliders.
SAFE_RANGES = {
    "film_curve.shadow_floor": (0.0, 16.0),   # was allowed up to 64; 25.5 caused noise
    "shadow.black_point": (0.0, 0.1),          # was 0.2
}


def matrix_cell_range(r: int, c: int):
    """Safe (min, max) for color_matrix cell (r, c).

    Diagonal = per-channel gain, kept modest; off-diagonal = cross-talk, kept
    small. Prevents a single channel boost / heavy cross-mix from re-exposing
    low-light read noise the way the 1.3.3 diagnosis traced.
    """
    if r == c:
        return (0.8, 1.2)
    return (-0.2, 0.2)


def safe_clamp(profile: dict) -> dict:
    """Pull a profile's over-reaching parameters into the safe band.

    Mutates *profile* in place and returns it. Scalars outside the band and
    color-matrix cells outside their band are clamped; everything else is left
    untouched so already-safe profiles pass through unchanged.
    """
    if not isinstance(profile, dict):
        return profile
    for path, (lo, hi) in SAFE_RANGES.items():
        v = _nget(profile, path)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            _nset(profile, path, min(hi, max(lo, float(v))))
    M = _nget(profile, "color_matrix.matrix_3x3")
    if isinstance(M, list) and len(M) == 3:
        for r in range(3):
            row = M[r]
            if not isinstance(row, list) or len(row) != 3:
                continue
            for c in range(3):
                v = row[c]
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    lo, hi = matrix_cell_range(r, c)
                    row[c] = min(hi, max(lo, float(v)))
    return profile


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="PhotographerProfile validator / default generator")
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("validate")
    v.add_argument("path")
    d = sub.add_parser("default")
    d.add_argument("name", nargs="?", default="Photographer A")
    args = p.parse_args(argv)

    if args.cmd == "validate":
        with open(args.path, "r", encoding="utf-8") as f:
            prof = json.load(f)
        ok, errors = validate_profile(prof)
        if ok:
            print(f"OK: {args.path} is a valid PhotographerProfile")
            return 0
        print(f"INVALID: {args.path}")
        for e in errors:
            print("  -", e)
        return 1

    print(json.dumps(default_profile(args.name), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
