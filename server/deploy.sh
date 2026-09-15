#!/usr/bin/env bash
# PhotographerCamera 实时调试日志接收服务 —— 服务器端一键部署
#
# 在服务器上执行（root 或具备 sudo 的普通用户均可）：
#   cd ~/photographer_log && bash deploy.sh 18888
#
# 会做五件事：
#   1) 放置 server.py 到部署目录（默认 $HOME/photographer_log）
#   2) 写 systemd 单元 photographer-log（崩溃自动重启 + 开机自启）
#   3) 放行本机防火墙端口（ufw / firewalld，都没有则跳过）
#   4) 启动并做本地自检（GET /  GET /api/sessions  POST /log）
#   5) 打印 Web 面板地址与排查命令
#
# 可用环境变量覆盖：
#   APP_DIR=...       部署目录（默认 $HOME/photographer_log）
#   SERVICE_USER=...  服务运行用户（默认当前用户；root 部署时默认 root）
set -euo pipefail

PORT="${1:-18888}"
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- 0) 权限与目录 ----------
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
  DEFAULT_USER="root"
  DEFAULT_DIR="/root/photographer_log"
else
  command -v sudo >/dev/null 2>&1 || {
    echo "✗ 非 root 且没有 sudo，无法注册 systemd 服务" >&2
    exit 1
  }
  # sudo 免密检测，避免脚本执行到一半卡在密码输入
  if ! sudo -n true 2>/dev/null; then
    echo "✗ sudo 需要密码。请先执行 'sudo -v' 缓存凭据，或用 sudo bash deploy.sh 运行" >&2
    exit 1
  fi
  SUDO="sudo"
  DEFAULT_USER="$(id -un)"
  DEFAULT_DIR="$HOME/photographer_log"
fi

APP_DIR="${APP_DIR:-$DEFAULT_DIR}"
SERVICE_USER="${SERVICE_USER:-$DEFAULT_USER}"
SERVICE_NAME="photographer-log"
UNIT_PATH="/etc/systemd/system/${SERVICE_NAME}.service"

echo "==> 部署用户: $SERVICE_USER"
echo "==> 部署目录: $APP_DIR"
echo "==> 监听端口: $PORT"

mkdir -p "$APP_DIR/logs"

# ---------- 1) 服务端文件 ----------
if [ ! -f "$SRC_DIR/server.py" ]; then
  echo "✗ 找不到 $SRC_DIR/server.py（请先把它和 deploy.sh 一起上传到同一目录）" >&2
  exit 1
fi
# 允许"在部署目录里直接执行"（此时源与目标同一路径，install 会自比自报错）
if [ "$(readlink -f "$SRC_DIR")" = "$(readlink -f "$APP_DIR")" ]; then
  chmod 0644 "$APP_DIR/server.py"
  [ -f "$APP_DIR/upload.token.local" ] && chmod 0600 "$APP_DIR/upload.token.local"
  echo "==> server.py 已在部署目录，跳过复制"
else
  install -m 0644 "$SRC_DIR/server.py" "$APP_DIR/server.py"
  echo "==> 已安装 server.py"
  if [ -f "$SRC_DIR/upload.token.local" ]; then
    install -m 0600 "$SRC_DIR/upload.token.local" "$APP_DIR/upload.token.local"
    echo "==> 已安装 upload.token.local"
  fi
fi

# ---------- 2) systemd 单元 ----------
$SUDO tee "$UNIT_PATH" >/dev/null <<EOF
[Unit]
Description=PhotographerCamera Remote Debug Log Receiver
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/env python3 $APP_DIR/server.py --host 0.0.0.0 --port $PORT --logdir $APP_DIR/logs
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
echo "==> 已写入 $UNIT_PATH"

# ---------- 3) 防火墙 ----------
FW_DONE=0
if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | grep -q "Status: active"; then
  $SUDO ufw allow "${PORT}/tcp" >/dev/null
  FW_DONE=1
  echo "==> ufw 已放行 ${PORT}/tcp"
elif command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
  $SUDO firewall-cmd --permanent --add-port="${PORT}/tcp" >/dev/null
  $SUDO firewall-cmd --reload >/dev/null
  FW_DONE=1
  echo "==> firewalld 已放行 ${PORT}/tcp"
fi
if [ "$FW_DONE" -eq 0 ]; then
  echo "==> 未检测到活动防火墙（若外网不通，请在腾讯云控制台安全组放行 ${PORT}/tcp）"
fi

# ---------- 4) 启动 ----------
$SUDO systemctl daemon-reload
$SUDO systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true
$SUDO systemctl restart "$SERVICE_NAME"
sleep 2
echo "==> 服务状态"
$SUDO systemctl --no-pager --lines=8 status "$SERVICE_NAME" || true

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
echo "   进程日志:  $SUDO journalctl -u $SERVICE_NAME -f"
echo "   重启服务:  $SUDO systemctl restart $SERVICE_NAME"
