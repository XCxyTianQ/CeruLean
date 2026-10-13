#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B1 调度探针：验证"区域确实被调度器 tick 了"，并给出调度统计。

判据（与调度模式无关，因此同一脚本可用于 serial 与 edf）：
  1. `/cerulean regions` 的调度摘要行存在且模式与配置一致；
  2. 压载（forceload）后，每个区域都有拓扑 tick 计数 > 0 ⇒ 区域真的被调度到；
  3. catch-up 计数不异常增长（准点调度时应当很少）——只在摘要里报告，不做硬断言；
  4. 归属闸门在 strict 下仍不应误杀（可选：由外层脚本控制）。

输出末尾机器可读行：MODE=... REGIONS=... TICKED=... MAX_CATCHUP=...
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
CONFIG = os.environ.get("CERULEAN_CONFIG", "./smoke-b1-report/config/paper-global.yml")
FAILURES = []


def check(name, cond, detail=""):
    print(f"  {'✓' if cond else '✗'} {name}" + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAILURES.append(name)


def read_yaml(path, key):
    inside = False
    for line in open(path, encoding="utf-8").read().split("\n"):
        if re.match(r"^cerulean-regions:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line.startswith((" ", "\t")):
                break
            m = re.match(r"^\s+" + key + r":\s*(\S+)\s*$", line)
            if m:
                return m.group(1).strip().strip("'\"")
    return None


def main():
    print("== B1 调度探针")
    mode = read_yaml(CONFIG, "scheduler") or "serial"
    print(f"  配置调度模式: {mode}")
    with Rcon(host=HOST, port=PORT, password=PASSWORD, timeout=60.0, quiet=True) as r:
        # 制造若干区域：三块互不相邻的区块区（相距远超 mergeRadius）
        r.cmd("forceload remove all", first_wait=30.0)
        time.sleep(2)
        for (x, z) in [(0, 0), (8192, 0), (0, 8192)]:
            r.cmd(f"forceload add {x} {z} {x + 255} {z + 255}", first_wait=90.0)
        time.sleep(6)                      # 让调度器跑若干轮

        text = r.cmd("cerulean regions") or ""
        scheduler_line = next((l.strip() for l in text.split("\n")
                              if "调度" in l), "")
        check("命令回显调度摘要", bool(scheduler_line), scheduler_line[:120])
        check("调度模式与配置一致",
              (f"串行调度" in scheduler_line) if mode == "serial" else ("EDF" in scheduler_line),
              scheduler_line[:120])

        region_lines = [l for l in text.split("\n") if re.search(r"#\d+ \w+ 段 \d+", l)]
        ticked = [l for l in region_lines if re.search(r"拓扑 tick (\d+)", l)
                  and int(re.search(r"拓扑 tick (\d+)", l).group(1)) > 0]
        check("存在区域", len(region_lines) > 0, f"{len(region_lines)} 个区域")
        check("所有区域都被调度到（拓扑 tick > 0）", len(ticked) == len(region_lines) and region_lines,
              f"{len(ticked)}/{len(region_lines)}")

        tick_counts = [int(re.search(r"拓扑 tick (\d+)", l).group(1)) for l in region_lines] or [0]
        print(f"  各区域 tick 次数: {tick_counts}")
        r.cmd("forceload remove all", first_wait=30.0)
    print()
    print(f"MODE={mode} REGIONS={len(region_lines)} TICKED={len(ticked)} MAXTICKS={max(tick_counts)}")
    if FAILURES:
        print(f"== 失败 {len(FAILURES)} 项: {', '.join(FAILURES)}")
        return 1
    print("== 全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
