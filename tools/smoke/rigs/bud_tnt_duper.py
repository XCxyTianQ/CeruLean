#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M3 . BUD 类 TNT 复制装置：vanilla 26.3 上「方块不减 + 刷出 primed TNT」的实测装置。

为什么以前做不到（docs/23 11.10）
--------------------------------
用 /setblock 把 TNT 放在电源邻位时，TntBlock.onPlace 立刻看到 hasNeighborSignal
并点燃 => 造不出 BUD 态（被供电、但没收到更新）。本装置不靠命令造 BUD，而是靠
**活塞移动本身的更新序列**：TNT 先用「导体 + 强电源」供电（电源与 TNT 不直接相邻，
所以没有任何更新打到 TNT 上 => BUD 态），再用活塞把它和一个**已点亮的观察者**一起推走。

机制（26.3 源码 + 逐 tick 实测）
-------------------------------
PistonBaseBlock.moveBlocks：
  1. 先把所有要推的方块状态快照进 toPushShapes（**移动前**的快照）；
  2. 反向遍历 toPush（离活塞最远的先处理）：目标位放 moving_piston + 建 moving BE
     （BE 里存的是步骤 1 的快照），源位 setBlock(air, ...MOVE_BY_PISTON...)；
  3. 源位变空气这一步会调 affectNeighborsAfterRemoval(oldState, pos, movedByPiston=true)。
     ObserverBlock 的该方法是**唯一不检查 movedByPiston** 的常见实现：
     若 observer 处于「已点亮且有 scheduled tick」（POWERED=true 且还有 2 tick 排程），
     它会 updateNeighborsInFront —— 通知它输出面（FACING 的反面）及其邻居。
  4. 因此：只要 observer 比 TNT 先被处理，它被推走时发出的更新会打到**还在原位**的 TNT；
     TNT 此时已被导体强供电 => neighborChanged -> prime()：
       · 源位置刷出 primed TNT 实体（这就是「复制出来的那份」）；
       · 源位置 removeBlock 变空气；
     但 moving BE 已经拿着**移动前的 TNT 快照**，2 tick 后照样在目标位落下 TNT 方块。
  => 一次移动后：TNT 方块数 1 -> 1（方块数不减），场上多一个 primed TNT。

装置（原点 O；活塞朝 +x；平台在 O.y-1）
--------------------------------------
    O+(-1,0,0)  piston[facing=east]              推手
    O+( 0,0,0)  slime_block                      被推动的载体（把 TNT 与 observer 一起带走）
    O+( 0,1,0)  tnt                              被复制的 TNT（BUD 态）
    O+( 1,0,0)  observer[facing=down]            触发器：输出面朝上（空气），观测面朝下
    O+( 0,2,0)  stone                            导体（把强信号传给 TNT，本身不通知 TNT）
    O+( 0,3,0)  lever[face=floor,powered=true]   强电源（与 TNT 隔两格，放置时不通知 TNT）
    O+(-2,0,0)  redstone_block                   活塞电源（放在活塞背面！放上面会挨着 TNT 提前点燃）
    O+( 1,-1,0) 平台格                            观测面：往这里放一块石头给 observer 打脉冲

时序（/tick freeze + /tick step 1 精确推进；tick 编号相对「给活塞上电」那一刻）
------------------------------------------------------------------------------
    t-2  在 O+(1,-1,0) 放石头 -> observer 收到 shape update -> 排程 2 tick
    t 0  observer tick：POWERED=true（亮 2 tick），此时 TNT 还没供电所以不会被点
         -> 装 lever（TNT 进入 BUD 态）-> 恢复平台格 -> 给活塞上电
    t+1  活塞 moveBlocks：observer 先被处理 -> 通知 TNT -> **TNT 在移动过程中被点燃**
         （逐 tick 观测：本 tick 就出现 1 个 primed TNT，目标位是 moving_piston）
    t+3  BE 落地：TNT 方块出现在 O+(1,1,0)，源位置只剩 primed TNT

判定（每轮）
------------
    pre  : 盒内 TNT 方块 = 1、primed TNT = 0
    during: 某个 tick 上 primed TNT >= 1（点燃发生在移动过程中）
    post : 盒内 TNT 方块 >= 1（**不减**）且 primed TNT >= 1
    三条同时满足才算复制；只出现实体、方块消失 = 点燃即消耗 -> FAIL。

用法
----
    # 默认：5 轮复制 + 2 组对照（对照组应当「不复制」），写证据 JSON
    python3 tools/smoke/rigs/bud_tnt_duper.py --port 25576 --password test1234 \
        --rounds 5 --controls --out $HOME/cerulean-scratch/bud-duper-vanilla.json

    # 与上一档/上次结果比对（可选）
    python3 tools/smoke/rigs/bud_tnt_duper.py --port 25577 --password test1234 \
        --rounds 5 --expect $HOME/cerulean-scratch/bud-duper-vanilla.json

退出码（与 tools/smoke/run_suite.py 一致）：0=PASS / 1=FAIL / 2=SKIP（RCON 连不上等）。

