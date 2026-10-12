#!/bin/sh
# 本地 Gradle 启动器（绕开 gradlew 脚本在本机 shell 下的参数错乱问题）
#
# 背景：本机没有真 bash（/bin/sh 为 mksh，DSH 的 bash 工具经 zsh 转发），
# 直接执行 ./gradlew 会把 -Xmx64m 当成主类名（"Could not find or load main class -Xmx64m"）。
# 本脚本改为直接用 java 调起 gradle-wrapper.jar，行为与 gradlew 等价。
#
# 用法： tools/gradle-local.sh applyAllPatches --stacktrace
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$HOME/.harmonybrew/opt/openjdk@25}
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=$(command -v java)
exec "$JAVA_BIN" -Xmx64m -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
