#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Diff upstream PhotonCamera engine sources against the fork, package-mapped.

Only compares Kotlin sources under imaging-related packages so the run stays
focused: processor / raw / gallery / lut / camera / viewmodel / processing /
utils / hdr / stabilization / livephoto.

Usage: python -u tools/_diff_engine.py [dir1,dir2,...]
"""
import base64
import difflib
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

OWNER = "bjzhou/PhotonCamera"
UP_PREFIX = "app/src/main/java/com/hinnka/mycamera/"
FORK_ROOT = "android/app/src/main/java/com/photographercamera/photon"
DEFAULT_DIRS = ["processor", "raw", "gallery", "lut", "camera", "viewmodel",
                "processing", "utils", "hdr", "stabilization", "livephoto",
                "ml", "settings", "data"]


def github_token():
    """Reuse the OAuth token stored by git-credential-manager (5000 req/h vs 60 anonymous)."""
    import subprocess
    p = subprocess.run(["git", "credential", "fill"],
                       input=b"protocol=https\nhost=github.com\n\n", capture_output=True)
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


TOKEN = github_token()


def fetch(rel):
    url = "https://api.github.com/repos/%s/contents/%s%s?ref=HEAD" % (OWNER, UP_PREFIX, rel)
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "diff-engine"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(url, headers=headers)
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.loads(r.read().decode("utf-8"))
            if isinstance(d, dict) and d.get("content"):
                return rel, base64.b64decode(d["content"]).decode("utf-8", "replace")
        except Exception as e:
            import time
            time.sleep(3 * (attempt + 1))
    return rel, None


def normalize(text):
    return text.replace("com.hinnka.mycamera", "com.photographercamera.photon")


def main():
    dirs = sys.argv[1].split(",") if len(sys.argv) > 1 else DEFAULT_DIRS
    up = json.load(open("_up_tree.json", encoding="utf-8"))
    targets = [(p[len(UP_PREFIX):], s) for p, s in up
               if p.startswith(UP_PREFIX) and p[len(UP_PREFIX):].split("/")[0] in dirs]
    print("comparing %d files in %s" % (len(targets), ",".join(dirs)))

    results = []
    with ThreadPoolExecutor(max_workers=8) as ex:
        for rel, text in ex.map(fetch, [r for r, _ in targets]):
            if text is None:
                print("  FETCH-FAIL", rel)
                continue
            fork_path = os.path.join(FORK_ROOT, rel.replace("/", os.sep))
            if not os.path.exists(fork_path):
                results.append((10 ** 6, rel, "MISSING-IN-FORK", ""))
                continue
            fork_text = open(fork_path, encoding="utf-8", errors="replace").read()
            a = normalize(fork_text).splitlines()
            b = normalize(text).splitlines()
            if a == b:
                continue
            diff = list(difflib.unified_diff(a, b, lineterm="", n=0))
            changed = sum(1 for l in diff if l[:1] in "+-" and not l.startswith(("+++", "---")))
            results.append((changed, rel, "%d changed lines" % changed, "\n".join(diff[:60])))

    results.sort(key=lambda x: -x[0])
    print("\n=== %d files differ ===" % len(results))
    for changed, rel, note, _ in results:
        print("  %-62s %s" % (rel, note))
    json.dump([(r[0], r[1], r[3]) for r in results],
              open("_engine_diff.json", "w", encoding="utf-8"), ensure_ascii=False)
    print("\nfull hunks written to _engine_diff.json")


if __name__ == "__main__":
    main()
