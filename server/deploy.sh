#!/usr/bin/env bash
# PhotographerCamera 实时调试日志接收服务 —— 服务器端一键部署
#
# 在服务器上以 root 执行：
#   cd /root/photographer_log && bash deploy.sh 18888
#
# 会做四件事：
#   1) 放置 server.py / start.sh 到 /root/photographer_log/
#   2) 写 systemd 单元 photographer-log（崩溃自动重启 + 开机自启）
#   3) 放行本机防火墙端口（firewalld / ufw，都没有则跳过）
#   4) 启动并做本地自检（GET /  GET /api/sessions  POST /log）
set -euo pipefail

PORT="${1:-18888}"
APP_DIR=/root/photographer_log
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "==> 部署目录: $APP_DIR"
echo "==> 监听端口: $PORT"

mkdir -p "$APP_DIR/logs"

# ---------- 1) 服务端文件 ----------
install -m 0644 "$SRC_DIR/server.py" "$APP_DIR/server.py"
if [ -f "$SRC_DIR/start.sh" ]; then
  install -m 0755 "$SRC_DIR/start.sh" "$APP_DIR/start.sh"
fi

# ---------- 2) systemd 单元 ----------
cat > /etc/systemd/system/photographer-log.service <<EOF
[Unit]
Description=PhotographerCamera Remote Debug Log Receiver
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/env python3 $APP_DIR/server.py --host 0.0.0.0 --port $PORT --logdir $APP_DIR/logs
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

# ---------- 3) 防火墙 ----------
if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-port="${PORT}/tcp" >/dev/null
  firewall-cmd --reload >/dev/null
  echo "==> firewalld 已放行 ${PORT}/tcp"
elif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "${PORT}/tcp" >/dev/null
  echo "==> ufw 已放行 ${PORT}/tcp"
else
  echo "==> 未检测到活动防火墙（若外网不通，请在腾讯云控制台安全组放行 ${PORT}/tcp）"
fi

# ---------- 4) 启动 ----------
systemctl daemon-reload
systemctl enable photographer-log >/dev/null 2>&1 || true
systemctl restart photographer-log
sleep 1
echo "==> 服务状态"
systemctl --no-pager --lines=6 status photographer-log || true

# ---------- 5) 自检 ----------
echo "==> 本地自检"
if command -v curl >/dev/null 2>&1; then
  curl -s -m 5 -o /dev/null -w "  GET  /              -> %{http_code}\n" "http://127.0.0.1:${PORT}/" || true
  curl -s -m 5 -o /dev/null -w "  GET  /api/sessions  -> %{http_code}\n" "http://127.0.0.1:${PORT}/api/sessions" || true
  curl -s -m 5 -X POST -H 'Content-Type: application/json' \
    -d '{"session":"deploy_verify","device":"server","seq":1,"lines":["deploy ok"]}' \
    -o /dev/null -w "  POST /log           -> %{http_code}\n" "http://127.0.0.1:${PORT}/log" || true
else
  echo "  (无 curl，跳过自检)"
fi

echo
echo "✅ 部署完成"
echo "   Web 面板:  http://<服务器公网IP>:$PORT/"
echo "   App 上报:  http://<服务器公网IP>:$PORT/log"
echo "   落盘日志:  $APP_DIR/logs/debug_YYYY-MM-DD.log   (tail -f 可看真机日志)"
echo "   进程日志:  journalctl -u photographer-log -f"
echo "   重启服务:  systemctl restart photographer-log"
