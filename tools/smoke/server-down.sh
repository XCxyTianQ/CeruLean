#!/bin/sh
# 停止 server-up.sh 启动的服务端（先 RCON stop，再兜底 kill）。
set -e
RUN_DIR=${1:-$PWD/smoke-run}
RCON_PORT=${RCON_PORT:-25575}
RCON_PASSWORD=${RCON_PASSWORD:-test1234}
PIDFILE="$RUN_DIR/server.pid"
if python3 "$(dirname "$0")/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" \
     --command "save-all flush" >/dev/null 2>&1; then
  python3 "$(dirname "$0")/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" --command stop >/dev/null 2>&1 || true
fi
i=0
while [ $i -lt 30 ]; do
  [ -f "$PIDFILE" ] || break
  kill -0 "$(cat "$PIDFILE")" 2>/dev/null || break
  i=$((i + 1)); sleep 1
done
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "RCON stop 未生效，发送 TERM"
  kill "$(cat "$PIDFILE")" 2>/dev/null || true
fi
echo "服务端已停止"
