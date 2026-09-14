/* PhotographerCamera Studio — Cloudflare Worker 版调色台。
 *
 * 100% 浏览器端：照片读自本机、预览走 GPU（gl_lut.js）、
 * 导入/保存/导出走 lut_pipe.js（与桌面端 profile_renderer.py 对拍一致）。
 * Worker 只负责托管静态文件 —— 训练请在桌面端 / 本地服务器完成。
 */

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const CAP = 2400; // longest side kept in memory for preview / render

const state = {
  images: [],        // [{file, name, url, bmp, w, h, selected}]
  current: null,
  profile: null,
  baseline: null,    // as-loaded profile, for "reset"
  mode: "wipe",
  wipe: 50,
};

let renderer = null; // GLRender instance

/* ─────────────────────────── labels ─────────────────────────── */

const GROUP_LABEL = {
  exposure: "曝光", white_balance: "白平衡", color_matrix: "色彩矩阵",
  tone_curve: "色调曲线", highlight_rolloff: "高光滚降", shadow: "阴影",
  film_curve: "胶片曲线", hsl: "HSL 分色", sharpen: "锐化", bloom: "柔光溢出",
  halation: "光晕", grain: "颗粒", noise: "噪点",
  vignette: "暗角", lens: "镜头",
};
const PARAM_LABEL = {
  bias: "曝光偏置 (EV)", temperature_bias: "色温偏移", tint_bias: "色调偏移",
  threshold: "阈值", strength: "强度", amount: "强度", radius: "半径",
  saturation: "饱和度", compression: "压缩", black_point: "黑点",
  contrast: "对比度", luma: "亮度噪点", chroma: "彩度噪点",
  size: "颗粒尺寸", density: "密度", warmth: "暖度",
  shadow_floor: "暗部下界", highlight_ceiling: "高光上界",
};
const HUE_CN = {
  red: "红", orange: "橙", yellow: "黄", green: "绿",
  cyan: "青", blue: "蓝", purple: "紫",
};
const HSL_PARAM_CN = { saturation: "饱和度", lightness: "明度", hue_shift: "色相偏移" };

/* ─────────────────────────── controls schema ─────────────────────────── */
/* Mirrors desktop/server.py build_controls() + profile_schema safe bands. */

const ROW = ["R", "G", "B"], COL = ["R", "G", "B"];

