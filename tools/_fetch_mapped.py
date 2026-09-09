#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Fetch upstream file(s), package-map to the fork namespace and overwrite the
local fork file.

Use ONLY for files whose full diff contains zero fork-specific content — an
overwrite is destructive for anything the fork changed on its own.

Usage:
  _fetch_mapped.py rel1 rel2 ...              # fetch at upstream HEAD
  _fetch_mapped.py --ref 0e3d2241 rel1 rel2   # fetch at a specific commit
  _fetch_mapped.py --dry-run rel1             # show what would change
  _fetch_mapped.py --test rel1                # map from app/src/test tree

--ref accepts a commit sha, branch or tag. When porting a fix commit, pass the
fix commit itself (its post-fix file state), never the parent.
"""
import base64
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


def token():
    p = subprocess.run(["git", "credential", "fill"],
                       input=b"protocol=https\nhost=github.com\n\n",
                       capture_output=True)
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


TOK = token()


def fetch(rel, ref, is_test=False):
    prefix = UP_TEST_PREFIX if is_test else UP_PREFIX
    url = "https://api.github.com/repos/%s/contents/%s%s?ref=%s" % (OWNER, prefix, rel, ref)
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "fetchmapped"}
    if TOK:
        headers["Authorization"] = "Bearer " + TOK
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as r:
                d = json.loads(r.read().decode("utf-8"))
            if isinstance(d, dict) and d.get("content"):
                return base64.b64decode(d["content"]).decode("utf-8", "replace")
            if isinstance(d, dict) and d.get("download_url"):
                with urllib.request.urlopen(urllib.request.Request(d["download_url"], headers=headers), timeout=180) as r:
                    return r.read().decode("utf-8", "replace")
        except Exception as e:
            print("attempt %d %s: %s" % (attempt, rel, e), file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return None


def mapped(text):
    if text is None:
        return None
    return (text.replace("com.hinnka.mycamera", "com.photographercamera.photon")
                .replace("com/hinnka/mycamera", "com/photographercamera/photon"))


def main(argv):
    ref = "HEAD"
    dry = False
    is_test = False
    rels = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--ref":
            i += 1
            ref = argv[i]
        elif a == "--dry-run":
            dry = True
        elif a == "--test":
            is_test = True
        else:
            rels.append(a)
        i += 1

    if not rels:
        print(__doc__)
        return 1

    for rel in rels:
        up = fetch(rel, ref, is_test)
        if up is None:
            print("FETCH FAIL", rel, "@", ref)
            continue
        upm = mapped(up)
        dst = os.path.join(FORK_TEST_ROOT if is_test else FORK_ROOT, rel)
        if dry:
            old = open(dst, encoding="utf-8").read() if os.path.exists(dst) else None
            if old == upm:
                print("SAME       %s (%d lines)" % (dst, len(upm.splitlines())))
            else:
                print("WOULD WRITE %s: %s -> %s lines" % (
                    dst,
                    len(old.splitlines()) if old else "MISSING",
                    len(upm.splitlines())))
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "w", encoding="utf-8", newline="\n") as f:
            f.write(upm)
        print("OVERWROTE  %s  %d lines  (ref=%s)" % (dst, len(upm.splitlines()), ref))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
