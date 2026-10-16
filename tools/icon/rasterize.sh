#!/bin/sh
# 把 assets/icon.svg 渲染成 assets/icon.png（1024）与 assets/icon-256.png（256）。
#
# 为什么要自己写栅格化器：本机没有 rsvg-convert / ImageMagick / inkscape，
# 只有 cairo（harmonybrew 提供）。svg2png.c 是一个只认
# rect/circle/line/path/text 的极简 SVG 渲染器（够图标用）。
#
# 用法： tools/icon/rasterize.sh
set -e
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
BIN="$DIR/tools/icon/svg2png"

if [ ! -x "$BIN" ]; then
  echo "编译栅格化器 → tools/icon/svg2png"
  # rpath 必须显式加：harmonybrew 的 cairo 不在默认库搜索路径里，
  # 否则二进制能链上但**运行时**报 "Error loading shared library libcairo.so.2"（踩过）。
  gcc -O2 -o "$BIN" "$DIR/tools/icon/svg2png.c" \
      $(pkg-config --cflags --libs cairo) \
      -Wl,-rpath,"$(pkg-config --variable=libdir cairo)"
fi

python3 "$DIR/tools/icon/gen_icon.py"
"$BIN" "$DIR/assets/icon.svg"     "$DIR/assets/icon.png"     1024
"$BIN" "$DIR/assets/icon.svg"     "$DIR/assets/icon-256.png" 256
ls -l "$DIR/assets/icon.svg" "$DIR/assets/icon.png" "$DIR/assets/icon-256.png"
