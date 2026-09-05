/* PhotographerCamera Studio — front-end logic (no framework, no build step). */

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  images: [],        // [{path, name, selected}]
  current: null,
  profile: null,
  baseline: null,    // as-generated profile, for "reset"
  controls: [],
  report: null,
  mode: "wipe",
  wipe: 50,
  reqSeq: 0,
  rendUrl: null,
  origUrl: null,
  jobId: null,
  icon: null,        // {ext: "png"|"jpg"|"webp", dataUrl: "data:image/..;base64,.."} — saved next to the profile JSON under the SAME file name
};

const GROUP_LABEL = {
  exposure: "曝光", white_balance: "白平衡", color_matrix: "色彩矩阵",
  tone_curve: "色调曲线", highlight_rolloff: "高光滚降", shadow: "阴影",
  hsl: "HSL 分色", sharpen: "锐化", bloom: "柔光溢出 Bloom",
  halation: "光晕 Halation", grain: "颗粒 Grain", noise: "噪点 Noise",
  vignette: "暗角 Vignette", saturation: "饱和度", contrast: "对比度",
  detail: "细节", film: "胶片", look: "整体影调",
};

const PARAM_LABEL = {
  bias: "曝光偏置 (EV)", temperature_bias: "色温偏移", tint_bias: "色调偏移",
  threshold: "阈值", strength: "强度", amount: "强度", radius: "半径",
  saturation: "饱和度", contrast: "对比度", compression: "压缩",
  black_point: "黑点", luma: "亮度噪点", chroma: "彩度噪点",
  size: "颗粒尺寸", density: "密度", warmth: "暖度", midpoint: "中点",
  feather: "羽化", roundness: "圆度", opacity: "不透明度", enabled: "启用",
};

/* HSL: the schema has 7 identical channel objects (red…purple), so raw labels
 * would repeat "饱和度"/"色相偏移" 7 times. Prefix each row with the channel's
 * Chinese colour name. */
const HUE_CN = {
  red: "红", orange: "橙", yellow: "黄", green: "绿",
  cyan: "青", blue: "蓝", purple: "紫",
};
const HSL_PARAM_CN = {
  saturation: "饱和度", lightness: "明度", hue_shift: "色相偏移",
};

/* ─────────────────────────── helpers ─────────────────────────── */

const api = async (path, opts = {}) => {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...opts,
  });
  const ct = res.headers.get("content-type") || "";
  const data = ct.includes("application/json") ? await res.json() : await res.blob();
  if (!res.ok) throw new Error(data?.error || `HTTP ${res.status}`);
  return data;
};

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

function setStatus(text, cls = "") {
  const el = $("#status");
  el.textContent = text;
  el.className = "status " + cls;
}

function setStep(n) {
  $$(".step").forEach((s) => s.classList.toggle("active", +s.dataset.step === n));
}

/* ─────────────────────────── image intake ─────────────────────────── */

function setImages(list) {
  state.images = list.map((x) => ({ ...x, selected: true }));
  renderThumbs();
  if (!state.current && state.images.length) setCurrent(state.images[0]);
  setStep(state.images.length ? 2 : 1);
}

function renderThumbs() {
  $("#imgCount").textContent = `${state.images.length} 张`;
  const box = $("#thumbs");
  box.innerHTML = "";
  state.images.forEach((img) => {
    const d = document.createElement("div");
    d.className = "thumb" + (state.current?.path === img.path ? " sel" : "");
    d.innerHTML =
      `<img src="/api/thumb?path=${encodeURIComponent(img.path)}" alt="">` +
      `<input type="checkbox" ${img.selected ? "checked" : ""}>`;
    d.querySelector("img").onclick = () => setCurrent(img);
    d.querySelector("input").onclick = (e) => {
      e.stopPropagation();
      img.selected = e.target.checked;
    };
    box.appendChild(d);
  });
  renderFilmstrip();
}

function renderFilmstrip() {
  const fs = $("#filmstrip");
  fs.innerHTML = "";
  state.images.forEach((img) => {
    const d = document.createElement("div");
    d.className = "thumb" + (state.current?.path === img.path ? " sel" : "");
    d.innerHTML = `<img src="/api/thumb?path=${encodeURIComponent(img.path)}" alt="">`;
    d.onclick = () => setCurrent(img);
    fs.appendChild(d);
  });
}

