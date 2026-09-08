#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Auto-diff every fetched upstream file (tools/_up_*.txt) against its fork counterpart."""
import difflib, glob, os, sys

FORK = "android/app/src/main/java/com/photographercamera/photon"

def normalize(text):
    return text.replace("com.hinnka.mycamera", "com.photographercamera.photon")\
               .replace("hinnka.mycamera", "photographercamera.photon")

ups = sorted(glob.glob("tools/_up_*.txt"))
summary = []
for up in ups:
    base = os.path.basename(up)[len("_up_"):-len(".txt")] + ".kt"
    cands = glob.glob(os.path.join(FORK, "**", base), recursive=True)
    if not cands:
        summary.append((-1, base, "NO-LOCAL-FILE", "")); continue
    lpath = cands[0]
    with open(lpath, encoding="utf-8", errors="replace") as f:
        a = normalize(f.read()).splitlines()
    with open(up, encoding="utf-8", errors="replace") as f:
        b = normalize(f.read()).splitlines()
    if a == b:
        summary.append((0, base, "IDENTICAL", "")); continue
    diff = list(difflib.unified_diff(a, b, fromfile="FORK", tofile="UPSTREAM", lineterm="", n=1))
    changed = sum(1 for l in diff if l[:1] in "+-" and not l.startswith(("+++", "---")))
    summary.append((changed, base, "%d changed lines" % changed, "\n".join(diff)))

summary.sort(key=lambda x: -x[0])
print("=== divergence map (fork vs upstream) ===")
for c, base, note, _ in summary:
    print("  %-40s %s" % (base, note))

# dump full hunks for files with modest changes
with open("_up_diff_hunks.txt", "w", encoding="utf-8") as out:
    for c, base, note, hunks in summary:
        if c > 0:
            out.write("\n\n########## %s : %s ##########\n" % (base, note))
            out.write(hunks)
print("\nfull hunks -> _up_diff_hunks.txt  (total differing files: %d)" %
      sum(1 for c, _, _, _ in summary if c > 0))
