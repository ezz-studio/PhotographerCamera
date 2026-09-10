#!/usr/bin/env bash
#
# 服务器端一键安装 / 升级 PhotographerCamera Studio（json.tybtool.top）
# 由本机 deploy.sh 上传到 R2 公开地址（studio/install.sh），服务器一句指令拉取执行：
#   url=https://app.tybtool.top/studio/install.sh; curl -sSO $url; bash install.sh
#
# 流程：从公开 version.json 取部署包地址 → sha256 校验 → 解压（保留 venv 与运行数据）
#   → 建/更新 venv 与依赖 → 注册 systemd（开机自启）→ 启动 → 健康检查。
# 幂等：重复运行 = 升级，不会清空已保存 profile 与 studio_session。
#
# 可选环境变量（覆盖默认值）：
#   STUDIO_VERSION_URL   版本清单地址（默认 $STUDIO_BASE/studio/studio_version.json）
#   STUDIO_BASE         公开基址（默认 https://app.tybtool.top）
#   INSTALL_DIR         安装根目录（默认 /home/admin/photographer-studio）
#   PORT                服务端口（默认 8765）
#   STUDIO_CADDY_PASSWORD  设置则自动配置 Caddy 反代 + basicauth 并 reload
#                          （需服务器已装 caddy 且 /etc/caddy/origin.pem + origin.key 已就位）
#   CADDY_HOST          对外域名（默认 json.tybtool.top）
#
set -euo pipefail

STUDIO_BASE="${STUDIO_BASE:-https://app.tybtool.top}"
DEFAULT_VER="$STUDIO_BASE/studio/studio_version.json"
VER_URL="${1:-${STUDIO_VERSION_URL:-$DEFAULT_VER}}"

INSTALL_DIR="${INSTALL_DIR:-/home/admin/photographer-studio}"
PORT="${PORT:-8765}"
CADDY_PASSWORD="${STUDIO_CADDY_PASSWORD:-${CADDY_PASSWORD:-}}"
CF_HOST="${CADDY_HOST:-json.tybtool.top}"

echo "==> PhotographerCamera Studio 一键安装 / 升级"
echo "    版本清单 : $VER_URL"
echo "    安装目录 : $INSTALL_DIR"
echo "    服务端口 : $PORT"

TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# 绕过 Cloudflare 边缘缓存（同文件名 version.json 会被缓存约 186 天，否则会装到旧版）
VER_FETCH="$VER_URL"
case "$VER_FETCH" in *\?*) VER_FETCH="$VER_FETCH&_=$(date +%s%N)";; *) VER_FETCH="$VER_FETCH?_=$(date +%s%N)";; esac

# 1) 拉取版本清单
echo "==> 下载 studio_version.json"
curl -fsSL "$VER_FETCH" -o "$TMP/version.json"
BUNDLE_URL="$(python3 -c "import json;print(json.load(open('$TMP/version.json'))['bundle'])")"
SHA256="$(python3 -c "import json;print(json.load(open('$TMP/version.json')).get('sha256',''))")"
TAG="$(python3 -c "import json;print(json.load(open('$TMP/version.json')).get('tag',''))")"
echo "    版本标签 : $TAG"

# 2) 下载部署包
echo "==> 下载部署包"
curl -fsSL "$BUNDLE_URL" -o "$TMP/bundle.tar.gz"
if [ -n "$SHA256" ]; then
  echo "==> 校验 sha256"
  echo "$SHA256  $TMP/bundle.tar.gz" | sha256sum -c -
fi

# 3) 停服（若存在），保留 venv 与运行数据
echo "==> 停止现有服务（若存在）"
systemctl stop photographer-studio 2>/dev/null || true

EXTRACT="$TMP/app"
mkdir -p "$EXTRACT"
tar xzf "$TMP/bundle.tar.gz" -C "$EXTRACT"