function buildControls() {
  const c = [];
  const num = (path, label, min, max, step) => ({ path, label, type: "number", min, max, step: step == null ? 0.01 : step });

  c.push(num("exposure.bias", "曝光偏置 (EV)", -2, 2, 0.01));

  c.push(num("white_balance.temperature_bias", "色温偏移", -1, 1));
  c.push(num("white_balance.tint_bias", "色调偏移", -1, 1));

  // 3x3 color matrix — 9 sliders, diagonal = per-channel gain, off-diag = cross-talk
  for (let r = 0; r < 3; r++)
    for (let cc = 0; cc < 3; cc++) {
      const diag = r === cc;
      c.push(num(
        `color_matrix.matrix_3x3.${r}.${cc}`, `${ROW[r]}→${COL[cc]}`,
        diag ? 0.8 : -0.2, diag ? 1.2 : 0.2, 0.001
      ));
    }

  // HSL: 7 hues × {saturation, lightness, hue_shift}
  for (const hue of ["red", "orange", "yellow", "green", "cyan", "blue", "purple"]) {
    c.push(num(`hsl.${hue}.saturation`, `${HUE_CN[hue]} 饱和度`, 0, 2));
    c.push(num(`hsl.${hue}.lightness`, `${HUE_CN[hue]} 明度`, 0, 2));
    c.push(num(`hsl.${hue}.hue_shift`, `${HUE_CN[hue]} 色相偏移`, -30, 30, 0.1));
  }

  c.push({ path: "tone_curve.points", label: "色调曲线", type: "tone_curve" });

  c.push(num("highlight_rolloff.threshold", "阈值", 0, 1));
  c.push(num("highlight_rolloff.strength", "强度", 0, 1));
  c.push(num("highlight_rolloff.saturation", "饱和度", 0, 2));

  c.push(num("shadow.black_point", "黑点", 0, 0.1));        // safe band (schema allows 0.2)
  c.push(num("shadow.compression", "压缩", 0, 1));
  c.push(num("shadow.saturation", "饱和度", 0, 2));
  c.push(num("shadow.contrast", "对比度", 0, 2));

  c.push(num("film_curve.shadow_floor", "暗部下界", 0, 16, 1));      // safe band (schema allows 64)
  c.push(num("film_curve.highlight_ceiling", "高光上界", 191, 255, 1));

  c.push(num("lens.vignette", "暗角", 0, 1));
  c.push(num("lens.chromatic_aberration", "色散", 0, 1));
  c.push(num("lens.sharpness_falloff", "锐度衰减", 0, 1));
  c.push(num("lens.distortion", "畸变", -1, 1));
  c.push(num("lens.bloom", "泛光", 0, 1));
  c.push(num("lens.flare", "光斑", 0, 1));

  c.push(num("grain.amount", "强度", 0, 1));
  c.push(num("grain.size", "尺寸", 0.5, 3));
  c.push(num("grain.density", "密度", 0.5, 3));

  c.push(num("noise.luma", "亮度噪点", 0, 1));
  c.push(num("noise.chroma", "彩度噪点", 0, 1));

  c.push(num("halation.amount", "强度", 0, 1));
  c.push(num("halation.radius", "半径", 0.5, 4));
  c.push(num("halation.threshold", "阈值", 0, 1));
  c.push(num("halation.warmth", "暖度", 0, 2));

  c.push(num("bloom.amount", "强度", 0, 1));
  c.push(num("bloom.radius", "半径", 0.5, 4));
  c.push(num("bloom.threshold", "阈值", 0, 1));

  c.push(num("vignette.amount", "强度", 0, 1));
  c.push(num("vignette.radius", "半径", 0, 1));
  c.push(num("vignette.feather", "羽化", 0, 1));

  c.push(num("sharpen.amount", "强度", 0, 1));
  c.push(num("sharpen.radius", "半径", 0.5, 3));

  return c;
}
const CONTROLS = buildControls();

/* ─────────────────────────── default profile ─────────────────────────── */

