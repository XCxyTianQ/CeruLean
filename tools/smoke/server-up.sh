#!/bin/sh
# 启动一个用于冒烟测试的服务端实例（paperclip 形态的 jar）。
#
# 用法：
#   CERULEAN_EULA=TRUE tools/smoke/server-up.sh <jar> [运行目录]
# 环境变量：
#   CERULEAN_EULA=TRUE      必须显式声明已阅读并同意 Minecraft EULA（不会替你同意）
#   MC_PORT=25565           游戏端口
#   RCON_PORT=25575         RCON 端口
#   RCON_PASSWORD=test1234  RCON 密码
#   JAVA_HOME=...           默认 ~/.harmonybrew/opt/openjdk@25
#   JAVA_MEM=2G             堆上限
set -e

JAR=${1:?用法: server-up.sh <jar> [运行目录]}
RUN_DIR=${2:-$PWD/smoke-run}
: "${MC_PORT:=25565}"
: "${RCON_PORT:=25575}"
: "${RCON_PASSWORD:=test1234}"
: "${JAVA_MEM:=2G}"
JAVA_HOME=${JAVA_HOME:-$HOME/.harmonybrew/opt/openjdk@25}
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=$(command -v java)

if [ "${CERULEAN_EULA:-}" != "TRUE" ]; then
  echo "拒绝启动：请先阅读 https://aka.ms/MinecraftEULA"
  echo "确认同意后，用 CERULEAN_EULA=TRUE 重新运行。"
  exit 2
fi

[ -f "$JAR" ] || { echo "找不到 jar: $JAR"; exit 2; }
JAR=$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")
mkdir -p "$RUN_DIR"
printf 'eula=true\n' > "$RUN_DIR/eula.txt"

if [ ! -f "$RUN_DIR/server.properties" ]; then
  cat > "$RUN_DIR/server.properties" <<EOF
level-name=smoke-world
level-type=minecraft\:flat
online-mode=false
enable-command-block=true
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
broadcast-rcon-to-ops=false
spawn-protection=0
max-players=5
view-distance=4
simulation-distance=4
motd=CeruLean smoke
EOF
fi

echo "启动服务端: $JAR"
echo "  运行目录 $RUN_DIR / 游戏端口 $MC_PORT / RCON $RCON_PORT / 堆 $JAVA_MEM"
cd "$RUN_DIR"
nohup "$JAVA_BIN" "-Xmx$JAVA_MEM" -jar "$JAR" --nogui --port "$MC_PORT" \
  > "$RUN_DIR/server-console.log" 2>&1 &
echo $! > "$RUN_DIR/server.pid"
echo "PID $(cat "$RUN_DIR/server.pid")，日志 $RUN_DIR/server-console.log"

# 等 RCON 就绪（最多 180 秒）
i=0
while [ $i -lt 90 ]; do
  if python3 "$(dirname "$0")/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" \
       --command list >/dev/null 2>&1; then
    echo "RCON 已就绪（等待 $((i * 2)) 秒）"
    exit 0
  fi
  i=$((i + 1))
  sleep 2
done
echo "RCON 在 180 秒内未就绪；请检查 $RUN_DIR/server-console.log"
exit 1
