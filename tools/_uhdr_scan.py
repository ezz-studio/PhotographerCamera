#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Scan Ultra HDR fix commits (0e3d2241 + f0c4e04b) and classify each touched
file by whether the local fork copy carries fork-specific edits.

For every upstream file we fetch three revisions:
  P = upstream at the commit's parent (pre-fix baseline)
  U = upstream at the commit itself  (post-fix)
  L = local fork file (package-mapped namespace)

Classification:
  SAFE   L == P            -> local is pristine upstream, overwrite with U is lossless
  DRIFT  L != P            -> must inspect diff(P, L): upstream drift (fork lagging)
                              vs fork-specific edit (overwrite would lose work)
Usage: _uhdr_scan.py
"""
import base64
import difflib
import json
import os
import subprocess
import sys
import time
import urllib.request

OWNER = "bjzhou/PhotonCamera"
UP_PREFIX = "app/src/main/java/com/hinnka/mycamera/"
UP_TEST_PREFIX = "app/src/test/java/com/hinnka/mycamera/"
FORK_ROOT = "android/app/src/main/java/com/photographercamera/photon"
FORK_TEST_ROOT = "android/app/src/test/java/com/photographercamera/photon"

COMMITS = {
    "0e3d2241": "7f4081c0",   # fix: Ultra HDR   -> parent
    "f0c4e04b": "3fe04197",   # fix: ultra hdr   -> parent
}

TARGETS = {
    "0e3d2241": [
        "gallery/GalleryManager.kt",
        "gallery/PhotoProcessor.kt",
        "raw/DngProfileGainTableRenderShader.kt",
        "raw/RawDemosaicProcessor.kt",
        "raw/RawEngineTonePass.kt",
        "raw/RawHdrReferenceMath.kt",
        "raw/RawHdrReferencePass.kt",
        "raw/RawOutputPass.kt",
        "raw/RawRenderingEngineToneAlgorithm.kt",
        "raw/RawHdrReferenceMathTest.kt",
    ],
    "f0c4e04b": [
        "hdr/GpuReferenceGainmapProducer.kt",
        "hdr/RawGainmapMath.kt",
        "hdr/RawGainmapMathTest.kt",
    ],
}


def token():
    p = subprocess.run(["git", "credential", "fill"],
                       input=b"protocol=https\nhost=github.com\n\n",
                       capture_output=True)
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


TOK = token()
HEADERS = {"Accept": "application/vnd.github+json", "User-Agent": "uhdrscan"}
if TOK:
    HEADERS["Authorization"] = "Bearer " + TOK


def fetch(rel, ref, is_test=False):
    prefix = UP_TEST_PREFIX if is_test else UP_PREFIX
    url = "https://api.github.com/repos/%s/contents/%s%s?ref=%s" % (OWNER, prefix, rel, ref)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=180) as r:
                d = json.loads(r.read().decode("utf-8"))
            if isinstance(d, dict) and d.get("content"):
                return base64.b64decode(d["content"]).decode("utf-8", "replace")
            if isinstance(d, dict) and d.get("download_url"):
                with urllib.request.urlopen(urllib.request.Request(d["download_url"], headers=HEADERS), timeout=180) as r:
                    return r.read().decode("utf-8", "replace")
        except Exception as e:
            print("  retry %d %s@%s: %s" % (attempt, rel, ref, e), file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return None


def mapped(text):
    if text is None:
        return None
    return (text.replace("com.hinnka.mycamera", "com.photographercamera.photon")
                .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def local_path(rel, is_test=False):
    root = FORK_TEST_ROOT if is_test else FORK_ROOT
    return os.path.join(root, rel)


def read_local(rel, is_test=False):
    path = local_path(rel, is_test)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return f.read()


def main():
    report = []
    for sha, parent in COMMITS.items():
        for rel in TARGETS[sha]:
            is_test = rel.endswith("Test.kt")
            print("scanning", rel, "@", sha, file=sys.stderr)
            P = mapped(fetch(rel, parent, is_test))
            U = mapped(fetch(rel, sha, is_test))
            L = read_local(rel, is_test)
            if P is None or U is None:
                report.append((rel, "FETCHFAIL", None))
                continue
            if L is None:
                report.append((rel, "LOCAL_MISSING", None))
                continue
            if L == P:
                report.append((rel, "SAFE", U))
                continue
            diff = list(difflib.unified_diff(
                P.splitlines(), L.splitlines(),
                fromfile="upstream@" + parent[:8], tofile="local", lineterm="", n=1))
            report.append((rel, "DRIFT", diff))

    print("\n===== CLASSIFICATION =====")
    safe, drift, other = [], [], []
    for rel, kind, payload in report:
        if kind == "SAFE":
            safe.append(rel)
            print("SAFE          %s" % rel)
        elif kind == "DRIFT":
            drift.append(rel)
            print("DRIFT         %s  (%d diff lines)" % (rel, len(payload)))
        else:
            other.append((rel, kind))
            print("%-13s %s" % (kind, rel))
    print("\nSAFE count = %d, DRIFT count = %d" % (len(safe), len(drift)))

    if drift:
        print("\n===== DRIFT DETAIL (upstream-pre-fix vs local) =====")
        for rel, kind, payload in report:
            if kind != "DRIFT":
                continue
            print("\n---- %s ----" % rel)
            for line in payload[:80]:
                print(line)
            if len(payload) > 80:
                print("... (%d more lines)" % (len(payload) - 80))

    with open("_diag/uhdr_classify.json", "w", encoding="utf-8") as f:
        json.dump({"safe": safe, "drift": drift, "other": other}, f, indent=2)
    print("\nwrote _diag/uhdr_classify.json")


if __name__ == "__main__":
    main()