function defaultProfile(name) {
  return {
    version: 1, schema_version: 1, name: name || "我的风格",
    validation_status: "pending",
    exposure: { bias: 0.0 },
    white_balance: { temperature_bias: 0.0, tint_bias: 0.0 },
    color_matrix: {
      matrix_3x3: [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
      input_gamut: "sRGB", output_gamut: "sRGB",
    },
    hsl: {
      red: { saturation: 1, lightness: 1, hue_shift: 0 },
      orange: { saturation: 1, lightness: 1, hue_shift: 0 },
      yellow: { saturation: 1, lightness: 1, hue_shift: 0 },
      green: { saturation: 1, lightness: 1, hue_shift: 0 },
      cyan: { saturation: 1, lightness: 1, hue_shift: 0 },
      blue: { saturation: 1, lightness: 1, hue_shift: 0 },
      purple: { saturation: 1, lightness: 1, hue_shift: 0 },
    },
    tone_curve: { points: [[0.0, 0.0], [1.0, 1.0]] },
    highlight_rolloff: { threshold: 0.8, strength: 0.0, saturation: 1.0 },
    shadow: { black_point: 0.0, compression: 0.0, saturation: 1.0, contrast: 1.0 },
    film_curve: { shadow_floor: 8, highlight_ceiling: 248 },
    lens: { vignette: 0.0, chromatic_aberration: 0.0, sharpness_falloff: 0.0, distortion: 0.0, bloom: 0.0, flare: 0.0 },
    grain: { amount: 0.0, size: 1.0, density: 1.0 },
    noise: { luma: 0.0, chroma: 0.0 },
    halation: { amount: 0.0, radius: 1.0, threshold: 0.9, warmth: 1.0 },
    bloom: { amount: 0.0, radius: 1.0, threshold: 0.9 },
    vignette: { amount: 0.0, radius: 1.0, feather: 0.5, center: [0.5, 0.5] },
    sharpen: { amount: 0.0, radius: 1.0 },
  };
}

/* Merge an imported profile over the default so every slider has a value.
 * Empty objects in the import keep their defaults (identity); arrays (matrix,
 * curve points, center) replace wholesale. */
function mergeImport(base, src) {
  for (const k in src) {
    const v = src[k];
    if (v && typeof v === "object" && !Array.isArray(v)) {
      if (!base[k] || typeof base[k] !== "object" || Array.isArray(base[k])) base[k] = {};
      if (Object.keys(v).length === 0) continue; // identity — keep defaults
      mergeImport(base[k], v);
    } else {
      base[k] = v;
    }
  }
  return base;
}

/* ─────────────────────────── helpers ─────────────────────────── */

const getPath = (obj, p) => p.split(".").reduce((o, k) => (o == null ? o : o[k]), obj);
const setPath = (obj, p, v) => {
  const ks = p.split(".");
  let o = obj;
  for (let i = 0; i < ks.length - 1; i++) {
    if (typeof o[ks[i]] !== "object" || o[ks[i]] === null) o[ks[i]] = {};
    o = o[ks[i]];
  }
  o[ks[ks.length - 1]] = v;
};
const clone = (o) => JSON.parse(JSON.stringify(o));

function setStatus(text, cls = "") {
  const el = $("#status");
  el.textContent = text;
  el.className = "status " + cls;
}

function download(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

function safeName() {
  return ($("#profileName").value.trim() || "studio").replace(/[\\/:*?"<>|]+/g, "_");
}

/* ─────────────────────────── image intake ─────────────────────────── */

const IMG_RE = /\.(jpe?g|png|webp|bmp|tif|tiff|gif|avif|heic)$/i;

function addFiles(fileList) {
  const files = Array.from(fileList).filter((f) => IMG_RE.test(f.name));
  if (!files.length) return setStatus("没有识别到图片文件", "err");
  const existing = new Set(state.images.map((i) => i.name + i.file.size));
  for (const f of files) {
    const key = f.name + f.size;
    if (existing.has(key)) continue;
    state.images.push({ file: f, name: f.name, url: URL.createObjectURL(f), bmp: null, w: 0, h: 0, selected: true });
  }
  renderThumbs();
  if (!state.current && state.images.length) setCurrent(state.images[0]);
  setStatus(`已载入 ${state.images.length} 张（本地内存）`, "ok");
}

async function decodeImage(img) {
  if (img.bmp) return;
  const bmp = await createImageBitmap(img.file);
  const s = Math.min(1, CAP / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * s));
  const h = Math.max(1, Math.round(bmp.height * s));
  const c = document.createElement("canvas");
  c.width = w; c.height = h;
  c.getContext("2d").drawImage(bmp, 0, 0, w, h);
  bmp.close && bmp.close();
  img.bmp = c; img.w = w; img.h = h;
}

function renderThumbs() {
  $("#imgCount").textContent = `${state.images.length} 张`;
  const box = $("#thumbs");
  box.innerHTML = "";
  state.images.forEach((img) => {
    const d = document.createElement("div");
    d.className = "thumb" + (state.current === img ? " sel" : "");
    d.innerHTML = `<img src="${img.url}" alt=""><input type="checkbox" ${img.selected ? "checked" : ""}>`;
    d.querySelector("img").onclick = () => setCurrent(img);
    d.querySelector("input").onclick = (e) => { e.stopPropagation(); img.selected = e.target.checked; };
    box.appendChild(d);
  });
}

async function setCurrent(img) {
  state.current = img;
  $("#viewName").textContent = img.name;
  $("#empty").hidden = true;
  renderThumbs();
  setStatus("解码原图…", "busy");
  try {
    await decodeImage(img);
    const cv = $("#cvsOrig");
    cv.width = img.w; cv.height = img.h;
    cv.getContext("2d").drawImage(img.bmp, 0, 0);
  } catch (e) {
    return setStatus("原图解码失败: " + e.message, "err");
  }
  if (!renderer || !renderer.ok) return setStatus("WebGL2 不可用，无法预览", "err");
  try {
    renderer.setImage(img.bmp);
  } catch (e) {
    return setStatus("渲染器载入失败: " + e.message, "err");
  }
  requestRender();
  setStatus("就绪", "");
}

/* ─────────────────────────── compare view ─────────────────────────── */

function applyMode() {
  const { mode, wipe } = state;
  const rend = $("#rendWrap");
  rend.style.display = mode === "orig" ? "none" : "flex";
  if (mode === "wipe") {
    rend.style.clipPath = `inset(0 0 0 ${wipe}%)`;
    $("#wipe").style.display = "block";
    $("#wipe").style.left = wipe + "%";
  } else {
    rend.style.clipPath = "none";
    $("#wipe").style.display = "none";
  }
  $("#cvsOrig").style.display = mode === "rend" ? "none" : "block";
  $$(".tag").forEach((t) => (t.style.display = mode === "wipe" ? "block" : "none"));
}

function initStage() {
  const stage = $("#stage");
  let dragging = false;
  const move = (e) => {
    if (!dragging) return;
    const r = stage.getBoundingClientRect();
    state.wipe = Math.max(0, Math.min(100, ((e.clientX - r.left) / r.width) * 100));
    applyMode();
  };
  stage.addEventListener("mousedown", (e) => {
    if (state.mode !== "wipe") return;
    dragging = true; move(e);
  });
  window.addEventListener("mousemove", move);
  window.addEventListener("mouseup", () => (dragging = false));

  $$(".seg-btn").forEach((b) => {
    b.onclick = () => {
      state.mode = b.dataset.mode;
      $$(".seg-btn").forEach((x) => x.classList.toggle("active", x === b));
      applyMode();
    };
  });
}

/* ─────────────────────────── preview render (local GPU) ─────────────────────────── */

let renderQueued = false;
function requestRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    refreshPreview();
  });
}

