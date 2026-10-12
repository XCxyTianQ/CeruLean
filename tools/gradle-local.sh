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

# paperweight 在应用 features 补丁时会在生成出来的源码仓库里 git commit，
# 必须提供提交者身份，否则报 "Committer identity unknown" 而失败。
# 这里用环境变量注入（可用外部同名变量覆盖），不改动机器的全局 git 配置。
# 同时用 GIT_CONFIG_GLOBAL 指向仓库内的身份文件：Gradle daemon 会用它启动子进程，
# 目标进程是长期存活的，只有配置文件方式对"已经启动的 daemon"也生效。
export GIT_CONFIG_GLOBAL=${GIT_CONFIG_GLOBAL:-"$DIR/tools/git-identity.gitconfig"}
export GIT_AUTHOR_NAME=${GIT_AUTHOR_NAME:-CeruLean Build}
export GIT_AUTHOR_EMAIL=${GIT_AUTHOR_EMAIL:-build@cerulean.local}
export GIT_COMMITTER_NAME=${GIT_COMMITTER_NAME:-CeruLean Build}
export GIT_COMMITTER_EMAIL=${GIT_COMMITTER_EMAIL:-build@cerulean.local}
exec "$JAVA_BIN" -Xmx64m -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
