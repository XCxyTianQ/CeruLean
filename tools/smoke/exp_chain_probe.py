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
import re
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


def ok_parse(got):
    """命令是否**被解析并受理**。Brigadier 解析失败会带 <--[HERE] 指针；
    只挡 'Unknown or incomplete command' 会漏掉 'Expected literal' 这类语法错（踩过）。"""
    return 'Unknown or incomplete' not in got and '<--[HERE]' not in got


def clear_rig():
    """清掉整个试验台（含红石块）。

    踩过：只清命令方块、留下上一轮的红石块 ⇒ `setblock` 放下的新方块**不会收到
    neighborChanged**（setblock 只通知新方块的邻居，不通知它自己），于是头块拿不到
    scheduled tick、链块拿不到 powered=true，两条都不执行，表现为"功能没生效"。
    """
    one(f'fill {HEAD} {CHAIN_REDSTONE} minecraft:air')


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
check('清空链位（含红石块）', 'fill 100 100 0 101 101 0 minecraft:air', lambda g: True)
check('放置头块（impulse）',
      f'setblock {HEAD} minecraft:command_block[facing=east]'
      f'{{Command:"say head-ran"}} replace',
      ok_parse)
check('放置链块',
      f"setblock {CHAIN} minecraft:chain_command_block[facing=east]"
      f"{{Command:'setblock {TARGET} minecraft:command_block{{Command:chain-ran}}'}} replace",
      ok_parse)
# 通电顺序很关键：
#   * 链块（SEQUENCE）的 isPowered() 读的是**方块实体字段** powered（默认 false，
#     CommandBlockEntity.java:23/111），必须靠相邻红石信号把它置 true，
#     否则 walker 里 `if (commandBlock.isPowered() || isAutomatic())` 直接跳过它；
#   * 头块是 REDSTONE 模式，通电即 scheduleTick ⇒ 下一个 tick 执行一次。
#   所以**先给链块通电，再触发头块**。
check('给链块通电（必须在触发头块之前）',
      f'setblock {CHAIN_REDSTONE} minecraft:redstone_block replace',
      ok_parse)
check('红石块触发头块', f'setblock {REDSTONE} minecraft:redstone_block replace',
      ok_parse)

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

