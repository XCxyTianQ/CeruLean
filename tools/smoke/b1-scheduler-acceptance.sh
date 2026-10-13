#!/bin/sh
# B1 验收：同一 jar 跑三次 —— serial（对拍基线）、edf 1 线程、edf 自动线程数；
# 每次都要求"区域确实被调度到"，并用 ownership=soft 观察 EDF 模式下是否出现"将会违规"。
# 用法: CERULEAN_EULA=TRUE tools/smoke/b1-scheduler-acceptance.sh <jar> [报告目录]
set -e
JAR=${1:?用法: b1-scheduler-acceptance.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-b1-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0"); CFG="$OUT/config/paper-global.yml"

set_cfg() {
  python3 - "$CFG" "$1" "$2" <<'PY'
import re, sys
path, mode, threads = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
s, n1 = re.subn(r"(?m)^(\s*)scheduler:\s*\S+\s*$", r"\g<1>scheduler: " + mode, s, count=1)
s, n2 = re.subn(r"(?m)^(\s*)threads:\s*-?\d+\s*$", r"\g<1>threads: " + threads, s, count=1)
assert n1 == 1 and n2 == 1, f"未能改写配置（scheduler={n1} threads={n2}）"
open(path, "w", encoding="utf-8").write(s)
PY
}

run_round() {
  echo "---- $1 ----"
  CERULEAN_CONFIG="$CFG" python3 "$DIR/b1_scheduler_probe.py" > "$OUT/probe-$1.txt" 2>&1
  rc=$?
  cat "$OUT/probe-$1.txt"
  return $rc
}

"$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
set +e; run_round serial; R1=$?; set -e
R2=0; R3=0
for spec in "edf 1" "edf -1"; do
  MODE=${spec% *}; THREADS=${spec#* }
  "$DIR/server-down.sh" "$OUT" >/dev/null
  set_cfg "$MODE" "$THREADS"
  "$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
  set +e; run_round "edf-t$THREADS"; rc=$?; set -e
  if [ "$THREADS" = "1" ]; then R2=$rc; else R3=$rc; fi
done
"$DIR/server-down.sh" "$OUT" >/dev/null
echo
echo "serial=$R1  edf(1)=$R2  edf(auto)=$R3"
grep -h "^MODE=" "$OUT"/probe-*.txt 2>/dev/null | sed 's/^/  /'
if [ "$R1" -eq 0 ] && [ "$R2" -eq 0 ] && [ "$R3" -eq 0 ]; then
  echo "== B1 验收通过（serial / edf 1 线程 / edf 自动线程数 都能把区域调度到）"
else
  echo "== B1 验收未通过"; exit 1
fi