成本：每轮约 20 s（rig_common 的 RCON 是每条命令一个独立连接，逐 tick 观测 = 每 tick
约 15 条命令）。机制是确定性的（所有轮次点燃都在 t+1），所以 4~5 轮 + 2 组对照足够；
--rounds 可以调大做长跑。
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rig_common import (  # noqa: E402
    Rcon, RigError, RigUnavailable,
    EXIT_PASS, EXIT_FAIL, EXIT_SKIP,
    DEFAULT_HOST, DEFAULT_PORT, DEFAULT_PASSWORD,
)

PROBE = "bud_tnt_duper"
DOC = "docs/23 11.10（未实现项）+ vanilla 26.3 逐 tick 实测"

# -- 装置方块 --------------------------------------------------
PISTON = "minecraft:piston[facing=east]"
SLIME = "minecraft:slime_block"
TNT = "minecraft:tnt"
OBSERVER = "minecraft:observer[facing=down]"
OBSERVER_LIT = "minecraft:observer[facing=down,powered=true]"
CONDUCTOR = "minecraft:stone"
LEVER = "minecraft:lever[face=floor,facing=north,powered=true]"
POWER = "minecraft:redstone_block"
PLATFORM = "minecraft:stone"
MOVING = "minecraft:moving_piston"
PISTON_EXT = "minecraft:piston[extended=true]"
PISTON_HEAD = "minecraft:piston_head"

DEFAULT_Y = -50
CLEAR_HALF = 6
CLEAR_UP = 6
CLEAR_DOWN = 8
OBS_OFFSETS = ((0, 1), (1, 1), (0, 0), (1, 0), (2, 0), (0, 2))


def geometry(dx=0, dz=0, y=DEFAULT_Y):
    """装置坐标（相对 O）。"""
    o = (dx, y, dz)
    return {
        "origin": o,
        "piston": (o[0] - 1, o[1], o[2]),
        "slime": (o[0], o[1], o[2]),
        "tnt": (o[0], o[1] + 1, o[2]),
        "observer": (o[0] + 1, o[1], o[2]),
        "conductor": (o[0], o[1] + 2, o[2]),
        "lever": (o[0], o[1] + 3, o[2]),
        "power": (o[0] - 2, o[1], o[2]),
        "trigger_cell": (o[0] + 1, o[1] - 1, o[2]),
        "tnt_after": (o[0] + 1, o[1] + 1, o[2]),
        "slime_after": (o[0] + 1, o[1], o[2]),
        "observer_after": (o[0] + 2, o[1], o[2]),
        # canary：不参与推动、但贴着 observer 输出面的第二个 BUD TNT。
        # 它只在「observer 被移走时确实发出了更新」时才会被点燃，因此是**时序见证器**：
        #   canary 点燃 + 主 TNT 没点燃 => 更新确实在 moveBlocks 里发了，但没赶上被推走的 TNT。
        "canary_tnt": (o[0] + 2, o[1] + 1, o[2]),
        "canary_conductor": (o[0] + 2, o[1] + 2, o[2]),
        "canary_lever": (o[0] + 2, o[1] + 3, o[2]),
    }


def clear_box(g):
    x, y, z = g["origin"]
    return (x - CLEAR_HALF, y - CLEAR_DOWN, z - CLEAR_HALF,
            x + CLEAR_HALF, y + CLEAR_UP, z + CLEAR_HALF)


def obs_cells(g):
    """TNT 这个装置里可能出现的位置（源位、目标位、载体位、锥体上方）。"""
    x, y, z = g["origin"]
    return [(x + dx, y + dy, z) for dx, dy in OBS_OFFSETS]


def entity_at(rc, pos):
    """指定 1x1x1 格子里有没有 primed TNT 实体（用体积选择器，避免 canary 污染计数）。"""
    x, y, z = pos
    out = (rc.cmd("execute if entity @e[type=minecraft:tnt,x=%d,y=%d,z=%d,dx=1,dy=1,dz=1]"
                  % (x, y, z)) or "").lower()
    if any(m in out for m in ("test passed", "测试通过")):
        return True
    if any(m in out for m in ("test failed", "测试失败")):
        return False
    return None


def observe(rc, g):
    """读一个 tick 的关键状态（frozen 下是确定的）。"""
    cells = obs_cells(g)
    tnt_blocks = [list(p) for p in cells if rc.probe_block(p, TNT)]
    primed_exists = rc.probe_entity("minecraft:tnt")
    primed = rc.entity_count("minecraft:tnt", objective="bud_duper_cnt")
    moving = [list(p) for p in (g["tnt_after"], g["slime_after"], g["observer_after"],
                               g["slime"]) if rc.probe_block(p, MOVING)]
    lit = rc.probe_block(g["observer"], OBSERVER_LIT) or \
        rc.probe_block(g["observer_after"], OBSERVER_LIT)
    return {
        "tnt_blocks": tnt_blocks,
        "primed": primed,
        # 只属于主 TNT 源位置的那个实体（canary 的实体不算）
        "main_primed": 1 if entity_at(rc, g["tnt"]) else 0,
        "primed_exists": bool(primed_exists),
        "piston_extended": rc.probe_block(g["piston"], PISTON_EXT),
        "piston_head": rc.probe_block(g["piston"], PISTON_HEAD),
        "moving": moving,
        "observer_lit": bool(lit),
    }


