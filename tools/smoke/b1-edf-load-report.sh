#!/bin/sh
# EDF 模式下重跑压载：这是"B2 的闸门终于会抓到东西吗"的关键实验。
#   edf + soft   → 统计"将会违规"的调用现场（= B5 的工作清单）
#   edf + strict → 当前架构能否在严格模式下存活（不能则给出首个违规现场）
# 用法: CERULEAN_EULA=TRUE tools/smoke/b1-edf-load-report.sh <jar> [报告目录]
set -e
JAR=${1:?用法: b1-edf-load-report.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-edf-load-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0"); CFG="$OUT/config/paper-global.yml"

set_cfg() {
  python3 - "$CFG" "$1" "$2" <<'PY'
import re, sys
path, sched, own = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
s, n1 = re.subn(r"(?m)^(\s*)scheduler:\s*\S+\s*$", r"\g<1>scheduler: " + sched, s, count=1)
s, n2 = re.subn(r"(?m)^(\s*)ownership-check:\s*\S+\s*$", r"\g<1>ownership-check: " + own, s, count=1)
assert n1 == 1 and n2 == 1, f"未能改写配置（{n1}/{n2}）"
open(path, "w", encoding="utf-8").write(s)
PY
}

# 注意：配置文件要**先写好再起服**（否则第一轮跑的是上一轮的配置——我踩过这个坑）
"$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
set_cfg edf soft || true
"$DIR/server-down.sh" "$OUT" >/dev/null
for OWN in soft strict; do
  set_cfg edf "$OWN"
  "$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null || { echo "OWN=$OWN SURVIVED=0（起服失败）"; continue; }
  echo "---- EDF + $OWN ----"
  CERULEAN_CONFIG="$CFG" python3 "$DIR/b2_load_probe.py" 2>&1 | tee "$OUT/load-$OWN.txt" || true
  [ -f "$OUT/server.pid" ] && kill -0 "$(cat "$OUT/server.pid")" 2>/dev/null || rm -f "$OUT/server.pid"
done
"$DIR/server-down.sh" "$OUT" >/dev/null 2>&1 || true
echo
grep -h "^MODE=" "$OUT"/load-*.txt 2>/dev/null | sed 's/^/  /'
