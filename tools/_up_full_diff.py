#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Full fork<->upstream source divergence scan.

Compares every upstream Kotlin file (from a local upstream checkout) with its
fork counterpart after package-name mapping, and reports:
  IDENTICAL      fork == upstream (package mapping aside)
  DIFF           fork differs -> how many changed lines
  LOCAL_MISSING  upstream file has no fork counterpart (candidate for porting)
FORK_ONLY files (present in fork, absent upstream) are listed separately —
those are fork-owned and must never be overwritten by a sync.

Usage: _up_full_diff.py [--up _diag/up_src/upstream] [--out _diag/up_full_diff]
"""
import difflib
import json
import os
import sys

FORK_ROOT = "android/app/src/main/java/com/photographercamera/photon"
UP_PREFIX = "app/src/main/java/com/hinnka/mycamera"


def normalize(text):
    return (text.replace("com.hinnka.mycamera", "com.photographercamera.photon")
                .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def main(argv):
    up_root = "_diag/up_src/upstream"
    out_base = "_diag/up_full_diff"
    for i, a in enumerate(argv):
        if a == "--up":
            up_root = argv[i + 1]
        elif a == "--out":
            out_base = argv[i + 1]

    up_java = os.path.join(up_root, UP_PREFIX)
    rows = []
    for dirpath, _, filenames in os.walk(up_java):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            up_path = os.path.join(dirpath, fn)
            rel = os.path.relpath(up_path, up_java).replace("\\", "/")
            fork_path = os.path.join(FORK_ROOT, rel).replace("\\", "/")
            with open(up_path, encoding="utf-8", errors="replace") as f:
                up_text = normalize(f.read())
            if not os.path.exists(fork_path):
                rows.append({"rel": rel, "kind": "LOCAL_MISSING",
                             "changed": len(up_text.splitlines()), "fork": None})
                continue
            with open(fork_path, encoding="utf-8", errors="replace") as f:
                fork_text = f.read()
            if fork_text == up_text:
                rows.append({"rel": rel, "kind": "IDENTICAL", "changed": 0, "fork": fork_path})
                continue
            diff = list(difflib.unified_diff(
                fork_text.splitlines(), up_text.splitlines(),
                fromfile="FORK", tofile="UPSTREAM", lineterm="", n=1))
            changed = sum(1 for l in diff if l[:1] in "+-" and not l.startswith(("+++", "---")))
            rows.append({"rel": rel, "kind": "DIFF", "changed": changed, "fork": fork_path})

    # fork-only files
    fork_only = []
    for dirpath, _, filenames in os.walk(FORK_ROOT):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            fp = os.path.join(dirpath, fn)
            rel = os.path.relpath(fp, FORK_ROOT).replace("\\", "/")
            if not os.path.exists(os.path.join(up_java, rel)):
                fork_only.append(rel)

    rows.sort(key=lambda r: (r["kind"] != "DIFF", -r["changed"]))
    with open(out_base + ".json", "w", encoding="utf-8") as f:
        json.dump({"rows": rows, "fork_only": fork_only}, f, indent=2)

    n_id = sum(1 for r in rows if r["kind"] == "IDENTICAL")
    n_df = sum(1 for r in rows if r["kind"] == "DIFF")
    n_ms = sum(1 for r in rows if r["kind"] == "LOCAL_MISSING")
    print("upstream files: %d   identical: %d   differ: %d   missing-locally: %d   fork-only: %d"
          % (len(rows), n_id, n_df, n_ms, len(fork_only)))
    print("\n=== DIFF (fork vs upstream), biggest first ===")
    for r in rows:
        if r["kind"] == "DIFF":
            print("  %6d  %s" % (r["changed"], r["rel"]))
    print("\n=== LOCAL_MISSING (upstream has it, fork does not) ===")
    for r in rows:
        if r["kind"] == "LOCAL_MISSING":
            print("  %6d  %s" % (r["changed"], r["rel"]))
    print("\nwrote %s.json" % out_base)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
