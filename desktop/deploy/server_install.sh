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
#   STUDIO_CADDY_PASSWORD  设置则自动进入 Caddy 反代配置（basicauth 密码），并引导输入 SSL 证书
#                          （留空则脚本交互询问；证书可粘贴内容或输入 .pem 文件路径）
#   STUDIO_SSL_CERT        origin 公钥：.pem 文件路径 或 内联 PEM 文本（非交互/自动化用）
#   STUDIO_SSL_KEY         origin 私钥：.pem 文件路径 或 内联 PEM 文本
#   CADDY_HOST          对外域名（默认 json.tybtool.top）
#
set -euo pipefail

STUDIO_BASE="${STUDIO_BASE:-https://app.tybtool.top}"
DEFAULT_VER="$STUDIO_BASE/studio/studio_version.json"
VER_URL="${1:-${STUDIO_VERSION_URL:-$DEFAULT_VER}}"

INSTALL_DIR="${INSTALL_DIR:-/home/admin/photographer-studio}"
PORT="${PORT:-8765}"
CADDY_PASSWORD="${STUDIO_CADDY_PASSWORD:-${CADDY_PASSWORD:-}}"
STUDIO_SSL_CERT="${STUDIO_SSL_CERT:-}"
STUDIO_SSL_KEY="${STUDIO_SSL_KEY:-}"
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

# 8) Caddy 反代 + basicauth（交互引导 SSL 证书输入）
# 读取一个 PEM：支持 (a) 交互粘贴多行内容 (b) 输入 .pem 文件路径 (c) 通过 envval 提供（文件或内联）
read_pem() {
  local prompt="$1" outfile="$2" envval="${3:-}" line data path=""
  if [ -n "$envval" ]; then
    if [ -f "$envval" ]; then
      $SUDO cp "$envval" "$outfile"; echo "    (已从环境变量文件复制: $envval)"; return 0
    fi
    printf '%s\n' "$envval" | $SUDO tee "$outfile" >/dev/null
    echo "    (已从环境变量写入 $outfile)"; return 0
  fi
  echo "$prompt"
  echo "  - 粘贴：直接 Ctrl+V 粘贴内容（以 -----BEGIN 开头），粘贴完在【空行】按回车结束"
  echo "  - 文件：直接输入 .pem 文件的绝对路径并回车"
  data=""
  while IFS= read -r line; do
    if [ -z "$line" ]; then
      [ -n "$data" ] && break        # 空行 = 粘贴结束
    else
      if [ -z "$data" ] && [ -f "$line" ]; then path="$line"; break; fi
      data+="$line"$'\n'
    fi
  done
  if [ -n "${path:-}" ]; then
    $SUDO cp "$path" "$outfile"; echo "    (已从文件复制: $path)"
  elif [ -n "$data" ]; then
    printf '%s' "$data" | $SUDO tee "$outfile" >/dev/null; echo "    (已写入 $outfile)"
  else
    echo "    !! 未读取到内容，跳过该项"; return 1
  fi
}

# 确保主 Caddyfile 能加载本目录、且不抢占 :80（否则 TLS 站点的 :80 重定向会冲突导致 caddy 启动失败）
ensure_caddy_import() {
  local cf=/etc/caddy/Caddyfile
  [ -f "$cf" ] || { echo "!! 未找到主 Caddyfile，跳过"; return 0; }
  # Debian 默认欢迎站点块 http://{} 占住 :80，删除它（先备份）
  if grep -qE '^[[:space:]]*http://[[:space:]]*\{' "$cf"; then
    $SUDO cp "$cf" "${cf}.bak.$(date +%s)"
    $SUDO sed -i '/^[[:space:]]*http:\/\/ {/,/^}/d' "$cf"
    echo "    (已移除默认欢迎站点块，备份 ${cf}.bak.*)"
  fi
  # 确保加载本目录（Debian 默认仅认 *.caddyfile；我们写 studio.caddyfile 已匹配，这里兜底）
  if ! grep -qE '^[[:space:]]*import[[:space:]]+/etc/caddy/Caddyfile\.d' "$cf"; then
    echo 'import /etc/caddy/Caddyfile.d/*' | $SUDO tee -a "$cf" >/dev/null
    echo "    (已追加 import /etc/caddy/Caddyfile.d/*)"
  fi
}

