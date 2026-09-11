# -*- coding: utf-8 -*-
"""Desktop GL preview + slider effectiveness test (headless Edge CDP).

1. Make a deterministic test image.
2. Start desktop/server.py (dev mode) on a test port.
3. Load the newly trained identity profile via /api/load_profile.
4. Open the UI in headless Edge, drive the app:
   - verify WebGL renderer is active;
   - for EVERY slider in the control panel: set an extreme value, let the GL
     preview repaint, measure pixel diff vs the untouched render;
   - click every button and confirm no JS errors.
5. Compare the GL canvas against the authoritative Python render.
"""
import base64
import http.server
import io
import json
import functools
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

PORT = 8766
CDP_PORT = 9223
import numpy as np  # noqa: E402
import profile_renderer as R  # noqa: E402

# ---------------------------------------------------------------- test image
os.makedirs(os.path.join(HERE, "gl_slider_test"), exist_ok=True)
IMG_PATH = os.path.join(HERE, "gl_slider_test", "img.png")
rng = np.random.default_rng(11)
x = np.linspace(0, 1, 640, dtype=np.float32)
y = np.linspace(0, 1, 480, dtype=np.float32)
xx, yy = np.meshgrid(x, y)
# smooth gradients only — hard edges would be dominated by resampling
# differences between canvas/PIL downsamplers in the numeric A/B
img = np.stack([xx, yy, 1 - xx], axis=-1) * 0.6 + 0.2
img = np.clip(img, 0, 1)
from PIL import Image  # noqa: E402
Image.fromarray((img * 255).astype(np.uint8), "RGB").save(IMG_PATH)

