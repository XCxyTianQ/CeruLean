#!/bin/sh
# 一键：启动服务端 → 跑语义基线 → 关服 → 输出 JSON 报告
#
# 用法: CERULEAN_EULA=TRUE tools/smoke/run-smoke.sh <jar> [报告目录]
set -e
JAR=${1:?用法: run-smoke.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-report}
DIR="$OUT"
mkdir -p "$OUT"
: "${RCON_PORT:=25575}"
: "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
"$(dirname "$0")/server-up.sh" "$JAR" "$OUT"
set +e
python3 "$(dirname "$0")/smoke_baseline.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" \
  --expect-mc "${CERULEAN_EXPECT_MC:-}" --json "$OUT/smoke-baseline.json"
CODE=$?
set -e
"$(dirname "$0")/server-down.sh" "$OUT"
echo "报告目录: $OUT"
exit $CODE