def fmt_obs(o):
    tnt = " ".join("(%d,%d,%d)" % tuple(p) for p in o["tnt_blocks"]) or "-"
    mov = " ".join("(%d,%d,%d)" % tuple(p) for p in o["moving"]) or "-"
    return ("TNT方块={%s}#%d primed=%s ext=%s moving={%s} obs_lit=%s"
            % (tnt, len(o["tnt_blocks"]), o["primed"], o["piston_extended"],
               mov, o["observer_lit"]))


def tick_step(rc, n=1):
    out = (rc.cmd("tick step %d" % n, wait=1.0) or "").lower()
    if "stepping" not in out and "advanc" not in out:
        raise RigError("tick step 回应无法判定：%r" % out[:160])
    return out


def build(rc, g, canary=False):
    """清场 + 搭装置（不装 lever / 活塞电源；那些在触发阶段装，保证时序）。"""
    box = clear_box(g)
    rc.clear_area(*box)
    x, y, z = g["origin"]
    rc.fill((x - 1, y - 1, z, x + 3, y - 1, z + 1), block=PLATFORM,
            replace="minecraft:air")
    rc.setblock(g["piston"], PISTON)
    rc.setblock(g["slime"], SLIME)
    rc.setblock(g["observer"], OBSERVER)
    rc.setblock(g["tnt"], TNT)
    rc.setblock(g["conductor"], CONDUCTOR)
    if canary:
        rc.setblock(g["canary_tnt"], TNT)
        rc.setblock(g["canary_conductor"], CONDUCTOR)
    # 观测面（observer 下方）保持平台石头：脉冲靠「把它挖成空气」来打，
    # 这样脉冲时刻完全由调用方控制（realtime 模式下 build 不会自己排程）。


def pulse_observer(rc, g, max_ticks=6):
    """把 observer 观测面的方块挖成空气 -> shape update -> 2 tick 后点亮。

    **固定步进 2 tick、不在中间插读命令**：Folia 上每条 RCON 命令都按 tick 派发，
    在 pulse 与 arm 之间插 probe 会把 arm 的时刻推后 1-2 tick，直接错过 observer 的亮窗
    （实测：带 probe 的版本在 CeruLean 上校准 0..6 全失败；去掉后同一序列稳定复制）。
    是否为"恰好 2 tick 点亮"由后续 ignite 判定兜底：没点燃就是 FAIL，不会被掩盖。
    """
    rc.setblock(g["trigger_cell"], "minecraft:air", verify=False)
    tick_step(rc, 2)
    return 2


def arm_and_power(rc, g, canary=False):
    """TNT 进入 BUD 态（lever 经导体强供电）+ 给活塞上电。

    必须在 observer 点亮之后、活塞移动之前完成：先 lever（与 TNT 隔两格，
    不会通知 TNT），再活塞电源（活塞背面，不挨着 TNT）。
    """
    rc.setblock(g["lever"], LEVER)
    if canary:
        rc.setblock(g["canary_lever"], LEVER)
    rc.setblock(g["power"], POWER, verify=False)


def round_verdict(mode, pre_tnt, pre_primed, ignite_tick, post_tnt, post_primed):
    """每轮判定：pre 1 个方块 0 实体 -> 移动中出现实体 -> post 方块不减且实体 >=1。"""
    notes = []
    ok = True
    if pre_tnt != 1:
        ok = False
        notes.append("pre 阶段 TNT 方块应为 1，实测 %d" % pre_tnt)
    if pre_primed != 0:
        ok = False
        notes.append("pre 阶段不应有 primed TNT，实测 %d" % pre_primed)
    if ignite_tick is None:
        ok = False
        notes.append("整轮没有出现 primed TNT（没点燃）")
    if post_tnt < pre_tnt:
        ok = False
        notes.append("TNT 方块数减少：%d -> %d（点燃即消耗，不是复制）" % (pre_tnt, post_tnt))
    if post_primed < 1:
        ok = False
        notes.append("结束时没有 primed TNT")

    duplicated = (ignite_tick is not None and post_tnt >= pre_tnt and post_primed >= 1)
    if mode != "dupe":
        control_ok = (ignite_tick is None and post_tnt == pre_tnt)
        ok = control_ok
        if not control_ok:
            notes.append("对照组出现了复制（无法归因于 BUD 触发）")
    return ok, bool(duplicated), notes


