#!/usr/bin/env python3
"""Static cross-verification between Python artifacts and Kotlin/Android code.

Checks (no Android SDK needed):
  1. Every key path in the shipped profile JSONs has a matching @SerialName /
     property in Profile.kt (kotlinx-serialization would fail loudly otherwise).
  2. Every uniform referenced in ProfileRenderer.kt is declared in some shader
     file under assets/shaders/.
  3. Every shader asset referenced by ProfileRenderer.kt / GLSL.kt exists.
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

# --- 2. Uniforms referenced in Kotlin vs declared in shaders -------------------
renderer = read(os.path.join(KT, "core", "gpu", "ProfileRenderer.kt"))
kotlin_uniforms = set(re.findall(r'"(u_[a-zA-Z_]+)"', renderer))
shader_dir = os.path.join(ASSETS, "shaders")
shader_text = ""
for f in os.listdir(shader_dir):
    shader_text += read(os.path.join(shader_dir, f))
declared = set(re.findall(r"uniform\s+\w+\s+(u_[a-zA-Z_]+)", shader_text))
for u in sorted(kotlin_uniforms):
    if u not in declared:
        errors.append(f"[uniform] '{u}' referenced in ProfileRenderer.kt but not "
                      f"declared in any shader")

# --- 3. Shader assets referenced from Kotlin exist -----------------------------
for m in re.findall(r'"shaders/([a-zA-Z0-9_.]+)"', renderer):
    if not os.path.exists(os.path.join(shader_dir, m)):
        errors.append(f"[assets] shader '{m}' referenced but missing")

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
print(f"Uniforms referenced: {len(kotlin_uniforms)}, declared in shaders: {len(declared)}")
print(f"Validation.kt range checks: {len(kt_checks)}, schema-matched: {matched} "
      f"(of {len(schema_bounds)} schema bounds)")
if errors:
    print(f"\nFAIL — {len(errors)} issue(s):")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print("\nALL STATIC CROSS-CHECKS PASS")
