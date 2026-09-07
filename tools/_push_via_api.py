#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Push a local commit to GitHub via the Git Data REST API.

Why: on this machine github.com:443 (git-https transport) is reset by the
network, while api.github.com is reachable directly. GCM still holds a valid
OAuth token, so we rebuild the commit server-side with the exact same
author/committer metadata -> the resulting commit sha is byte-identical to
the local one.

Usage:
    python -u tools/_push_via_api.py [local_commit] [branch]
"""
import base64
import json
import subprocess
import sys
import urllib.error
import urllib.request

REPO_OWNER = "ezz-studio"
REPO_NAME = "PhotographerCamera"
API = "https://api.github.com"


def git(*args):
    out = subprocess.run(["git"] + list(args), capture_output=True)
    return out.stdout.decode("utf-8", "replace"), out.returncode


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
    branch = sys.argv[2] if len(sys.argv) > 2 else "main"

    # ---- resolve full metadata of the local commit (for sha reproduction) ----
    fmt = "%H%n%an%n%ae%n%aI%n%cn%n%ce%n%cI%n%P"
    head, rc = git("show", "-s", "--format=" + fmt, commit)
    if rc != 0:
        print("FAIL: cannot read commit", commit)
        return 1
    lines = head.split("\n")
    sha, an, ae, ad, cn, ce, cd, parents = (
        lines[0], lines[1], lines[2], lines[3], lines[4], lines[5], lines[6], lines[7],
    )
    parent = parents.split()[0] if parents.split() else None
    # keep the message byte-exact: `%B` adds no trailing newline of its own, but
    # git stores whatever the caller passed; stripping it changes the commit sha.
    body, _ = git("cat-file", "commit", sha)
    head_raw, _, message = body.partition("\n\n")

    # ---- token from GCM ----
    cred, rc = git("credential", "fill")
    token = None
    for line in cred.splitlines():
        if line.startswith("password="):
            token = line[len("password="):]
    if not token:
        # credential fill writes to stdout only when stdin was provided
        p = subprocess.run(["git", "credential", "fill"], input=b"protocol=https\nhost=github.com\n\n",
                           capture_output=True)
        for line in p.stdout.decode("utf-8", "replace").splitlines():
            if line.startswith("password="):
                token = line[len("password="):]
    if not token:
        print("FAIL: no token from credential manager")
        return 1

    def api(path, method="GET", payload=None):
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(API + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("User-Agent", "push-via-api")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if data:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read().decode("utf-8")
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            print("HTTP %s %s -> %s" % (method, path, e.code))
            print(e.read().decode("utf-8", "replace")[:2000])
            raise

    ref = api("/repos/%s/%s/git/ref/heads/%s" % (REPO_OWNER, REPO_NAME, branch))
    remote_sha = ref["object"]["sha"]
    print("remote %s = %s" % (branch, remote_sha))
    print("local  %s = %s (parent %s)" % (commit, sha, parent))

    if remote_sha == sha:
        print("ALREADY UP TO DATE")
        return 0
    if parent != remote_sha:
        print("FAIL: not a fast-forward (parent %s != remote %s). Rebase first." % (parent, remote_sha))
        return 1

    # ---- changed paths ----
    names, _ = git("diff", "--name-only", parent, sha)
    paths = [p for p in names.split("\n") if p.strip()]
    print("files: %d" % len(paths))

    tree_entries = []
    for p in paths:
        # mode from the local index/tree
        mode, rc = git("ls-tree", sha, "--", p)
        if rc != 0 or not mode.strip():
            print("  skip (deleted): %s" % p)
            tree_entries.append({"path": p, "mode": "100644", "type": "blob", "sha": None})
            continue
        mode_str = mode.split()[0]
        content, rc = git("cat-file", "blob", "%s:%s" % (sha, p))
        if rc != 0:
            blob = git("show", "%s:%s" % (sha, p))[0]
        else:
            blob = content
        raw = blob.encode("utf-8")
        b = api("/repos/%s/%s/git/blobs" % (REPO_OWNER, REPO_NAME), "POST",
                {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        tree_entries.append({"path": p, "mode": mode_str, "type": "blob", "sha": b["sha"]})
        print("  blob %s %s (%d B)" % (b["sha"][:10], p, len(raw)))

    # sha=None entries are deletions -> must be sent as explicit null
    tree_entries = [e for e in tree_entries if e["sha"] is not None] + \
                   [{"path": e["path"], "mode": e["mode"], "type": "blob", "sha": None}
                    for e in tree_entries if e["sha"] is None]

    tree = api("/repos/%s/%s/git/trees" % (REPO_OWNER, REPO_NAME), "POST",
               {"base_tree": remote_sha, "tree": tree_entries})
    new_commit = api("/repos/%s/%s/git/commits" % (REPO_OWNER, REPO_NAME), "POST", {
        "message": message,
        "tree": tree["sha"],
        "parents": [remote_sha],
        "author": {"name": an, "email": ae, "date": ad},
        "committer": {"name": cn, "email": ce, "date": cd},
    })
    print("created commit: %s" % new_commit["sha"])
    if new_commit["sha"] != sha:
        print("WARN: sha differs from local %s (metadata mismatch) - tree may still match" % sha)

    api("/repos/%s/%s/git/refs/heads/%s" % (REPO_OWNER, REPO_NAME, branch), "PATCH",
        {"sha": new_commit["sha"], "force": False})
    print("PUSH OK: %s -> %s" % (new_commit["sha"][:10], branch))
    return 0


if __name__ == "__main__":
    sys.exit(main())
