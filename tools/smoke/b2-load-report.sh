#!/bin/sh
# B2 压载报告：off（代价基线）→ soft（违规普查）→ strict（能否存活）
# 用法: CERULEAN_EULA=TRUE tools/smoke/b2-load-report.sh <jar> [报告目录]
set -e
JAR=${1:?用法: b2-load-report.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-load-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0"); CFG="$OUT/config/paper-global.yml"

"$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
FIRST=1
for MODE in off soft strict; do
  if [ "$FIRST" -eq 1 ]; then FIRST=0; else
    "$DIR/server-down.sh" "$OUT" >/dev/null || true
    python3 - "$CFG" "$MODE" <<'PY'
import re, sys
path, mode = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
s2, n = re.subn(r"(?m)^(\s*)ownership-check:\s*\S+\s*$", r"\g<1>ownership-check: " + mode, s, count=1)
assert n == 1, "未能改写 ownership-check"
open(path, "w", encoding="utf-8").write(s2)
PY
    "$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null || { echo "MODE=$MODE SURVIVED=0（起服失败）"; continue; }
  fi
  echo "---- 模式 $MODE ----"
  CERULEAN_CONFIG="$CFG" python3 "$DIR/b2_load_probe.py" 2>&1 | tee "$OUT/load-$MODE.txt" || true
  # strict 下若服务端已死，下一轮起服前需要清掉陈旧 pid
  [ -f "$OUT/server.pid" ] && kill -0 "$(cat "$OUT/server.pid")" 2>/dev/null || rm -f "$OUT/server.pid"
done
"$DIR/server-down.sh" "$OUT" >/dev/null 2>&1 || true
echo
echo "== 各模式结论:"
grep -h "^MODE=" "$OUT"/load-*.txt 2>/dev/null | sed 's/^/  /'
