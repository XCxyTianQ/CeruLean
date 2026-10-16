#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1 验收：接 Folia/Paper **自带**的可观测面 + 我们自己的自检命令。

L0 只回答"基座活着吗"；L1 回答"基座把自己的状态报出来了吗/有没有在报警"。
这里刻意**尽量用上游能力**（区域级 TPS、spark、watchdog 日志），
只把上游没有的诊断（区域拓扑、投递/EXP 自检）算作我们自己的补充。

用法（需服务端已起且 RCON 就绪）：
    python3 tools/smoke/l1_observability_probe.py [日志路径]
"""
import sys

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

LOG = sys.argv[1] if len(sys.argv) > 1 else 'smoke-run/server-console.log'
FAIL, OK = [], []


def one(cmd, wait=3.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=90.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def check(label, cmd, expect, wait=3.0):
    got = one(cmd, wait=wait)
    ok = bool(expect(got))
    (OK if ok else FAIL).append((label, cmd, got))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}\n         $ {cmd}\n         -> {got[:220]}")
    return got


def ok_parse(got):
    return 'Unknown or incomplete' not in got and '<--[HERE]' not in got


print("== L1 可观测面")

# ── 上游自带：区域级 TPS（Folia 的 0003/paper-0007 补丁把区域 TPS 接进 /tps）──
check('/tps 可用（区域级 TPS 是 Folia 自带的）', 'tps',
      lambda g: ok_parse(g) and ('TPS' in g or 'tps' in g or 'region' in g.lower()))
# ── 上游自带：spark（Paper/Folia 内置性能分析器）─────────────────────────
# 注意：**spark 是 Bukkit 插件命令，不经 Brigadier**，RCON 的 performPrefixedCommand
# 派发不到它（实测 "Unknown or incomplete command"；`save-all` 同理）。
# 所以这里改为断言"上游确实内置了 spark"这个控制台可观测事实，而不是发命令。
try:
    _log = open(LOG, encoding='utf-8', errors='replace').read()
except OSError:
    _log = ''
_has_spark = 'bundles the spark profiler' in _log
(OK if _has_spark else FAIL).append(('spark profiler 已内置（上游能力）', LOG, _has_spark))
print(f"  [{'OK  ' if _has_spark else 'FAIL'}] spark profiler 已内置（控制台 'bundles the spark profiler'）")
# ── 上游自带：Paper 命令面 ─────────────────────────────────────────────
check('/paper 命令面可用', 'paper', lambda g: ok_parse(g) and len(g) > 0)

# ── 我们自己的诊断：区域拓扑 ───────────────────────────────────────────
check('/cerulean regions 报告区域拓扑与调度线程数', '/cerulean regions',
      lambda g: 'minecraft:overworld: 区域' in g and '调度线程' in g)
check('/cerulean regionat 定位区块所属区域', '/cerulean regionat 6 0',
      lambda g: '覆盖' in g)
# ── 我们自己的诊断：投递与 EXP 链自检 ─────────────────────────────────
check('/cerulean dispatch 输出投递自检（含 fastFails 计数）', '/cerulean dispatch',
      lambda g: 'callBlocking=' in g and 'fastFails=' in g)
check('/cerulean exp 输出 EXP 链自检（含登记/挂起计数）', '/cerulean exp',
      lambda g: '挂起' in g and '登记' in g)

# ── watchdog：有没有区域在报警（Folia 自带，写在控制台）──────────────────
try:
    log = open(LOG, encoding='utf-8', errors='replace').read()
except OSError:
    log = ''
stalls = [l for l in log.splitlines() if 'has not responded in' in l]
(OK if not stalls else FAIL).append(('watchdog 无区域停摆告警', LOG, len(stalls)))
print(f"  [{'OK  ' if not stalls else 'FAIL'}] watchdog 无区域停摆告警（'has not responded in' 行数={len(stalls)}）")

print()
print(f"== 小结（L1）：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, cmd, got in FAIL:
    print(f"  FAIL {label}: {str(got)[:160]}")
sys.exit(1 if FAIL else 0)
