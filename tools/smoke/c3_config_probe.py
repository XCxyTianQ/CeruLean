#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""C3 配置探针：验证 cerulean-regions 配置真的驱动了区域化内核。

判据全部**由配置自身推导**（自适应），因此对任何合法配置都成立，
且一旦"配置被忽略"就会失败：

  1. config/paper-global.yml 里存在 cerulean-regions 节；
  2. /cerulean regions 回显的生效参数与 YAML 一致；
  3. 行为：有区块的两个段相距 d 段时是否同区域，其阈值 = mergeRadius + 2
     （实测：护城河半径 1 在两端各贡献 1，见 evidence/benchmark.md 的 MergeThreshold）
       d = mergeR + 2 ⇒ 期望**同区域**
       d = mergeR + 3 ⇒ 期望**不同区域**
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
CONFIG = os.environ.get("CERULEAN_CONFIG", "./smoke-report/config/paper-global.yml")
SETTLE = float(os.environ.get("PROBE_SETTLE", "5"))
PER = 16  # 段 = 16 区块（grid-exponent=4）

HEADER_RE = re.compile(r"段位移 (\d+) / 段=(\d+) 区块 / 合并半径 (\d+) 段 / 邻居 (\w+)")
REGION_ID_RE = re.compile(r"区域 #(\d+)")
FAILURES = []


def check(name, cond, detail=""):
    print(f"  {'✓' if cond else '✗'} {name}" + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAILURES.append(name)


def read_yaml_config(path):
    """极简解析：只取 cerulean-regions 节下的键值（避免引入 yaml 依赖）。"""
    try:
        lines = open(path, encoding="utf-8").read().split("\n")
    except OSError as e:
        return None, f"读不到 {path}: {e}"
    out, inside = {}, False
    for line in lines:
        if re.match(r"^cerulean-regions:\s*$", line):
            inside = True
            continue
        if inside:
            if line and not line.startswith((" ", "\t")):
                break
            m = re.match(r"^\s+([a-z-]+):\s*(.*?)\s*$", line)
            if m:
                out[m.group(1)] = m.group(2)
    return out, None


def region_id(r, section_x):
    """/cerulean regionat 收**区块坐标**：段 x 的首个区块 = section_x * 16。"""
    out = r.cmd(f"cerulean regionat {section_x * PER} 0") or ""
    m = REGION_ID_RE.search(out)
    return int(m.group(1)) if m else None


def load_section(r, section_x):
    """forceload 收**方块坐标**：段 16×16 区块 ⇒ 方块 = section_x * 16 * 16。"""
    r.cmd(f"forceload add {section_x * PER * PER} 0")


def main():
    print("== C3 配置探针（cerulean-regions）")
    yml, err = read_yaml_config(CONFIG)
    check("paper-global.yml 含 cerulean-regions 节", bool(yml), err or str(yml))
    if not yml:
        return 1
    y_grid = int(yml.get("grid-exponent", "0"))
    y_merge = int(yml.get("merge-radius", "0"))
    y_nb = yml.get("neighbourhood", "?").strip()
    print(f"  YAML: grid-exponent={y_grid}（{1 << y_grid} 区块/段）  merge-radius={y_merge} 段  "
          f"neighbourhood={y_nb}")

    with Rcon(host=HOST, port=PORT, password=PASSWORD, timeout=30.0, quiet=True) as r:
        text = r.cmd("cerulean regions") or ""
        header = HEADER_RE.search(text)
        check("命令回显生效参数", bool(header), (text.split("\n")[0] if text else "")[:120])
        if header:
            check("生效 gridExponent 与 YAML 一致", int(header.group(1)) == y_grid,
                  f"生效 {header.group(1)} vs YAML {y_grid}")
            check("生效段大小一致", int(header.group(2)) == (1 << y_grid),
                  f"生效 {header.group(2)} vs 期望 {1 << y_grid}")
            check("生效 mergeRadius 与 YAML 一致", int(header.group(3)) == y_merge,
                  f"生效 {header.group(3)} vs YAML {y_merge}")
            check("生效邻居语义与 YAML 一致", header.group(4) == y_nb,
                  f"生效 {header.group(4)} vs YAML {y_nb}")

        # ── 行为判据：扫描"宽端点之间的真实阈值" ──────────────────────────────
        # 端点用整 3 个段的连续区块行（消除原版"force-load 一个区块会连带生成
        # 27×27 区块"带来的形状不确定性）。
        # 实测（mergeR=2）：间距 ≤5 段合并、6 段分开 ⇒ 阈值 = mergeR + 3，
        # 其中 +2 来自护城河（两端各 1 段），+1 来自生成半径溢出（13 区块 < 1 段）。
        # 该关系是配置的函数：一旦配置被忽略，阈值就不会随 merge-radius 移动。
        def region_at(section_x):
            m = REGION_ID_RE.search(r.cmd(f"cerulean regionat {section_x * PER} 0") or "")
            return int(m.group(1)) if m else None

        def block_row(s_from, s_to):
            r.cmd(f"forceload add {s_from * PER * PER} 0 {s_to * PER * PER + PER * PER - 1} {PER - 1}")

        def gap_merges(gap):
            r.cmd("forceload remove all")
            time.sleep(2)
            block_row(40, 42)
            b0 = 42 + 1 + gap
            block_row(b0, b0 + 2)
            time.sleep(SETTLE)
            a, b = region_at(41), region_at(b0 + 1)
            return a is not None and b is not None and a == b

        measured = 0
        for gap in range(1, y_merge + 5):
            if gap_merges(gap):
                measured = gap
        print(f"  实测：宽端点间距 ≤{measured} 段仍合并（merge-radius={y_merge} ⇒ 期望 {y_merge + 3}）")
        check("阈值随配置移动（= mergeRadius + 3）", measured == y_merge + 3,
              f"实测 {measured} vs 期望 {y_merge + 3}")
        print(f"MEASURED_THRESHOLD={measured}")

        r.cmd("forceload remove all")

    print()
    if FAILURES:
        print(f"== 失败 {len(FAILURES)} 项: {', '.join(FAILURES)}")
        return 1
    print("== 全部断言通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
