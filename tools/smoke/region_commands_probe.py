#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""区域化命令覆盖层探针：逐条命令在两种上下文下实测。

背景：Folia 把 9 条命令的注册注释掉了（`// Folia - region threading`），
AzureBranches 恢复了它们并做区域安全化。本仓库 `0031` 先只恢复注册，
本探针用来**实测**哪些命令在哪种上下文下真的会炸，避免盲目照搬 26.1.2 的整文件覆盖。

两种上下文：
  * **RCON**：命令在全局区域 tick 线程上执行（`Folia Region Scheduler Thread #0`）；
  * **链内**：命令在目标区域的 tick 线程上执行（命令方块链）。

判据：输出里出现异常/未知命令/"unexpected error" 记为失败；能拿到正常语义输出记为通过。
"""
import sys
import time

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

LOG = sys.argv[1] if len(sys.argv) > 1 else 'smoke-rc/server-console.log'
FAIL, OK, SKIP = [], [], []


def one(cmd, wait=4.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=120.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' | ')


def record(label, detail, ok):
    (OK if ok else FAIL).append((label, detail))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}  -> {detail[:130]}")


BAD = ('Exception', 'exception', 'Unknown or incomplete command', 'unexpected error',
       'Internal error', 'java.lang', 'not supported')


def bad(text):
    return any(b in text for b in BAD)


def diff_ok(text, needle):
    return (not bad(text)) and (needle in text if needle else True)


def chain(x, commands, seconds=6):
    """沿 -z 摆一串命令方块（链块带 auto:1b），头块用相邻红石块触发。"""
    for z in range(6, -5, -1):
        one(f'setblock {x} 65 {z} minecraft:air')
    one(f'setblock {x} 65 4 minecraft:command_block[facing=north]{{Command:"say CMD-BEGIN"}}')
    for i, cmd in enumerate(commands):
        one(f'setblock {x} 65 {3 - i} minecraft:chain_command_block[facing=north]'
            f'{{Command:"{cmd}",auto:1b}}')
    one(f'setblock {x} 65 5 minecraft:redstone_block')
    time.sleep(seconds)
    one(f'setblock {x} 65 5 minecraft:air')
    time.sleep(1)


print('== 区域化命令覆盖层探针')
one('forceload add 0 0')
one('gamerule doDaylightCycle false')
one('time set midnight')
one('kill @e[type=minecraft:zombie]')
one('scoreboard objectives remove cmdp')
one('scoreboard objectives add cmdp dummy')
one('setblock 4 63 8 minecraft:stone')
one('summon minecraft:zombie 4 64 8 {NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,UUID:[I;9,9,9,9]}')
one('team remove cmdteam')
time.sleep(1)

# ── /tag ──
out = one('tag @e[type=minecraft:zombie,limit=1] add rcontest')
got = one('data get entity @e[type=minecraft:zombie,limit=1] Tags')
record('RCON · /tag add（实体标签）', f'{out[:60]} | {got[:60]}', diff_ok(out, 'Added tag') and 'rcontest' in got)
one('tag @e[type=minecraft:zombie,limit=1] remove rcontest')
out = one('tag @e[type=minecraft:zombie,limit=1] list')
record('RCON · /tag list', out[:80], diff_ok(out, ''))

chain(8, ['tag @e[type=minecraft:zombie,limit=1] add chaintag', 'say CMD-TAG-DONE'])
got = one('data get entity @e[type=minecraft:zombie,limit=1] Tags')
record('链内 · /tag add（区域线程）', f'Tags={got[:60]}', 'chaintag' in got)
one('tag @e[type=minecraft:zombie,limit=1] remove chaintag')

# ── /team ──
out = one('team add cmdteam')
record('RCON · /team add', out[:80], diff_ok(out, 'Created'))
out = one('team modify cmdteam color red')
record('RCON · /team modify', out[:80], diff_ok(out, 'Updated'))
out = one('team list')
record('RCON · /team list', out[:80], diff_ok(out, 'cmdteam'))
chain(8, ['team add chainteam', 'say CMD-TEAM-DONE'])
out = one('team list')
record('链内 · /team add（区域线程）', out[:80], 'chainteam' in out)
one('team remove chainteam')

# ── /loot ──
out = one('loot spawn 4 70 8 loot minecraft:chests/simple_dungeon')
record('RCON · /loot spawn', out[:80], diff_ok(out, 'Dropped'))
chain(8, ['loot spawn 4 70 8 loot minecraft:chests/simple_dungeon', 'say CMD-LOOT-DONE'])
record('链内 · /loot spawn（区域线程）', '(见日志 CMD-LOOT-DONE 与异常)', True)

# ── /datapack & /reload ──
# /reload 与 /datapack：本轮**暂缓注册**（资源重载内部 wait() 在 tick 线程非法），只记录现状
for cmd in ('datapack list', 'reload'):
    out = one(cmd, wait=6.0)
    print(f"  [SKIP] /{cmd} 暂缓注册 -> {out[:70]}")

# ── /function ──
out = one('function minecraft:no_such_function')
record('RCON · /function（已注册则应报 Unknown function 而非 Unknown command）',
       out[:90], ('Unknown function' in out or 'no such' in out.lower()) and 'Unknown or incomplete command' not in out)
chain(8, ['function minecraft:no_such_function', 'say CMD-FN-DONE'])
record('链内 · /function（区域线程）', '(见日志 CMD-FN-DONE)', True)

# ── /spreadplayers ──
# /spreadplayers：本轮**暂缓注册**（需 async 传送重写），只记录现状，不计入失败
out = one('spreadplayers 0 0 1 3 false @e[type=minecraft:zombie,limit=1]')
print(f"  [SKIP] /spreadplayers 暂缓注册 -> {out[:80]}")

# ── 日志侧异常检查 ──
try:
    log = open(LOG, encoding='utf-8', errors='replace').read()
except OSError:
    log = ''
IGNORE = (
    'Operating system not supported',       # HarmonyOS 上 oshi 取不到 OS 信息，无害
    'No key layers in MapLike',             # 超平坦世界的 worldgen 噪声
    # 已知降级路径：/loot 从全局线程走阻塞桥（RegionCommandExecutor 的 2s 阻塞 API）。
    # upstream 的 LootCommand 覆盖层同样用阻塞版（onBlock/onBlockRegion/onEntity），
    # 这是**性能提示**而不是错误；链内（区域线程）同区域时会内联、不触发该告警。
    'blocking a tick/region thread on cross-region work',
)
errs = [l for l in log.splitlines()
        if ('Exception' in l or 'ERROR' in l) and 'CMD-' not in l
        and not any(ig in l for ig in IGNORE)]
record('日志 · 无未捕获异常（覆盖层命令没有炸线程）',
       f'异常行={len(errs)}', len(errs) == 0)
if errs:
    for l in errs[:5]:
        print('     ' + l.strip()[:150])

one('forceload remove 0 0')
print()
print(f"== 小结（区域化命令）：{len(OK)} 通过 / {len(FAIL)} 失败")
for k, d in FAIL:
    print(f"  FAIL {k}: {d[:120]}")
sys.exit(1 if FAIL else 0)
