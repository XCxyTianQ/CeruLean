#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B2 归属校验探针：验证"闸门真的会按策略放行/拒绝"。

做法：读配置里的 ownership-check 模式 → forceload 一个区块 → 运行
`/cerulean ownership verify <chunkX> <chunkZ>`（该命令把区域临时绑到假线程，
再让命令线程走真正的 TickThread.ensureTickThread），然后断言结论与策略一致：

  off    → 归属层允许、ensureTickThread 未抛异常（与 Paper 行为一致）
  soft   → 允许，但 /cerulean ownership 应记录到"将会违规"≥1 次
  strict → 归属层拒绝、ensureTickThread 抛 IllegalStateException

退出码 0 = 全部断言通过。
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon  # noqa: E402

HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))
PASSWORD = os.environ.get("RCON_PASSWORD", "test1234")
CONFIG = os.environ.get("CERULEAN_CONFIG", "./smoke-ownership-report/config/paper-global.yml")
CHUNK_X, CHUNK_Z = 160, 0          # 段 10 内的一个区块
FAILURES = []


def check(name, cond, detail=""):
    print(f"  {'✓' if cond else '✗'} {name}" + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAILURES.append(name)


def norm(value):
    """去掉 YAML 给字符串加的引号（configurate 会写成 'off'）。"""
    return value.strip().strip("'\"")


def read_mode(path):
    lines = open(path, encoding="utf-8").read().split("\n")
    inside = False
    for line in lines:
        if re.match(r"^cerulean-regions:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line.startswith((" ", "\t")):
                break
            m = re.match(r"^\s+ownership-check:\s*(\S+)\s*$", line)
            if m:
                return m.group(1).strip()
    return None


def main():
    print("== B2 归属校验探针")
    mode = read_mode(CONFIG)
    check("能读到 cerulean-regions.ownership-check", mode is not None, str(mode))
    if mode is None:
        return 1
    mode = norm(mode)
    print(f"  配置模式: {mode}")

    with Rcon(host=HOST, port=PORT, password=PASSWORD, timeout=30.0, quiet=True) as r:
        summary = r.cmd("cerulean ownership") or ""
        # 汇总里打印的是大写枚举名（OFF/SOFT/STRICT）
        check("命令回显策略与配置一致", f"归属校验: {mode.upper()}" in summary.upper(),
              summary.strip()[:110])

        r.cmd("forceload remove all")
        time.sleep(2)
        r.cmd(f"forceload add {CHUNK_X * 16} {CHUNK_Z * 16}")
        time.sleep(5)

        out = r.cmd(f"cerulean ownership verify {CHUNK_X} {CHUNK_Z}") or ""
        line = next((l.strip() for l in out.split("\n") if "归属自检" in l), out.strip())
        print(f"  verify 输出: {line[:150]}")
        expected_allow = mode in ("off", "soft")
        check(f"归属层{'允许' if expected_allow else '拒绝'}",
              ("归属层=允许" if expected_allow else "归属层=拒绝") in line, line[:110])
        if expected_allow:
            check("ensureTickThread 未抛异常（策略放行）", "未抛异常" in line, line[:110])
        else:
            check("ensureTickThread 抛出 IllegalStateException（闸门生效）",
                  "抛出 IllegalStateException" in line, line[:110])

        if mode == "soft":
            summary2 = r.cmd("cerulean ownership") or ""
            m = re.search(r"将会违规 (\d+) 次", summary2)
            check("soft 模式记录了“将会违规”现场", m is not None and int(m.group(1)) >= 1,
                  (m.group(0) if m else summary2.strip()[:110]))
        if mode == "off":
            summary2 = r.cmd("cerulean ownership") or ""
            check("off 模式下没有任何记录（零成本、零行为变更）", "将会违规" not in summary2,
                  summary2.strip()[:110])

        r.cmd("forceload remove all")

    print()
    if FAILURES:
        print(f"== 失败 {len(FAILURES)} 项: {', '.join(FAILURES)}")
        return 1
    print("== 全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
