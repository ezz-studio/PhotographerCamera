"""Verify the profile-icon save path end to end (HTTP level).

Regression: app.js sent the dataURL under the key ``dataUrl`` while
``server._save_icon`` read ``icon["data"]`` -> icon silently dropped and the UI
reported "未选图标". Both keys must now work.
"""
import base64
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import time
import urllib.request
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import argparse

_ap = argparse.ArgumentParser()
_ap.add_argument("--port", type=int, default=8796)
_ap.add_argument("--exe", default=None,
                 help="run the frozen exe instead of desktop/server.py "
                      "(WORK = the exe's own directory, so files land in dist/)")
_a = _ap.parse_args()
PORT = _a.port
EXE = _a.exe
BASE = f"http://127.0.0.1:{PORT}"
PROFILE_REL = "profiles/studio/_icon_test_tmp.json"


def tiny_png(color=(200, 80, 40)):
    w = h = 8
    raw = b"".join(b"\x00" + bytes(color) * w for _ in range(h))

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def post(path, payload):
    req = urllib.request.Request(
        BASE + path, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_up(timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with urllib.request.urlopen(BASE + "/api/health", timeout=2) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.4)
    return False


def cleanup(anchor=None):
    for base in (os.path.join(ROOT, os.path.splitext(PROFILE_REL)[0]),
                 os.path.join(ROOT, "dist", os.path.splitext(PROFILE_REL)[0]),
                 anchor or ""):
        if not base:
            continue
        for ext in (".png", ".jpg", ".webp"):
            p = base + ext
            if os.path.isfile(p):
                os.remove(p)
        if os.path.isfile(base + ".json"):
            os.remove(base + ".json")


def main():
    png = tiny_png()
    data_url = "data:image/png;base64," + base64.b64encode(png).decode("ascii")
    profile = {"name": "_icon_test_tmp", "exposure": {"bias": 0.0}}

    cases = [
        ("new key 'data'", {"ext": "png", "data": data_url}),
        ("legacy key 'dataUrl'", {"ext": "png", "dataUrl": data_url}),
        ("bare base64", {"ext": "png", "data": base64.b64encode(png).decode("ascii")}),
        ("jpeg ext", {"ext": "jpg", "data": data_url}),
        ("no icon", None),
    ]
    ok = True
    if EXE:
        proc = subprocess.Popen([EXE, "--port", str(PORT), "--no-browser"],
                                cwd=os.path.dirname(EXE),
                                stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    else:
        proc = subprocess.Popen(
            [sys.executable, os.path.join(ROOT, "desktop", "server.py"),
             "--port", str(PORT), "--no-browser"],
            cwd=ROOT, env=dict(os.environ),
            stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    try:
        if not wait_up():
            print("FAIL: server did not start")
            return 1
        for label, icon in cases:
            cleanup()
            payload = {"profile": profile, "path": PROFILE_REL}
            if icon:
                payload["icon"] = icon
            r = post("/api/save", payload)
            got = r.get("icon_path")
            # frozen exe writes next to the returned profile path, not the repo root
            anchor = os.path.dirname(r.get("path") or os.path.join(ROOT, PROFILE_REL))
            expect_ext = (icon or {}).get("ext", "png")
            want_path = os.path.join(anchor, "_icon_test_tmp." + expect_ext)
            if icon:
                good = bool(got) and os.path.isfile(want_path) and os.path.getsize(want_path) == len(png)
            else:
                good = got is None
            ok &= good
            print(f"{'PASS' if good else 'FAIL'}  {label:<22} icon_path={got}")
        # display.icon flag must be persisted for the App preset list
        cleanup()
        prof = dict(profile)
        prof["display"] = {"name": "测试", "icon": "png"}
        r = post("/api/save", {"profile": prof, "path": PROFILE_REL,
                               "icon": {"ext": "png", "data": data_url}})
        saved = json.load(open(r["path"], encoding="utf-8"))
        good = saved.get("display", {}).get("icon") == "png"
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  display.icon persisted  = {saved.get('display')}")
    finally:
        cleanup(os.path.dirname(os.path.join(ROOT, "dist", PROFILE_REL)))
        proc.kill()
    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
