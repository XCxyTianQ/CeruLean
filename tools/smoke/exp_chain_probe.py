#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T4 验收探针：EXP 可挂起命令方块链（AzureBranches EXP v2 语义）。

怎么触发才有效（踩过的坑）：
  * `/setblock` 放下的**循环**命令方块不会被安排 tick —— 首次 tick 只来自
    `setPlacedBy`（玩家放置）或红石边沿。AUTO 模式在 `neighborChanged` 里对
    "本来自动"的方块 **直接 return**（CommandBlock.java:73-75），所以给自动方块通电也没用。
    必须用**冲激命令方块（REDSTONE 模式）+ 相邻红石块**触发：那条路径会
    `level.scheduleTick(pos, this, 1)`，然后 `tick` 里 mode==REDSTONE 执行一次。
  * 头块自己的命令由 `CommandBlock.tick → execute → performCommand` 执行；
    `executeChainExp → walkExpChain` 里 `pos` 先 `move(direction)` 再判定
    （CommandBlock.java:261-267）⇒ **walker 永不执行头块**，只沿链块走。

可验证的判据（不依赖 `execute if block`——它在 global tick 线程上读世界会 NPE，基座缺口）：
  * 链块执行时会 `setblock` 出一个**方块实体**（命令方块），于是可以用我们已复活的
    `data get block <目标> Command` 直接读到它的命令串 ⇒ "链真的走了"的硬证据。
  * 控制台出现 `command_blocks.mode=EXP` ⇒ 配置确实被读到（该行由 CommandBlockSupport 打印）。

用法（需服务端已起且 RCON 就绪，配置 mode 与参数一致）：
    python3 tools/smoke/exp_chain_probe.py SAFE|EXP [日志路径]