function refreshPreview() {
  if (!renderer || !renderer.ok || !state.current || !state.profile) return;
  $("#spinner").hidden = false;
  try {
    renderer.render(state.profile);
    $("#empty").hidden = true;
  } catch (e) {
    setStatus("渲染失败: " + e.message, "err");
  } finally {
    $("#spinner").hidden = true;
  }
}

/* ─────────────────────────── controls panel ─────────────────────────── */

function renderControls() {
  const box = $("#ctrlBox");
  if (!state.profile) {
    box.innerHTML = '<div class="hint">尚未载入 Profile。点击上方「导入 Profile」或「新建空白 Profile」。</div>';
    return;
  }
  box.innerHTML = "";
  const groups = {};
  CONTROLS.forEach((c) => {
    const g = c.path.split(".")[0];
    (groups[g] = groups[g] || []).push(c);
  });

  Object.entries(groups).forEach(([g, items], gi) => {
    const wrap = document.createElement("div");
    wrap.className = "group" + (gi > 2 ? " collapsed" : "");
    wrap.innerHTML =
      `<div class="group-head"><span>${GROUP_LABEL[g] || g}</span><span class="arrow">▼</span></div>` +
      `<div class="group-body"></div>`;
    const body = wrap.querySelector(".group-body");

    items.forEach((c) => {
      const cur = getPath(state.profile, c.path);
      if (cur === undefined) return;

      if (c.type === "tone_curve") { renderToneCurve(body, c); return; }

      const row = document.createElement("div");
      row.className = "slider";
      const segs = c.path.split(".");
      let label = c.label || PARAM_LABEL[segs[segs.length - 1]] || c.path;
      if (segs[0] === "hsl" && HUE_CN[segs[1]]) {
        label = HUE_CN[segs[1]] + "·" + (HSL_PARAM_CN[segs[2]] || label);
      }

      const step = c.step != null ? c.step : 0.01;
      row.innerHTML =
        `<div class="lab"><span class="name">${label}</span>` +
        `<span class="val" contenteditable="false">${(+cur).toFixed(3)}</span></div>` +
        `<input type="range" min="${c.min}" max="${c.max}" step="${step}" value="${cur}">`;
      const range = row.querySelector("input");
      const valEl = row.querySelector(".val");
      range.oninput = () => {
        const v = parseFloat(range.value);
        setPath(state.profile, c.path, v);
        valEl.textContent = v.toFixed(3);
        row.classList.toggle("changed", Math.abs(v - (getPath(state.baseline, c.path) ?? v)) > 1e-9);
        requestRender();
      };
      valEl.onclick = () => {
        const s = prompt(`${label} (${c.min} ~ ${c.max})`, getPath(state.profile, c.path));
        if (s === null) return;
        const v = Math.max(c.min, Math.min(c.max, parseFloat(s)));
        if (Number.isFinite(v)) {
          setPath(state.profile, c.path, v);
          range.value = v;
          valEl.textContent = v.toFixed(3);
          row.classList.toggle("changed", Math.abs(v - (getPath(state.baseline, c.path) ?? v)) > 1e-9);
          requestRender();
        }
      };
      body.appendChild(row);
    });

    if (!body.children.length) return;
    wrap.querySelector(".group-head").onclick = () => wrap.classList.toggle("collapsed");
    box.appendChild(wrap);
  });
}

