#!/usr/bin/env bash
#
# 一键部署 PhotographerCamera Studio 到 json.tybtool.top（独立服务器）。
# 模型：R2 拉取（pull）。本机只负责「打包 + 上传 R2（公开）」；服务器用一句指令即可安装。
# 与 app.tybtool.top 的 APK 下载完全隔离：Studio 对象落在 app-update 桶的 studio/ 前缀，
# 经公开 URL 访问（不走签名、不依赖 Cloudflare 缓存的签名有效期）。
#
# 用法：  bash desktop/deploy/deploy.sh
# 跑完后在服务器执行终端打印出的那一句指令即可。
set -euo pipefail

# Git Bash 下 mktemp 默认返回 Windows 路径（C:\...），会被 tar 当成远程主机，强制 POSIX 临时目录
export TMPDIR=/tmp

# ============ 按你的环境修改 ============
# install.sh / studio_version.json / 部署包 的公开基址（需 R2 bucket 已对 studio/ 前缀开启 public access，并绑定此域）
R2_PUBLIC_BASE="https://app.tybtool.top"
REMOTE_DIR="/home/admin/photographer-studio"
PORT="${PORT:-8765}"
# =========================================

REPO="$(cd "$(dirname "$0")/../.." && pwd)"       # 仓库根 = PhotographerCamera/
DEPLOY="$REPO/desktop/deploy"

# python 探测（Windows 开发机优先用 venv 里的）
if [ -x "$REPO/.venv/Scripts/python.exe" ]; then PY="$REPO/.venv/Scripts/python.exe"
elif command -v python3 >/dev/null 2>&1; then PY=python3
else PY=python; fi

echo "==> 仓库根: $REPO"

# 1) 打部署包：仅搬运运行所需（代码 + schema + 依赖清单），不含运行期数据
TAG="$(date +%Y%m%d-%H%M%S)-$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo nogit)"
TARBALL="$(mktemp -t studio-XXXXXX).tar.gz"
echo "==> 构建部署包 studio-$TAG.tar.gz"
tar czf "$TARBALL" --exclude='__pycache__' --exclude='*.pyc' \
  -C "$REPO" desktop tools profiles/schema requirements.txt
SHA="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
echo "    size=$(du -h "$TARBALL" | cut -f1)  sha256=$SHA"

# 2) 上传部署包（公开可读，长期固定地址）
BUNDLE_KEY="studio/photographer-studio-$TAG.tar.gz"
"$PY" "$DEPLOY/r2_upload.py" --file "$TARBALL" --key "$BUNDLE_KEY" --public >/dev/null
BUNDLE_URL="$R2_PUBLIC_BASE/$BUNDLE_KEY"

# 3) 生成版本清单并上传（公开，充当发布开关）
VERSION_JSON="$(mktemp -t studio_version-XXXXXX).json"
cat > "$VERSION_JSON" <<EOF
{
  "tag": "$TAG",
  "bundle": "$BUNDLE_URL",
  "sha256": "$SHA",
  "ts": $(date +%s)000,
  "notes": "studio deploy $TAG"
}
EOF
"$PY" "$DEPLOY/r2_upload.py" --file "$VERSION_JSON" --key "studio/studio_version.json" --public >/dev/null
VER_URL="$R2_PUBLIC_BASE/studio/studio_version.json"

# 4) 上传安装脚本（公开，固定地址 install.sh）
"$PY" "$DEPLOY/r2_upload.py" --file "$DEPLOY/server_install.sh" --key "studio/install.sh" --public >/dev/null
INSTALL_URL="$R2_PUBLIC_BASE/studio/install.sh"

rm -f "$TARBALL" "$VERSION_JSON"

echo
echo "==> 上传完成（Studio 部署包已就位，APK 下载链路未触碰）。"
echo
echo "==> 服务器一句指令安装（SSH 进服务器后整行粘贴执行）："
echo
echo "    url=$INSTALL_URL; if [ -f /usr/bin/curl ]; then curl -sSO \$url; else wget -O install.sh \$url; fi; bash install.sh"
echo
echo "    可选：带 Caddy basicauth 一键配好公网反代（需服务器已装 caddy + Cloudflare Origin CA 证书）："
echo "    url=$INSTALL_URL; if [ -f /usr/bin/curl ]; then curl -sSO \$url; else wget -O install.sh \$url; fi; STUDIO_CADDY_PASSWORD='你的密码' bash install.sh"
echo
echo "==> 升级：服务器上重跑上面那一句即可（代码覆盖、venv/已保存 profile 保留，开机自启不变）。"
echo "    install.sh / studio_version.json 为固定公开地址长期有效；bundle 每次 deploy 换 tag 并在 version.json 指向新包。"
