#!/usr/bin/env bash
# PhotographerCamera 远程调试日志接收服务 —— 一键启动（Linux 服务器，零依赖）
#
# 用法:
#   ./start.sh            # 默认端口 8080，后台运行，日志写到 logs/server.out
#   ./start.sh 9000       # 指定端口
#
# 停止:  pkill -f "server.py --host"
#
# 依赖: 仅 Python 3 标准库（无需 pip install）。
set -e
PORT="${1:-8080}"
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
LOGDIR="$DIR/logs"
mkdir -p "$LOGDIR"
nohup python3 server.py --host 0.0.0.0 --port "$PORT" --logdir "$LOGDIR" \
  > "$LOGDIR/server.out" 2>&1 &
echo "✅ 已启动  pid=$!"
echo "   Web 面板:  http://<服务器IP>:$PORT/"
echo "   App 上报:  http://<服务器IP>:$PORT/log"
echo "   进程日志:  $LOGDIR/server.out"