/* ─────────────────────────── tone curve editor ─────────────────────────── */

function renderToneCurve(body, control) {
  const tc = state.profile.tone_curve;
  if (!tc || !Array.isArray(tc.points) || tc.points.length < 2) return;

  const W = 260, H = 190, PAD = 18;
  const X = (u) => PAD + u * (W - 2 * PAD);
  const Y = (v) => H - PAD - v * (H - 2 * PAD);

  const wrap = document.createElement("div");
  wrap.className = "tone-editor";
  body.appendChild(wrap);

  const svgNS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(svgNS, "svg");
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  svg.setAttribute("class", "tone-svg");
  wrap.appendChild(svg);

  const mk = (tag, attrs) => {
    const el = document.createElementNS(svgNS, tag);
    for (const k in attrs) el.setAttribute(k, attrs[k]);
    return el;
  };
  for (let i = 0; i <= 4; i++) {
    const t = i / 4;
    svg.appendChild(mk("line", { x1: X(t), y1: Y(0), x2: X(t), y2: Y(1), class: "tone-grid" }));
    svg.appendChild(mk("line", { x1: X(0), y1: Y(t), x2: X(1), y2: Y(t), class: "tone-grid" }));
  }
  svg.appendChild(mk("line", { x1: X(0), y1: Y(0), x2: X(1), y2: Y(1), class: "tone-identity" }));

  const curve = mk("polyline", { class: "tone-curve" });
  svg.appendChild(curve);
  const dots = mk("g", {});
  svg.appendChild(dots);

  function sampleY(points, u) {
    const idx = points.map((_, i) => i).sort((a, b) => points[a][0] - points[b][0]);
    const xs = idx.map((i) => points[i][0]);
    const ys = idx.map((i) => points[i][1]);
    if (u <= xs[0]) return ys[0];
    if (u >= xs[xs.length - 1]) return ys[ys.length - 1];
    for (let i = 0; i < xs.length - 1; i++) {
      if (u >= xs[i] && u <= xs[i + 1]) {
        const t = (u - xs[i]) / Math.max(1e-6, xs[i + 1] - xs[i]);
        return ys[i] + (ys[i + 1] - ys[i]) * t;
      }
    }
    return ys[ys.length - 1];
  }

  function draw() {
    const pts = tc.points;
    const line = [];
    for (let i = 0; i <= 96; i++) {
      const u = i / 96;
      line.push(`${X(u)},${Y(sampleY(pts, u))}`);
    }
    curve.setAttribute("points", line.join(" "));

    while (dots.childNodes.length < pts.length) {
      const c = mk("circle", { r: 5, class: "tone-dot" });
      c.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        svg.setPointerCapture(e.pointerId);
        c.dataset.active = "1";
        onDrag(e);
      });
      c.addEventListener("pointermove", (e) => { if (c.dataset.active) onDrag(e); });
      const end = () => { if (c.dataset.active) { delete c.dataset.active; draw(); } };
      c.addEventListener("pointerup", end);
      c.addEventListener("pointercancel", end);
      dots.appendChild(c);
    }
    while (dots.childNodes.length > pts.length) dots.removeChild(dots.lastChild);

    pts.forEach((p, i) => {
      const c = dots.childNodes[i];
      c.setAttribute("cx", X(p[0]));
      c.setAttribute("cy", Y(p[1]));
      c.dataset.idx = i;
    });
  }

  function onDrag(e) {
    const active = dots.querySelector('[data-active="1"]');
    if (!active) return;
    const i = +active.dataset.idx;
    const r = svg.getBoundingClientRect();
    const u = Math.max(0, Math.min(1, ((e.clientX - r.left) / r.width * W - PAD) / (W - 2 * PAD)));
    const v = Math.max(0, Math.min(1, 1 - ((e.clientY - r.top) / r.height * H - PAD) / (H - 2 * PAD)));
    const pts = tc.points;
    const EPS = 0.005;
    const minX = i === 0 ? 0 : Math.min(1 - EPS, pts[i - 1][0] + EPS);
    const maxX = i === pts.length - 1 ? 1 : Math.max(EPS, pts[i + 1][0] - EPS);
    pts[i] = [Math.max(minX, Math.min(maxX, u)), v];
    draw();
    requestRender();
  }

  const bar = document.createElement("div");
  bar.className = "tone-btns";
  bar.innerHTML =
    `<button class="tone-btn" data-act="linear">重置为线性</button>` +
    `<button class="tone-btn" data-act="resample">均匀 17 点</button>` +
    `<span class="tone-hint">${tc.points.length} 点 · 拖拽控制点调整</span>`;
  wrap.appendChild(bar);

  bar.querySelector('[data-act="linear"]').onclick = () => {
    tc.points = [[0, 0], [1, 1]];
    bar.querySelector(".tone-hint").textContent = "2 点 · 拖拽控制点调整";
    draw(); requestRender();
  };
  bar.querySelector('[data-act="resample"]').onclick = () => {
    const src = tc.points.map((p) => [p[0], p[1]]);
    tc.points = [];
    for (let i = 0; i < 17; i++) tc.points.push([i / 16, sampleY(src, i / 16)]);
    bar.querySelector(".tone-hint").textContent = "17 点 · 拖拽控制点调整";
    draw(); requestRender();
  };

  draw();
}

