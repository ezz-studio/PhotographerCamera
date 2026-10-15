#!/usr/bin/env python3
"""Static cross-verification between Python artifacts and Kotlin/Android code.

Checks (no Android SDK needed):
  1. Every key path in the shipped profile JSONs has a matching @SerialName /
     property in Profile.kt (kotlinx-serialization would fail loudly otherwise).
  2. Every uniform looked up at runtime (glGetUniformLocation(..., "u_x")) in the
     live Android render chain is DECLARED in some embedded GLSL string
     (uniform <type> u_x;). A lookup without a declaration is a silent no-op
     uniform (glUniform* on location -1) — the class of bug that produced the
     1.7.4/1.7.5 preview blackout.
     NOTE: this replaced the pre-0.9.0 check against core/gpu/ProfileRenderer.kt,
     which was deleted in commit 4c463ab (engine switch). The live chain is now
     photon/lut/* (LutImageProcessor / LutRenderer / PreviewColorShader /
     FilmGrain*) plus core/photon/color/*.
  3. Every shader asset referenced from Kotlin ("shaders/...") exists.
     The legacy assets/shaders/ directory has had no loader since 0.9.0
     (shaders are embedded Kotlin strings now), so zero references is the
     expected healthy state — reported, not an error.
  4. Validation ranges in Validation.kt cover the schema's min/max bounds.
"""
from __future__ import annotations

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "android", "app", "src", "main", "java", "com", "photographercamera")
ASSETS = os.path.join(ROOT, "android", "app", "src", "main", "assets")
errors: list[str] = []


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


# --- 1. JSON keys vs Profile.kt ------------------------------------------------
profile_kt = read(os.path.join(KT, "core", "profile", "Profile.kt"))
serial_names = set(re.findall(r'@SerialName\("([^"]+)"\)', profile_kt))

def json_key_paths(obj, prefix=""):
    keys = set()
    if isinstance(obj, dict):
        for k, v in obj.items():
            path = f"{prefix}.{k}" if prefix else k
            keys.add((path, k))
            keys |= json_key_paths(v, path)
    elif isinstance(obj, list):
        for item in obj[:1]:
            keys |= json_key_paths(item, prefix + "[]")
    return keys

asset_profiles_dir = os.path.join(ASSETS, "profiles")
asset_profiles = sorted(f for f in os.listdir(asset_profiles_dir) if f.endswith(".json"))
if not asset_profiles:
    errors.append("[assets] no profile JSONs under assets/profiles/")
for profile_file in asset_profiles:
    p = os.path.join(asset_profiles_dir, profile_file)
    data = json.load(open(p, encoding="utf-8"))
    for path, key in json_key_paths(data):
        if key in serial_names:
            continue
        # keys that map by naming convention or are ignored are fine if declared
        camel = re.sub(r"_([a-z])", lambda m: m.group(1).upper(), key)
        if camel in profile_kt:
            continue
        errors.append(f"[json->kt] {profile_file}: key '{key}' (path {path}) has no "
                      f"@SerialName or property in Profile.kt")

# --- 2. Uniform lookups vs GLSL declarations (live render chain) ----------------
# Live render-chain Kotlin files (post-0.9.0 engine switch). Uniform names are
# looked up literally via glGetUniformLocation(program, "u_xxx").
LIVE_CHAIN_DIRS = [
    os.path.join(KT, "photon", "lut"),
    os.path.join(KT, "photon", "gpu"),
    os.path.join(KT, "photon", "video"),
    os.path.join(KT, "core", "photon", "color"),
    os.path.join(KT, "core", "photon", "lens"),
]
kt_sources: dict[str, str] = {}
for d in LIVE_CHAIN_DIRS:
    if not os.path.isdir(d):
        continue
    for fn in sorted(os.listdir(d)):
        if fn.endswith(".kt"):
            kt_sources[os.path.join(d, fn)] = read(os.path.join(d, fn))

lookup_re = re.compile(r'[gG]etUniformLocation\(\s*[^,]+?,\s*"(u[A-Za-z0-9_]+)"')
# GLSL declarations may carry a precision qualifier and/or a multi-word type
# (e.g. "uniform mediump sampler3D uLutTexture;"); collect them from the WHOLE
# Kotlin tree because GLSL is injected across files (PreviewColorShader pulls in
# ShadowsHighlightsShader.GLSL, RAW shaders live under photon/raw/, ...).
declare_re = re.compile(r"uniform\s+(?:lowp\s+|mediump\s+|highp\s+)?\w+\s+(u[A-Za-z0-9_]+)\s*(?:\[\s*\d*\s*\])?\s*;")