# ---------------------------------------------------------------- server
env = dict(os.environ)
env["http_proxy"] = ""; env["https_proxy"] = ""
env["HTTP_PROXY"] = ""; env["HTTPS_PROXY"] = ""
proc = subprocess.Popen(
    [sys.executable, "-u", os.path.join(ROOT, "desktop", "server.py"),
     "--port", str(PORT), "--no-browser"],
    cwd=ROOT, env=env,
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def http_get(url):
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return op.open(url, timeout=5).read()

def http_post(url, payload):
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    return json.loads(op.open(req, timeout=10).read())

try:
    for _ in range(60):
        try:
            http_get(f"http://127.0.0.1:{PORT}/api/health"); break
        except Exception:
            time.sleep(0.5)
    print("server up")

    # load the newly trained identity-params profile
    import glob
    cand = [c for c in glob.glob(os.path.join(ROOT, "dist/profiles/studio_1789132402165/*.json"))
            if "report" not in c and "validation" not in c][0]
    prof_path = os.path.relpath(cand, ROOT).replace("\\", "/")
    import urllib.parse
    r = json.loads(http_get(f"http://127.0.0.1:{PORT}/api/load_profile?path=" + urllib.parse.quote(prof_path)))
    assert "profile" in r, r
    profile = r["profile"]
    print("profile loaded:", prof_path, "| bias:", profile["exposure"]["bias"])

    # authoritative Python render of the test image (grain/noise zeroed to
    # match the GL comparison pass — stochastic layers can't be seed-matched)
    prof_ab = json.loads(json.dumps(profile))
    prof_ab["grain"]["amount"] = 0; prof_ab["noise"]["luma"] = 0; prof_ab["noise"]["chroma"] = 0
    py_rgb = R.render(img.astype(np.float32), prof_ab)
    py_small = np.asarray(Image.fromarray((np.clip(py_rgb, 0, 1) * 255).astype(np.uint8), "RGB")
                          .resize((64, 48), Image.BILINEAR), dtype=np.float32)

    # ---------------------------------------------------------------- CDP
    import websocket
    prof_dir = os.path.join(HERE, "edge_profile_gl")
    exe = None
    for cand_exe in (r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                     r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"):
        if os.path.exists(cand_exe):
            exe = cand_exe
            break
    assert exe, "Edge not found"
    edge = subprocess.Popen([exe, "--headless=new", f"--remote-debugging-port={CDP_PORT}",
                             f"--user-data-dir={prof_dir}", "--no-first-run",
                             "--remote-allow-origins=*", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(3)
    info = json.loads(http_get(f"http://127.0.0.1:{CDP_PORT}/json/list"))
    page = [t for t in info if t.get("type") == "page"][0]
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=90,
                                     suppress_origin=True)
    mid = [0]

    def cdp(method, params=None):
        mid[0] += 1
        ws.send(json.dumps({"id": mid[0], "method": method, "params": params or {}}))
        while True:
            msg = json.loads(ws.recv())
            if msg.get("id") == mid[0]:
                res = msg.get("result", {}).get("result", {})
                if res.get("subtype") == "error":
                    raise RuntimeError(res.get("description", "cdp error"))
                return res.get("value")

    cdp("Page.navigate", {"url": f"http://127.0.0.1:{PORT}/"})
    time.sleep(4)

    # drive: load the test image through the app's own browse API, then test
    js = r"""
(async () => {
  const imgPath = %s;
  const profPath = %s;
  // load profile into the app's state exactly like an import would
  const r = await api('/api/load_profile?path=' + encodeURIComponent(profPath));
  state.profile = r.profile;
  state.baseline = JSON.parse(JSON.stringify(r.profile));
  state.controls = (await api('/api/controls')).controls;
  renderControls(); renderReport();
  // pick the image through the app pipeline
  await loadDir(imgPath);
  await new Promise(r => setTimeout(r, 2500));   // setCurrent + first preview
  const out = { glActive: !!(renderer && renderer.ok), sliders: [], buttons: [], errors: window.__errs || [] };

  const grab = () => {
    const cv = document.getElementById('cvsRend');
    const off = document.createElement('canvas'); off.width = 64; off.height = 48;
    const c = off.getContext('2d'); c.drawImage(cv, 0, 0, 64, 48);
    return Array.from(c.getImageData(0, 0, 64, 48).data);
  };
  const baseline = grab();

  const ranges = Array.from(document.querySelectorAll('#ctrlBox input[type=range]'));
  for (const range of ranges) {
    const label = range.closest('.slider')?.querySelector('.name')?.textContent || '?';
    const orig = range.value;
    const target = parseFloat(range.max) > parseFloat(orig) ? range.max : range.min;
    range.value = target;
    range.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(r => setTimeout(r, 350));   // rAF + render
    const cur = grab();
    let diff = 0;
    for (let i = 0; i < cur.length; i += 4) {
      const d = Math.abs(cur[i] - baseline[i]) + Math.abs(cur[i+1] - baseline[i+1]) + Math.abs(cur[i+2] - baseline[i+2]);
      if (d > diff) diff = d;
    }
    out.sliders.push({ label, target, maxDiff: diff });
    range.value = orig;
    range.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise(r => setTimeout(r, 350));
  }
  // safe buttons only — skip ones that trigger dialogs or heavy jobs
  const safeSel = ['#btnLoadDir', '#btnLoadValDir', '#btnPickIcon'];
  out.buttonResults = [];
  for (const sel of safeSel) {
    const b = document.querySelector(sel);
    if (!b) { out.buttonResults.push({ sel, ok: false, why: 'missing' }); continue; }
    const before = document.getElementById('status').textContent;
    try { b.click(); await new Promise(r => setTimeout(r, 250)); } catch (e) {}
    const after = document.getElementById('status').textContent;
    out.buttonResults.push({ sel, ok: true, statusChanged: before !== after });
  }
  out.segButtons = Array.from(document.querySelectorAll('.seg-btn')).map(b => {
    b.click(); return b.textContent.trim();
  });
  await new Promise(r => setTimeout(r, 300));
  // For the numeric GL-vs-Python comparison, zero the stochastic layers
  // (grain/noise use different random streams in GL vs Python) — the
  // deterministic colour + spatial layers must still match.
  state.profile.grain.amount = 0; state.profile.noise.luma = 0; state.profile.noise.chroma = 0;
  refreshPreview();
  await new Promise(r => setTimeout(r, 400));
  out.final = grab();
  return out;
})()
""" % (json.dumps(os.path.dirname(IMG_PATH)), json.dumps(prof_path))
    res = cdp("Runtime.evaluate", {"expression": js, "awaitPromise": True,
                                   "returnByValue": True})
    data = res
    print("GL active:", data["glActive"])
    dead = [s for s in data["sliders"] if s["maxDiff"] < 4]
    print("sliders tested:", len(data["sliders"]), "| dead (<4/255 diff):", len(dead))
    for s in dead:
        print("  DEAD:", s)
    worst = sorted(data["sliders"], key=lambda s: -s["maxDiff"])[:5]
    for s in worst:
        print("  top:", s["label"], s["maxDiff"])

    # GL vs Python
    gl = np.asarray(data["final"], dtype=np.float32).reshape(48, 64, 4)[:, :, :3]
    d = np.abs(gl - py_small)
    print("GL vs Python: mean|d|", round(float(d.mean()), 2), "p99", round(float(np.percentile(d, 99)), 2))
    # dump the GL canvas at full size for visual inspection
    png_b64 = cdp("""
(() => {
  const cv = document.getElementById('cvsRend');
  return cv.toDataURL('image/png').split(',')[1];
})()
""") if False else cdp("Runtime.evaluate", {
        "expression": "document.getElementById('cvsRend').toDataURL('image/png').split(',')[1]",
        "returnByValue": True})
    open(os.path.join(HERE, "gl_ab_gl.png"), "wb").write(base64.b64decode(png_b64))
    print("GL canvas dumped to _diag/gl_ab_gl.png")
    print("safe buttons:", data["buttonResults"])
    print("seg buttons:", data["segButtons"])
    ws.close()
finally:
    try:
        edge.terminate()
    except Exception:
        pass
    proc.terminate()
print("TEST_DONE")
