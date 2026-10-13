#!/bin/sh
# B2 归属校验验收：同一 jar 跑三次，唯一变量是 cerulean-regions.ownership-check（off / soft / strict）。
#   期望：off 放行且零记录；soft 放行但记录"将会违规"；strict 拒绝并抛 IllegalStateException。
# 用法: CERULEAN_EULA=TRUE tools/smoke/b2-ownership-acceptance.sh <jar> [报告目录]
set -e
JAR=${1:?用法: b2-ownership-acceptance.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-ownership-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0")
CFG="$OUT/config/paper-global.yml"

# 注意：必须单独取退出码——管道/|| 都会吞掉它（我们在 CI 上踩过这个坑）。
round() {
  echo "== 模式 $1"
  CERULEAN_CONFIG="$CFG" python3 "$DIR/b2_ownership_probe.py" > "$OUT/probe-$1.txt" 2>&1
  rc=$?
  cat "$OUT/probe-$1.txt"
  return $rc
}

RCS=""
FIRST=1
for MODE in off soft strict; do
  if [ "$FIRST" -eq 1 ]; then
    "$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
    FIRST=0
  else
    "$DIR/server-down.sh" "$OUT" >/dev/null
    python3 - "$CFG" "$MODE" <<'PY'
import re, sys
path, mode = sys.argv[1], sys.argv[2]
s = open(path, encoding="utf-8").read()
s2, n = re.subn(r"(?m)^(\s*)ownership-check:\s*\S+\s*$", r"\g<1>ownership-check: " + mode, s, count=1)
if n != 1:
    raise SystemExit("未能改写 ownership-check")
open(path, "w", encoding="utf-8").write(s2)
print(f"  ✓ 配置已改为 ownership-check: {mode}")
PY
    "$DIR/server-up.sh" "$JAR" "$OUT" >/dev/null
  fi
  set +e; round "$MODE"; rc=$?; set -e
  RCS="$RCS $MODE=$rc"
done
"$DIR/server-down.sh" "$OUT" >/dev/null
echo
echo "各模式退出码:$RCS"
for kv in $RCS; do
  [ "${kv#*=}" -eq 0 ] || { echo "== B2 验收未通过（$kv）"; exit 1; }
done
echo "== B2 归属校验验收通过（三种模式行为都符合预期）"