setup_caddy() {
  if ! command -v caddy >/dev/null 2>&1; then
    echo "!! 未检测到 caddy，跳过公网反代配置。"
    echo "   安装 caddy 后再配置：Debian/Ubuntu 用 'sudo apt install -y caddy'，"
    echo "   或官方脚本 https://caddyserver.com/docs/install ；然后参考 DEPLOY.md 手动加反代。"
    return 0
  fi

  # 是否配置：env 指定密码 → 自动；否则交互询问（仅 tty）
  local do_caddy=""
  if [ -n "$CADDY_PASSWORD" ]; then
    do_caddy="y"
  elif [ -t 0 ]; then
    read -r -p "是否配置公网反代 (Caddy + basicauth)? [y/N] " do_caddy
  fi
  case "$do_caddy" in y|Y|yes|YES) ;; *)
    echo "==> 跳过 Caddy 反代（仅本机 http://127.0.0.1:$PORT 可用）"; return 0;; esac

  # basicauth 密码
  if [ -z "$CADDY_PASSWORD" ] && [ -t 0 ]; then
    read -r -s -p "请输入公网访问密码 (basicauth): " CADDY_PASSWORD; echo
  fi
  if [ -z "$CADDY_PASSWORD" ]; then
    echo "!! 未提供密码，跳过 Caddy 反代"; return 0
  fi

  # SSL 证书方式
  local mode="1"
  if [ -t 0 ]; then
    echo "SSL 证书方式："
    echo "  1) Cloudflare Origin CA 证书（Cloudflare SSL 模式需设为 Full (strict)）"
    echo "  2) 暂不提供证书（Cloudflare SSL 模式需设为 Flexible，Caddy 仅监听 80）"
    read -r -p "请选择 [1/2，默认1]: " mode
  fi
  case "$mode" in 2) mode=2;; *) mode=1;; esac

  $SUDO mkdir -p /etc/caddy/Caddyfile.d
  $SUDO rm -f /etc/caddy/Caddyfile.d/studio.conf   # 清理旧扩展名，避免重复加载
  ensure_caddy_import
  local use_tls=0
  if [ "$mode" = "1" ]; then
    if read_pem "请输入 SSL 公钥 (origin_certificate.pem，可粘贴或输入文件路径):" \
                /etc/caddy/origin.pem "$STUDIO_SSL_CERT" \
       && read_pem "请输入 SSL 私钥 (private_key.pem，可粘贴或输入文件路径):" \
                   /etc/caddy/origin.key "$STUDIO_SSL_KEY"; then
      use_tls=1
    else
      echo "    !! 证书读取失败，回退为「不提供证书」模式（Cloudflare SSL 需设为 Flexible）"
    fi
  fi
  local tls_line=""
  [ "$use_tls" = "1" ] && tls_line="    tls /etc/caddy/origin.pem /etc/caddy/origin.key"

  local HASH="$(caddy hash-password "$CADDY_PASSWORD")"
  $SUDO tee /etc/caddy/Caddyfile.d/studio.caddyfile >/dev/null <<EOF
$CF_HOST {
$tls_line
    basicauth {
        studio $HASH
    }
    encode gzip
    reverse_proxy 127.0.0.1:$PORT
}
EOF
  $SUDO systemctl reload caddy 2>/dev/null || $SUDO systemctl restart caddy
  echo "==> Caddy 已配置 $CF_HOST 并 reload（basicauth 已启用，模式: $([ "$use_tls" = 1 ] && echo 'Full(strict)' || echo 'Flexible')）"
}

setup_caddy

echo
echo "==> 安装完成：$TAG 已运行，开机自启已启用。"
echo "    本机访问 : http://127.0.0.1:$PORT"
[ -n "$CADDY_PASSWORD" ] && echo "    公网访问 : https://$CF_HOST/"