mkdir -p "$INSTALL_DIR"
if command -v rsync >/dev/null 2>&1; then
  # 合并更新：同步代码文件，但保留已存在的 venv / profiles/studio / studio_session / .env
  rsync -a --delete \
    --exclude='venv' --exclude='profiles/studio' --exclude='studio_session' --exclude='.env' \
    "$EXTRACT/" "$INSTALL_DIR/"
else
  # 退化方案：先把 venv/数据移走，全量解压后再放回
  [ -d "$INSTALL_DIR/venv" ] && mv "$INSTALL_DIR/venv" "$TMP/venv.bak"
  [ -d "$INSTALL_DIR/profiles/studio" ] && cp -a "$INSTALL_DIR/profiles/studio" "$TMP/studio_bak"
  tar xzf "$TMP/bundle.tar.gz" -C "$INSTALL_DIR"
  [ -d "$TMP/venv.bak" ] && mv "$TMP/venv.bak" "$INSTALL_DIR/venv"
  [ -d "$TMP/studio_bak" ] && mkdir -p "$INSTALL_DIR/profiles" && cp -a "$TMP/studio_bak" "$INSTALL_DIR/profiles/studio"
fi

# 4) 运行期可写目录（保存的 profile / 会话，不随代码更新被覆盖）
mkdir -p "$INSTALL_DIR/profiles/studio" "$INSTALL_DIR/studio_session"

# 5) venv + 依赖
if [ ! -d "$INSTALL_DIR/venv" ]; then
  echo "==> 创建 venv"
  python3 -m venv "$INSTALL_DIR/venv"
fi
# shellcheck disable=SC1091
source "$INSTALL_DIR/venv/bin/activate"
echo "==> 安装依赖（已装则快速跳过）"
pip install --upgrade pip -q
pip install -r "$INSTALL_DIR/requirements.txt"

# 6) systemd（开机自启）
RUN_USER="$(whoami)"
SUDO=""; [ "$(id -u)" -eq 0 ] && { SUDO="sudo"; RUN_USER="${SUDO_USER:-$RUN_USER}"; }
UNIT=/etc/systemd/system/photographer-studio.service
$SUDO tee "$UNIT" >/dev/null <<EOF
[Unit]
Description=PhotographerCamera Studio (JSON profile editor)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/venv/bin/python $INSTALL_DIR/desktop/server.py --host 127.0.0.1 --port $PORT --no-browser
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

# 7) 健康检查
echo "==> 等待服务就绪 (http://127.0.0.1:$PORT/api/health)"
OK=0
for i in $(seq 1 20); do
  if curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then OK=1; break; fi
  sleep 1
done
[ "$OK" -eq 1 ] && echo "    健康检查通过" || echo "    !! 健康检查未通过，请查: journalctl -u photographer-studio -f"

# 8) 可选 Caddy 反代 + basicauth
if [ -n "$CADDY_PASSWORD" ]; then
  if command -v caddy >/dev/null 2>&1; then
    HASH="$(caddy hash-password "$CADDY_PASSWORD")"
    $SUDO mkdir -p /etc/caddy/Caddyfile.d
    $SUDO tee /etc/caddy/Caddyfile.d/studio.conf >/dev/null <<EOF
$CF_HOST {
    tls /etc/caddy/origin.pem /etc/caddy/origin.key
    basicauth {
        studio $HASH
    }
    encode gzip
    reverse_proxy 127.0.0.1:$PORT
}
EOF
    $SUDO systemctl reload caddy
    echo "==> Caddy 已配置 $CF_HOST 并 reload（basicauth 已启用）"
  else
    echo "!! 未检测到 caddy，跳过反代；请手动将 $CF_HOST 反代到 127.0.0.1:$PORT"
  fi
fi

echo
echo "==> 安装完成：$TAG 已运行，开机自启已启用。"
echo "    本机访问 : http://127.0.0.1:$PORT"
[ -n "$CADDY_PASSWORD" ] && echo "    公网访问 : https://$CF_HOST/"