# ── Step 3 验收：跨区域 setblock 必须被登记成"可等待的延迟工作" ──────
# 只有命令侧接上 ExpChainSupport.registerDeferred（SetBlockCommand 的 CeruLean 分支），
# walker 才会攒出待派发批次、在链尾批边界挂起（onSuspend）并在远程完成后恢复（onResume）。
# 因此这里同时断言两件事：跨区域目标真的被写出来 + 挂起/恢复计数真的增长。
if MODE == 'EXP':
    def exp_counter(name):
        m = re.search(name + r'\s+(\d+)', one('/cerulean exp'))
        return int(m.group(1)) if m else None

    susp0, resm0 = exp_counter('挂起'), exp_counter('恢复')
    inl0 = exp_counter('内联')
    print(f"  登记（内联）={inl0} 挂起/恢复={susp0}/{resm0}")

    # 本冒烟环境能不能构造"跨区域"？force-load 的两个区块簇会被并入同一个区域
    # （实测 overworld 只有 1 个区域）⇒ sameRegion 恒 true ⇒ 永远不产生延迟批次。
    # 因此这里先量拓扑，再决定"挂起/恢复"是断言还是显式跳过（SKIP 不能算通过）。
    regions_txt = one('/cerulean regions')
    m = re.search(r'minecraft:overworld: 区域 (\d+)', regions_txt)
    overworld_regions = int(m.group(1)) if m else None
    print(f"  overworld 区域数 = {overworld_regions}")

    XREGION_TARGET = '1000 100 0'
    check('跨区域链 · forceload 远端区域', 'forceload add 992 0 1008 16', lambda g: True)
    one(f'setblock {XREGION_TARGET} minecraft:air')
    # 必须连红石块一起清：上一段留下的红石块会让新方块收不到 neighborChanged（见 clear_rig 注释）
    clear_rig()
    check('跨区域链 · 清空试验台', 'fill 100 100 0 101 101 0 minecraft:air', lambda g: True)
    check('跨区域链 · 头块',
          f'setblock {HEAD} minecraft:command_block[facing=east]{{Command:"say head-ran"}} replace',
          ok_parse)
    check('跨区域链 · 链块（命令写到 ~1000 格外的区域）',
          f"setblock {CHAIN} minecraft:chain_command_block[facing=east]"
          f"{{Command:'setblock {XREGION_TARGET} minecraft:command_block{{Command:xregion-ran}}'}} replace",
          ok_parse)
    check('跨区域链 · 给链块通电', f'setblock {CHAIN_REDSTONE} minecraft:redstone_block replace',
          ok_parse)
    check('跨区域链 · 触发头块', f'setblock {REDSTONE} minecraft:redstone_block replace',
          ok_parse)
    poll('跨区域链 · 远端真的被写出来了',
         f'data get block {XREGION_TARGET} Command',
         lambda g: 'xregion-ran' in g, tries=20)
    susp1, resm1 = exp_counter('挂起'), exp_counter('恢复')
    inl1, dfr1 = exp_counter('内联'), exp_counter('跨区域')
    print(f"  登记（内联/跨区域）= {inl1}/{dfr1} 挂起/恢复 = {susp1}/{resm1}")

    # ★ EXP6 NBT 数据域：让一条链块执行 `/data merge entity`，断言 NBT 写拦截真的发生。
    # 只落数据面钩子而不构造用例，就无法证明第四个数据域（实体 NBT）真的接线了。
    print("  — EXP6 NBT 域用例 —")
    one('kill @e[type=minecraft:armor_stand]')
    check('NBT 域 · 召唤一个盔甲架', 'summon minecraft:armor_stand 101 100 2 {NoGravity:1b}',
          lambda g: 'Unknown or incomplete' not in g and 'Summoned' in g or True)
    clear_rig()
    check('NBT 域 · 头块', f'setblock {HEAD} minecraft:command_block[facing=east]{{Command:"say nbt-head"}} replace', ok_parse)
    check('NBT 域 · 链块（data merge entity）',
          f"setblock {CHAIN} minecraft:chain_command_block[facing=east]"
          f"{{Command:'data merge entity @e[type=minecraft:armor_stand,limit=1] {{CustomNameVisible:1b}}'}} replace",
          ok_parse)
    check('NBT 域 · 给链块通电', f'setblock {CHAIN_REDSTONE} minecraft:redstone_block replace', ok_parse)
    check('NBT 域 · 触发头块', f'setblock {REDSTONE} minecraft:redstone_block replace', ok_parse)
    nbt0 = None
    for _ in range(15):
        time.sleep(1)
        m = re.search(r'NBT写拦截 (\d+)', one('/cerulean exp'))
        nbt0 = int(m.group(1)) if m else None
        if nbt0 and nbt0 > 0:
            break
    record('EXP6 NBT 域 · `/data merge entity` 的写拦截真的发生（NBT写拦截 > 0）',
           '/cerulean exp', f'NBT写拦截={nbt0}', bool(nbt0 and nbt0 > 0))
    one('kill @e[type=minecraft:armor_stand]')

    # ★ OCC 是否真的在工作：数据面钩子必须被走到（否则读集恒空、PhaseValidator 永不触发），
    # 且链尾的读集校验必须真的跑过一次（validationPassed ≥ 1）。
    occ = one('/cerulean exp')

    def occ_nums(pattern):
        m = re.search(pattern, occ)
        return m.groups() if m else None

    # 注意：不要用"方块读"（= 缓存命中次数）当判据 —— 缓存未命中才是常态，它会是 0。
    # 真正的判据是"读集记录数"，即 OCC 的方块读集到底有没有被填过。
    m_data = re.search(r'读集记录 (\d+)', occ)
    recorded = int(m_data.group(1)) if m_data else -1
    record('OCC 数据面 · Level.getBlockState 钩子被走到（读集记录非空）',
           '/cerulean exp', f'读集记录={recorded}', recorded > 0)
    m_val = re.search(r'通过 (\d+) / 重试 (\d+) / 耗尽 (\d+)', occ)
    passed = int(m_val.group(1)) if m_val else -1
    record('OCC 校验 · 链尾读集校验真的跑过（validationPassed ≥ 1）',
           '/cerulean exp', f'通过={passed}', passed >= 1)

    # 确定性判据：命令侧（SetBlockCommand 的 EXP 分支）真的调用了 registerDeferred。
    # 只落链本体（0020）而不接 0021 时，这个计数**永远不会增长**（走的是上游 fire-and-forget）。
    record('跨区域链 · 命令侧确实接进了 EXP 上下文（registerDeferred 被调用）',
           '/cerulean exp', f'inline={inl1} deferred={dfr1}',
           inl1 is not None and inl0 is not None and (inl1 > inl0 or (dfr1 or 0) > 0))

    if overworld_regions is not None and overworld_regions >= 2:
        record('跨区域链 · 链真的挂起过（walker 批边界触发）', '/cerulean exp', susp1,
               susp0 is not None and susp1 is not None and susp1 > susp0)
        record('跨区域链 · 链真的恢复过（远程完成后回 home 区继续）', '/cerulean exp', resm1,
               resm0 is not None and resm1 is not None and resm1 > resm0)
    else:
        print(f"  [SKIP] 链挂起/恢复：本环境 overworld 只有 {overworld_regions} 个区域"
              f"（force-load 的两个区块簇被并入同一区域）⇒ sameRegion 恒 true，"
              f"不会产生跨区域延迟批次，该路径**无法构造**。已记入 docs/16，待有玩家/多区域环境时补测。")
    one(f'setblock {XREGION_TARGET} minecraft:air')

# ── 收尾：拆掉命令方块与红石块，避免持续 tick 污染后续用例 ──────────
clear_rig()
one(f'setblock {TARGET} minecraft:air')

print()
print(f"== 小结（{MODE}）：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, cmd, got in FAIL:
    print(f"  FAIL {label}: {str(got)[:160]}")
sys.exit(1 if FAIL else 0)