async function setCurrent(img) {
  state.current = img;
  $("#viewName").textContent = img.name;
  renderThumbs();
  $("#empty").hidden = true;
  setStatus("载入原图…", "busy");
  try {
    const blob = await api(`/api/img?path=${encodeURIComponent(img.path)}&max=1600`);
    if (state.origUrl) URL.revokeObjectURL(state.origUrl);
    state.origUrl = URL.createObjectURL(blob);
    $("#imgOrig").src = state.origUrl;
  } catch (e) {
    setStatus("原图载入失败: " + e.message, "err");
    return;
  }
  setStep(3);
  await refreshPreview();
}

async function loadDir(path) {
  setStatus("扫描目录…", "busy");
  try {
    const r = await api(`/api/browse?path=${encodeURIComponent(path)}`);
    if (!r.count) return setStatus("该目录下没有找到图片", "err");
    setImages(r.images);
    setStatus(`已载入 ${r.count} 张`, "ok");
    $("#dirInput").value = path;
  } catch (e) {
    setStatus(e.message, "err");
  }
}

async function compressFile(file, maxSide = 1600) {
  const bmp = await createImageBitmap(file);
  const s = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
  const c = document.createElement("canvas");
  c.width = Math.max(1, Math.round(bmp.width * s));
  c.height = Math.max(1, Math.round(bmp.height * s));
  c.getContext("2d").drawImage(bmp, 0, 0, c.width, c.height);
  bmp.close?.();
  return c.toDataURL("image/jpeg", 0.9);
}

async function uploadFiles(files) {
  if (!files.length) return;
  setStatus(`压缩并上传 ${files.length} 张…`, "busy");
  const payload = [];
  for (const f of files) {
    try {
      payload.push({ name: f.name, data: await compressFile(f) });
    } catch (e) {
      console.warn("skip", f.name, e);
    }
  }
  try {
    const r = await api("/api/upload", {
      method: "POST",
      body: JSON.stringify({ files: payload }),
    });
    setImages(r.images);
    setStatus(`已上传 ${r.count} 张`, "ok");
  } catch (e) {
    setStatus("上传失败: " + e.message, "err");
  }
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
  $("#imgOrig").style.display = mode === "rend" ? "none" : "block";
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
    dragging = true;
    move(e);
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

/* ─────────────────────────── preview render ─────────────────────────── */

let previewTimer = null;
function schedulePreview() {
  clearTimeout(previewTimer);
  previewTimer = setTimeout(refreshPreview, 220);
}

async function refreshPreview() {
  if (!state.current || !state.profile) return;
  const seq = ++state.reqSeq;
  $("#spinner").hidden = false;
  setStatus("渲染中…", "busy");
  try {
    const blob = await api("/api/preview", {
      method: "POST",
      body: JSON.stringify({ path: state.current.path, profile: state.profile, max: 1600 }),
    });
    if (seq !== state.reqSeq) return;      // a newer request won
    if (state.rendUrl) URL.revokeObjectURL(state.rendUrl);
    state.rendUrl = URL.createObjectURL(blob);
    $("#imgRend").src = state.rendUrl;
    $("#empty").hidden = true;
    setStatus("渲染完成", "ok");
  } catch (e) {
    if (seq === state.reqSeq) setStatus("渲染失败: " + e.message, "err");
  } finally {
    if (seq === state.reqSeq) $("#spinner").hidden = true;
  }
}

/* ─────────────────────────── controls panel ─────────────────────────── */

function renderControls() {
  const box = $("#ctrlBox");
  if (!state.controls.length || !state.profile) {
    box.innerHTML = '<div class="hint">尚未载入风格。</div>';
    return;
  }
  box.innerHTML = "";
  const groups = {};
  state.controls.forEach((c) => {
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
      if (cur === undefined) return;                 // not present in this profile
      const row = document.createElement("div");
      row.className = "slider";
      const segs = c.path.split(".");
      let label = PARAM_LABEL[segs[segs.length - 1]] || c.label;
      if (segs[0] === "hsl" && HUE_CN[segs[1]]) {
        label = HUE_CN[segs[1]] + "·" + (HSL_PARAM_CN[segs[2]] || label);
      }

      if (c.type === "boolean") {
        row.innerHTML =
          `<div class="lab"><span class="name">${label}</span>` +
          `<span class="val">${cur ? "开" : "关"}</span></div>`;
        const btn = row.querySelector(".val");
        btn.onclick = () => {
          const v = !getPath(state.profile, c.path);
          setPath(state.profile, c.path, v);
          btn.textContent = v ? "开" : "关";
          schedulePreview();
        };
      } else {
        const step = c.type === "integer" ? 1 : (c.step || 0.01);
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
          row.classList.toggle("changed",
            Math.abs(v - (getPath(state.baseline, c.path) ?? v)) > 1e-9);
          schedulePreview();
        };
        valEl.onclick = () => {
          const s = prompt(`${label} (${c.min} ~ ${c.max})`, getPath(state.profile, c.path));
          if (s === null) return;
          const v = Math.max(c.min, Math.min(c.max, parseFloat(s)));
          if (Number.isFinite(v)) {
            setPath(state.profile, c.path, v);
            range.value = v;
            valEl.textContent = v.toFixed(3);
            row.classList.toggle("changed",
              Math.abs(v - (getPath(state.baseline, c.path) ?? v)) > 1e-9);
            schedulePreview();
          }
        };
      }
      body.appendChild(row);
    });

    if (!body.children.length) return;
    wrap.querySelector(".group-head").onclick = () => wrap.classList.toggle("collapsed");
    box.appendChild(wrap);
  });
}

