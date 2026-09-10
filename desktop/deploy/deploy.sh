#!/usr/bin/env bash
#
# 一键部署 PhotographerCamera Studio 到 json.tybtool.top（独立服务器）。
#
# 模型：R2 拉取（pull）。本机只负责「打包 + 上传 R2 + 生成版本清单」，
#   服务器通过 SSH 拉取 R2 上的部署包并自装。APK 下载（app.tybtool.top / R2
#   app-update 桶）完全不触碰——Studio 对象只落在 app-update 桶的 studio/ 前缀，
#   经 R2 S3 端点预签名 URL 下载，不经过 Cloudflare 公网缓存。
#
# 用法（在本机 Git Bash 跑，需已配好到服务器的 SSH 免密）：
#   bash desktop/deploy/deploy.sh
#
set -euo pipefail

# Git Bash 下 mktemp 默认返回 Windows 路径（C:\...），会被 tar 当成远程主机，
# 因此强制用 POSIX 临时目录
export TMPDIR=/tmp

# ===================== 按你的环境修改 =====================
SERVER="admin@52.220.50.185"                     # 目标服务器 user@host
REMOTE_DIR="/home/admin/photographer-studio"     # 服务器部署目录
# =========================================================

REPO="$(cd "$(dirname "$0")/../.." && pwd)"       # 仓库根 = PhotographerCamera/
DEPLOY="$REPO/desktop/deploy"

# python 探测（Windows 开发机优先用 venv 里的）
if [ -x "$REPO/.venv/Scripts/python.exe" ]; then PY="$REPO/.venv/Scripts/python.exe"
elif command -v python3 >/dev/null 2>&1; then PY=python3
else PY=python; fi

echo "==> 仓库根: $REPO"
echo "==> 部署到 $SERVER:$REMOTE_DIR"

# 1) 打部署包：仅搬运运行所需（代码 + schema + 依赖清单），不含运行期数据
TAG="$(date +%Y%m%d-%H%M%S)-$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo nogit)"
TARBALL="$(mktemp -t studio-XXXXXX).tar.gz"
echo "==> 构建部署包 studio-$TAG.tar.gz"
tar czf "$TARBALL" --exclude='__pycache__' --exclude='*.pyc' \
  -C "$REPO" desktop tools profiles/schema requirements.txt
SHA="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
echo "    size=$(du -h "$TARBALL" | cut -f1)  sha256=$SHA"

# 2) 上传部署包到 R2（studio/ 前缀），拿到预签名 GET URL
BUNDLE_KEY="studio/photographer-studio-$TAG.tar.gz"
BUNDLE_URL="$("$PY" "$DEPLOY/r2_upload.py" --file "$TARBALL" --key "$BUNDLE_KEY")"

# 3) 生成版本清单并上传（充当「发布开关」，类似安卓的 version.json）
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
VER_URL="$("$PY" "$DEPLOY/r2_upload.py" --file "$VERSION_JSON" --key "studio/studio_version.json")"

# 4) 上传引导脚本（供服务器独立复用）
BOOT_URL="$("$PY" "$DEPLOY/r2_upload.py" --file "$DEPLOY/bootstrap.sh" --key "studio/bootstrap.sh")"

# 5) SSH 到服务器：拉取 R2 上的引导脚本并自装（开机自启由 setup_remote.sh 注册）
echo "==> 触发服务器安装（从 R2 拉取部署包）"
ssh "$SERVER" "curl -sS '$BOOT_URL' | STUDIO_VERSION_URL='$VER_URL' REMOTE_DIR='$REMOTE_DIR' bash -s"

rm -f "$TARBALL" "$VERSION_JSON"

echo
echo "==> 部署完成。Studio 已在服务器开机自启（systemd enable）。"
echo "    配置 Caddy/Nginx 把 json.tybtool.top 反代到 127.0.0.1:8765 即可公网访问。"
echo
echo "==> 以后仅更新代码时，可只在服务器侧重跑（无需本机）："
echo "    ssh $SERVER 'curl -sS $BOOT_URL | STUDIO_VERSION_URL='\\''$VER_URL'\\'' bash -s'"
echo "    （说明：VER_URL 每次 deploy 会变；永久固定请改用下面的独立引导方式）"
echo
echo "==> 独立引导（VER_URL 失效后重新生成）："
echo "    本机重新跑一次 deploy.sh 即可，或手动："
echo "    PY='$PY' ; VER=\$(\$PY '$DEPLOY/r2_upload.py' --file <无需> ... )  # 见 DEPLOY.md"
