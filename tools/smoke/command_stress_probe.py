#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""区域化命令 + EXP 链的混合压力测试。

目标：在**持续并发**下确认 0031 打开的命令集合（/tag /team /loot /function）
与 EXP 链（OCC）一起跑不会炸线程、不会死锁、不会让 OCC 计数器失控。

构造：
  * 4 条并列的命令方块链（x=4/12/20/28），每条 8 块，循环执行
    setblock → loot spawn → tag add/remove → scoreboard set/get → say；
  * 主循环用 RCON 反复触发这些链（每条链一个红石块脉冲），同时穿插
    RCON 侧的 /tag、/team、/loot、/scoreboard、/data 命令；
  * 结束后检查：服务器仍活着、无未捕获异常、/cerulean exp 计数器合理。

用法：python3 tools/smoke/command_stress_probe.py <日志路径> [轮数] [每轮间隔秒]
"""
import re
import sys
import time

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

LOG = sys.argv[1] if len(sys.argv) > 1 else 'smoke-stress/server-console.log'
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 20
PAUSE = float(sys.argv[3]) if len(sys.argv) > 3 else 0.5
FAIL, OK = [], []
XS = (4, 12, 20, 28)
Y = 65


def one(cmd, wait=0.25, first_wait=30.0):
    """RCON 客户端是**读到超时才返回**的 ⇒ wait 就是每次调用的挂钟成本。
    批量压测必须用小 wait（0.25s），否则几百次调用会变成几十分钟。"""
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=120.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=first_wait) or '').strip().replace('\n', ' | ')


def record(label, detail, ok):
    (OK if ok else FAIL).append((label, detail))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}  -> {detail[:120]}")


def build_chain(x):
    """一条 8 块的链：命令侧覆盖 setblock/loot/tag/scoreboard/get。"""
    for z in range(10, -6, -1):
        one(f'setblock {x} {Y} {z} minecraft:air')
    cmds = [
        f'say STRESS-{x}-BEGIN',
        'setblock 10 64 10 minecraft:stone',
        'loot spawn 4 70 8 loot minecraft:chests/simple_dungeon',
        'tag @e[type=minecraft:zombie,limit=1] add stress',
        'scoreboard players set @e[type=minecraft:zombie,limit=1] stressobj 5',
        'execute if score @e[type=minecraft:zombie,limit=1] stressobj matches 5 run say STRESS-OK',
        'tag @e[type=minecraft:zombie,limit=1] remove stress',
        f'say STRESS-{x}-END',
    ]
    one(f'setblock {x} {Y} 6 minecraft:command_block[facing=north]{{Command:"{cmds[0]}"}}')
    for i, c in enumerate(cmds[1:]):
        one(f'setblock {x} {Y} {5 - i} minecraft:chain_command_block[facing=north]{{Command:"{c}",auto:1b}}')


def fire(x):
    one(f'setblock {x} {Y} 7 minecraft:redstone_block')
    time.sleep(0.15)
    one(f'setblock {x} {Y} 7 minecraft:air')


print(f'== 区域化命令 + EXP 链混合压力测试（{ROUNDS} 轮 x {len(XS)} 条链，间隔 {PAUSE}s）')
one('forceload add 0 0')
one('gamerule doDaylightCycle false')
one('time set midnight')
one('kill @e[type=minecraft:zombie]')
one('kill @e[type=minecraft:item]')
one('scoreboard objectives remove stressobj')
one('scoreboard objectives add stressobj dummy')
for i, x in enumerate(XS):
    one(f'setblock {x} 63 8 minecraft:stone')
one('summon minecraft:zombie 4 64 8 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,UUID:[I;5,5,5,5]}')
time.sleep(1)

t0 = time.time()
chain_fires = 0
rcon_ops = 0
for rnd in range(ROUNDS):
    for x in XS:
        build_chain(x)
        fire(x)
        chain_fires += 1
    # 穿插 RCON 侧命令（全局区域线程）
    for cmd in (
        'tag @e[type=minecraft:zombie,limit=1] add rstress',
        'tag @e[type=minecraft:zombie,limit=1] remove rstress',
        'loot spawn 4 70 8 loot minecraft:chests/simple_dungeon',
        'scoreboard players add @e[type=minecraft:zombie,limit=1] stressobj 1',
        'team add stteam',
        'team remove stteam',
        'data get entity @e[type=minecraft:zombie,limit=1] Tags',
    ):
        one(cmd)
        rcon_ops += 1
    time.sleep(PAUSE)
elapsed = time.time() - t0

# 让尾部批次落地
time.sleep(3)

# ── 检查 1：服务器还活着 ──
alive = False
try:
    one('say STRESS-ALIVE', wait=0.5)
    alive = True
except Exception:
    alive = False
record('存活 · 高并发后服务器仍可响应', 'alive' if alive else 'no response', alive)

# ── 检查 2：计数器合理性 ──
out = one('/cerulean exp', wait=1.0)
def num(pat):
    m = re.search(pat, out)
    return int(m.group(1)) if m else -1
passed, retried, exhausted = num(r'通过 (\d+) / 重试 (\d+) / 耗尽 (\d+)'), num(r'重试 (\d+) / 耗尽'), num(r'耗尽 (\d+) / 冲突率')
passed = num(r'通过 (\d+)')
retried = num(r'/ 重试 (\d+)')
exhausted = num(r'/ 耗尽 (\d+)')
record('OCC · 没有校验耗尽（重试没有失控）', f'通过={passed} 重试={retried} 耗尽={exhausted}', exhausted == 0)
record('OCC · 校验确实在跑（通过 > 0）', f'通过={passed}', passed > 0)

# ── 检查 3：日志里没有致命异常 ──
try:
    log = open(LOG, encoding='utf-8', errors='replace').read()
except OSError:
    log = ''
IGNORE = ('Operating system not supported', 'No key layers in MapLike',
          'blocking a tick/region thread on cross-region work')
bad = [l for l in log.splitlines()
       if ('Exception' in l or 'ERROR' in l) and not any(ig in l for ig in IGNORE)]
record('日志 · 高并发下无未捕获异常', f'异常行={len(bad)}', len(bad) == 0)
for l in bad[:5]:
    print('     ' + re.sub(r'\x1b\[[0-9;]*m', '', l).strip()[:150])

# ── 清理 ──
for x in XS:
    for z in range(10, -6, -1):
        one(f'setblock {x} {Y} {z} minecraft:air')
one('setblock 10 64 10 minecraft:air')
one('kill @e[type=minecraft:item]')
one('kill @e[type=minecraft:zombie]')
one('forceload remove 0 0')

print()
print(f"== 吞吐：{chain_fires} 次链触发 + {rcon_ops} 次 RCON 命令 / {elapsed:.1f}s "
      f"≈ {(chain_fires + rcon_ops) / elapsed:.2f} ops/s")
print(f"== 小结（混合压力）：{len(OK)} 通过 / {len(FAIL)} 失败")
for k, d in FAIL:
    print(f"  FAIL {k}: {d[:110]}")
sys.exit(1 if FAIL else 0)
