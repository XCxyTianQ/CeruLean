#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""EXP6Plus OCC 用例探针（以 AzureBranches 的 exp6plus-smoke.py 为参照改写）。

上游三个用例（`exp6plus-smoke.py` C1/C2/C3）测的是 **OCC 在真实冲突下的行为**：
  C1  实体存活 → 校验 COMMIT（相位不重放）
  C2  实体在挂起期间死亡 → 校验 RETRY（重放 Phase，但 impulse 头块只跑一次）
  C3  重试补偿后，死实体**不得**残留分数条目（Folia 禁用了 vanilla 的 entityRemoved，
      靠 EntityLayer/ScoreLayer 的补偿主动清除 —— 即"幽灵分数"防护）

改写要点：
  * 触发方式沿用上游：沿 -z 摆一串 `facing=north` 的命令方块，链块带 `auto:1b`
    （`isAutomatic()` 为真 ⇒ 不必逐个通电），头块用相邻红石块触发；
  * 标记沿用上游的 `say`（实测：RCON 与命令方块的 say 都会进服务端控制台日志）；
  * **断言比上游更硬**：除了日志标记，还直接从 `/cerulean exp` 读 OCC 计数器
    （校验 通过/重试/耗尽、挂起/恢复），不依赖"日志里数了几次"这种间接推断。

前置：服务端已起、`command_blocks.mode=EXP`、`command_blocks.exp.validation.enabled=true`。

用法：
    python3 tools/smoke/exp6plus_occ_probe.py [日志路径]
