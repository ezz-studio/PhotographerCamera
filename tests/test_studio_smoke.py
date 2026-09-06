"""Smoke-test the Studio web workbench end to end (no browser required).

Exercises: health -> browse -> generate (async job) -> preview -> save ->
export_android -> batch, against synthetic images so it runs anywhere.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

PORT = 8791
BASE = f"http://127.0.0.1:{PORT}"
DATA = os.path.join(ROOT, "studio_session", "testdata")


def _make_dataset(n: int = 12) -> str:
    os.makedirs(DATA, exist_ok=True)
    rng = np.random.default_rng(7)
    for i in range(n):
        h, w = 300 + i * 7, 340 + i * 5
        yy, xx = np.mgrid[0:h, 0:w]
        base = np.dstack([
            0.35 + 0.4 * (xx / w) + rng.normal(0, 0.02, (h, w)),
            0.40 + 0.3 * (yy / h) + rng.normal(0, 0.02, (h, w)),
            0.30 + 0.5 * (1 - yy / h) + rng.normal(0, 0.02, (h, w)),
        ])
        # give each frame a distinct look so style analysis has signal
        base = np.clip(base * (0.8 + 0.4 * rng.random()), 0, 1) ** (0.8 + 0.6 * rng.random())
        Image.fromarray((base * 255).astype(np.uint8), "RGB").save(
            os.path.join(DATA, f"frame_{i:02d}.jpg"), quality=92
        )
    return DATA


def _req(path: str, payload: dict | None = None, raw: bool = False, timeout: int = 600):
    if payload is None:
        req = urllib.request.Request(BASE + path)
    else:
        req = urllib.request.Request(
            BASE + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        data = r.read()
    return data if raw else json.loads(data.decode("utf-8"))


def main() -> int:
    data = _make_dataset()
    print(f"[0] synthetic dataset: {data} ({len(os.listdir(data))} images)")

    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.Popen(
        [sys.executable, os.path.join(ROOT, "desktop", "server.py"),
         "--port", str(PORT), "--no-browser"],
        cwd=ROOT, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
    )
    try:
        # wait for readiness
        for _ in range(60):
            try:
                _req("/api/health")
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise SystemExit("server did not start")

        print("[1] health        ", _req("/api/health")["ok"])

        # --- controls -----------------------------------------------------
        ctrl = _req("/api/controls")
        paths = [c["path"] for c in ctrl["controls"]]
        assert all("." in p for p in paths), "metadata leaked into controls"
        print(f"[2] controls      {len(paths)} sliders, groups: "
              f"{sorted({p.split('.')[0] for p in paths})}")

        # --- browse -------------------------------------------------------
        browsed = _req(f"/api/browse?path={urllib.parse.quote(data)}")
        assert browsed["count"] == len(os.listdir(data)), browsed["count"]
        print(f"[3] browse        {browsed['count']} images")

        # --- thumbnails / full image --------------------------------------
        p0 = browsed["images"][0]["path"]
        thumb = _req(f"/api/thumb?path={urllib.parse.quote(p0)}", raw=True)
        full = _req(f"/api/img?path={urllib.parse.quote(p0)}&max=1600", raw=True)
        assert thumb[:2] == b"\xff\xd8" and full[:2] == b"\xff\xd8", "not a JPEG"
        print(f"[4] thumb {len(thumb)}B / full {len(full)}B  (both JPEG)")

        # --- generate (async) ---------------------------------------------
        images = [i["path"] for i in browsed["images"]]
        job = _req("/api/generate", {"images": images, "name": "Smoke Look",
                                     "outdir": "profiles/studio_smoke"})
        jid = job["job"]
        while True:
            j = _req(f"/api/job?id={jid}")
            if j["status"] in ("done", "error"):
                break
            time.sleep(1.0)
        if j["status"] != "done":
            print(j["log"][-3000:])
            raise SystemExit("generate failed")
        res = j["result"]
        prof = res["profile"]
        val = res["validation"]
        print(f"[5] generate      status={prof.get('validation_status')} "
              f"loss={val.get('overall_test_loss')} n_test={val.get('n_test')}")

        # --- preview ------------------------------------------------------
        t0 = time.time()
        prev = _req("/api/preview", {"path": p0, "profile": prof, "max": 1600}, raw=True)
        assert prev[:2] == b"\xff\xd8"
        print(f"[6] preview       {len(prev)}B in {time.time() - t0:.2f}s")

        # a tuned profile must render differently from the baseline
        import copy
        tuned = copy.deepcopy(prof)
        tuned["exposure"]["bias"] = round(prof["exposure"]["bias"] + 0.9, 3)
        tuned["vignette"]["amount"] = 0.6
        prev2 = _req("/api/preview", {"path": p0, "profile": tuned, "max": 800}, raw=True)
        assert prev2 != prev, "tuning had no effect on the render"
        print("[7] tuning        render changes as expected")

        v = _req("/api/validate", {"profile": prof})
        print(f"[8] schema check  ok={v['ok']} errors={v['errors'][:2]}")

        # save WITH a PNG icon -> the icon must land next to the JSON under the
        # SAME base name (App preset list pairs <id>.json with <id>.png).
        import base64, io as _io
        _icon_buf = _io.BytesIO()
        Image.fromarray(np.zeros((16, 16, 3), dtype=np.uint8), "RGB").save(_icon_buf, format="PNG")
        _icon_data = "data:image/png;base64," + base64.b64encode(_icon_buf.getvalue()).decode()
        saved = _req("/api/save", {
            "profile": tuned,
            "path": "profiles/studio_smoke/tuned.json",
            "icon": {"ext": "png", "data": _icon_data},
        })
        assert os.path.isfile(saved["path"]), "profile json not saved"
        assert saved.get("icon_path") and os.path.isfile(saved["icon_path"]), "same-named icon not saved"
        assert os.path.splitext(saved["path"])[0] == os.path.splitext(saved["icon_path"])[0], \
            "profile/icon name mismatch"
        print(f"[9] save+icon     {os.path.basename(saved['path'])} + {os.path.basename(saved['icon_path'])}")

        exp = _req("/api/export_android", {"profile": prof, "name": "smoke_look"})
        print(f"[10] android      {exp['path']}")

        batch = _req("/api/batch", {"profile": prof, "images": images[:4],
                                    "outdir": "studio_session/smoke_exports"})
        print(f"[11] batch        {batch['count']} files -> {batch['outdir']}")

        print("\nSTUDIO SMOKE TEST PASS")
        return 0
    except urllib.error.HTTPError as e:
        print("HTTP ERROR", e.code, e.read().decode("utf-8", "replace")[:2000])
        return 1
    finally:
        proc.terminate()
        try:
            out = proc.communicate(timeout=15)[0]
        except Exception:
            proc.kill()
            out = ""
        if "HTTP ERROR" in locals() or True:
            tail = (out or "")[-1500:]
            if tail.strip():
                print("--- server log tail ---")
                print(tail)


if __name__ == "__main__":
    raise SystemExit(main())