/* ─────────────────────────── report ─────────────────────────── */

function renderReport() {
  const box = $("#reportBox");
  const v = state.report;
  if (!v) {
    box.innerHTML = '<div class="hint">生成完成后显示留出集损失与验证状态。</div>';
    return;
  }
  const status = v.validation_status || (state.profile?.validation_status) || "unknown";
  const badgeCls = status === "validated" ? "ok" : status === "pending" ? "pend" : "err";
  const loss = v.overall_test_loss;
  const ds = v.dataset || {};
  let html =
    `<div class="rep-row"><span class="k">验证状态</span>` +
    `<span class="badge ${badgeCls}">${status}</span></div>` +
    `<div class="rep-row"><span class="k">载入照片</span><span class="v">` +
    `${ds.loaded ?? "-"}${ds.discovered != null ? " / 发现 " + ds.discovered : ""}</span></div>` +
    `<div class="rep-row"><span class="k">训练照片（参与优化）</span>` +
    `<span class="v">${ds.optimize_images ?? "-"}</span></div>` +
    `<div class="rep-row"><span class="k">对照集（留出，不参与拟合）</span>` +
    `<span class="v">${v.n_test ?? ds.test ?? "-"}</span></div>` +
    `<div class="rep-row"><span class="k">总体损失</span><span class="v">` +
    `${typeof loss === "number" ? loss.toFixed(5) : "-"}</span></div>` +
    `<div class="rep-row"><span class="k">阈值</span><span class="v">${v.loss_threshold ?? 1.0}</span></div>`;

  const comps = v.components || {};
  const entries = Object.entries(comps);
  if (entries.length) {
    const max = Math.max(...entries.map(([, x]) => Math.abs(x) || 0), 1e-6);
    html += '<div class="comp">';
    entries.forEach(([k, x]) => {
      html +=
        `<div class="comp-row"><span>${k}</span><span>${(+x).toFixed(4)}</span></div>` +
        `<div class="bar"><i style="width:${Math.min(100, (Math.abs(x) / max) * 100)}%"></i></div>`;
    });
    html += "</div>";
  }
  box.innerHTML = html;
}

/* ─────────────────────────── generate job ─────────────────────────── */

async function generate() {
  const picked = state.images.filter((i) => i.selected).map((i) => i.path);
  if (picked.length < 3) {
    setStatus("至少需要 3 张参考照片（建议 10 张以上）", "err");
    return;
  }
  const name = $("#profileName").value.trim() || "My Look";
  $("#btnGenerate").disabled = true;
  const logBox = $("#jobLog");
  logBox.hidden = false;
  setStatus("生成中…", "busy");
  setStep(2);
  try {
    const r = await api("/api/generate", {
      method: "POST",
      body: JSON.stringify({ images: picked, name, outdir: `profiles/studio_${Date.now()}` }),
    });
    state.jobId = r.job;
    try { localStorage.setItem(JOB_KEY, JSON.stringify({ id: r.job, t: Date.now() })); } catch {}
    pollJob(r.job);
  } catch (e) {
    setStatus(e.message, "err");
    $("#btnGenerate").disabled = false;
  }
}