looked_up: dict[str, list[str]] = {}   # uniform -> [files that look it up]
declared: set[str] = set()
for path, text in kt_sources.items():
    for m in lookup_re.finditer(text):
        looked_up.setdefault(m.group(1), []).append(os.path.basename(path))

# Declarations may live anywhere in the Kotlin tree (GLSL strings are injected
# across modules), so scan every .kt under the app source root.
for dirpath, _dirs, files in os.walk(KT):
    for fn in sorted(files):
        if not fn.endswith(".kt"):
            continue
        fp = os.path.join(dirpath, fn)
        if fp in kt_sources:
            text = kt_sources[fp]
        else:
            with open(fp, encoding="utf-8") as f:
                text = f.read()
        for m in declare_re.finditer(text):
            declared.add(m.group(1))

for u in sorted(looked_up):
    if u not in declared:
        files = ", ".join(sorted(set(looked_up[u])))
        errors.append(f"[uniform] '{u}' looked up in {files} but not declared in "
                      f"any embedded GLSL string (silent no-op uniform)")

# --- 3. Shader asset references exist ------------------------------------------
shader_dir = os.path.join(ASSETS, "shaders")
shader_refs = 0
for path, text in kt_sources.items():
    for m in re.finditer(r'"shaders/([a-zA-Z0-9_.]+)"', text):
        shader_refs += 1
        if not os.path.exists(os.path.join(shader_dir, m.group(1))):
            errors.append(f"[assets] shader '{m.group(1)}' referenced in "
                          f"{os.path.basename(path)} but missing")
if shader_refs == 0:
    n_assets = len(os.listdir(shader_dir)) if os.path.isdir(shader_dir) else 0
    print(f"Shader asset references from live chain: 0 "
          f"(assets/shaders/ holds {n_assets} unloaded files since 0.9.0 — OK)")

# --- 4. Validation ranges vs schema -------------------------------------------
schema = json.load(open(os.path.join(ROOT, "profiles", "schema",
                                     "photographer_profile.schema.json"), encoding="utf-8"))
validation_kt = read(os.path.join(KT, "core", "profile", "Validation.kt"))
kt_checks = re.findall(r'check\w*\(\s*errs,\s*"([^"]+)",\s*[-\w.]+,\s*([-\d.]+)f?,\s*([-\d.]+)f?', validation_kt)
schema_bounds = {}
def walk_schema(node, prefix=""):
    if isinstance(node, dict):
        if "minimum" in node or "maximum" in node:
            schema_bounds[prefix] = (node.get("minimum"), node.get("maximum"))
        for k, v in node.get("properties", {}).items():
            walk_schema(v, f"{prefix}.{k}" if prefix else k)
walk_schema(schema)

# Kotlin check names use the same snake_case paths as the schema
# (e.g. "white_balance.temperature_bias"), so match exactly.
matched = 0
for name, lo, hi in kt_checks:
    lo, hi = float(lo), float(hi)
    b = schema_bounds.get(name)
    if b is None:
        continue  # e.g. array-element checks with no per-element schema bounds
    smin, smax = b
    if smin is not None and smax is not None:
        if abs(lo - float(smin)) > 1e-6 or abs(hi - float(smax)) > 1e-6:
            errors.append(f"[range] Validation.kt '{name}' = [{lo},{hi}] but schema "
                          f"'{name}' = [{smin},{smax}]")
        matched += 1

print(f"JSON keys checked against Profile.kt @SerialName set ({len(serial_names)} names)")
print(f"Live-chain Kotlin files scanned: {len(kt_sources)}; "
      f"uniforms looked up: {len(looked_up)}, declared in GLSL: {len(declared)}")
print(f"Shader asset references: {shader_refs}")
print(f"Validation.kt range checks: {len(kt_checks)}, schema-matched: {matched} "
      f"(of {len(schema_bounds)} schema bounds)")
if errors:
    print(f"\nFAIL — {len(errors)} issue(s):")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\nALL STATIC CROSS-CHECKS PASS")
