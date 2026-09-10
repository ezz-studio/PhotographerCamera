#!/usr/bin/env bash
#
# 服务器端安装：建 venv、装依赖、注册 systemd（开机自启）、启动 Studio。幂等可重跑。
# 由 bootstrap.sh 通过 `bash setup_remote.sh <部署目录>` 调用，也可手动执行。
#
set -euo pipefail
DEPLOY_DIR="${1:-/home/admin/photographer-studio}"
PORT=8765
cd "$DEPLOY_DIR"

# 运行期可写目录（保存的 profile / 会话），不随代码更新被覆盖
mkdir -p "$DEPLOY_DIR/profiles/studio" "$DEPLOY_DIR/studio_session"

python3 -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate
pip install --upgrade pip -q
pip install -r requirements.txt

# Studio 仅监听本机回环，由 Caddy 反代对外（不直接暴露公网）
UNIT=/etc/systemd/system/photographer-studio.service
RUN_USER="${SUDO_USER:-$(whoami)}"
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi
$SUDO tee "$UNIT" >/dev/null <<EOF
[Unit]
Description=PhotographerCamera Studio (JSON profile editor)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$DEPLOY_DIR
ExecStart=$DEPLOY_DIR/venv/bin/python $DEPLOY_DIR/desktop/server.py --host 127.0.0.1 --port $PORT --no-browser
Restart=on-failure
RestartSec=3
User=$RUN_USER
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
EOF

$SUDO systemctl daemon-reload
$SUDO systemctl enable photographer-studio          # 开机自启
$SUDO systemctl restart photographer-studio
sleep 2
$SUDO systemctl status photographer-studio --no-pager || true

echo
echo "==> Studio 已开机自启（systemd enable），本地回环 http://127.0.0.1:$PORT"
echo "    配置 Caddy/Nginx 将 json.tybtool.top 反代到 127.0.0.1:$PORT 即可公网访问"
