#!/bin/sh
# C3 配置验收：同一 jar 跑两次，唯一变量是 config/paper-global.yml 里的 merge-radius。
#   第 1 轮（默认 merge-radius=2）：桥接段间距 2 ⇒ 期望**合并**
#   第 2 轮（改为 merge-radius=1）：间距 2 > 1 ⇒ 期望**不合并**
# 两轮都必须通过"命令回显与 YAML 一致"的核对 ⇒ 证明配置真的驱动了内核。
# 用法: CERULEAN_EULA=TRUE tools/smoke/c3-config-acceptance.sh <jar> [报告目录]
set -e
JAR=${1:?用法: c3-config-acceptance.sh <jar> [报告目录]}
OUT=${2:-$PWD/smoke-config-report}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
: "${RCON_PORT:=25575}"; : "${RCON_PASSWORD:=test1234}"
export RCON_PORT RCON_PASSWORD
DIR=$(dirname "$0")
CFG="$OUT/config/paper-global.yml"

# 注意：不要用 `python3 ... | tee ...`——那样 $? 是 tee 的退出码，探针失败会被吞掉
# （我们在 CI workflow 上踩过同一个坑，见 docs/03 附三）。
round() {
  echo "== 第 $1 轮（$2）"
  CERULEAN_CONFIG="$CFG" python3 "$DIR/c3_config_probe.py" > "$OUT/probe-round$1.txt" 2>&1
  rc=$?
  cat "$OUT/probe-round$1.txt"
  return $rc
}

echo "== 第 1 轮：默认配置"
"$DIR/server-up.sh" "$JAR" "$OUT"
set +e; round 1 "判据由 YAML 推导"; R1=$?; set -e
"$DIR/server-down.sh" "$OUT"

echo "== 修改配置：merge-radius 2 → 1"
python3 - "$CFG" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s2, n = re.subn(r"(?m)^(\s*)merge-radius:\s*\d+\s*$", r"\g<1>merge-radius: 1", s, count=1)
if n != 1:
    raise SystemExit("未能改写 merge-radius（配置节结构可能变了）")
open(p, "w", encoding="utf-8").write(s2)
print("  ✓ 已写入 merge-radius: 1")
PY
grep -n -A1 "merge-radius" "$CFG" | head -4 | sed 's/^/    /'

echo "== 第 2 轮：merge-radius=1"
"$DIR/server-up.sh" "$JAR" "$OUT"
set +e; round 2 "mergeRadius 已改为 1，判据应整体左移"; R2=$?; set -e
"$DIR/server-down.sh" "$OUT"

echo
echo "第 1 轮退出码=$R1  第 2 轮退出码=$R2"
T1=$(sed -n 's/^MEASURED_THRESHOLD=//p' "$OUT/probe-round1.txt" | head -1)
T2=$(sed -n 's/^MEASURED_THRESHOLD=//p' "$OUT/probe-round2.txt" | head -1)
echo "实测阈值：merge-radius=2 → ${T1:-?} 段；merge-radius=1 → ${T2:-?} 段"
# 跨轮比较：这是"配置真的驱动了内核"的最强证据——阈值必须整体左移 1 段。
if [ "${T1:-0}" -eq 0 ] || [ "${T2:-0}" -eq 0 ] || [ $((T1 - T2)) -ne 1 ]; then
  echo "== 失败：阈值未随 merge-radius 左移 1 段（${T1:-?} → ${T2:-?}）"
  exit 1
fi
echo "  ✓ 阈值随 merge-radius 精确左移 1 段（${T1} → ${T2}）"
if [ "$R1" -eq 0 ] && [ "$R2" -eq 0 ]; then
  echo "== C3 配置验收通过（配置真的改变了拓扑行为）"
  exit 0
fi
echo "== C3 配置验收未通过"
exit 1