"""
import sys
import time

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

MODE = (sys.argv[1] if len(sys.argv) > 1 else 'EXP').upper()
LOG = sys.argv[2] if len(sys.argv) > 2 else 'smoke-run/server-console.log'

FAIL = []
OK = []

# 头块 / 链块 / 链块写出的目标 / 红石块
HEAD, CHAIN = '100 100 0', '101 100 0'
TARGET = '131 100 0'
REDSTONE = '100 101 0'
CHAIN_REDSTONE = '101 101 0'
WARMUP = '96 100 0'


def one(cmd, wait=2.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=90.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def record(label, cmd, got, ok):
    (OK if ok else FAIL).append((label, cmd, got))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}\n         $ {cmd}\n         -> {str(got)[:200]}")
    return got


def check(label, cmd, expect, wait=2.0):
    got = one(cmd, wait=wait)
    return record(label, cmd, got, bool(expect(got)))


def poll(label, cmd, expect, tries=15, delay=1.0):
    """异步/投递类命令的断言必须轮询——投递后立即读会得到上一次的结论。"""
    got = ''
    for _ in range(tries):
        got = one(cmd)
        if expect(got):
            return record(label, cmd, got, True)
        time.sleep(delay)
    return record(label + f'（轮询 {tries} 次未成立）', cmd, got, False)


def log_text():
    try:
        return open(LOG, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


print(f"== T4 EXP 链验收：mode={MODE}")

# ── 预热：必须先证明"投递到该区域的命令能跑通" ────────────────────
# 动机：本会话观察到一次未复现的异常——刚 forceload/首次生成后若立刻投递，
# AwaitableDispatch 会 5s 超时（区域任务未被执行）。预热把"环境未就绪"与"功能坏"
# 分开，避免把前者误判成后者。
check('预热 · forceload', 'forceload add 96 0 144 16', lambda g: True)
one(f'setblock {WARMUP} minecraft:command_block{{Command:"say warmup"}}')
poll('预热 · 区域任务可被执行', f'data get block {WARMUP} Command',
     lambda g: 'warmup' in g, tries=20)

log_before = log_text()

# ── 建链：冲激头块（REDSTONE 模式）+ 链块，最后用红石块触发 ─────────
check('清空目标区', f'fill 129 100 0 133 100 0 minecraft:air', lambda g: True)
check('清空链位', 'fill 100 100 0 101 100 0 minecraft:air', lambda g: True)
check('放置头块（impulse）',
      f'setblock {HEAD} minecraft:command_block[facing=east]'
      f'{{Command:"say head-ran"}} replace',
      lambda g: 'Unknown or incomplete' not in g)
check('放置链块',
      f"setblock {CHAIN} minecraft:chain_command_block[facing=east]"
      f"{{Command:'setblock {TARGET} minecraft:command_block{{Command:chain-ran}}'}} replace",
      lambda g: 'Unknown or incomplete' not in g)
# 通电顺序很关键：
#   * 链块（SEQUENCE）的 isPowered() 读的是**方块实体字段** powered（默认 false，
#     CommandBlockEntity.java:23/111），必须靠相邻红石信号把它置 true，
#     否则 walker 里 `if (commandBlock.isPowered() || isAutomatic())` 直接跳过它；
#   * 头块是 REDSTONE 模式，通电即 scheduleTick ⇒ 下一个 tick 执行一次。
#   所以**先给链块通电，再触发头块**。
check('给链块通电（必须在触发头块之前）',
      f'setblock {CHAIN_REDSTONE} minecraft:redstone_block replace',
      lambda g: 'Unknown or incomplete' not in g)
check('红石块触发头块', f'setblock {REDSTONE} minecraft:redstone_block replace',
      lambda g: 'Unknown or incomplete' not in g)

if MODE == 'SAFE':
    # SAFE 下这条**本来就该不成立**，所以不能轮询（轮询 15 次后才报 FAIL 是设计缺陷，
    # 会让"预期不执行"被计成失败）。这里只读一次，断言目标不存在。
    chain_target = check('SAFE：链块没有写下目标方块',
                         f'data get block {TARGET} Command',
                         lambda g: 'chain-ran' not in g)
else:
    chain_target = poll('链块执行 ⇒ 目标位置出现链块写下的命令方块',
                        f'data get block {TARGET} Command',
                        lambda g: 'chain-ran' in g, tries=15)
chain_ran = 'chain-ran' in str(chain_target)

log_after = log_text()
notice = 'command_blocks.mode=EXP' in log_after
def unexpected_errors(text):
    """只数**非预期** ERROR：data get 探测"目标不是方块实体"是本探针的预期噪声。"""
    return [l for l in text.splitlines()
            if 'ERROR]' in l and '/data get block' not in l]

new_errors = len(unexpected_errors(log_after)) - len(unexpected_errors(log_before))

print(f"  SuccessCount：头块={one(f'data get block {HEAD} SuccessCount')!r} "
      f"链块={one(f'data get block {CHAIN} SuccessCount')!r}")
print(f"  日志新增 ERROR 行数={new_errors}；EXP 提示={'有' if notice else '无'}")

if MODE == 'SAFE':
    record('SAFE：链块**没有**执行（Folia 行为保持）', f'data get block {TARGET} Command',
           chain_target, not chain_ran)
    record('SAFE：没有打印 EXP 提示', LOG, notice, not notice)
elif MODE == 'EXP':
    record('EXP：链块真的执行了（walker 走通整条链）', f'data get block {TARGET} Command',
           chain_target, chain_ran)
    record('EXP：配置被读到（控制台出现 command_blocks.mode=EXP）', LOG, notice, notice)
    record('EXP：本轮没有新增 ERROR', LOG, new_errors, new_errors == 0)

# ── 收尾：拆掉命令方块与红石块，避免持续 tick 污染后续用例 ──────────
one('fill 100 100 0 101 100 0 minecraft:air')
one(f'setblock {REDSTONE} minecraft:air')
one(f'setblock {CHAIN_REDSTONE} minecraft:air')
one(f'setblock {TARGET} minecraft:air')

print()
print(f"== 小结（{MODE}）：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, cmd, got in FAIL:
    print(f"  FAIL {label}: {str(got)[:160]}")
sys.exit(1 if FAIL else 0)
