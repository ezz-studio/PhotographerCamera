#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Show fork<->upstream difference for one or more relative source paths.

Usage: _up_file_diff.py raw/RawLegacyAutoExposureMatcher.kt ...
Paths are relative to com/photographercamera/photon (fork) and
com/hinnka/mycamera (upstream checkout).
"""
import difflib
import os
import sys

FORK_ROOT = "android/app/src/main/java/com/photographercamera/photon"
UP_ROOT = "_diag/up_src/upstream/app/src/main/java/com/hinnka/mycamera"


def normalize(text):
    return (text.replace("com.hinnka.mycamera", "com.photographercamera.photon")
                .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def main(argv):
    if not argv:
        print(__doc__)
        return 1
    for rel in argv:
        fork_path = os.path.join(FORK_ROOT, rel).replace("\\", "/")
        up_path = os.path.join(UP_ROOT, rel).replace("\\", "/")
        print("=" * 72)
        print("### %s" % rel)
        if not os.path.exists(up_path):
            print("  (no upstream file)")
            continue
        if not os.path.exists(fork_path):
            print("  (no fork file — upstream only)")
            continue
        with open(fork_path, encoding="utf-8", errors="replace") as f:
            a = f.read()
        with open(up_path, encoding="utf-8", errors="replace") as f:
            b = normalize(f.read())
        if a == b:
            print("  IDENTICAL")
            continue
        d = list(difflib.unified_diff(a.splitlines(), b.splitlines(),
                                      fromfile="FORK", tofile="UPSTREAM",
                                      lineterm="", n=3))
        print("\n".join(d))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
