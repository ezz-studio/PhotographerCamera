"""Smoke test for the frozen PhotographerStudio.exe.

Starts the exe with --no-browser on a scratch port, waits for the port, then
exercises the routes that matter in a frozen bundle:
  health / controls (schema resolved from _MEIPASS) / default_profile
  browse + thumb (image decode) / preview (full render pipeline: numpy + cv2 + PIL)
Any failure here would otherwise only show up when a photographer clicks
"generate" — i.e. minutes into a real run.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, "dist")
EXE = os.path.join(DIST, "PhotographerStudio.exe")


def wait_port(port, timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with socket.create_connection(("127.0.0.1", port), 0.5):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=60) as r:
        return r.status, r.read()


def post_json(base, path, payload):
    req = urllib.request.Request(base + path,
                                 data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.status, r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8791)
    ap.add_argument("--imgdir", default=os.path.join(ROOT, "studio_session", "selection"))
    args = ap.parse_args()

    if not os.path.isfile(EXE):
        print(f"MISSING exe: {EXE}")
        return 2
    print(f"exe: {EXE}  ({os.path.getsize(EXE) / 1e6:.1f} MB)")

    proc = subprocess.Popen([EXE, "--no-browser", "--port", str(args.port)],
                            cwd=DIST, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    ok = True
    try:
        if not wait_port(args.port):
            print("FAIL: server never opened the port")
            print(proc.stdout.read()[-3000:] if proc.stdout else "")
            return 1
        base = f"http://127.0.0.1:{args.port}"

        s, b = get(base, "/api/health")
        health = json.loads(b)
        print(f"[health] {s} frozen={health.get('frozen')} root={health.get('root')}")
        ok &= (s == 200 and health.get("frozen") is True)

        s, b = get(base, "/api/controls")
        controls = json.loads(b)["controls"]
        print(f"[controls] {s} count={len(controls)} sample={controls[0]['path'] if controls else None}")
        ok &= (s == 200 and len(controls) > 20)

        s, b = get(base, "/api/default_profile")
        print(f"[default_profile] {s} bytes={len(b)}")
        ok &= (s == 200)

        imgs = sorted(glob.glob(os.path.join(args.imgdir, "*.jpg")))[:3]
        if imgs:
            s, b = get(base, "/api/thumb?path=" + urllib.parse.quote(imgs[0]))
            print(f"[thumb] {s} bytes={len(b)}")
            ok &= (s == 200 and len(b) > 1000)

            prof = json.loads(get(base, "/api/default_profile")[1])["profile"]
            s, b = post_json(base, "/api/preview", {"path": imgs[0], "profile": prof, "max": 512})
            print(f"[preview] {s} jpeg_bytes={len(b)}")
            ok &= (s == 200 and len(b) > 2000)
        else:
            print("[thumb/preview] SKIPPED (no sample images)")

        print("\nRESULT:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    except urllib.error.HTTPError as e:
        print(f"FAIL: HTTP {e.code} {e.reason}: {e.read()[:500]}")
        return 1
    finally:
        # kill, not terminate: the frozen exe's console process can ignore
        # SIGTERM and leave communicate() hanging
        proc.kill()
        try:
            out = proc.communicate(timeout=10)[0]
        except subprocess.TimeoutExpired:
            out = ""
        if out and out.strip():
            tail = out.strip().splitlines()[-15:]
            print("--- server output tail ---")
            print("\n".join(tail))


if __name__ == "__main__":
    raise SystemExit(main())
