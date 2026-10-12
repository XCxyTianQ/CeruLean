#!/bin/sh
# 一键：启动服务端 → 跑语义基线 → 关服 → 输出 JSON 报告
#
# 用法: CERULEAN_EULA=TRUE tools/smoke/run-smoke.sh <jar> [报告目录]
#
# 注意：paperclip 容器会把"解包后的依赖库与缓存"放在**运行目录**里（实测约 180 MB）。
# 因此运行目录默认**复用**：删掉它会让下次启动多花 3–5 分钟（需重新解包 62 MB 原版 bundler），
# 容易误判为"启动失败"。需要冷启动验证时自行 rm -rf 运行目录。
set -e
JAR=${1:?用法: run-smoke.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-report}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
DIR="$OUT"
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
