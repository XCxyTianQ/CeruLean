#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""C2 验收探针：在真服务端上验证区域拓扑（切分 + 合并 + 串行 tick）。

判据用**区域 id 比对**而不是"区域总数"，因为出生点区域本来就可能已加载一大片。

段 = 16×16 区块，合并半径 = 2 段（=32 区块）。取远离出生点的段：
  · 段 10（区块 160，方块 2560）
  · 段 16（区块 256，方块 4096）          相距 6 段 > 2 ⇒ 必须是【两个不同区域】
  · 段 12（方块 3072）与段 14（方块 3584）作为桥接：10-(2)-12-(2)-14-(2)-16 连成链
                                          ⇒ 两者应【并成同一区域】
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
SETTLE = float(os.environ.get("PROBE_SETTLE", "5"))

REGION_ID_RE = re.compile(r"区域 #(\d+)")
TICKS_RE = re.compile(r"拓扑 tick (\d+)")
FAILURES = []


def check(name, cond, detail=""):
    print(f"  {'✓' if cond else '✗'} {name}" + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAILURES.append(name)


def region_id(r, chunk_x, chunk_z):
    out = r.cmd(f"cerulean regionat {chunk_x} {chunk_z}") or ""
    m = REGION_ID_RE.search(out)
    return (int(m.group(1)) if m else None), out.strip()


def main():
    print("== C2 区域拓扑探针（判据：区域 id 比对）")
    with Rcon(host=HOST, port=PORT, password=PASSWORD, timeout=30.0, quiet=True) as r:
        r.cmd("forceload remove all")
        time.sleep(2)

        # 两处相距 6 段的位置
        r.cmd("forceload add 2560 0")     # 段 10
        r.cmd("forceload add 4096 0")     # 段 16
        time.sleep(SETTLE)
        id_a, line_a = region_id(r, 160, 0)
        id_b, line_b = region_id(r, 256, 0)
        print(f"  段 10 所在区域: #{id_a}   （{line_a[:70]}）")
        print(f"  段 16 所在区域: #{id_b}   （{line_b[:70]}）")
        check("两处相距 6 段 ⇒ 落在不同区域", id_a is not None and id_b is not None and id_a != id_b,
              f"#{id_a} vs #{id_b}")

        text = r.cmd("cerulean regions") or ""
        check("区域统计可读", "区域拓扑" in text)
        check("区块计数 > 0（chunk holder 生命周期已接入）",
              bool(re.search(r"区块 (\d+)", text)) and max(int(x) for x in re.findall(r"/ 区块 (\d+)", text)) > 0)
        ticks = [int(x) for x in TICKS_RE.findall(text)]
        check("串行拓扑 tick 已在驱动（存在 tick 计数 > 0）", any(t > 0 for t in ticks), f"ticks={ticks[:6]}")

        # 桥接：段 12 与段 14
        r.cmd("forceload add 3072 0")
        r.cmd("forceload add 3584 0")
        time.sleep(SETTLE)
        id_a2, _ = region_id(r, 160, 0)
        id_b2, _ = region_id(r, 256, 0)
        print(f"  桥接后: 段 10 → #{id_a2}，段 16 → #{id_b2}")
        check("加入两个桥接段后合并为同一区域", id_a2 is not None and id_a2 == id_b2,
              f"#{id_a2} vs #{id_b2}（此前 #{id_a} vs #{id_b}）")

        text2 = r.cmd("cerulean regions") or ""
        check("回调计数里出现合并", bool(re.search(r"合并 [1-9]", text2)),
              (re.search(r"回调计数: .*", text2) or [""])[0][:90] if isinstance(re.search(r"回调计数: .*", text2), re.Match) else "")

        # 断开：移除桥接段（forceload remove 会让区块卸载 → 段消亡）
        r.cmd("forceload remove 3072 0")
        r.cmd("forceload remove 3584 0")
        time.sleep(SETTLE)
        id_a3, _ = region_id(r, 160, 0)
        id_b3, _ = region_id(r, 256, 0)
        print(f"  断开桥接后: 段 10 → #{id_a3}，段 16 → #{id_b3}")
        check("移除桥接段后重新分裂为两个区域", id_a3 is not None and id_b3 is not None and id_a3 != id_b3,
              f"#{id_a3} vs #{id_b3}")

        r.cmd("forceload remove all")

    print()
    if FAILURES:
        print(f"== 失败 {len(FAILURES)} 项: {', '.join(FAILURES)}")
        return 1
    print("== 全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
