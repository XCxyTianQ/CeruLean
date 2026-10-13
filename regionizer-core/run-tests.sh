#!/bin/sh
# 编译并运行 regionizer-core 的测试。
# 纯 JDK：不依赖 gradle、不依赖任何第三方库（这是 C1 的验收要求之一）。
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
JH=${JAVA_HOME:-$HOME/.harmonybrew/opt/openjdk@25}
if [ ! -x "$JH/bin/javac" ]; then
    echo "找不到 JDK：$JH（可设 JAVA_HOME 覆盖）" >&2
    exit 2
fi
OUT="$DIR/build/classes"
rm -rf "$OUT"
mkdir -p "$OUT"
echo "== 编译 main（-Xlint:all -Werror）"
"$JH/bin/javac" -Xlint:all -Werror -d "$OUT" $(find "$DIR/src/main/java" -name '*.java')
echo "== 编译 test"
"$JH/bin/javac" -cp "$OUT" -d "$OUT" $(find "$DIR/src/test/java" -name '*.java')
echo "== 运行"
"$JH/bin/java" -cp "$OUT" io.cerulean.regionizer.test.AllTests