def run_round(rc, g, idx, mode="dupe", step_after=4, canary=False, arm_offset=0):
    """mode: dupe / control_no_arm / control_unlit

    arm_offset：observer 点亮后再等 N 个 tick 才装 lever / 上电。
    为什么需要：Folia 上 RCON 命令是**按 tick 派发**的（命令在下一 tick 才生效），
    而 observer 的亮窗只有 2 tick——不补偿就会「上电晚一 tick ⇒ 推走 observer 时它已熄灭
    ⇒ affectNeighborsAfterRemoval 因 hasScheduledTick=false 不发更新 ⇒ TNT 不点燃」。
    vanilla 上是 0；CeruLean 实测 2。用 --calibrate 自动扫。
    """
    t0 = time.time()
    rc.kill_entities("minecraft:tnt")
    build(rc, g, canary=canary)

    # pre 观测放在脉冲**之前**：Folia 上每条 RCON 命令按 tick 派发，脉冲→arm 之间
    # 夹着 6 条读命令会把有效时序抖掉（实测校准失败的主因）。
    pre = observe(rc, g)
    pre_tnt = len(pre["tnt_blocks"])
    pre_primed = pre["primed"]
    canary_pre = rc.probe_block(g["canary_tnt"], TNT) if canary else None

    pulse_steps = 0
    if mode != "control_unlit":
        pulse_steps = pulse_observer(rc, g)
    if arm_offset and mode != "control_unlit":
        tick_step(rc, arm_offset)

    if mode == "control_no_arm":
        rc.setblock(g["power"], POWER, verify=False)
    elif mode == "control_unlit":
        rc.setblock(g["lever"], LEVER)
        rc.setblock(g["power"], POWER, verify=False)
    else:
        arm_and_power(rc, g, canary=canary)

    ticks = []
    ignite_tick = None
    for i in range(1, step_after + 1):
        tick_step(rc, 1)
        st = observe(rc, g)
        st["tick"] = i
        ticks.append(st)
        if st.get("main_primed", st["primed"]) >= 1 and ignite_tick is None:
            ignite_tick = i

    post = ticks[-1] if ticks else observe(rc, g)
    post_tnt = len(post["tnt_blocks"])
    post_primed = post.get("main_primed", post["primed"])

    ok, duplicated, notes = round_verdict(
        mode, pre_tnt, pre_primed, ignite_tick, post_tnt, post_primed)

    canary_post = rc.probe_block(g["canary_tnt"], TNT) if canary else None
    canary_ignited = bool(canary_pre) and canary_post is False
    if canary and not duplicated and canary_ignited:
        notes.append("canary 已点燃（说明 observer 移走时确实发了更新），"
                     "但被推动的 TNT 没点燃 => 更新赶不上已变空气的源位置")
    rc.kill_entities("minecraft:tnt")

    return {
        "round": idx,
        "mode": mode,
        "canary": None if not canary else {
            "tnt_before": bool(canary_pre), "tnt_after": bool(canary_post),
            "ignited": bool(canary_ignited)},
        "pre": {"tnt_blocks": pre["tnt_blocks"], "primed": pre_primed,
                "observer_lit": bool(pre["observer_lit"])},
        "pulse_steps": pulse_steps,
        "arm_offset": arm_offset,
        "armed": mode != "control_no_arm",
        "ticks": ticks,
        "ignite_tick": ignite_tick,
        "post": {"tnt_blocks": post["tnt_blocks"], "primed": post_primed},
        "duplicated": bool(duplicated),
        "tnt_blocks_before": pre_tnt,
        "tnt_blocks_after": post_tnt,
        "ok": bool(ok),
        "notes": notes,
        "elapsed_ms": round((time.time() - t0) * 1000.0, 1),
    }


# -- realtime（不 freeze，世界按 tick rate 运行）----------------
def obs_light(rc, g):
    """realtime 采样用的轻量观测（约 4 条命令，尽量别跨太多 tick）。"""
    return {
        "primed_exists": bool(rc.probe_entity("minecraft:tnt")),
        "main_primed": 1 if entity_at(rc, g["tnt"]) else 0,
        "tnt_at_src": rc.probe_block(g["tnt"], TNT),
        "tnt_at_dst": rc.probe_block(g["tnt_after"], TNT),
        "piston_extended": rc.probe_block(g["piston"], PISTON_EXT),
    }


def realtime_rate(rc, rate):
    """/tick rate：把实时世界放慢，让 RCON 能在 observer 的 2 tick 亮窗内装 lever/上电。"""
    out = (rc.cmd("tick rate %s" % rate, wait=0.8) or "").strip()
    low = out.lower()
    if "unknown" in low or "incomplete" in low or "incorrect" in low:
        raise RigError("realtime 模式需要 /tick rate（本服不支持）：%r" % out[:160])
    return out


def wait_lit_realtime(rc, g, rate=1.0):
    """世界在跑：轮询等 observer 点亮，返回等待秒数。"""
    t0 = time.time()
    timeout = max(4.0, 5.0 / float(rate) + 2.0)
    while time.time() - t0 < timeout:
        if rc.probe_block(g["observer"], OBSERVER_LIT):
            return round(time.time() - t0, 3)
    raise RigError("realtime 下 observer 在 %.1fs 内没有点亮（观测面脉冲没生效）" % timeout)