async function pollJob(id) {
  const logBox = $("#jobLog");
  const MAX_FAILS = 6;                 // tolerate transient network hiccups
  let fails = 0;
  while (true) {
    await new Promise((r) => setTimeout(r, 700));
    let j;
    try {
      j = await api(`/api/job?id=${id}`);
      fails = 0;
    } catch (e) {
      fails++;
      if (fails >= MAX_FAILS) {
        setStatus(`任务查询失败（已重试 ${MAX_FAILS} 次）: ${e.message}` +
          "。任务可能仍在后台运行，结果会写入 profiles/ 目录；刷新页面可自动恢复轮询。", "err");
        break;
      }
      setStatus(`轮询中断，重试中 (${fails}/${MAX_FAILS})…`, "busy");
      continue;
    }
    logBox.textContent = j.log || "";
    logBox.scrollTop = logBox.scrollHeight;
    if (j.status === "done") {
      const res = j.result || {};
      state.profile = res.profile;
      state.baseline = JSON.parse(JSON.stringify(res.profile));
      state.report = res.validation;
      renderControls();
      renderReport();
      setStatus("生成完成", "ok");
      setStep(3);
      localStorage.removeItem(JOB_KEY);
      await refreshPreview();
      break;
    }
    if (j.status === "error") {
      setStatus("生成失败，见日志", "err");
      logBox.textContent += "\n" + (j.error || "");
      localStorage.removeItem(JOB_KEY);
      break;
    }
  }
  $("#btnGenerate").disabled = false;
}

/* The generate job lives server-side (in-memory). Persist the id so a page
 * reload — or a dropped poll loop — can reconnect to the still-running job
 * instead of losing track of it. */
const JOB_KEY = "pc_studio_job";

async function tryResumeJob() {
  let rec = null;
  try { rec = JSON.parse(localStorage.getItem(JOB_KEY) || "null"); } catch { return; }
  if (!rec || !rec.id) return;
  let j;
  try {
    j = await api(`/api/job?id=${rec.id}`);
  } catch {
    return;                              // server unreachable right now; keep the record for a later reload
  }
  if (j.status === "running") {
    state.jobId = rec.id;
    $("#jobLog").hidden = false;
    setStatus("检测到进行中的生成任务，已恢复轮询…", "busy");
    setStep(2);
    pollJob(rec.id);
  } else if (j.status === "done" && j.result?.profile) {
    state.profile = j.result.profile;
    state.baseline = JSON.parse(JSON.stringify(j.result.profile));
    state.report = j.result.validation;
    renderControls();
    renderReport();
    setStep(3);
    refreshPreview();
    setStatus("已恢复上次生成的风格", "ok");
    localStorage.removeItem(JOB_KEY);
  } else {
    localStorage.removeItem(JOB_KEY);
  }
}

/* ─────────────────────────── export ─────────────────────────── */

