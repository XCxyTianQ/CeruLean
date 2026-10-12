#!/bin/sh
# 在不稳定网络下重试推送（本机到 GitHub 的连接会间歇性中断）
# 用法: tools/push-retry.sh [remote] [branch]   默认 origin main
R=${1:-origin}; B=${2:-main}; i=0
while [ "$i" -lt 8 ]; do
    i=$((i+1))
    echo "== 第 $i 次尝试: git push $R $B"
    if git push "$R" "$B"; then echo "== 推送成功"; exit 0; fi
    echo "== 失败，10 秒后重试（git 具备断点续传式的对象复用，已传对象不会重传）"
    sleep 10
done
echo "== 连续 8 次失败：请检查代理（当前 HTTP(S)_PROXY=$HTTPS_PROXY）"
exit 1
