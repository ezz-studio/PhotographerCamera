#!/usr/bin/env bash
#
# 服务器端引导（从 R2 拉取 Studio 部署包并安装为 systemd 服务，开机自启）。
# 由 deploy.sh 通过预签名 GET URL 拉取后直接管道执行；也可手动复用：
#   curl -sS '<BOOTSTRAP_GET_URL>' | STUDIO_VERSION_URL='<VER_URL>' bash -s
#
# 依赖：curl / python3（解析 JSON）/ sha256sum / systemd
set -euo pipefail

VER_URL="${STUDIO_VERSION_URL:?请设置 STUDIO_VERSION_URL（studio_version.json 的预签名 GET URL）}"
REMOTE_DIR="${REMOTE_DIR:-/home/admin/photographer-studio}"
TMP="$(mktemp -d)"

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

echo "==> 下载 studio_version.json"
curl -sS --fail "$VER_URL" -o "$TMP/studio_version.json"
BUNDLE="$(python3 -c "import json;print(json.load(open('$TMP/studio_version.json'))['bundle'])")"
SHA="$(python3 -c "import json;print(json.load(open('$TMP/studio_version.json'))['sha256'])")"
TAG="$(python3 -c "import json;print(json.load(open('$TMP/studio_version.json')).get('tag',''))")"

echo "==> 下载部署包 ($TAG)"
curl -sS --fail "$BUNDLE" -o "$TMP/bundle.tar.gz"

echo "==> 校验 sha256"
echo "$SHA  $TMP/bundle.tar.gz" | sha256sum -c -

mkdir -p "$REMOTE_DIR"
echo "==> 解压到 $REMOTE_DIR"
tar xzf "$TMP/bundle.tar.gz" -C "$REMOTE_DIR"

echo "==> 执行安装（建 venv / 装依赖 / 注册 systemd 并开机自启）"
bash "$REMOTE_DIR/desktop/deploy/setup_remote.sh" "$REMOTE_DIR"

echo "==> 完成。Studio 已开机自启，监听 127.0.0.1:8765"
