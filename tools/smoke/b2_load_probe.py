#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B2 压载观测：在真实区块加载负载下，归属闸门会看到什么。

- `soft`：统计"将会违规"的调用现场（这是 B5 的工作清单，按频次排序）；
- `strict`：观察当前架构在严格模式下能否存活（不能则给出首个违规现场）；
- 同时记录压载耗时，用于粗看归属检查（开启时）的代价。

用法: python3 b2_load_probe.py
输出末尾有机器可读行：MODE=... VIOLATIONS=... SITES=... LOAD_SECONDS=... SURVIVED=...
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

# 4 个角 + 中心各 16×16=256 区块（forceload 上限 256/次），循环 3 轮加卸载
REGIONS = [(0, 0), (4096, 0), (0, 4096), (4096, 4096), (2048, 2048)]
CYCLES = 3
SIZE = 255          # 方块跨度 → 16×16 区块


def read_mode(path):
    inside = False
    for line in open(path, encoding="utf-8").read().split("\n"):
        if re.match(r"^cerulean-regions:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line.startswith((" ", "\t")):
                break
            m = re.match(r"^\s+ownership-check:\s*(\S+)\s*$", line)
            if m:
                return m.group(1).strip().strip("'\"")
    return "?"


def main():
    mode = read_mode(CONFIG)
    print(f"== B2 压载观测（ownership-check = {mode}）")
    survived = True
    with Rcon(host=HOST, port=PORT, password=PASSWORD, timeout=60.0, quiet=True) as r:
        r.cmd("forceload remove all")
        time.sleep(2)
        start = time.time()
        try:
            for _ in range(CYCLES):
                for (x, z) in REGIONS:
                    # 大范围 forceload 要现生成区块，可能耗时数秒 ⇒ 首个响应包给 90s
                    r.cmd(f"forceload add {x} {z} {x + SIZE} {z + SIZE}", first_wait=90.0)
                time.sleep(2)
                r.cmd("forceload remove all", first_wait=30.0)
                time.sleep(2)
        except Exception as ex:                      # strict 下服务器可能在压载中挂掉
            survived = False
            print(f"  ✗ 压载过程中通道断开: {type(ex).__name__}: {ex}")
        load_seconds = time.time() - start
        regions_line, ownership_line = "", ""
        if survived:
            try:
                regions_line = (r.cmd("cerulean regions") or "").strip()
                ownership_line = (r.cmd("cerulean ownership") or "").strip()
                r.cmd("forceload remove all")
            except Exception as ex:
                survived = False
                print(f"  ✗ 压载后查询失败: {type(ex).__name__}: {ex}")
        if regions_line:
            print("  压载后拓扑: " + " / ".join(regions_line.split("\n")[1:3]).strip()[:160])
        violations = 0
        sites = 0
        if ownership_line:
            m = re.search(r"将会违规 (\d+) 次 / 不同现场 (\d+) 个", ownership_line)
            if m:
                violations, sites = int(m.group(1)), int(m.group(2))
            print("  " + ownership_line.split("\n")[0][:160])
    print()
    print(f"MODE={mode} VIOLATIONS={violations} SITES={sites} "
          f"LOAD_SECONDS={load_seconds:.1f} SURVIVED={1 if survived else 0}")
    return 0 if survived or mode != "strict" else 1


if __name__ == "__main__":
    sys.exit(main())
