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

# 脚本目录必须先绝对化：后面会 cd 进运行目录，届时相对路径会失效（实测踩过：
# 就绪轮询里调用 rcon.py 时路径失效，导致"服务端已就绪却一直等到超时"）。
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

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
# 绝对化：后面会 cd 进运行目录，相对路径再拼一次就会指向不存在的子目录
RUN_DIR=$(cd "$RUN_DIR" && pwd)
printf 'eula=true\n' > "$RUN_DIR/eula.txt"

# 每次启动都按参数重写：运行目录是可抛弃的；残留配置（尤其 rcon.port/rcon.password）
# 会造成"服务端已就绪但 RCON 连不上"的误判（实测踩过）。
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

echo "启动服务端: $JAR"
echo "  运行目录 $RUN_DIR / 游戏端口 $MC_PORT / RCON $RCON_PORT / 堆 $JAVA_MEM"
cd "$RUN_DIR"
# MC_LIBS_DIR 非空时按 classpath 方式启动（用于运行"重建出来的裸 server jar"，
# 而不是 paperclip 容器）；否则用 java -jar（paperclip 容器自带依赖）。
if [ -n "${MC_LIBS_DIR:-}" ]; then
  echo "  以 classpath 方式启动：$JAR + $MC_LIBS_DIR/*"
  nohup "$JAVA_BIN" "-Xmx$JAVA_MEM" -cp "$JAR:$MC_LIBS_DIR/*" org.bukkit.craftbukkit.Main \
    --nogui --port "$MC_PORT" > "$RUN_DIR/server-console.log" 2>&1 &
else
  nohup "$JAVA_BIN" "-Xmx$JAVA_MEM" -jar "$JAR" --nogui --port "$MC_PORT" \
    > "$RUN_DIR/server-console.log" 2>&1 &
fi
echo $! > "$RUN_DIR/server.pid"
echo "PID $(cat "$RUN_DIR/server.pid")，日志 $RUN_DIR/server-console.log"

# 等 RCON 就绪（最多 420 秒）
# 说明：paperclip 容器首次运行需要解包 62MB 原版 bundler + 103 个依赖库并现场打补丁，
# 实测首次启动到 RCON 可用会超过 3 分钟（控制台刷进度符 >），因此上限放宽。
i=0
SERVER_PID=$(cat "$RUN_DIR/server.pid")
while [ $i -lt 210 ]; do
  if python3 "$SCRIPT_DIR/rcon.py" --port "$RCON_PORT" --password "$RCON_PASSWORD" \
       --command list >/dev/null 2>&1; then
    echo "RCON 已就绪（等待 $((i * 2)) 秒）"
    exit 0
  fi
  # 关键：进程已经退出就别再干等（否则"jar 不对/启动即崩"要白等 7 分钟，
  # 而且失败信息被淹没）。这里立刻失败并把控制台尾部打出来。
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "服务端进程已退出（PID $SERVER_PID），未等到 RCON。控制台最后几行："
    tail -5 "$RUN_DIR/server-console.log" 2>/dev/null | sed 's/^/    /'
    exit 1
  fi
  i=$((i + 1))
  sleep 2
done
echo "RCON 在 420 秒内未就绪；请检查 $RUN_DIR/server-console.log"
exit 1
