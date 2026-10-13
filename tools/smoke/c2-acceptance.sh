#!/bin/sh
# C2 验收：起服 → 语义基线（与 vanilla 行为一致性）→ 区域拓扑探针 → 关服
# 用法: CERULEAN_EULA=TRUE tools/smoke/c2-acceptance.sh <jar> [报告目录]
set -e
JAR=${1:?用法: c2-acceptance.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0")
"$DIR/server-up.sh" "$JAR" "$OUT"
set +e
echo "== 1/2 语义基线（判据：与 vanilla Paper 行为逐项一致）"
python3 "$DIR/smoke_baseline.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" --json "$OUT/smoke-baseline.json"
BASE=$?
echo "== 2/2 区域拓扑探针"
python3 "$DIR/c2_region_probe.py" | tee "$OUT/c2-region-probe.txt"
PROBE=$?
set -e
"$DIR/server-down.sh" "$OUT"
echo "基线退出码=$BASE  探针退出码=$PROBE"
[ "$BASE" -eq 0 ] && [ "$PROBE" -eq 0 ] && echo "== C2 验收通过" || echo "== C2 验收未通过"
exit $([ "$BASE" -eq 0 ] && [ "$PROBE" -eq 0 ] && echo 0 || echo 1)
