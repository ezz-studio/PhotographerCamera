#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Build a map of recent upstream changes (since SINCE date), aggregated per file.

Output answers: "which upstream commits touched this file, and how big was the
change" — the entry point for deciding what to port into the fork.

Writes _diag/up_recent_map.json and prints a per-directory summary.
Usage: _up_recent_map.py [--since 2026-09-05] [--per-page 40]
"""
import json
import subprocess
import sys
import time
import urllib.request
from collections import defaultdict

OWNER = "bjzhou/PhotonCamera"
SINCE_DEFAULT = "2026-09-05"


def token():
    p = subprocess.run(["git", "credential", "fill"],
                       input=b"protocol=https\nhost=github.com\n\n",
                       capture_output=True)
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    return None


TOK = token()
HEADERS = {"Accept": "application/vnd.github+json", "User-Agent": "uprecent"}
if TOK:
    HEADERS["Authorization"] = "Bearer " + TOK


def get(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=180) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            print("  retry %d %s: %s" % (attempt, url[-60:], e), file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return None


def main(argv):
    since = SINCE_DEFAULT
    per_page = 40
    for i, a in enumerate(argv):
        if a == "--since":
            since = argv[i + 1]
        elif a == "--per-page":
            per_page = int(argv[i + 1])

    commits = get("https://api.github.com/repos/%s/commits?per_page=%d" % (OWNER, per_page))
    if not commits:
        print("failed to list commits")
        return 1

    per_file = defaultdict(list)
    picked = []
    for c in commits:
        date = c["commit"]["author"]["date"][:10]
        if date < since:
            continue
        sha = c["sha"]
        detail = get("https://api.github.com/repos/%s/commits/%s" % (OWNER, sha))
        if not detail:
            continue
        msg = detail["commit"]["message"].splitlines()[0]
        picked.append((sha[:8], date, msg))
        for f in detail.get("files", []):
            per_file[f["filename"]].append({
                "sha": sha[:8],
                "date": date,
                "msg": msg,
                "status": f["status"],
                "add": f.get("additions", 0),
                "del": f.get("deletions", 0),
            })

    print("=== upstream commits since %s (%d) ===" % (since, len(picked)))
    for sha, date, msg in picked:
        print("  %s %s %s" % (sha, date, msg[:76]))

    # group by directory, kotlin/java sources only
    by_dir = defaultdict(list)
    for path, entries in per_file.items():
        if not (path.endswith(".kt") or path.endswith(".java")):
            continue
        d = "/".join(path.split("/")[:-1])
        by_dir[d].append((path, entries))

    print("\n=== changed source files by directory ===")
    for d in sorted(by_dir):
        items = sorted(by_dir[d], key=lambda x: -sum(e["add"] + e["del"] for e in x[1]))
        print("\n[%s]" % d)
        for path, entries in items:
            tot = sum(e["add"] + e["del"] for e in entries)
            shas = ",".join(e["sha"] for e in entries)
            print("   %-52s %5d  %s" % (path.split("/")[-1], tot, shas))

    with open("_diag/up_recent_map.json", "w", encoding="utf-8") as f:
        json.dump({"since": since, "commits": picked, "files": per_file}, f, indent=2)
    print("\nwrote _diag/up_recent_map.json (%d files)" % len(per_file))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
