#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Three-way classify the DRIFT files from the Ultra HDR fix commits.

For each file we compare:
  P = upstream @ commit parent (pre-fix)
  U = upstream @ fix commit    (post-fix)
  H = upstream @ HEAD          (latest)
  L = local fork copy          (package-mapped)

Signals printed per file:
  L==U  -> local already carries the fix (no action)
  L==H  -> local equals upstream HEAD (no action)
  else  -> full diff(P, L) written so the hunks can be judged as
           "upstream drift" (safe to overwrite) vs "fork-specific edit"
           (must be merged by hand)
Output: _diag/uhdr_tri.txt
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
FORK_ROOT = "android/app/src/main/java/com/photographercamera/photon"

# rel -> (fix commit sha, parent sha)
TARGETS = {
    "gallery/GalleryManager.kt": ("0e3d2241", "7f4081c0"),
    "gallery/PhotoProcessor.kt": ("0e3d2241", "7f4081c0"),
    "raw/DngProfileGainTableRenderShader.kt": ("0e3d2241", "7f4081c0"),
    "raw/RawDemosaicProcessor.kt": ("0e3d2241", "7f4081c0"),
    "raw/RawHdrReferenceMath.kt": ("0e3d2241", "7f4081c0"),
    "raw/RawHdrReferencePass.kt": ("0e3d2241", "7f4081c0"),
    "raw/RawOutputPass.kt": ("0e3d2241", "7f4081c0"),
    "hdr/GpuReferenceGainmapProducer.kt": ("f0c4e04b", "3fe04197"),
    "hdr/RawGainmapMath.kt": ("f0c4e04b", "3fe04197"),
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
HEADERS = {"Accept": "application/vnd.github+json", "User-Agent": "uhdrtri"}
if TOK:
    HEADERS["Authorization"] = "Bearer " + TOK


def fetch(rel, ref):
    url = "https://api.github.com/repos/%s/contents/%s%s?ref=%s" % (OWNER, UP_PREFIX, rel, ref)
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


def mapped(t):
    return None if t is None else (
        t.replace("com.hinnka.mycamera", "com.photographercamera.photon")
         .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def main():
    out = []
    verdict = {}
    for rel, (sha, parent) in TARGETS.items():
        print("tri", rel, file=sys.stderr)
        P = mapped(fetch(rel, parent))
        U = mapped(fetch(rel, sha))
        H = mapped(fetch(rel, "HEAD"))
        path = os.path.join(FORK_ROOT, rel)
        L = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        flags = []
        if L == U:
            flags.append("L==U (fix already present)")
        if L == H:
            flags.append("L==H (equals upstream HEAD)")
        if not flags:
            flags.append("MANUAL")
        verdict[rel] = flags
        out.append("=" * 70)
        out.append("%s   [%s]" % (rel, "; ".join(flags)))
        if "MANUAL" in flags:
            d = list(difflib.unified_diff(
                P.splitlines(), L.splitlines(),
                fromfile="upstream@" + parent[:8] + "(pre-fix)",
                tofile="local", lineterm="", n=2))
            out.append("---- diff(pre-fix upstream, local): %d lines ----" % len(d))
            out.extend(d)
            if H != U:
                d2 = list(difflib.unified_diff(
                    U.splitlines(), H.splitlines(),
                    fromfile="upstream@fix", tofile="upstream@HEAD", lineterm="", n=2))
                out.append("---- diff(fix, upstream HEAD): %d lines ----" % len(d2))
                out.extend(d2[:200])
    txt = "\n".join(out)
    open("_diag/uhdr_tri.txt", "w", encoding="utf-8").write(txt)
    json.dump(verdict, open("_diag/uhdr_tri.json", "w", encoding="utf-8"), indent=2)
    print(txt[:6000])


if __name__ == "__main__":
    main()
