#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Fetch a full upstream file (relative to com/hinnka/mycamera/) and save raw to tools/_up_<base>.txt."""
import base64, sys, subprocess, urllib.request, os, json, time

OWNER = "bjzhou/PhotonCamera"
UP_PREFIX = "app/src/main/java/com/hinnka/mycamera/"
rel = sys.argv[1]  # e.g. viewmodel/CameraViewModel.kt

p = subprocess.run(["git", "credential", "fill"],
                   input=b"protocol=https\nhost=github.com\n\n", capture_output=True)
token = None
for line in p.stdout.decode("utf-8", "replace").splitlines():
    if line.startswith("password="):
        token = line[len("password="):]
url = "https://api.github.com/repos/%s/contents/%s%s?ref=HEAD" % (OWNER, UP_PREFIX, rel)
headers = {"Accept": "application/vnd.github+json", "User-Agent": "upfile-fetch"}
if token:
    headers["Authorization"] = "Bearer " + token
for attempt in range(6):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as r:
            d = json.loads(r.read().decode("utf-8"))
        if isinstance(d, dict) and d.get("content"):
            text = base64.b64decode(d["content"]).decode("utf-8", "replace")
            break
    except Exception as e:
        import time
        print("attempt", attempt, "fail", e, file=sys.stderr)
        time.sleep(3 * (attempt + 1))
else:
    print("FETCH FAIL", rel, file=sys.stderr)
    sys.exit(1)

base = os.path.basename(rel).replace(".kt", ".txt")
out = os.path.join("tools", "_up_" + base)
with open(out, "w", encoding="utf-8") as f:
    f.write(text)
print("saved", out, "lines:", len(text.splitlines()))
