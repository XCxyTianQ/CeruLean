#!/bin/sh
# fail-stop 验证：让一次区域 tick 故意抛异常，断言"服务端真的停下来了"。
# 判据（缺一不可）：
#   1) 控制台出现 fail-stop 日志（区域 id / 段数 / 维度 / 异常栈）；
#   2) 控制台出现"正在停服"的日志；
#   3) **进程在超时前退出**（这才是 fail-stop 的决定性证据——不是只打日志）。
# 用法: CERULEAN_EULA=TRUE tools/smoke/b1-failstop-check.sh <jar> [报告目录]
set -e
JAR=${1:?用法: b1-failstop-check.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-failstop-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0"); LOG="$OUT/server-console.log"

"$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
PID=$(cat "$OUT/server.pid")
echo "服务端 PID=$PID"

python3 - "$OUT" <<'PY'
import os, re, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)) if False else 'tools/smoke'))
from rcon import Rcon
out = sys.argv[1]
with Rcon(host='127.0.0.1', port=int(os.environ.get('RCON_PORT','25575')),
          password=os.environ.get('RCON_PASSWORD','test1234'), timeout=60.0, quiet=True) as r:
    r.cmd("forceload remove all", first_wait=30.0)
    r.cmd("forceload add 0 0 255 255", first_wait=90.0)     # 先造出至少一个区域
    time.sleep(4)
    print("  武装自检:", (r.cmd("cerulean selftest throw-in-tick") or "").strip()[:120])
PY

echo "等待 fail-stop 生效（最多 60 秒）..."
STOPPED=0
i=0
while [ $i -lt 60 ]; do
  if ! kill -0 "$PID" 2>/dev/null; then STOPPED=1; break; fi
  i=$((i + 1)); sleep 1
done

echo "  进程已退出: $([ $STOPPED -eq 1 ] && echo 是 || echo 否)（等待 ${i}s）"
grep -q "区域 tick 失败 ⇒ fail-stop" "$LOG" && echo "  ✓ 已记录 fail-stop 现场" || echo "  ✗ 未记录 fail-stop 现场"
grep -q "正在停服" "$LOG" && echo "  ✓ 已请求停服" || echo "  ✗ 未请求停服"
grep -q "故意在区域 tick 中抛出异常" "$LOG" && echo "  ✓ 异常来源可追溯（自检异常）" || echo "  ✗ 未见自检异常"
"$DIR/server-down.sh" "$OUT" >/dev/null 2>&1 || true

if [ "$STOPPED" -eq 1 ] \
   && grep -q "区域 tick 失败 ⇒ fail-stop" "$LOG" \
   && grep -q "正在停服" "$LOG"; then
  echo "== fail-stop 验证通过（异常 → 记录 → halt → 停服）"
  exit 0
fi
echo "== fail-stop 验证未通过"
exit 1
