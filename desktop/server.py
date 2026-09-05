"""PhotographerCamera Studio — local photographer-style authoring workbench.

A dependency-free (stdlib-only) HTTP server plus a browser front-end that exposes
the existing offline pipeline (Phases 3-18) as an interactive tool:

  * import a folder of reference photos (paste a local path, or drag/select files
    which are downscaled in-browser and uploaded)
  * generate a PhotographerProfile end-to-end (style analysis -> AI/heuristic v1
    -> optimiser -> held-out validation)
  * compare original vs rendered side by side with a wipe slider
  * hand-tune every scalar the schema exposes, with live re-render
  * export the profile JSON (and optionally push it into the Android assets)

Run:  python desktop/server.py [--port 8765] [--no-browser]
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import io
import json
import os
import re
import sys
import threading
import time
import traceback
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)          # code + packaged resources
# When frozen by PyInstaller the code lives in a temp _MEIPASS directory, while
# the user's workspace (profiles, uploads, exports) must stay next to the .exe.
BUNDLE = getattr(sys, "_MEIPASS", ROOT)
WORK = os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else ROOT
WEB_DIR = os.path.join(BUNDLE, "desktop", "web")

# The pipeline modules under tools/ import each other in *script* style
# (`sys.path.insert(0, os.path.dirname(__file__)); import profile_optimizer`).
# That trick dies inside a frozen bundle, where the .py sources are not on disk —
# so expose tools/ itself on sys.path and import them by plain module name, which
# resolves identically in source mode and in the frozen app.
for _p in (BUNDLE, os.path.join(BUNDLE, "tools")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import numpy as np  # noqa: E402
from PIL import Image  # noqa: E402

import build_profile as B  # noqa: E402
import profile_renderer as R  # noqa: E402
import profile_schema as S  # noqa: E402
from dataset_loader import discover_images  # noqa: E402

IMAGE_EXT = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff"}

STATE = {
    "session": os.path.join(WORK, "studio_session"),
    "profile": None,
    "profile_path": None,
    "jobs": {},
    "images": [],          # current image index for the picker
}
_LOCK = threading.Lock()

# --------------------------------------------------------------------------- utils


def _safe_join(base: str, rel: str) -> str:
    """Join *rel* under *base*, refusing escapes outside it."""
    target = os.path.normpath(os.path.join(base, rel))
    base_n = os.path.normpath(base)
    if not (target == base_n or target.startswith(base_n + os.sep)):
        raise ValueError(f"path escapes root: {rel}")
    return target


def _img_to_jpeg(arr_u8: np.ndarray, quality: int = 92) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(arr_u8, "RGB").save(buf, format="JPEG", quality=quality)
    return buf.getvalue()


def _load_rgb(path: str, max_side: int = 0) -> np.ndarray:
    """Decode to float32 in [0,1]; optionally downscale the longest side."""
    img = Image.open(path)
    img = img.convert("RGB")
    if max_side:
        w, h = img.size
        m = max(w, h)
        if m > max_side:
            s = max_side / m
            img = img.resize((max(1, int(w * s)), max(1, int(h * s))), Image.Resampling.LANCZOS)
    return np.asarray(img, dtype=np.float32) / 255.0


def _render_jpeg(path: str, profile: dict, max_side: int = 1600) -> bytes:
    arr = _load_rgb(path, max_side)
    out = R.render(arr, profile or {}, seed=0)
    out8 = (np.clip(out, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    return _img_to_jpeg(out8)


# ------------------------------------------------------------- schema -> controls


def _resolve_ref(ref: str, root: dict) -> dict:
    node = root
    for part in ref.lstrip("#/").split("/"):
        node = node.get(part, {})
    return node or {}


def _walk_controls(node: dict, root: dict, prefix: str, out: list) -> None:
    if not isinstance(node, dict):
        return
    if "$ref" in node:
        node = _resolve_ref(node["$ref"], root)
    props = node.get("properties")
    if isinstance(props, dict):
        for key, sub in props.items():
            path = f"{prefix}.{key}" if prefix else key
            _walk_controls(sub, root, path, out)
        return
    t = node.get("type")
    if t in ("number", "integer") and ("minimum" in node or "maximum" in node):
        step = 0.01 if t == "number" else 1
        out.append({
            "path": prefix,
            "label": prefix.replace("_", " ").rsplit(".", 1)[-1],
            "type": t,
            "min": float(node.get("minimum", 0.0)),
            "max": float(node.get("maximum", 1.0)),
            "step": node.get("multipleOf", step),
            "default": node.get("default"),
        })
    elif t == "boolean":
        out.append({
            "path": prefix, "label": prefix.replace("_", " ").rsplit(".", 1)[-1],
            "type": "boolean", "default": node.get("default"),
        })


def build_controls() -> list:
    schema = S.load_schema()
    out: list = []
    _walk_controls(schema, schema, "", out)
    # Arrays (tone curve points, HSL tables, 3x3 matrix) are authored by the
    # generator/optimiser, not by sliders. Top-level scalars (version, schema_version)
    # are metadata, not style knobs.
    out = [
        c for c in out
        if "." in c["path"]
        and not re.search(r"(points|matrix_3x3)\b", c["path"])
    ]
    return out


# --------------------------------------------------------------------- job runner


class _LogSink:
    def __init__(self, job: dict):
        self.job = job

    def write(self, s: str) -> int:
        if s:
            self.job["log"] += s
            self.job["log"] = self.job["log"][-20000:]
        return len(s)

    def flush(self) -> None:
        pass


def _run_generate(job: dict, images_root: str, name: str, out_dir: str) -> None:
    try:
        os.makedirs(out_dir, exist_ok=True)
        with contextlib.redirect_stdout(_LogSink(job)):
            profile = B.build(images_root, name, out_dir)
        job["log"] += f"\n[{time.strftime('%H:%M:%S')}] build() returned\n"
        reports = {}
        for fn in ("validation_report.json", "optimization_report.json",
                   "profile_generation_report.json"):
            fp = os.path.join(out_dir, fn)
            if os.path.isfile(fp):
                with open(fp, encoding="utf-8") as f:
                    reports[fn] = json.load(f)
        with _LOCK:
            STATE["profile"] = profile
            STATE["profile_path"] = os.path.join(out_dir, "profile_final.json")
        job["status"] = "done"
        job["result"] = {
            "profile": profile,
            "outdir": out_dir,
            "validation": reports.get("validation_report.json", {}),
            "optimization": reports.get("optimization_report.json", {}),
        }
    except Exception:
        job["status"] = "error"
        job["error"] = traceback.format_exc()
        job["log"] += "\n" + traceback.format_exc()


# ----------------------------------------------------------------------- handler


class Handler(BaseHTTPRequestHandler):
    server_version = "PhotographerStudio/0.1"

    def log_message(self, fmt, *args):  # keep the console readable
        sys.stderr.write("[http] " + (fmt % args) + "\n")

    # -- helpers ------------------------------------------------------------
    def _json(self, obj, code: int = 200) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _bytes(self, data: bytes, ctype: str, code: int = 200) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return {}

    # -- GET ----------------------------------------------------------------
    def do_GET(self) -> None:  # noqa: N802
        u = urlparse(self.path)
        p = u.path
        q = parse_qs(u.query)

        try:
            if p in ("/", "/index.html"):
                return self._file(os.path.join(WEB_DIR, "index.html"), "text/html; charset=utf-8")
            if p.startswith("/static/"):
                rel = p[len("/static/"):]
                return self._file(_safe_join(WEB_DIR, rel), _mime(rel))

            if p == "/api/health":
                return self._json({"ok": True, "root": WORK, "frozen": bool(getattr(sys, "frozen", False))})

            if p == "/api/controls":
                return self._json({"controls": build_controls()})

            if p == "/api/default_profile":
                return self._json({"profile": S.default_profile("Untitled")})

            if p == "/api/browse":
                root = unquote(q.get("path", [""])[0])
                if not root or not os.path.isdir(root):
                    return self._json({"error": f"not a directory: {root}"}, 400)
                paths = [x for x in discover_images(root)
                         if os.path.splitext(x)[1].lower() in IMAGE_EXT]
                with _LOCK:
                    STATE["images"] = paths
                return self._json({"root": root, "count": len(paths),
                                   "images": [{"path": x, "name": os.path.basename(x)} for x in paths]})

            if p == "/api/img":
                path = unquote(q.get("path", [""])[0])
                if not path or not os.path.isfile(path):
                    return self._json({"error": f"no such image: {path}"}, 404)
                max_side = int(q.get("max", ["1600"])[0])
                arr = _load_rgb(path, max_side)
                return self._bytes(_img_to_jpeg((arr * 255.0 + 0.5).astype(np.uint8)),
                                   "image/jpeg")

            if p == "/api/thumb":
                path = unquote(q.get("path", [""])[0])
                if not path or not os.path.isfile(path):
                    return self._json({"error": f"no such image: {path}"}, 404)
                arr = _load_rgb(path, 320)
                return self._bytes(_img_to_jpeg((arr * 255.0 + 0.5).astype(np.uint8), 82),
                                   "image/jpeg")

            if p == "/api/profiles":
                base = os.path.join(WORK, "profiles")
                items = []
                for dirpath, _dirs, files in os.walk(base):
                    for fn in files:
                        if fn.endswith(".json") and fn.startswith("profile"):
                            items.append(os.path.relpath(os.path.join(dirpath, fn), WORK))
                return self._json({"profiles": sorted(items)})

            if p == "/api/load_profile":
                rel = unquote(q.get("path", [""])[0])
                fp = rel if os.path.isabs(rel) else os.path.join(WORK, rel)
                if not os.path.isfile(fp):
                    fp = os.path.join(BUNDLE, rel)
                    return self._json({"error": f"no such profile: {rel}"}, 404)
                with open(fp, encoding="utf-8") as f:
                    prof = json.load(f)
                with _LOCK:
                    STATE["profile"] = prof
                    STATE["profile_path"] = fp
                return self._json({"profile": prof, "path": fp})

            if p == "/api/job":
                jid = q.get("id", [""])[0]
                with _LOCK:
                    job = STATE["jobs"].get(jid)
                if not job:
                    return self._json({"error": "unknown job"}, 404)
                return self._json({k: job[k] for k in
                                   ("status", "log", "error", "result", "kind")})

            return self._json({"error": f"unknown route {p}"}, 404)
        except Exception as exc:  # pragma: no cover - defensive
            traceback.print_exc()
            return self._json({"error": str(exc)}, 500)

    def _file(self, path: str, ctype: str) -> None:
        if not os.path.isfile(path):
            return self._json({"error": f"missing {path}"}, 404)
        with open(path, "rb") as f:
            self._bytes(f.read(), ctype)

    # -- POST ---------------------------------------------------------------
    def do_POST(self) -> None:  # noqa: N802
        u = urlparse(self.path)
        p = u.path
        body = self._body()

        try:
            if p == "/api/upload":
                files = body.get("files", [])
                dest = _safe_join(STATE["session"], "uploads")
                os.makedirs(dest, exist_ok=True)
                saved = []
                for item in files:
                    name = os.path.basename(str(item.get("name", "image.jpg")))
                    name = re.sub(r"[^\w.\-]", "_", name, flags=re.UNICODE)
                    data = item.get("data", "")
                    if "," in data:
                        data = data.split(",", 1)[1]
                    raw = base64.b64decode(data)
                    fp = os.path.join(dest, name)
                    with open(fp, "wb") as f:
                        f.write(raw)
                    saved.append(fp)
                return self._json({"dir": dest, "count": len(saved),
                                   "images": [{"path": x, "name": os.path.basename(x)} for x in saved]})

            if p == "/api/generate":
                src = body.get("dir") or ""
                files = body.get("images") or []
                if files:
                    src_dir = _safe_join(STATE["session"], "selection")
                    os.makedirs(src_dir, exist_ok=True)
                    for f in os.listdir(src_dir):
                        os.remove(os.path.join(src_dir, f))
                    for rel in files:
                        s = rel if os.path.isabs(rel) else os.path.join(WORK, rel)
                        if os.path.isfile(s):
                            with open(s, "rb") as fh, \
                                    open(os.path.join(src_dir, os.path.basename(s)), "wb") as oh:
                                oh.write(fh.read())
                    src = src_dir
                if not src or not os.path.isdir(src):
                    return self._json({"error": f"invalid source dir: {src}"}, 400)
                name = str(body.get("name") or "Photographer (studio)")
                out_rel = str(body.get("outdir") or f"profiles/studio_{time.strftime('%Y%m%d_%H%M%S')}")
                out_dir = out_rel if os.path.isabs(out_rel) else os.path.join(WORK, out_rel)
                jid = uuid.uuid4().hex[:12]
                job = {"status": "running", "log": "", "error": None,
                       "result": None, "kind": "generate", "started": time.time()}
                with _LOCK:
                    STATE["jobs"][jid] = job
                threading.Thread(target=_run_generate, args=(job, src, name, out_dir),
                                 daemon=True).start()
                return self._json({"job": jid})

            if p == "/api/preview":
                path = body.get("path")
                if not path or not os.path.isfile(path):
                    return self._json({"error": f"no such image: {path}"}, 404)
                prof = body.get("profile")
                if prof is None:
                    with _LOCK:
                        prof = STATE["profile"]
                if not prof:
                    return self._json({"error": "no profile loaded"}, 400)
                max_side = int(body.get("max", 1400))
                t0 = time.time()
                data = _render_jpeg(path, prof, max_side)
                return self._bytes(data, "image/jpeg")

            if p == "/api/validate":
                prof = body.get("profile") or {}
                ok, errors = S.validate_profile(prof)
                return self._json({"ok": ok, "errors": errors})

            if p == "/api/save":
                prof = body.get("profile")
                if not prof:
                    return self._json({"error": "no profile"}, 400)
                rel = str(body.get("path") or "profiles/studio/profile_final.json")
                fp = rel if os.path.isabs(rel) else os.path.join(WORK, rel)
                os.makedirs(os.path.dirname(fp), exist_ok=True)
                with open(fp, "w", encoding="utf-8") as f:
                    json.dump(prof, f, indent=2, ensure_ascii=False)
                with _LOCK:
                    STATE["profile"] = prof
                    STATE["profile_path"] = fp
                icon_path = _save_icon(os.path.splitext(fp)[0], body.get("icon"))
                return self._json({"path": fp, "icon_path": icon_path})

            if p == "/api/export_android":
                prof = body.get("profile")
                if not prof:
                    return self._json({"error": "no profile"}, 400)
                # keep CJK in profile names (App preset name = file name);
                # strip only Windows-illegal filename characters
                name = re.sub(r"[\\/:*?\"<>|]+", "_", str(body.get("name") or "studio")) + ".json"
                dest = os.path.join(WORK, "android", "app", "src", "main", "assets", "profiles", name)
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "w", encoding="utf-8") as f:
                    json.dump(prof, f, indent=2, ensure_ascii=False)
                icon_path = _save_icon(os.path.splitext(dest)[0], body.get("icon"))
                return self._json({"path": dest, "icon_path": icon_path})

            if p == "/api/export_lut":
                prof = body.get("profile")
                if not prof:
                    with _LOCK:
                        prof = STATE["profile"]
                if not prof:
                    return self._json({"error": "no profile"}, 400)
                name = re.sub(r'[\\/:*?\"<>|]+', "_", str(body.get("name") or "photographer_look"))
                out_rel = f"studio_session/luts/{name}_{time.strftime('%Y%m%d_%H%M%S')}.cube"
                out_path = out_rel if os.path.isabs(out_rel) else os.path.join(WORK, out_rel)
                os.makedirs(os.path.dirname(out_path), exist_ok=True)
                with open(out_path, "w", encoding="utf-8", newline="\n") as f:
                    f.write(_profile_color_lut_cube(prof, n=33))
                return self._json({"path": out_path})

            if p == "/api/batch":
                prof = body.get("profile") or {}
                files = body.get("images") or []
                out_rel = str(body.get("outdir") or f"studio_session/exports_{time.strftime('%H%M%S')}")
                out_dir = out_rel if os.path.isabs(out_rel) else os.path.join(WORK, out_rel)
                os.makedirs(out_dir, exist_ok=True)
                done = []
                for rel in files:
                    s = rel if os.path.isabs(rel) else os.path.join(WORK, rel)
                    if os.path.isfile(s):
                        out = os.path.join(out_dir, os.path.basename(s))
                        R.render_file(s, prof, out, seed=0)
                        done.append(out)
                return self._json({"outdir": out_dir, "count": len(done), "files": done})

            return self._json({"error": f"unknown route {p}"}, 404)
        except Exception as exc:  # pragma: no cover - defensive
            traceback.print_exc()
            return self._json({"error": str(exc)}, 500)


def _profile_color_lut_cube(profile: dict, n: int = 33) -> str:
    """Render the profile's PER-PIXEL color pipeline into an Adobe .cube 3D LUT.

    Covered: exposure -> white balance -> color matrix -> tone curve ->
    highlight rolloff -> shadow -> HSL. Spatially-varying layers (grain,
    noise, vignette, bloom, halation, sharpen) are intentionally excluded —
    a 3D LUT cannot express them; the Android App renders them live.
    Row order follows the .cube spec: RED varies fastest."""
    p = profile or {}
    axes = np.linspace(0.0, 1.0, n, dtype=np.float32)
    b, g, r = np.meshgrid(axes, axes, axes, indexing="ij")  # r fastest
    rgb = np.stack([r, g, b], axis=-1).reshape(1, -1, 3)
    rgb = R.apply_exposure(rgb, p.get("exposure", {}).get("bias", 0.0))
    wb = p.get("white_balance", {})
    rgb = R.apply_white_balance(rgb, wb.get("temperature_bias", 0.0), wb.get("tint_bias", 0.0))
    rgb = R.apply_color_matrix(rgb, p.get("color_matrix", {}).get("matrix_3x3"))
    rgb = R.apply_tone_curve(rgb, p.get("tone_curve", {}).get("points", []))
    hr = p.get("highlight_rolloff", {})
    rgb = R.apply_highlight_rolloff(rgb, hr.get("threshold", 0.8), hr.get("strength", 0.0), hr.get("saturation", 1.0))
    sh = p.get("shadow", {})
    rgb = R.apply_shadow(rgb, sh.get("black_point", 0.0), sh.get("compression", 0.0),
                         sh.get("tint"), sh.get("saturation", 1.0), sh.get("contrast", 1.0))
    rgb = R.apply_hsl(rgb, p.get("hsl", {}))
    rgb = np.clip(rgb, 0.0, 1.0).reshape(-1, 3)
    lines = [
        'TITLE "PhotographerCamera profile LUT"',
        "# color layers only; grain/vignette/bloom/halation are App-side live effects",
        f"LUT_3D_SIZE {n}",
        "",
    ]
    lines += [f"{v[0]:.6f} {v[1]:.6f} {v[2]:.6f}" for v in rgb]
    return "\n".join(lines) + "\n"


def _save_icon(base_no_ext: str, icon) -> str | None:
    """Write an icon image next to a profile JSON under the SAME name (only the
    extension differs) — the Android preset list pairs <id>.json with <id>.<ext>.
    icon = {"ext": "png|jpg|webp", "data": "data:image/...;base64,..."}. """
    if not isinstance(icon, dict):
        return None
    ext = str(icon.get("ext", "png")).lower()
    if ext not in ("png", "jpg", "webp"):
        return None
    m = re.match(r"^data:image/\w+;base64,(.+)$", str(icon.get("data", "")), re.S)
    if not m:
        return None
    try:
        raw = base64.b64decode(m.group(1))
    except Exception:
        return None
    out = f"{base_no_ext}.{ext}"
    with open(out, "wb") as f:
        f.write(raw)
    return out


def _mime(name: str) -> str:
    return {
        ".html": "text/html; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".js": "application/javascript; charset=utf-8",
        ".svg": "image/svg+xml",
        ".png": "image/png",
        ".ico": "image/x-icon",
    }.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="PhotographerCamera Studio (local web workbench)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args(argv)

    os.makedirs(STATE["session"], exist_ok=True)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://{args.host}:{args.port}/"
    print(f"PhotographerCamera Studio running at {url}")
    print(f"workspace: {WORK}")
    print(f"resources: {BUNDLE}")
    print("Ctrl+C to quit.")
    if not args.no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nbye.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