// Style name as entered by the user. Keep CJK characters (the App shows the
// filename as the preset name); only strip Windows-illegal filename chars.
function safeName() {
  return ($("#profileName").value.trim() || "studio").replace(/[\\/:*?"<>|]+/g, "_");
}

function localStamp() {
  const d = new Date(), p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

// Icon file picked by the user; kept in memory as a dataURL and written next
// to the profile JSON under the SAME name (only the extension differs).
function pickIcon(file) {
  if (!file) return;
  const ext = (file.name.split(".").pop() || "png").toLowerCase();
  if (!["png", "jpg", "jpeg", "webp"].includes(ext)) return setStatus("图标仅支持 png/jpg/webp", "err");
  const rd = new FileReader();
  rd.onload = () => {
    state.icon = { ext: ext === "jpeg" ? "jpg" : ext, dataUrl: String(rd.result) };
    const box = $("#iconPreview");
    box.hidden = false;
    box.querySelector("img").src = state.icon.dataUrl;
    $("#iconMeta").textContent = file.name;
  };
  rd.readAsDataURL(file);
}

// display payload for the App preset list (intro text + optional icon flag)
function collectDisplay() {
  const intro = $("#profileIntro").value.trim();
  const d = {};
  if (intro) d.intro = intro;
  if (state.icon) d.icon = state.icon.ext; // App looks for <name>.<ext> next to the json
  return Object.keys(d).length ? d : undefined;
}

async function exportLut() {
  if (!state.profile) return setStatus("没有可导出的风格", "err");
  const name = safeName();
  setStatus("生成 3D LUT (.cube)…", "busy");
  try {
    const r = await api("/api/export_lut", {
      method: "POST",
      body: JSON.stringify({ profile: state.profile, name }),
    });
    setStatus("已保存 LUT " + r.path, "ok");
  } catch (e) {
    setStatus("LUT 保存失败: " + e.message, "err");
  }
}

async function exportAndroid() {
  if (!state.profile) return setStatus("没有可导出的风格", "err");
  const name = safeName();
  const payload = { profile: { ...state.profile }, name };
  const d = collectDisplay();
  if (d) payload.profile.display = d;
  if (state.icon) payload.icon = state.icon;
  setStatus("写入 Android 资产…", "busy");
  try {
    const r = await api("/api/export_android", {
      method: "POST",
      body: JSON.stringify(payload),
    });
    setStatus("已写入 " + r.path + (r.icon_path ? " + 图标" : ""), "ok");
  } catch (e) {
    setStatus("导出失败: " + e.message, "err");
  }
}

async function batchRender() {
  const picked = state.images.filter((i) => i.selected).map((i) => i.path);
  if (!picked.length || !state.profile) return setStatus("没有选片或风格", "err");
  setStatus(`批量渲染 ${picked.length} 张…`, "busy");
  try {
    const r = await api("/api/batch", {
      method: "POST",
      body: JSON.stringify({ profile: state.profile, images: picked }),
    });
    setStatus(`已输出 ${r.count} 张到 ${r.outdir}`, "ok");
  } catch (e) {
    setStatus("批量渲染失败: " + e.message, "err");
  }
}

/* ─────────────────────────── wire up ─────────────────────────── */

async function init() {
  initStage();
  applyMode();

  try {
    const r = await api("/api/controls");
    state.controls = r.controls;
  } catch (e) {
    console.warn("controls unavailable", e);
  }

  $("#btnLoadDir").onclick = () => {
    const p = $("#dirInput").value.trim();
    if (p) loadDir(p);
  };
  $("#dirInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") $("#btnLoadDir").click();
  });

  $("#btnPick").onclick = () => $("#fileInput").click();
  $("#fileInput").onchange = (e) => uploadFiles(Array.from(e.target.files));

  $("#btnPickIcon").onclick = () => $("#iconInput").click();
  $("#iconInput").onchange = (e) => {
    pickIcon(e.target.files && e.target.files[0]);
    e.target.value = "";
  };

  const dz = $("#dropzone");
  ["dragenter", "dragover"].forEach((t) =>
    dz.addEventListener(t, (e) => {
      e.preventDefault();
      dz.classList.add("over");
    })
  );
  ["dragleave", "drop"].forEach((t) =>
    dz.addEventListener(t, (e) => {
      e.preventDefault();
      dz.classList.remove("over");
    })
  );
  dz.addEventListener("drop", (e) => {
    const files = Array.from(e.dataTransfer.files).filter((f) => f.type.startsWith("image/"));
    uploadFiles(files);
  });

  $("#selectAll").onchange = (e) => {
    state.images.forEach((i) => (i.selected = e.target.checked));
    renderThumbs();
  };

  $("#btnGenerate").onclick = generate;
  $("#btnSave").onclick = exportLut;
  $("#btnExportAndroid").onclick = exportAndroid;
  $("#btnBatch").onclick = batchRender;
  $("#btnReset").onclick = () => {
    if (!state.baseline) return;
    state.profile = JSON.parse(JSON.stringify(state.baseline));
    renderControls();
    refreshPreview();
    setStatus("已重置为生成值", "ok");
  };

  setStep(1);
  setStatus("就绪");
  tryResumeJob();
}

init();