def run_round_realtime(rc, g, idx, mode="dupe", rate=20.0, step_after=4, max_samples=10,
                      canary=False, arm_offset=0):
    """realtime 一轮：预置仍用 /tick freeze 做精确定时，**活塞移动那一步解冻**，
    让 moveBlocks 与邻居更新派发在**运行中的世界**里发生，然后按时间轮询 N tick。

    为什么这样定义：observer 的亮窗只有 2 tick，而 Folia 上 RCON 命令是「按 tick 派发」的
    （rate=1 时单条命令就可能占掉 1 tick），纯实时根本没法在窗内完成 arm+上电。
    把「移动/更新序列」放到解冻后执行，既保住了时序，又测到了真正的实时语义。
    """
    t0 = time.time()
    rc.kill_entities("minecraft:tnt")
    build(rc, g, canary=canary)

    pre = observe(rc, g)
    pre_tnt = len(pre["tnt_blocks"])
    pre_primed = pre["primed"]
    canary_pre = rc.probe_block(g["canary_tnt"], TNT) if canary else None

    pulse_steps = 0
    if mode != "control_unlit":
        pulse_steps = pulse_observer(rc, g)      # frozen（精确 2 tick 后点亮）
    if arm_offset and mode != "control_unlit":
        tick_step(rc, arm_offset)

    if mode == "control_no_arm":
        rc.setblock(g["power"], POWER, verify=False)
    elif mode == "control_unlit":
        rc.setblock(g["lever"], LEVER)
        rc.setblock(g["power"], POWER, verify=False)
    else:
        arm_and_power(rc, g, canary=canary)

    # === 解冻：活塞移动 + 更新派发在运行中的世界里发生 ===
    rc.cmd("tick unfreeze", wait=0.5)
    t_trig = time.time()
    samples = []
    ignite = None
    while len(samples) < max_samples:
        time.sleep(0.05)
        el = time.time() - t_trig
        st = obs_light(rc, g)
        st["t"] = round(el, 3)
        st["est_tick"] = round(el * float(rate), 2)
        samples.append(st)
        if st.get("main_primed", st["primed_exists"]) and ignite is None:
            ignite = st
        if el >= (step_after + 1) / float(rate) + 0.15:
            break
    rc.cmd("tick freeze", wait=0.5)              # 回到冻结，读终态（实体还在，80 tick 引信）

    post = observe(rc, g)
    post_tnt = len(post["tnt_blocks"])
    post_primed = post.get("main_primed", post["primed"])
    ignite_tick = None
    if ignite is not None:
        ignite_tick = max(1, int(round(ignite["est_tick"])))

    ok, duplicated, notes = round_verdict(
        mode, pre_tnt, pre_primed, ignite_tick, post_tnt, post_primed)
    canary_post = rc.probe_block(g["canary_tnt"], TNT) if canary else None
    canary_ignited = bool(canary_pre) and canary_post is False
    if canary and not duplicated and canary_ignited:
        notes.append("canary 已点燃（说明 observer 移走时确实发了更新），"
                     "但被推动的 TNT 没点燃 => 更新赶不上已变空气的源位置")
    rc.kill_entities("minecraft:tnt")

    return {
        "round": idx,
        "mode": mode,
        "realtime": True,
        "canary": None if not canary else {
            "tnt_before": bool(canary_pre), "tnt_after": bool(canary_post),
            "ignited": bool(canary_ignited)},
        "tick_rate": rate,
        "pre": {"tnt_blocks": pre["tnt_blocks"], "primed": pre_primed,
                "observer_lit": bool(pre["observer_lit"])},
        "pulse_steps": pulse_steps,
        "arm_offset": arm_offset,
        "samples": samples,
        "ignite_tick": ignite_tick,
        "ignite_tick_estimated": True,
        "ignite_sample": ignite,
        "post": {"tnt_blocks": post["tnt_blocks"], "primed": post_primed},
        "duplicated": bool(duplicated),
        "tnt_blocks_before": pre_tnt,
        "tnt_blocks_after": post_tnt,
        "ok": bool(ok),
        "notes": notes,
        "elapsed_ms": round((time.time() - t0) * 1000.0, 1),
    }


# -- 期望比对 --------------------------------------------------
EXPECT_KEYS = ("dupe", "ignite_tick", "tnt_blocks_after")