"""
import re
import sys
import time

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

LOG = sys.argv[1] if len(sys.argv) > 1 else 'smoke-exp/server-console.log'
FAIL, OK = [], []
Z = '@e[type=minecraft:zombie,limit=1]'
UUID = '00000001-0000-0002-0000-000300000004'
Y = 65


def one(cmd, wait=3.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=90.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def record(label, detail, ok):
    (OK if ok else FAIL).append((label, detail))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}  -> {detail}")


def counters():
    """从 /cerulean exp 读 OCC 计数器。"""
    out = one('/cerulean exp')
    c = {}
    m = re.search(r'通过 (\d+) / 重试 (\d+) / 耗尽 (\d+)', out)
    if m:
        c['passed'], c['retry'], c['exhausted'] = map(int, m.groups())
    m = re.search(r'挂起 (\d+) / 恢复 (\d+)', out)
    if m:
        c['suspend'], c['resume'] = map(int, m.groups())
    m = re.search(r'NBT写拦截 (\d+)', out)
    if m:
        c['nbt'] = int(m.group(1))
    m = re.search(r'补偿：计分 (\d+) / 失败 (\d+)', out)
    if m:
        c['comp'], c['compFail'] = map(int, m.groups())
    m = re.search(r'/ 竞争 (\d+)', out)
    if m:
        c['race'] = int(m.group(1))
    return c


def set_impulse(x, z, command):
    # 与上游同形：命令用双引号嵌在 SNBT 里，所以命令本身不能含双引号
    one(f'setblock {x} {Y} {z} minecraft:command_block[facing=north]{{Command:"{command}"}}')


def set_chain(x, z, command):
    one(f'setblock {x} {Y} {z} minecraft:chain_command_block[facing=north]{{Command:"{command}",auto:1b}}')


def clear_area(x):
    for z in range(6, -4, -1):
        one(f'setblock {x} {Y} {z} minecraft:air')


def fresh_zombie():
    one('kill @e[type=minecraft:zombie]')
    # ★ 冒险岛环境：冒烟服是超平坦世界，地面在 y≈-61，在 y=64 直接 summon 会**摔死**，
    # 于是链里 `@e[type=zombie,limit=1]` 解析不到实体（表现为分数写不进去、看起来像 OCC 误判）。
    # 垫一块落点支撑即可。注意**不能**用 Invulnerable —— C2 需要在相位内把实体打死。
    # ★ 僵尸高 1.95 格，站在 y=64 时占据 y=64~65.9。链线的命令方块在 (x,65,4..-1)，
    # 若把僵尸放在 (4,64,4)，它会被**脉冲命令方块窒息致死**（实测 isAlive()=false）。
    # 因此放在链线之外，(4,64,8)，并垫落点支撑（超平坦地面在 y≈-61，不垫会摔死）。
    one('setblock 4 63 8 minecraft:stone')
    one('summon minecraft:zombie 4 64 8 {NoAI:1b,PersistenceRequired:1b,UUID:[I;1,2,3,4]}')
    one(f'scoreboard players set {UUID} exp6p 0')


def log_tail(marker):
    try:
        text = open(LOG, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''
    idx = text.rfind(marker)
    return text[idx:] if idx >= 0 else text[-12000:]


def fire(x, seconds):
    """用相邻红石块触发头块，等 seconds 秒后拆掉。"""
    one(f'setblock {x} {Y} 5 minecraft:redstone_block')
    time.sleep(seconds)
    one(f'setblock {x} {Y} 5 minecraft:air')
    time.sleep(2)


print('== EXP6Plus OCC 用例（C1/C2/C3，参照 exp6plus-smoke.py）')
c0 = counters()
print(f"  起始计数器: {c0}")
mode_line = [l for l in one('/cerulean exp').split(' ⏎ ') if '模式' in l]
record('前置 · 处于 EXP 模式', mode_line[0] if mode_line else '?', bool(mode_line and 'EXP' in mode_line[0]))

marker = 'EXP6PLUS-OCC-BEGIN'
one(f'say {marker}')
for x in (4, 8, 12):
    clear_area(x)
one('forceload add 0 0')
one('time set midnight')
one('gamerule doDaylightCycle false')
one('weather clear')
one('kill @e[type=minecraft:zombie]')
one('scoreboard objectives remove exp6p')
one('scoreboard objectives add exp6p dummy')
time.sleep(1)

# ═══════════ C1：实体存活 → COMMIT（相位不重放）═══════════
clear_area(4)
set_impulse(4, 4, 'say P1-PHASE-START')
set_chain(4, 3, f'scoreboard players set {Z} exp6p 5')
set_chain(4, 2, f'execute if score {UUID} exp6p matches 5 run say P1-SET-OK')
set_chain(4, 1, 'setblock 10 64 10 minecraft:stone')     # 注册 future ⇒ 挂起 + 校验
set_chain(4, 0, f'execute if score {UUID} exp6p matches 5 run say P1-COMMIT-KEEP-5')
set_chain(4, -1, 'say P1-END')
fresh_zombie()
time.sleep(1)
c1_before = counters()
fire(4, 12)
lines = log_tail(marker)


def cnt(n):
    return lines.count(n)


record('C1 三个阶段标记齐全（SET-OK / COMMIT-KEEP-5 / END）',
       f"SET-OK={cnt('P1-SET-OK')} COMMIT-KEEP-5={cnt('P1-COMMIT-KEEP-5')} END={cnt('P1-END')}",
       cnt('P1-SET-OK') >= 1 and cnt('P1-COMMIT-KEEP-5') >= 1 and cnt('P1-END') >= 1)
record('C1 无重放（impulse 只跑一次）', f"PHASE-START={cnt('P1-PHASE-START')}",
       cnt('P1-PHASE-START') == 1)
c1_after = counters()
retry_delta = (c1_after.get('retry', 0) - c1_before.get('retry', 0))
record('C1 没有发生校验重试（自写自读不再被当成外部冲突）',
       f'retry {c1_before.get("retry")}→{c1_after.get("retry")}', retry_delta == 0)
one('setblock 10 64 10 minecraft:air')
clear_area(4)

# ═══════════ C4：NBT 域"自写自读"不得误判（与 C1 的方块/计分对称）═══════════
# /data get 把某个 NBT 路径记进读集，随后 /data merge 写同一路径 ⇒ 校验时必须排除自写键，
# 否则会被当成"非可重复读"而无谓重试（0028 修了方块/计分，0030 补 NBT）。
clear_area(4)
set_impulse(4, 4, 'say P4-PHASE-START')
set_chain(4, 3, f'data get entity {Z} CustomNameVisible')
set_chain(4, 2, f'data merge entity {Z} {{CustomNameVisible:1b}}')
set_chain(4, 1, 'setblock 10 64 10 minecraft:stone')
set_chain(4, 0, 'say P4-END')
fresh_zombie()
time.sleep(1)
c4_before = counters()
fire(4, 12)
lines = log_tail(marker)
record('C4 NBT 域 · 链路走完（/data get → /data merge → 校验）',
       f"P4-END={lines.count('P4-END')}", lines.count('P4-END') >= 1)
c4_after = counters()
record('C4 NBT 域 · 自写自读不被误判（无重试）',
       f'retry {c4_before.get("retry")}→{c4_after.get("retry")}',
       (c4_after.get('retry', 0) - c4_before.get('retry', 0)) == 0)
one('setblock 10 64 10 minecraft:air')
clear_area(4)

# ═══════════ C2：实体在挂起期间死亡 → RETRY ═══════════
clear_area(8)
set_impulse(8, 4, 'say P2-PHASE-START')
set_chain(8, 3, f'scoreboard players set {Z} exp6p 7')
set_chain(8, 2, f'execute as {Z} run damage @s 999999')  # 立即致死
set_chain(8, 1, 'setblock 10 64 10 minecraft:stone')     # 注册 future ⇒ 挂起 + 校验
set_chain(8, 0, f'execute if score {UUID} exp6p matches 7 run say P2-SET-OK')
set_chain(8, -1, 'say P2-END')
fresh_zombie()
time.sleep(1)
c2_before = counters()
fire(8, 15)
lines = log_tail(marker)
p2 = lines.count('P2-PHASE-START')
c2_after = counters()
retry_delta2 = (c2_after.get('retry', 0) - c2_before.get('retry', 0))
record('C2 impulse 仅一次（重放的是 Phase，不是头块）', f'PHASE-START={p2}', p2 == 1)
record('C2 校验发生了 RETRY（实体读集冲突被检出）',
       f'retry {c2_before.get("retry")}→{c2_after.get("retry")} (Δ{retry_delta2})', retry_delta2 >= 1)
one('setblock 10 64 10 minecraft:air')
clear_area(8)

# ═══════════ C3：幽灵分数防护 ═══════════
clear_area(12)
set_impulse(12, 4, 'say P3-START')
set_chain(12, 3, f'execute unless score {UUID} exp6p matches -2147483648..2147483647 run say P3-NO-ENTRY')
set_chain(12, 2, f'execute if score {UUID} exp6p matches 5 run say P3-GHOST-5')
set_chain(12, 1, f'execute if score {UUID} exp6p matches 7 run say P3-GHOST-7')
set_chain(12, 0, 'say P3-END')
time.sleep(1)
fire(12, 6)
lines = log_tail(marker)
ghost5, ghost7, noentry = lines.count('P3-GHOST-5'), lines.count('P3-GHOST-7'), lines.count('P3-NO-ENTRY')
record('C3 无幽灵分数（补偿清除了死实体条目）',
       f'NO-ENTRY={noentry} GHOST-5={ghost5} GHOST-7={ghost7}',
       noentry >= 1 and ghost5 == 0 and ghost7 == 0)

# 收尾断言：OCC 机制"干净收场"，且补偿/竞争的可观测性可用
final = counters()
record('收尾 · 校验没有失控（耗尽 = 0）', f"exhausted={final.get('exhausted')}", final.get('exhausted', 0) == 0)
record('收尾 · 补偿与竞争计数可见（回滚路径真的被走过）',
       f"补偿={final.get('comp')} 失败={final.get('compFail')} 竞争={final.get('race')}",
       'comp' in final and 'race' in final and (final.get('comp', 0) + final.get('compFail', 0)) >= 1)
record('收尾 · 至少两次校验通过（C1 COMMIT + 至少一次收尾 COMMIT）',
       f"通过={final.get('passed')}", final.get('passed', 0) >= 2)

# 收尾
for x in (4, 8, 12):
    clear_area(x)
one('setblock 10 64 10 minecraft:air')
one('kill @e[type=minecraft:zombie]')
one('forceload remove 0 0')

print()
print(f"== 小结（EXP6Plus OCC）：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, detail in FAIL:
    print(f"  FAIL {label}: {detail}")
sys.exit(1 if FAIL else 0)
