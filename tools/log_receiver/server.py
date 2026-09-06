#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PhotographerCamera 远程调试日志接收服务（轻量、零依赖，仅用 Python 标准库）。

手机 App（android core/debug/RemoteLog.kt）会把调试日志以批量 JSON POST 到 /log，
本服务：
  1. 内存环形缓冲：按 session 限流（默认每 session 5000 行），总容量上限（10 万行），
     超出丢弃最旧，绝不让内存膨胀把服务器拖垮。
  2. 落盘滚动文件：每条日志追加到 logs/ 下按天滚动、单文件 20MB 切分的文本文件，
     方便在服务器上直接 `cat` / `tail -f` 做真机调试。
  3. Web UI：GET / 打开页面，左侧设备/session 列表，右侧 SSE 实时日志流，可清空。

启动：
    python3 server.py [--host 0.0.0.0] [--port 8080] [--logdir logs]
"""

import argparse
import json
import os
import queue
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

# ----------------------------- 调参（容量上限） -----------------------------
# 真机调试要"尽可能多的日志"，故内存保留给得宽裕；仍全部有界，绝不会撑爆。
MAX_SESSIONS = 100           # 最多保留多少个 session（超出丢弃最旧的）
MAX_LINES_PER_SESSION = 20000 # 单个 session 内存保留行数（≈4MB/会话）
MAX_TOTAL_LINES = 500_000   # 全局内存行数硬上限（≈100MB，超限按最旧 session 裁剪）
DISK_ROTATE_BYTES = 50 * 1024 * 1024  # 落盘单文件 50MB 滚动（真机日志量可能很大）
HTTP_BODY_LIMIT = 4 * 1024 * 1024     # 单次请求 body 上限 4MB（防畸形大包拖垮）

STORE = None  # LogHub 单例，在 main 中初始化


def utcnow_ms():
    return time.time_ns() // 1_000_000


class Session:
    __slots__ = ("id", "device", "lines", "last_ts", "seq_max", "dropped", "created")

    def __init__(self, sid, device):
        self.id = sid
        self.device = device
        self.lines = deque()          # (wallclock_ms, seq, text)
        self.last_ts = 0
        self.seq_max = -1
        self.dropped = 0              # 服务端因内存上限丢弃的行数
        self.created = utcnow_ms()


class LogHub:
    """线程安全的日志中心：内存 + 落盘 + SSE 订阅。"""

    def __init__(self, logdir: str):
        self.logdir = Path(logdir)
        self.logdir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._sessions: dict[str, Session] = {}
        self._order = deque()         # session id 访问顺序（LRU 丢弃）
        self._total = 0
        self._subs: dict[str, list[queue.Queue]] = {}
        self._disk_lock = threading.Lock()
        self._disk_path = None
        self._disk_size = 0

    # ---------------- 内存写入 ----------------
    def push(self, sid: str, device: str, seq: int, ts: int, lines: list):
        entries = [(ts if ts else utcnow_ms(), seq, t) for t in lines]
        with self._lock:
            s = self._sessions.get(sid)
            if s is None:
                s = Session(sid, device)
                self._sessions[sid] = s
                self._order.append(sid)
                if len(self._order) > MAX_SESSIONS:
                    old = self._order.popleft()
                    self._sessions.pop(old, None)
            # 访问顺序更新
            if self._order[-1] != sid:
                try:
                    self._order.remove(sid)
                except ValueError:
                    pass
                self._order.append(sid)
            s.last_ts = entries[-1][0] if entries else s.last_ts
            if seq > s.seq_max:
                s.seq_max = seq
            for e in entries:
                s.lines.append(e)
                self._total += 1
            # session 级裁剪
            while len(s.lines) > MAX_LINES_PER_SESSION:
                s.lines.popleft()
                self._total -= 1
                s.dropped += 1
            # 全局裁剪（最旧 session 优先）
            while self._total > MAX_TOTAL_LINES and self._order:
                victim = self._order[0]
                vs = self._sessions.get(victim)
                if vs is None:
                    self._order.popleft()
                    continue
                if vs is s and len(s.lines) <= 1:
                    break
                vs.lines.popleft()
                self._total -= 1
                vs.dropped += 1
                if not vs.lines:
                    self._order.popleft()
                    self._sessions.pop(victim, None)
        # 落盘（锁外，避免阻塞内存路径）
        self._write_disk(sid, device, entries)
        # 推送 SSE
        self._broadcast(sid, entries)

    def _write_disk(self, sid, device, entries):
        try:
            with self._disk_lock:
                today = datetime.now().strftime("%Y-%m-%d")
                path = self.logdir / f"debug_{today}.log"
                if path != self._disk_path:
                    self._disk_path = path
                    self._disk_size = path.stat().st_size if path.exists() else 0
                lines = []
                for ts, seq, text in entries:
                    wall = datetime.fromtimestamp(ts / 1000, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
                    lines.append(f"{wall}Z session={sid} device={device} seq={seq} {text}\n")
                data = "".join(lines).encode("utf-8", "replace")
                if self._disk_size + len(data) > DISK_ROTATE_BYTES:
                    # 滚动：重命名旧文件，开新文件
                    self._disk_path = self.logdir / f"debug_{today}_{int(time.time())}.log"
                    self._disk_size = 0
                with open(self._disk_path, "ab") as f:
                    f.write(data)
                    self._disk_size += len(data)
        except Exception:
            pass  # 落盘失败绝不影响接收

    def _broadcast(self, sid, entries):
        qs = self._subs.get(sid)
        if not qs:
            return
        # 每条日志一个完整 SSE 帧（与历史尾格式一致，客户端无需区分），
        # 避免把整个 batch 当成一个 JSON 数组发给浏览器导致解析错位。
        frames = "".join(
            "data: " + json.dumps({"ts": e[0], "seq": e[1], "text": e[2]}, ensure_ascii=False) + "\n\n"
            for e in entries
        ).encode("utf-8")
        dead = []
        for q in qs:
            try:
                q.put_nowait(frames)
            except Exception:
                dead.append(q)
        for q in dead:
            try:
                qs.remove(q)
            except ValueError:
                pass

    # ---------------- 读取 ----------------
    def snapshot(self):
        with self._lock:
            return [
                {
                    "id": sid,
                    "device": self._sessions[sid].device,
                    "lines": len(self._sessions[sid].lines),
                    "dropped": self._sessions[sid].dropped,
                    "last_ts": self._sessions[sid].last_ts,
                    "seq_max": self._sessions[sid].seq_max,
                }
                for sid in reversed(self._order)
            ]

    def tail(self, sid: str, n: int = 500):
        with self._lock:
            s = self._sessions.get(sid)
            if not s:
                return []
            return [{"ts": e[0], "seq": e[1], "text": e[2]} for e in list(s.lines)[-n:]]

    def clear(self, sid: str = None):
        with self._lock:
            if sid:
                self._sessions.pop(sid, None)
                try:
                    self._order.remove(sid)
                except ValueError:
                    pass
            else:
                self._sessions.clear()
                self._order.clear()
            self._total = 0

    # ---------------- SSE ----------------
    def subscribe(self, sid: str) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=2000)
        with self._lock:
            self._subs.setdefault(sid, []).append(q)
        return q


# ----------------------------- HTTP 处理 -----------------------------
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # 支持 keep-alive，SSE 需要

    def log_message(self, fmt, *args):  # 静默默认访问日志（避免刷屏）
        pass

    def _send(self, code, body: bytes, ctype="application/json; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            return self._serve_ui()
        if path == "/api/sessions":
            return self._send(200, json.dumps(STORE.snapshot(), ensure_ascii=False).encode("utf-8"))
        if path == "/api/logs":
            sid = self._arg("session", "")
            n = int(self._arg("tail", "500") or 500)
            return self._send(200, json.dumps(STORE.tail(sid, n), ensure_ascii=False).encode("utf-8"))
        if path == "/api/stream":
            return self._serve_sse()
        if path == "/api/clear":
            sid = self._arg("session", "")
            STORE.clear(sid or None)
            return self._send(200, b'{"ok":true}')
        return self._send(404, b'{"error":"not found"}')

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/log":
            return self._send(404, b'{"error":"not found"}')
        try:
            length = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            return self._send(400, b'{"error":"bad length"}')
        if length <= 0 or length > HTTP_BODY_LIMIT:
            return self._send(400, b'{"error":"body too large"}')
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8", "replace"))
        except Exception:
            return self._send(400, b'{"error":"bad json"}')
        sid = str(data.get("session") or data.get("device") or "default")
        device = str(data.get("device") or "unknown")
        seq = int(data.get("seq") or 0)
        ts = int(data.get("ts") or 0)
        lines = data.get("lines")
        if not isinstance(lines, list):
            lines = [str(lines)] if lines is not None else []
        # 过滤非字符串 / 超长行
        clean = []
        for ln in lines:
            if not isinstance(ln, str):
                ln = str(ln)
            if len(ln) > 1000:
                ln = ln[:1000] + "…"
            clean.append(ln)
        if clean:
            STORE.push(sid, device, seq, ts, clean)
        return self._send(200, b'{"ok":true}')

    def _arg(self, name, default):
        from urllib.parse import parse_qs, urlparse
        qs = parse_qs(urlparse(self.path).query)
        v = qs.get(name)
        return v[0] if v else default

    def _serve_ui(self):
        html = PAGE.encode("utf-8")
        self._send(200, html, "text/html; charset=utf-8")

    def _serve_sse(self):
        sid = self._arg("session", "")
        if not sid or sid not in STORE._sessions:  # noqa: SLF001
            # 仍订阅，等待该 session 出现
            pass
        q = STORE.subscribe(sid)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        try:
            # 先发历史尾
            for e in STORE.tail(sid, 200):
                self.wfile.write(f"data: {json.dumps(e, ensure_ascii=False)}\n\n".encode("utf-8"))
            self.wfile.flush()
            while True:
                try:
                    payload = q.get(timeout=15)
                except queue.Empty:
                    # 心跳保活
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                # payload 已是完整 SSE 帧（data: ...\n\n）
                self.wfile.write(payload)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            try:
                STORE._subs.get(sid, []).remove(q)  # noqa: SLF001
            except (ValueError, KeyError):
                pass


PAGE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>PhotographerCamera 调试日志</title>
<style>
  :root { --bg:#0e0f13; --panel:#16181f; --line:#22252e; --fg:#d7dae0; --muted:#7c828c; --accent:#4ea1ff; --err:#ff6b6b; }
  * { box-sizing:border-box; }
  html,body { margin:0; height:100%; background:var(--bg); color:var(--fg);
    font-family:ui-monospace,Menlo,Consolas,"Cascadia Code",monospace; font-size:13px; }
  #app { display:flex; height:100vh; }
  #side { width:280px; min-width:280px; background:var(--panel); border-right:1px solid var(--line);
    display:flex; flex-direction:column; }
  #side h1 { font-size:13px; margin:0; padding:12px 14px; border-bottom:1px solid var(--line); color:var(--accent); }
  #sessions { flex:1; overflow:auto; }
  .sess { padding:9px 12px; border-bottom:1px solid var(--line); cursor:pointer; }
  .sess:hover { background:#1d2029; }
  .sess.active { background:#22304a; border-left:3px solid var(--accent); }
  .sess .dev { color:var(--fg); font-weight:600; }
  .sess .meta { color:var(--muted); font-size:11px; margin-top:3px; }
  .sess .drop { color:var(--err); }
  #side .foot { padding:10px 12px; border-top:1px solid var(--line); display:flex; gap:8px; }
  button { background:#22262f; color:var(--fg); border:1px solid var(--line); border-radius:6px;
    padding:6px 10px; cursor:pointer; font-size:12px; }
  button:hover { background:#2c313c; }
  #main { flex:1; display:flex; flex-direction:column; min-width:0; }
  #bar { padding:10px 14px; border-bottom:1px solid var(--line); display:flex; align-items:center; gap:12px;
    background:var(--panel); }
  #bar .title { color:var(--accent); font-weight:600; }
  #bar .stat { color:var(--muted); font-size:12px; }
  #log { flex:1; overflow:auto; padding:10px 14px; white-space:pre-wrap; word-break:break-all; line-height:1.5; }
  .l { color:var(--fg); }
  .l.e { color:var(--err); }
  .l.crash { background:#3a1414; }
  #empty { color:var(--muted); padding:30px; text-align:center; }
</style>
</head>
<body>
<div id="app">
  <div id="side">
    <h1>📡 调试日志接收</h1>
    <div id="sessions"></div>
    <div class="foot">
      <button id="clearOne">清空当前</button>
      <button id="clearAll">清空全部</button>
      <button id="refresh">刷新</button>
    </div>
  </div>
  <div id="main">
    <div id="bar">
      <span class="title" id="curTitle">未选择</span>
      <span class="stat" id="curStat"></span>
      <span style="flex:1"></span>
      <button id="auto">自动滚动: 开</button>
    </div>
    <div id="log"><div id="empty">从左侧选择一个设备/session</div></div>
  </div>
</div>
<script>
let cur = null, auto = true, es = null;
const $ = s => document.querySelector(s);
function fmtTs(ms){ const d=new Date(ms); return d.toTimeString().slice(0,8)+'.'+String(d.getMilliseconds()).padStart(3,'0'); }
function loadSessions(){
  fetch('/api/sessions').then(r=>r.json()).then(list=>{
    const box=$('#sessions'); const prev=cur;
    box.innerHTML='';
    list.forEach(s=>{
      const d=document.createElement('div');
      d.className='sess'+(s.id===cur?' active':'');
      d.innerHTML=`<div class="dev">${esc(s.device)}</div>
        <div class="meta">${s.id.slice(0,16)} · ${s.lines} 行`+
        (s.dropped>0?` <span class="drop">丢弃${s.dropped}</span>`:``)+`</div>`;
      d.onclick=()=>select(s.id);
      box.appendChild(d);
    });
    if(prev && !list.find(x=>x.id===prev)) {/* 仍在，保持 */}
    if(!cur && list.length) select(list[0].id);
    if(cur){ const m=list.find(x=>x.id===cur); if(m) $('#curStat').textContent=`${m.lines} 行 · seq≤${m.seq_max}`+(m.dropped>0?` · 丢弃${m.dropped}`:''); }
  });
}
function esc(s){ return String(s).replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }
function select(id){
  cur=id;
  document.querySelectorAll('.sess').forEach(e=>e.classList.remove('active'));
  loadSessions();
  $('#curTitle').textContent=id;
  $('#log').innerHTML='';
  if(es) es.close();
  es=new EventSource('/api/stream?session='+encodeURIComponent(id));
  es.onmessage=e=>{
    try{ const o=JSON.parse(e.data); appendLine(o); }
    catch(_){ appendRaw(e.data); }
  };
}
function appendLine(o){
  const div=document.createElement('div');
  const t=o.text||'';
  div.className='l'+(/\\bCRASH\\b|\\bERR\\b|Exception|FATAL/.test(t)?' e':'')+(/CRASH/.test(t)?' crash':'');
  div.textContent=`${fmtTs(o.ts)} ${t}`;
  const log=$('#log');
  const atBottom = log.scrollTop+log.clientHeight >= log.scrollHeight-30;
  log.appendChild(div);
  // 限制 DOM 行数
  while(log.childElementCount>2000) log.removeChild(log.firstChild);
  if(auto && atBottom) log.scrollTop=log.scrollHeight;
}
function appendRaw(s){ const d=document.createElement('div'); d.className='l'; d.textContent=s; $('#log').appendChild(d); }
$('#auto').onclick=()=>{ auto=!auto; $('#auto').textContent='自动滚动: '+(auto?'开':'关'); };
$('#refresh').onclick=loadSessions;
$('#clearOne').onclick=()=>{ if(cur) fetch('/api/clear?session='+encodeURIComponent(cur)).then(loadSessions); };
$('#clearAll').onclick=()=>{ if(confirm('清空全部会话？')) fetch('/api/clear').then(()=>{cur=null;loadSessions();}); };
loadSessions();
setInterval(loadSessions, 5000);
</script>
</body>
</html>
"""


def main():
    global STORE
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--logdir", default="logs")
    args = ap.parse_args()
    STORE = LogHub(args.logdir)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[log_receiver] listening on http://{args.host}:{args.port}  (logdir={os.path.abspath(args.logdir)})")
    print("[log_receiver] App 上报地址填: http://<本机IP>:{}/log".format(args.port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[log_receiver] stopped")


if __name__ == "__main__":
    main()
