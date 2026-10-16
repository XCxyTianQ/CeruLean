#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Folia 基座验收探针：证明（1）服务端在 Folia 基座上能起来、（2）区域线程真的在跑、
（3）我们收缩后的 /cerulean 命令可用、（4）区域拓扑真的随区块加载变化。"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon  # noqa: E402

FAIL = []


def check(name, cond, detail=""):
    print(f"  {'✓' if cond else '✗'} {name}" + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAIL.append(name)


def main():
    print("== Folia 基座验收探针")
    with Rcon(host="127.0.0.1", port=int(os.environ.get("RCON_PORT", "25575")),
              password=os.environ.get("RCON_PASSWORD", "test1234"), timeout=60.0, quiet=True) as r:
        out = r.cmd("cerulean regions") or ""
        print("  /cerulean regions →\n    " + "\n    ".join(out.strip().split("\n")[:6]))
        m = re.search(r"调度线程 (\d+)", out)
        threads = int(m.group(1)) if m else -1
        check("区域调度线程数 > 0（Folia 的 TickRegionScheduler 在跑）", threads > 0, f"线程 {threads}")
        check("命令报告了各 world 的区域数与段参数", "区域" in out and "段" in out, out.strip().split("\n")[-1][:90])

        r.cmd("forceload remove all", first_wait=30.0)
        time.sleep(2)
        r.cmd("forceload add 0 0 255 255", first_wait=90.0)          # 造一个区域
        time.sleep(6)
        after = r.cmd("cerulean regions") or ""
        regions_after = [int(x) for x in re.findall(r"区域 (\d+)", after)]
        check("区块加载后 overworld 出现区域", bool(regions_after) and max(regions_after) > 0,
              f"各 world 区域数 {regions_after}")

        at = r.cmd("cerulean regionat 8 8") or ""
        print("  /cerulean regionat 8 8 → " + at.strip()[:100])
        check("regionat 能定位到区域", "区域" in at and "无区域" not in at, at.strip()[:90])
        # 注意：computeForRegions 会按 merge/create 半径外扩（上游语义），
        # 远处区块仍可能落在某个区域的外扩范围内 ⇒ 这里只要求命令**能给出确定回答**，
        # 不再断言"必须无区域"（那是我最初的错误期望）。
        far = r.cmd("cerulean regionat 4000 4000") or ""
        check("未加载处的回答是确定的（无区域 / 被 N 个区域覆盖）",
              ("无区域" in far) or ("被" in far and "覆盖" in far), far.strip()[:90])
        r.cmd("forceload remove all", first_wait=30.0)
    print()
    if FAIL:
        print(f"== 失败 {len(FAIL)} 项: {', '.join(FAIL)}")
        return 1
    print("== Folia 基座验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