def parse_expect(text):
    """--expect：文件（本脚本产出的结果 JSON，或只含 summary 的对象）/ 内联 JSON。

    只比对三项：dupe（是否复制）、ignite_tick（点燃发生在触发后第几 tick）、
    tnt_blocks_after（结束时盒内 TNT 方块数）。跨基线/跨版本时这三项就是机制指纹。
    """
    if not text:
        return None, None
    raw = text.strip()
    if os.path.isfile(raw):
        with open(raw, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        src = "file:%s" % os.path.abspath(raw)
    elif raw.startswith("{"):
        data = json.loads(raw)
        src = "json-inline"
    else:
        raise RigError("--expect 应为结果 JSON 路径或内联 JSON，收到：%r" % text)
    if not isinstance(data, dict):
        raise RigError("--expect 必须是 JSON 对象")
    exp = data.get("summary") if isinstance(data.get("summary"), dict) else data
    out = {}
    for k in EXPECT_KEYS:
        if k in exp:
            out[k] = exp[k]
    if not out:
        raise RigError("--expect 里没有可识别的键（允许 %s）" % (list(EXPECT_KEYS),))
    return out, src


def compare(rounds, expect):
    """每轮都要与期望一致；返回 (ok, detail)。"""
    for r in rounds:
        got = {
            "dupe": r["duplicated"],
            "ignite_tick": r["ignite_tick"],
            "tnt_blocks_after": r["tnt_blocks_after"],
        }
        for k, want in expect.items():
            if got.get(k) != want:
                return False, "第 %d 轮 %s 实测 %r != 期望 %r" % (r["round"], k, got.get(k), want)
    return True, "每轮都与 --expect 一致"


# -- main ------------------------------------------------------
def main(argv=None):
    ap = argparse.ArgumentParser(
        description="BUD 类 TNT 复制装置（vanilla 26.3 实测；docs/23 11.10 未实现项）")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--wait", type=float, default=0.05)
    ap.add_argument("--rounds", type=int, default=5,
                    help="复制轮数（每轮完整重建 -> 预置 -> 触发 -> 逐 tick 断言）")
    ap.add_argument("--step-after", type=int, default=4,
                    help="触发后逐 tick 观测多少 tick（默认 4：点燃=t+1，落地=t+3）")
    ap.add_argument("--arm-offset", type=int, default=0,
                    help="observer 点亮后再等 N tick 才装 lever/上电（Folia 上 RCON 按 tick 派发，"
                         "需要补偿；用 --calibrate 自动扫。vanilla=0，CeruLean 实测=2）")
    ap.add_argument("--calibrate", action="store_true",
                    help="先扫 arm-offset 0..6（每档 1 轮），把第一档能复制的偏移用于正式轮次；"
                         "都失败则 FAIL（退出码 1）")
    ap.add_argument("--controls", action="store_true",
                    help="额外跑 2 组对照（不装 lever / observer 不打脉冲），预期都不复制")
    ap.add_argument("--realtime", action="store_true",
                    help="不 freeze：世界按 /tick rate 实时跑（默认 rate=1），按时间采样")
    ap.add_argument("--canary", action="store_true",
                    help="额外放一个不参与推动的 BUD TNT 作时序见证器：它点燃 => 更新确实发了")
    ap.add_argument("--realtime-rate", type=float, default=20.0,
                    help="realtime 模式用于把秒换算成 tick 的速率（默认 20，与实际 tick rate 一致）")
    ap.add_argument("--offset-x", type=int, default=0)
    ap.add_argument("--offset-z", type=int, default=0)
    ap.add_argument("--y", type=int, default=DEFAULT_Y)
    ap.add_argument("--no-forceload", action="store_true",
                    help="不 forceload（默认会做：26.3 出生点不在 0,0）")
    ap.add_argument("--baseline", default="vanilla",
                    help="基线名（vanilla/paper/cerulean），写进结果 JSON")
    ap.add_argument("--label", default="")
    ap.add_argument("--expect", default=None)
    ap.add_argument("--out", default=None,
                    help="结果 JSON 路径；默认 $HOME/cerulean-scratch/bud-duper-<baseline>.json")
    ap.add_argument("--print-json", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if args.rounds < 1:
        print("[FAIL] --rounds 至少为 1", file=sys.stderr)
        return EXIT_FAIL
    if args.step_after < 2:
        print("[FAIL] --step-after 至少为 2（点燃在 t+1，落地在 t+3）", file=sys.stderr)
        return EXIT_FAIL

    try:
        expect, expect_src = parse_expect(args.expect)
    except (RigError, OSError, ValueError) as exc:
        print("[FAIL] --expect 解析失败：%s" % exc, file=sys.stderr)
        return EXIT_FAIL

    g = geometry(args.offset_x, args.offset_z, args.y)
    box = clear_box(g)
    rc = Rcon(host=args.host, port=args.port, password=args.password,
              timeout=args.timeout, wait=args.wait)
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    t0 = time.time()

    print("== BUD 类 TNT 复制（%s）" % DOC)
    print("   活塞=%s  TNT=%s  observer=%s  导体=%s  lever=%s  电源=%s"
          % (g["piston"], g["tnt"], g["observer"], g["conductor"], g["lever"], g["power"]))
    print("   RCON %s:%d  轮数 %d  对照 %s  清场盒 %s"
          % (rc.host, rc.port, args.rounds, "on" if args.controls else "off", box))
    print("   判据：pre TNT方块=1/primed=0 -> 移动过程中出现 primed -> post TNT方块不减 且 primed>=1")

    env = {}
    try:
        env["list"] = (rc.cmd("list", wait=0.5) or "").strip()
        got = (rc.cmd("gamerule tnt_explodes", wait=0.2) or "").strip()
        if "currently set to" in got.lower():
            env["gamerule_tnt_explodes"] = got
        env["tick_query"] = (rc.cmd("tick query", wait=0.5) or "").strip()
    except RigUnavailable as exc:
        print("[SKIP] RCON 不可用：%s" % exc, file=sys.stderr)
        return EXIT_SKIP

    rounds = []
    controls = []
    aborted_kind = None
    aborted_msg = ""
    chunks = []
    frozen = False
    calibration = None
    try:
        fr = (rc.cmd("tick freeze", wait=0.5) or "").lower()
        if "frozen" not in fr:
            raise RigError("tick freeze 不可用（26.3 应自带）：%r" % fr[:160])
        frozen = True
        if args.realtime:
            print("   /tick freeze 生效（预置阶段用 step 精确定时）；"
                  "每轮「活塞移动」那一步会解冻，让 moveBlocks 在实时世界里跑，再按时间轮询")
        else:
            print("   /tick freeze 生效：世界已冻结（预置阶段不 tick，用 /tick step 推进）")

        if not args.no_forceload:
            chunks, fl_errors = rc.ensure_loaded(*box)
            print("   区块加载：%d 个 %s" % (len(chunks), chunks[:4]))
            for (cx, cz), err in fl_errors:
                print("   警告：forceload chunk %s,%s 失败：%s" % (cx, cz, err), file=sys.stderr)

        calibration = None

        def do_round(idx, mode, arm_offset=None):
            off = args.arm_offset if arm_offset is None else arm_offset
            if args.realtime:
                return run_round_realtime(rc, g, idx, mode, rate=args.realtime_rate,
                                          step_after=args.step_after, canary=args.canary,
                                          arm_offset=off)
            return run_round(rc, g, idx, mode, step_after=args.step_after, canary=args.canary,
                             arm_offset=off)

        if args.calibrate:
            tried = []
            chosen = None
            for k in range(0, 7):
                res = do_round(0, "dupe", arm_offset=k)
                tried.append({"arm_offset": k, "duplicated": res["duplicated"],
                              "ignite_tick": res["ignite_tick"],
                              "tnt_blocks_after": res["tnt_blocks_after"],
                              "primed": res["post"]["primed"]})
                print("   校准 arm-offset=%d -> duplicated=%s ignite=%s tnt_after=%s primed=%s"
                      % (k, res["duplicated"], res["ignite_tick"],
                         res["tnt_blocks_after"], res["post"]["primed"]))
                if res["duplicated"] and chosen is None:
                    chosen = k
            calibration = {"tried": tried, "chosen": chosen}
            if chosen is None:
                raise RigError("arm-offset 0..6 都没能复制（校准失败）")
            args.arm_offset = chosen
            print("   校准结果：用 arm-offset=%d 跑正式轮次" % chosen)

        def print_round(res):
            seq = res.get("ticks") or res.get("samples") or []
            for st in seq:
                if "tick" in st:
                    print("      +%d %s" % (st["tick"], fmt_obs(st)))
                else:
                    print("      t=%5.2fs(~tick %5.2f) primed=%s src=%s dst=%s ext=%s"
                          % (st["t"], st["est_tick"], st["primed_exists"],
                             st["tnt_at_src"], st["tnt_at_dst"], st["piston_extended"]))

        if args.controls:
            for mode, title in (("control_no_arm", "对照 A：TNT 不供电（非 BUD 态）"),
                                ("control_unlit", "对照 B：observer 不打脉冲（不触发）")):
                res = do_round(0, mode)
                controls.append(res)
                print("   %s -> %s" % (title, "OK（不复制，符合预期）" if res["ok"] else "异常"))
                if not args.quiet:
                    print_round(res)

        for i in range(1, args.rounds + 1):
            res = do_round(i, "dupe")
            rounds.append(res)
            if not args.quiet:
                print("   第 %d 轮: 点燃 tick=%s%s  TNT方块 %d->%d  primed=%d  duplicated=%s  (%sms)"
                      % (i, res["ignite_tick"], "（估算）" if res.get("ignite_tick_estimated") else "",
                         res["tnt_blocks_before"], res["tnt_blocks_after"],
                         res["post"]["primed"], res["duplicated"], res["elapsed_ms"]))
                print_round(res)
            if not res["ok"]:
                print("      注意：%s" % "；".join(res["notes"]), file=sys.stderr)

    except RigUnavailable as exc:
        aborted_kind, aborted_msg = "unavailable", str(exc)
        print("[SKIP] RCON 中断：%s" % exc, file=sys.stderr)
    except RigError as exc:
        aborted_kind, aborted_msg = "error", str(exc)
        print("[FAIL] 装置命令失败：%s" % exc, file=sys.stderr)
    finally:
        try:
            rc.kill_entities("minecraft:tnt")
        except Exception:
            pass
        if frozen:
            try:
                rc.cmd("tick unfreeze", wait=0.5)
                print("   /tick unfreeze（世界恢复 tick）")
            except Exception:
                pass


    # -- 汇总 --
    ok_rounds = [r for r in rounds if r["ok"]]
    dup_rounds = [r for r in rounds if r["duplicated"]]
    ignition_ticks = sorted({r["ignite_tick"] for r in rounds if r["ignite_tick"] is not None})
    before = sorted({r["tnt_blocks_before"] for r in rounds})
    after = sorted({r["tnt_blocks_after"] for r in rounds})
    controls_ok = all(c["ok"] for c in controls) if controls else None

    verdict, reason = "PASS", ""
    if aborted_kind == "unavailable":
        verdict, reason = "SKIP", "RCON 中断，仅完成 %d/%d 轮：%s" % (len(rounds), args.rounds, aborted_msg)
    elif aborted_kind == "error":
        verdict, reason = "FAIL", "装置命令失败，仅完成 %d/%d 轮：%s" % (len(rounds), args.rounds, aborted_msg)
    elif len(rounds) < args.rounds:
        verdict, reason = "FAIL", "只完成 %d/%d 轮" % (len(rounds), args.rounds)
    elif len(dup_rounds) < len(rounds):
        verdict, reason = "FAIL", ("%d/%d 轮没有复制成功（逐 tick 原始读数见 rounds）"
                                   % (len(rounds) - len(dup_rounds), len(rounds)))
    elif controls_ok is False:
        verdict, reason = "FAIL", "对照组出现了复制：机关归因不成立"
    elif expect is not None:
        ok, detail = compare(rounds, expect)
        verdict, reason = ("PASS" if ok else "FAIL"), detail
    else:
        tail = "，对照组均不复制" if controls else ""
        verdict, reason = "PASS", ("%d 轮全部复制：TNT 方块数不减（%s -> %s），点燃发生在 %s tick%s"
                                   % (len(rounds), before, after, ignition_ticks, tail))

    summary = {
        "dupe": verdict == "PASS",
        "ignite_tick": ignition_ticks[0] if len(ignition_ticks) == 1 else ignition_ticks,
        "tnt_blocks_after": after[0] if len(after) == 1 else after,
        "tnt_blocks_before": before[0] if len(before) == 1 else before,
        "rounds_ok": len(ok_rounds),
        "rounds_total": len(rounds),
        "duplicated_rounds": len(dup_rounds),
        "controls_ok": controls_ok,
        "verdict": verdict,
        "reason": reason,
    }

    print()
    print("== 汇总：复制成功 %d/%d 轮；TNT 方块数 %s -> %s；点燃 tick %s"
          % (len(dup_rounds), len(rounds), before, after, ignition_ticks))
    if controls:
        print("   对照：%s" % ("全部不复制（符合预期）" if controls_ok else "出现异常复制"))
    if expect is not None:
        print("   期望（%s）：%s" % (expect_src, json.dumps(expect, ensure_ascii=False, sort_keys=True)))
    print("== 判定 %s: %s" % (verdict, reason))

    result = {
        "probe": PROBE,
        "doc": DOC,
        "baseline": args.baseline,
        "label": args.label,
        "started_at": started,
        "elapsed_s": round(time.time() - t0, 2),
        "rcon": {"host": rc.host, "port": rc.port, "commands": rc.commands},
        "environment": env,
        "geometry": {k: list(v) for k, v in g.items()},
        "clear_box": list(box),
        "chunks": [list(c) for c in chunks],
        "device": {
            "push_direction": "+x",
            "blocks": [
                {"pos": list(g["piston"]), "block": PISTON},
                {"pos": list(g["slime"]), "block": SLIME},
                {"pos": list(g["tnt"]), "block": TNT},
                {"pos": list(g["observer"]), "block": OBSERVER},
                {"pos": list(g["conductor"]), "block": CONDUCTOR},
                {"pos": list(g["lever"]), "block": LEVER},
                {"pos": list(g["power"]), "block": POWER},
            ],
            "mechanism": ("TNT 经导体（stone）由 lever 强供电而保持 BUD 态；活塞推动时"
                          "observer 的 affectNeighborsAfterRemoval（不检查 movedByPiston）"
                          "通知 TNT -> 移动过程中点燃；moving BE 用移动前快照仍落下 TNT"),
        },
        "realtime": bool(args.realtime),
        "arm_offset": args.arm_offset,
        "calibration": calibration,
        "canary_enabled": bool(args.canary),
        "tick_rate": None if not args.realtime else args.realtime_rate,
        "rounds_requested": args.rounds,
        "rounds": rounds,
        "controls": controls,
        "summary": summary,
        "expect": expect,
        "expect_source": expect_src,
        "verdict": verdict,
        "reason": reason,
    }

    out = args.out
    if not out:
        out = os.path.join(os.path.expanduser("~"), "cerulean-scratch",
                           "bud-duper-%s.json" % (args.baseline or "vanilla"))
    parent = os.path.dirname(os.path.abspath(out))
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
    print("   结果 JSON: %s" % os.path.abspath(out))
    if args.print_json:
        print(json.dumps(result, ensure_ascii=False, indent=2))

    if verdict == "SKIP":
        return EXIT_SKIP
    if verdict == "FAIL":
        return EXIT_FAIL
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())