/* ─────────────────────────── import / export ─────────────────────────── */

function loadProfile(prof, name) {
  // 严格按 JSON 显示：不再在导入时 neutralize 参数层（旧逻辑会把整组键
  // 重置成架构默认值，导致滑块显示 1/0 或 0.8 而非真实值）。WebGL 预览
  // 会按 LUT→film→参数 链路如实施加 JSON 内的参数，便于在此之上微调。
  const merged = mergeImport(defaultProfile(name), prof);
  state.profile = merged;
  state.baseline = clone(merged);
  if (merged.display) {
    $("#profileName").value = merged.display.name || name || "我的风格";
    $("#profileIntro").value = merged.display.intro || "";
  } else {
    $("#profileName").value = merged.name || name || "我的风格";
  }
  renderControls();
  if (state.current) requestRender();
}

function importProfile(file) {
  if (!file) return;
  setStatus("读取 JSON…", "busy");
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const prof = JSON.parse(String(reader.result));
      if (!prof || typeof prof !== "object") throw new Error("不是有效的 profile 对象");
      loadProfile(prof, file.name.replace(/\.json$/i, ""));
      setStatus("已导入 " + file.name, "ok");
    } catch (e) {
      setStatus("导入失败: " + e.message, "err");
    }
  };
  reader.onerror = () => setStatus("文件读取失败", "err");
  reader.readAsText(file);
}

function collectDisplay() {
  const rawName = $("#profileName").value.trim();
  const intro = $("#profileIntro").value.trim();
  const d = { name: rawName || "我的风格" };
  if (intro) d.intro = intro;
  return d;
}

function saveProfile() {
  if (!state.profile) return setStatus("没有可保存的 Profile", "err");
  let prof = clone(state.profile);
  prof.display = collectDisplay();
  prof.name = prof.display.name;
  // 与桌面端保存语义一致：LUT profile 上把 film+参数烘焙回网格，
  // 保证任何引擎（含不认参数链的旧安卓端）看到的都是预览效果。
  if (window.LUTPIPE && LUTPIPE.isLutProfile(prof)) {
    const baked = LUTPIPE.bakeParamsIntoLut(prof);
    if (baked) prof = baked;
  }
  const blob = new Blob([JSON.stringify(prof, null, 2)], { type: "application/json" });
  download(blob, safeName() + ".json");
  setStatus("已导出 " + safeName() + ".json", "ok");
}

