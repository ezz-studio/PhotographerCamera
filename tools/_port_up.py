#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Port upstream files into the fork from the local upstream checkout.

Source of truth is _diag/up_src/upstream (a shallow clone), not the GitHub API —
large files (GlesYuvStacker, RawDemosaicProcessor...) truncate over the API.

Two mappings are applied automatically:
  Kotlin : com.hinnka.mycamera / com/hinnka/mycamera -> com.photographercamera.photon
  Native : Java_com_hinnka_mycamera_ -> Java_com_photographercamera_core_photon_

Usage:
  _port_up.py raw/Foo.kt bar/Baz.kt            # kotlin paths relative to com/hinnka/mycamera
  _port_up.py --cpp raw_legacy_auto_exposure_solver.cpp
  _port_up.py --dry-run <paths>                # show what would change
Only run this on files with zero fork-specific content (check `git log -- <file>`:
a single import commit means the fork never edited it).
"""
import os
import subprocess
import sys

UP = "_diag/up_src/upstream"
UP_JAVA = os.path.join(UP, "app/src/main/java/com/hinnka/mycamera")
UP_CPP = os.path.join(UP, "app/src/main/cpp")
FORK_JAVA = "android/app/src/main/java/com/photographercamera/photon"
FORK_CPP = "android/app/src/main/cpp"


def map_kotlin(text):
    return (text.replace("com.hinnka.mycamera", "com.photographercamera.photon")
                .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def map_native(text):
    return text.replace("Java_com_hinnka_mycamera_",
                        "Java_com_photographercamera_core_photon_")


def show_history(rel):
    """Warn when the fork has edited this file beyond its import commit."""
    p = subprocess.run(["git", "log", "--oneline", "--", rel],
                       capture_output=True, text=True)
    lines = [l for l in p.stdout.strip().splitlines() if l]
    return lines


def main(argv):
    mode = "kotlin"
    dry = False
    rels = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--cpp":
            mode = "native"
        elif a == "--kotlin":
            mode = "kotlin"
        elif a == "--dry-run":
            dry = True
        else:
            rels.append(a)
        i += 1
    if not rels:
        print(__doc__)
        return 1

    for rel in rels:
        if mode == "native":
            src = os.path.join(UP_CPP, rel)
            dst = os.path.join(FORK_CPP, rel)
            text = map_native(open(src, encoding="utf-8").read())
        else:
            src = os.path.join(UP_JAVA, rel)
            dst = os.path.join(FORK_JAVA, rel).replace("\\", "/")
            text = map_kotlin(open(src, encoding="utf-8").read())

        if not os.path.exists(src):
            print("NO UPSTREAM SOURCE  %s" % src)
            continue
        old = open(dst, encoding="utf-8").read() if os.path.exists(dst) else None
        hist = show_history(dst.replace("\\", "/"))
        tag = " (%d fork commits)" % len(hist) if hist else ""
        if old == text:
            print("SAME              %s%s" % (rel, tag))
            continue
        if dry:
            print("WOULD PORT        %s  %s -> %s lines%s" % (
                rel, len(old.splitlines()) if old else "MISSING",
                len(text.splitlines()), tag))
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("PORTED            %s  %s -> %s lines%s" % (
            rel, len(old.splitlines()) if old else "MISSING",
            len(text.splitlines()), tag))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