function exportLut() {
  if (!state.profile) return setStatus("没有可导出的风格", "err");
  if (!window.LUT3D) return setStatus("LUT 模块未加载", "err");
  if (!window.LUTPIPE) return setStatus("管线模块未加载", "err");
  setStatus("烘焙 3D LUT…", "busy");
  try {
    // 完整色彩管线（LUT → film → 参数）按网格采样；HSL 被动过时用 65³
    // 规避色相硬分箱的插值发散（与桌面端一致）。
    const hslTweaked = Object.values(state.profile.hsl || {}).some((ch) => !!ch);
    const n = hslTweaked ? 65 : 33;
    const grid = LUTPIPE.colorPipelineCube(state.profile, n);
    const cube = LUT3D.lut3dToCube(grid, n, safeName());
    download(new Blob([cube], { type: "text/plain" }), safeName() + ".cube");
    setStatus(`已导出 ${safeName()}.cube（${n}³ 完整色彩管线）`, "ok");
  } catch (e) {
    setStatus("LUT 导出失败: " + e.message, "err");
  }
}

async function batchRender() {
  const picked = state.images.filter((i) => i.selected);
  if (!picked.length) return setStatus("没有选中照片", "err");
  if (!state.profile) return setStatus("没有可应用的 Profile", "err");
  if (!renderer || !renderer.ok) return setStatus("WebGL 不可用", "err");
  setStatus(`批量渲染 ${picked.length} 张…`, "busy");
  try {
    for (let i = 0; i < picked.length; i++) {
      const img = picked[i];
      await decodeImage(img);
      renderer.setImage(img.bmp);
      renderer.render(state.profile);
      const c2 = document.createElement("canvas");
      c2.width = renderer.canvas.width; c2.height = renderer.canvas.height;
      c2.getContext("2d").drawImage(renderer.canvas, 0, 0);
      const blob = await new Promise((res) => c2.toBlob(res, "image/png"));
      if (blob) download(blob, img.name.replace(/\.[^.]+$/, "") + "_graded.png");
      setStatus(`批量渲染中 ${i + 1}/${picked.length}`, "busy");
    }
    setStatus(`已导出 ${picked.length} 张到下载目录`, "ok");
  } catch (e) {
    setStatus("批量渲染失败: " + e.message, "err");
  }
}

/* ─────────────────────────── wire up ─────────────────────────── */

function init() {
  initStage();
  applyMode();

  if (window.GLRender) {
    renderer = GLRender.create($("#cvsRend"));
    if (!renderer.ok) {
      renderer = null;
      setStatus("当前浏览器不支持 WebGL2，预览不可用", "err");
    }
  } else {
    setStatus("渲染模块未加载", "err");
  }

  $("#btnPickFolder").onclick = () => $("#dirInput").click();
  $("#dirInput").onchange = (e) => addFiles(e.target.files);
  $("#btnPickFiles").onclick = () => $("#fileInput").click();
  $("#fileInput").onchange = (e) => addFiles(e.target.files);

  const dz = $("#dropzone");
  ["dragenter", "dragover"].forEach((t) =>
    dz.addEventListener(t, (e) => { e.preventDefault(); dz.classList.add("over"); })
  );
  ["dragleave", "drop"].forEach((t) =>
    dz.addEventListener(t, (e) => { e.preventDefault(); dz.classList.remove("over"); })
  );
  dz.addEventListener("drop", (e) => {
    if (e.dataTransfer && e.dataTransfer.files) addFiles(e.dataTransfer.files);
  });

  $("#selectAll").onchange = (e) => {
    state.images.forEach((i) => (i.selected = e.target.checked));
    renderThumbs();
  };

  $("#btnImport").onclick = () => $("#importInput").click();
  $("#importInput").onchange = (e) => {
    const f = e.target.files && e.target.files[0];
    importProfile(f);
    e.target.value = "";
  };

  $("#btnNew").onclick = () => {
    loadProfile(defaultProfile($("#profileName").value.trim() || "我的风格"));
    setStatus("已新建空白 Profile", "ok");
  };

  $("#btnSaveProfile").onclick = saveProfile;
  $("#btnSave").onclick = exportLut;
  $("#btnBatch").onclick = batchRender;
  $("#btnReset").onclick = () => {
    if (!state.baseline) return;
    state.profile = clone(state.baseline);
    renderControls();
    requestRender();
    setStatus("已重置为载入值", "ok");
  };

  setStatus("就绪 · 选择照片并导入 Profile");
}

init();
