#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""装置库公共层：RCON 封装 + 断言 + rig JSON 执行器（docs/23 P0/P1）。

为什么单独一层
--------------
docs/23 §6.1 的判定原则是「同一装置在三档基线上观测逐项相同」。要做到这一条，
装置脚本里就不能出现任何一档专属的写法，于是把「发命令 / 读状态 / 断言」收进这一层；
所有装置（m1_piston_race.py、将来的 m2..m7）只描述装置本身。

三条硬约束（都来自踩过的坑）
---------------------------
1) **每条命令一个独立连接**（docs/23 §5.1）：Folia 下部分命令异步执行，共用连接会
   让响应与下一条命令交错，把「命令未注册」和「响应错位」混为一谈。
2) **fire-and-forget 命令不拿返回值做断言**：setblock / fill / kill 的响应可能是空串，
   成败一律用后续的 execute if block 复核（smoke_one 路径里踩过）。
3) **不要用「第 N tick 的精确状态」做断言**（docs/23 §5.3）：RCON 有毫秒级抖动，
   读完再读一次、两次一致才算稳定（见 settle_read）。

退出码（与 tools/smoke/run_suite.py 一致）：0=PASS / 1=FAIL / **2=SKIP（环境前提不满足）**。

命令行（全部参数外部化，不硬编码 host/port/password）
-----------------------------------------------------
    # P0 最小检查：list / gamerule / setblock + execute if block / 清场 / 实体计数 / 快照
    python3 tools/smoke/rigs/rig_common.py --p0 --port 25576 --password test1234

    # 跑一台装置（JSON 契约见 docs/23 §5.4 与同目录 README.md）
    python3 tools/smoke/rigs/rig_common.py --rig tools/smoke/rigs/rigs/m6_control_push.json --json

    # 只校验 JSON 契约，不连服（CI / 写装置时用）
    python3 tools/smoke/rigs/rig_common.py --check-json tools/smoke/rigs/rigs/m2_tnt_duper.json

    # 临时发一条命令（同样一条命令一个连接）
    python3 tools/smoke/rigs/rig_common.py -c "list" -c "gamerule doDaylightCycle"
"""
import argparse
import json
import os
import sys
import time

_SMOKE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _SMOKE_DIR not in sys.path:
    sys.path.insert(0, _SMOKE_DIR)

from rcon import Rcon as _BaseRcon   # noqa: E402
from rcon import RconError           # noqa: E402

EXIT_PASS, EXIT_FAIL, EXIT_SKIP = 0, 1, 2

DEFAULT_HOST = os.environ.get("CERULEAN_RCON_HOST", "127.0.0.1")
DEFAULT_PORT = int(os.environ.get("CERULEAN_RCON_PORT", "25575"))
DEFAULT_PASSWORD = os.environ.get("CERULEAN_RCON_PASSWORD", "test1234")

# cmd() 的 wait 是「收到首个响应包之后再等多久」的窗口（见 tools/smoke/rcon.py）。
# 单行响应的查询命令 0.05 s 足够；fill / forceload / list 这类慢命令显式传大值。
WAIT_FAST = 0.05
WAIT_MEDIUM = 0.5
WAIT_SLOW = 3.0
WAIT_FILL = 2.0

PASS_MARKERS = ("test passed", "测试通过")
FAIL_MARKERS = ("test failed", "测试失败")

# 明确的命令失败特征（小写子串）。注意：**不要**把 "no entity was found" /
# "no blocks were filled" 放进来 —— 那是空集上的正常回答，不是失败。
ERROR_MARKERS = (
    "unknown or incomplete command",
    "unknown command",
    "incorrect argument",
    "expected whitespace",
    "unknown block type",
    "invalid block state",
    "is not loaded",
    "out of bounds",
    "outside of the world",
    "do not have permission",
    "cannot set block",
    "cannot place block",
    "failed to ",
    "this position is not",
)

# 26.3 实测：/setblock 把方块设成**它已经是的样子**时返回 "Could not set the block"
# （no-op 被报成失败）。这不是错误 —— 必须用 execute if block 复核后才能区分
# 「已经是目标状态」和「真的没写进去」，否则每次重跑都会假失败。
NOOP_MARKERS = ("could not set the block",)

# 断言类型白名单：**只允许这五种**，与队友 ws-s2 的 rig JSON 严格对齐。
ASSERT_TYPES = ("entity_min", "entity_max", "block_is",
                "block_min_in_box", "block_max_in_box")

# snapshot 的候选方块集合：execute if block 只能做「是不是 X」的判定，
# 因此快照本质是**候选匹配**（顺序即优先级）。装置自定义候选时传 candidates。
DEFAULT_CANDIDATES = (
    "minecraft:air",
    "minecraft:stone",
    "minecraft:redstone_block",
    "minecraft:redstone_torch",
    "minecraft:redstone_wire",
    "minecraft:piston",
    "minecraft:piston_head",
    "minecraft:moving_piston",
    "minecraft:tnt",
    "minecraft:white_carpet",
    "minecraft:rail",
    "minecraft:powered_rail",
    "minecraft:slime_block",
    "minecraft:observer",
    "minecraft:command_block",
    "minecraft:repeater",
    "minecraft:comparator",
    "minecraft:iron_block",
    "minecraft:oak_planks",
    "minecraft:cobblestone",
    "minecraft:glass",
    "minecraft:torch",
    "minecraft:lever",
)


# 每台装置开始前先 /kill 的「瞬时实体」集合。
# 为什么必须清：primed TNT 有 80 tick（约 4 s）引信，上一台/上一轮残留的实体会让
# 下一轮的 entity_min tnt 1 **假 PASS**，还会在别的装置旁边爆炸（M2/M5 的实际风险）。
# 这不是 rig JSON 的字段（契约不扩展），是执行器的固定卫生动作，可用 --kill-types 覆盖。
TRANSIENT_ENTITY_TYPES = (
    "minecraft:tnt",            # 已点燃的 TNT（PrimedTnt）
    "minecraft:item",           # 复制类装置掉落的物品
    "minecraft:falling_block",  # 被推动后下落的方块实体
    "minecraft:experience_orb",
)


class RigError(RuntimeError):
    """装置层错误：命令失败、JSON 契约不合法、快照越界等。"""


class RigUnavailable(RconError):
    """RCON 连不上 / 起服未就绪 —— 上层应映射为 SKIP（exit 2），不是 FAIL。"""


# ── 坐标 / 盒子工具 ────────────────────────────────────────────
def _norm_pos(pos):
    if not isinstance(pos, (list, tuple)) or len(pos) != 3:
        raise RigError(f"坐标必须是 3 元组 [x,y,z]，收到 {pos!r}")
    return tuple(int(v) for v in pos)


def box_chunks(box):
    """盒子覆盖到的所有区块坐标 (cx, cz)，闭区间。"""
    x1, y1, z1, x2, y2, z2 = box
    cxs = range(min(x1, x2) // 16, max(x1, x2) // 16 + 1)
    czs = range(min(z1, z2) // 16, max(z1, z2) // 16 + 1)
    return [(cx, cz) for cx in cxs for cz in czs]


def _entry_pos_block(raw):
    """方块条目两种写法都接受：[x,y,z,block] 或 ((x,y,z), block)（后者来自 load_rig_json）。"""
    if isinstance(raw, (list, tuple)) and len(raw) == 2 and isinstance(raw[0], (list, tuple)):
        return _norm_pos(raw[0]), str(raw[1])
    if isinstance(raw, (list, tuple)) and len(raw) == 4:
        return _norm_pos(raw[:3]), str(raw[3])
    raise RigError(f"方块条目必须是 [x,y,z,block] 或 ((x,y,z), block)，收到 {raw!r}")


def _as_box(args):
    """把 (x1,y1,z1,x2,y2,z2) 或单个 6 元组/列表统一成 6 元组。"""
    if len(args) == 1 and isinstance(args[0], (list, tuple)):
        vals = list(args[0])
    else:
        vals = list(args)
    if len(vals) != 6:
        raise RigError(f"盒子要 6 个整数 [x1,y1,z1,x2,y2,z2]，收到 {vals!r}")
    return tuple(int(v) for v in vals)


def _box_cells(box, max_cells=4096):
    """盒子内所有整数位置（含边界）；顺序固定，便于逐项比对。"""
    x1, y1, z1, x2, y2, z2 = box
    xs = range(min(x1, x2), max(x1, x2) + 1)
    ys = range(min(y1, y2), max(y1, y2) + 1)
    zs = range(min(z1, z2), max(z1, z2) + 1)
    n = len(xs) * len(ys) * len(zs)
    if n > max_cells:
        raise RigError(f"盒子 {box} 有 {n} 格，超过上限 {max_cells}")
    return [(x, y, z) for x in xs for y in ys for z in zs]


# ── RCON（每条命令一个独立连接）────────────────────────────────
class Rcon(object):
    """每条命令一个独立连接的 RCON 客户端。

    与 tools/smoke/rcon.py 的 Rcon 是**同一套协议实现**（直接复用，不复制粘贴），
    区别只在生命周期：这里每次 cmd() 新建连接、发一条、关闭。
    """

    def __init__(self, host=None, port=None, password=None, timeout=30.0,
                 wait=WAIT_FAST, quiet=True, retries=2):
        self.host = host or DEFAULT_HOST
        self.port = int(DEFAULT_PORT if port is None else port)
        self.password = DEFAULT_PASSWORD if password is None else password
        self.timeout = float(timeout)
        self.wait = float(wait)
        self.quiet = quiet
        self.retries = max(1, int(retries))
        self.commands = 0
        self.last_error = ""

    # ── 底层 ────────────────────────────────────────────────
    def cmd(self, command, wait=None, first_wait=None):
        """发一条命令并返回文本响应（可能为空串）。

        只有**连接阶段**失败才重试（此时命令尚未发出，重试不会重复执行）；
        命令发出后的读失败直接抛 RigUnavailable —— 否则 setblock / scoreboard add
        这类非幂等命令会被重复执行。
        """
        last = None
        for attempt in range(self.retries):
            rc = _BaseRcon(self.host, self.port, self.password,
                           timeout=self.timeout, quiet=self.quiet)
            try:
                rc.connect()
            except RconError as exc:
                last = exc
                rc.close()
                if attempt + 1 >= self.retries:
                    break
                time.sleep(0.5)
                continue
            try:
                # first_wait 是「首个响应包」的上限，必须比 wait 宽松：
                # wait 只是收到首个包之后再等「多余包」的窗口，两者混用会在服务器
                # 有负载时把正常响应当成超时（返回空串 -> 无法判定 -> 假失败）。
                base_wait = self.wait if wait is None else float(wait)
                fw = first_wait if first_wait is not None else max(base_wait, 0.75)
                out = rc.cmd(command, wait=base_wait, first_wait=fw)
                self.commands += 1
                return out
            except RconError as exc:
                # 命令已经发出去了：**不重试**（可能非幂等），按环境问题上报 -> SKIP
                raise RigUnavailable(
                    f"RCON 读取失败 {self.host}:{self.port}（命令 {command!r}）: {exc}") from exc
            finally:
                rc.close()
        self.last_error = str(last)
        raise RigUnavailable(
            f"RCON 不可用 {self.host}:{self.port}（命令 {command!r}）: {last}")

    def _raise_on_error(self, out, desc):
        low = (out or "").lower()
        for marker in ERROR_MARKERS:
            if marker in low:
                raise RigError(f"{desc} 失败：服务端返回 {(out or '').strip()[:220]!r}")

    # ── 写入 ────────────────────────────────────────────────
    def setblock(self, pos, block, verify=True, wait=None):
        """放一个方块；verify=True 时用 execute if block 复核（不信 fire-and-forget 响应）。

        26.3 的坑：目标格已经是这个状态时，/setblock 返回 "Could not set the block"。
        这既可能是「已经是目标状态」（正常），也可能是「真没写进去」（异常），
        所以 noop 响应一样要复核；verify=False 的供电块遇到 noop 也复核一次，把两者分开。
        """
        x, y, z = _norm_pos(pos)
        out = self.cmd(f"setblock {x} {y} {z} {block}", wait=wait)
        low = (out or "").lower()
        noop = any(m in low for m in NOOP_MARKERS)
        if not noop:
            self._raise_on_error(out, f"setblock {x} {y} {z} {block}")
        if verify or noop:
            if not self.assert_block((x, y, z), block):
                raise RigError(
                    f"setblock {x} {y} {z} {block} 发出后复核不通过"
                    f"（服务端返回 {(out or '').strip()[:160]!r}）")
        return out

    def setblocks(self, blocks, verify=True):
        """批量 /setblock：按给定顺序逐条放，任何一条失败立刻抛。

        blocks 里每项是 [x, y, z, "minecraft:xxx[state=...]"]。
        **顺序有意义**（装置普遍要求先搭结构、最后放供电方块），这里不排序、不去重。
        """
        count = 0
        for raw in blocks:
            pos, block = _entry_pos_block(raw)
            self.setblock(pos, block, verify=verify)
            count += 1
        return count

    def set_blocks_same_tick(self, entries):
        """把若干 (pos, block) 写在**同一 tick** —— 用一次 /fill 覆盖同 id 的连续格。

        本探针用不到，留给 P2/P3；实现上只支持「同一方块、同一 y、x/z 共线且连续」，
        不满足时抛 RigError（宁可不做，也不要在不同 tick 里假装同时）。
        """
        raise RigError("set_blocks_same_tick 尚未实现：多供电块的同 tick 触发请改用单个红石块")

    def clear_area(self, *args, wait=WAIT_FILL, block="minecraft:air"):
        """清场：/fill <box> air。接受 6 个整数或一个 6 元组。

        注意：**清场会连平台一起清掉**，所以盒子的 y 边界要自己负责（M1 只清 y=-50 这一层，
        平台 y=-51 保持不动）。
        """
        box = _as_box(args)
        x1, y1, z1, x2, y2, z2 = box
        out = self.cmd(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}", wait=wait)
        self._raise_on_error(out, f"fill {box} {block}")
        return out

    def fill(self, *args, block="minecraft:stone", replace=None, wait=WAIT_FILL):
        """通用 /fill（replace 为空时是全量覆盖）。"""
        box = _as_box(args)
        x1, y1, z1, x2, y2, z2 = box
        cmd = f"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}"
        if replace:
            cmd += f" replace {replace}"
        out = self.cmd(cmd, wait=wait)
        self._raise_on_error(out, f"fill {box} {block} replace={replace}")
        return out

    def ensure_loaded(self, *args, wait=WAIT_MEDIUM):
        """把盒子覆盖到的每个区块 /forceload add（幂等），返回 (chunks, errors)。

        为什么必须做：26.3 的世界出生点由启动时的 "Selecting global world spawn" 动态选，
        (0,0) 不一定在出生点常加载区里 —— 首次 setblock / execute if block 会直接报
        "That position is not loaded"（本机 vanilla 实测）。forceload 让测试区与出生点无关，
        也顺带把区块加载固定下来（M1 需要稳定 tick，不能靠"玩家在附近"这种偶然条件）。

        参数同 clear_area：6 个整数或一个 6 元组。错误不抛，收集后返回，由调用方决定是否 FAIL。
        """
        box = _as_box(args)
        chunks, errors = [], []
        for cx, cz in box_chunks(box):
            chunks.append((cx, cz))
            try:
                # /forceload add <x> <z> 收的是**方块坐标**（实测 add 20 20 -> chunk [1,1]）
                out = self.cmd(f"forceload add {cx * 16 + 8} {cz * 16 + 8}", wait=wait)
                low = (out or "").lower()
                # 三种正常回答：刚标记 / 早就标记过 / "No chunks were marked"（已是强制加载）
                if "marked chunk" in low or "already" in low or "no chunks were marked" in low:
                    continue
                self._raise_on_error(out, f"forceload add chunk {cx},{cz}")
                errors.append(((cx, cz), (out or "").strip()[:120]))
            except RigError as exc:
                errors.append(((cx, cz), str(exc)[:160]))
        return chunks, errors

    def kill_entities(self, entity, distance=None, wait=WAIT_MEDIUM):
        """清实体：/kill @e[type=...]。空集不是错误（"No entity was found"）。"""
        sel = f"@e[type={entity}]"
        if distance is not None:
            sel = f"@e[type={entity},distance=..{distance}]"
        out = self.cmd(f"kill {sel}", wait=wait)
        low = (out or "").lower()
        if "no entity was found" not in low:
            self._raise_on_error(out, f"kill {sel}")
        return out

    # ── 读取 / 断言 ─────────────────────────────────────────
    def probe_block(self, pos, block):
        """execute if block -> True / False / None（None = 响应无法判定）。

        空响应不直接判 None：先重读一次（本地网络抖动 / 服务器瞬时卡顿都会导致空串），
        仍无法判定才算 None —— 避免把一次丢包变成假失败。
        """
        x, y, z = _norm_pos(pos)
        for attempt in range(2):
            out = (self.cmd(f"execute if block {x} {y} {z} {block}") or "").lower()
            if any(m in out for m in PASS_MARKERS):
                return True
            if any(m in out for m in FAIL_MARKERS):
                return False
            self.last_error = out.strip()[:200]
            if attempt == 0:
                time.sleep(0.05)
        return None

    def assert_block(self, pos, block):
        """方块必须是 block（block 可以带状态谓词，例如 minecraft:piston[extended=true]）。"""
        res = self.probe_block(pos, block)
        if res is None:
            raise RigError(
                f"execute if block {tuple(_norm_pos(pos))} {block} 响应无法判定："
                f"{self.last_error!r}")
        return res

    def probe_entity(self, entity, selector_extra=""):
        sel = f"@e[type={entity}{selector_extra}]"
        out = (self.cmd(f"execute if entity {sel}") or "").lower()
        if any(m in out for m in PASS_MARKERS):
            return True
        if any(m in out for m in FAIL_MARKERS):
            return False
        self.last_error = out.strip()[:200]
        return None

    def entity_count(self, entity, objective="ceru_rig_count"):
        """实体个数。

        vanilla 没有「返回实体数」的命令，这里用记分板求和的标准写法：
            scoreboard objectives add <obj> dummy
            scoreboard players set #count <obj> 0
            execute as @e[type=X] run scoreboard players add #count <obj> 1
            scoreboard players get #count <obj>
        每次 as 都在同一个假玩家上 +1，等于计数。

        **Folia 注意**：execute as @e 依赖选择器能否看到目标实体；若 CeruLean 的
        区域化命令层只暴露当前区域内的实体，计数会偏小。跨区装置（P3）必须改用
        「每个候选位置单独 execute if block」或分区域计数，不要直接信这个数。
        """
        self.cmd(f"scoreboard objectives add {objective} dummy", wait=WAIT_FAST)
        self.cmd(f"scoreboard players set #count {objective} 0", wait=WAIT_FAST)
        out = self.cmd(f"execute as @e[type={entity}] run "
                       f"scoreboard players add #count {objective} 1", wait=WAIT_MEDIUM)
        self._raise_on_error(out, f"execute as @e[type={entity}] 计数")
        got = self.cmd(f"scoreboard players get #count {objective}", wait=WAIT_FAST)
        self._raise_on_error(got, f"scoreboard players get #count {objective}")
        for token in (got or "").replace("(", " ").replace(")", " ").split():
            try:
                return int(token)
            except ValueError:
                continue
        raise RigError(f"无法从 {got!r} 解析实体计数")

    def check_entity(self, entity, min_=None, max_=None):
        """(ok, 说明)。min_/max_ 为 None 表示该侧不限。

        只有「存在/不存在」就够的场景走 execute if/unless entity（快且不依赖记分板）；
        需要确切数量时才计数。
        """
        if min_ is not None and min_ <= 0:
            min_ = None                      # min=0 是「不限下界」，不能退化成「必须存在」
        if min_ is not None and max_ is not None and min_ > max_:
            raise RigError(f"矛盾断言：min={min_} > max={max_}（entity {entity}）")
        if min_ is None and max_ is None:
            return True, f"entity {entity} 无上下界约束（恒真）"
        if max_ == 0:
            exists = self.probe_entity(entity)
            if exists is None:
                raise RigError(f"execute if entity @e[type={entity}] 响应无法判定")
            return (not exists), f"entity {entity} 期望 0 个（execute if entity → {exists}）"
        if min_ is not None and min_ <= 1 and max_ is None:
            exists = self.probe_entity(entity)
            if exists is None:
                raise RigError(f"execute if entity @e[type={entity}] 响应无法判定")
            return bool(exists), f"entity {entity} 存在={exists}（min={min_}）"
        n = self.entity_count(entity)
        ok = True
        if min_ is not None and n < min_:
            ok = False
        if max_ is not None and n > max_:
            ok = False
        return ok, f"entity {entity} count={n}（min={min_} max={max_}）"

    def assert_entity_count(self, entity, min_=None, max_=None):
        return self.check_entity(entity, min_, max_)[0]

    def assert_entity_min(self, entity, n):
        return self.assert_entity_count(entity, min_=n)

    def assert_entity_max(self, entity, n):
        return self.assert_entity_count(entity, max_=n)

    def count_block_in_box(self, block, *args, max_cells=4096):
        """盒子里 block 的个数（按 execute if block 逐格判定）。"""
        box = _as_box(args)
        cells = _box_cells(box, max_cells=max_cells)
        n = 0
        for pos in cells:
            if self.probe_block(pos, block):
                n += 1
        return n

    def count_block_until(self, block, box, stop_above=None, stop_at_or_above=None,
                          max_cells=4096):
        """带早停的计数：够了就不再发命令（大盒子省时间）。

        stop_at_or_above: 计数一到这个值就返回（用于 min 判定）。
        stop_above: 计数一超过这个值就返回（用于 max 判定，返回 max+1 即可）。
        """
        cells = _box_cells(box, max_cells=max_cells)
        n = 0
        for pos in cells:
            if self.probe_block(pos, block):
                n += 1
                if stop_at_or_above is not None and n >= stop_at_or_above:
                    return n
                if stop_above is not None and n > stop_above:
                    return n
        return n

    def assert_block_min_in_box(self, block, n, *args):
        box = _as_box(args)
        got = self.count_block_until(block, box, stop_at_or_above=n)
        return got >= n

    def assert_block_max_in_box(self, block, n, *args):
        box = _as_box(args)
        got = self.count_block_until(block, box, stop_above=n)
        return got <= n

    def snapshot(self, *args, candidates=None, max_cells=1024):
        """把盒子读成 {(x,y,z): '匹配到的候选方块名或 ?'}。

        机制限制（必须知道）：vanilla 没有「读某格是什么方块」的单条命令
        （/data get block 只对**方块实体**有效）。所以这里用 execute if block
        对**候选集合**逐个试，第一个命中即为结果；没命中记 '?'
        ⇒ 候选里没列到的方块会显示 '?'，装置要显式传 candidates。
        每条 (位置 x 候选) 都是一条独立连接，盒子别开太大（默认上限 1024 格）。
        """
        box = _as_box(args)
        cands = tuple(candidates or DEFAULT_CANDIDATES)
        cells = _box_cells(box, max_cells=max_cells)
        out = {}
        for pos in cells:
            hit = "?"
            for cand in cands:
                if self.probe_block(pos, cand):
                    hit = cand
                    break
            out[f"{pos[0]},{pos[1]},{pos[2]}"] = hit
        return out

    # ── 稳定性采样 ──────────────────────────────────────────
    def settle_read(self, probe, interval=0.10, timeout_ms=2000, need=2):
        """反复采样直到连续 need 次结果相同；返回 (sample, elapsed_ms, stable)。

        为什么必须有它：docs/23 §5.3 —— 不要用「第 N tick 的精确状态」做断言。
        活塞的 moving_piston 只存在 1 tick（S5），服务器有负载时 tick 会拉长，
        单次读可能正好落在移动中间态；两次一致才说明场景已经定住。
        """
        t0 = time.time()
        prev_key = None
        prev = None
        same = 0
        while True:
            cur = probe()
            key = json.dumps(cur, sort_keys=True, ensure_ascii=False, default=str)
            if key == prev_key:
                same += 1
            else:
                same = 1
                prev, prev_key = cur, key
            elapsed = (time.time() - t0) * 1000.0
            if same >= need:
                return prev, elapsed, True
            if elapsed >= timeout_ms:
                return cur, elapsed, False
            time.sleep(interval)


# ── rig JSON 契约（docs/23 §5.4）───────────────────────────────
def _rel_pos(raw, origin, what):
    if not isinstance(raw, (list, tuple)) or len(raw) != 3:
        raise RigError(f"{what} 必须是相对 origin 的 [dx,dy,dz]，收到 {raw!r}")
    return (origin[0] + int(raw[0]), origin[1] + int(raw[1]), origin[2] + int(raw[2]))


def _rel_box(raw, origin, what):
    if not isinstance(raw, (list, tuple)) or len(raw) != 6:
        raise RigError(f"{what} 必须是相对 origin 的 [dx1,dy1,dz1,dx2,dy2,dz2]，收到 {raw!r}")
    v = [int(x) for x in raw]
    return (origin[0] + v[0], origin[1] + v[1], origin[2] + v[2],
            origin[0] + v[3], origin[1] + v[4], origin[2] + v[5])


def _norm_entries(raw, origin, what):
    if raw is None:
        return []
    if not isinstance(raw, (list, tuple)):
        raise RigError(f"{what} 必须是列表，收到 {type(raw).__name__}")
    out = []
    for item in raw:
        if not isinstance(item, (list, tuple)) or len(item) != 4:
            raise RigError(f"{what} 每项必须是 [dx,dy,dz,block]，收到 {item!r}")
        out.append((_rel_pos(item[:3], origin, what), str(item[3])))
    return out


def _norm_asserts(raw, origin):
    if raw is None:
        return []
    if not isinstance(raw, (list, tuple)):
        raise RigError("assert 必须是列表")
    out = []
    for idx, a in enumerate(raw):
        if not isinstance(a, dict):
            raise RigError(f"assert[{idx}] 必须是对象，收到 {a!r}")
        t = a.get("type")
        if t not in ASSERT_TYPES:
            raise RigError(
                f"assert[{idx}] 的 type={t!r} 不在允许集合 {list(ASSERT_TYPES)}"
                "（只允许这五种，新增类型要先改文档和本文件）")
        if t in ("entity_min", "entity_max"):
            entity = a.get("entity")
            n = a.get("n")
            if not isinstance(entity, str) or not entity:
                raise RigError(f"assert[{idx}] {t} 需要 entity（字符串）")
            if not isinstance(n, int) or n < 0:
                raise RigError(f"assert[{idx}] {t} 需要 n（>=0 的整数）")
            out.append({"type": t, "entity": entity, "n": n})
        elif t == "block_is":
            pos = a.get("pos")
            block = a.get("block")
            if not isinstance(block, str) or not block:
                raise RigError(f"assert[{idx}] block_is 需要 block（字符串）")
            out.append({"type": t, "pos": _rel_pos(pos, origin, "assert.block_is.pos"),
                        "block": block, "rel": list(pos)})
        else:  # block_min_in_box / block_max_in_box
            block = a.get("block")
            n = a.get("n")
            box = a.get("box")
            if not isinstance(block, str) or not block:
                raise RigError(f"assert[{idx}] {t} 需要 block（字符串）")
            if not isinstance(n, int) or n < 0:
                raise RigError(f"assert[{idx}] {t} 需要 n（>=0 的整数）")
            out.append({"type": t, "block": block, "n": n,
                        "box": _rel_box(box, origin, f"assert[{idx}].box")})
    return out


def load_rig_json(path):
    """读 + 规范化 + 校验 rig JSON（**不连服**）。契约见 README.md。"""
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise RigError(f"{path}: 顶层必须是 JSON 对象")
    name = data.get("name") or os.path.splitext(os.path.basename(path))[0]
    origin = _norm_pos(data.get("origin", [0, 0, 0]))
    blocks = _norm_entries(data.get("blocks"), origin, "blocks")
    power = _norm_entries(data.get("power"), origin, "power")
    asserts = _norm_asserts(data.get("assert"), origin)
    clear = data.get("clear")
    clear_box = _rel_box(clear, origin, "clear") if clear else None
    wait_ms = int(data.get("wait_ms", 300))
    if wait_ms < 0:
        raise RigError(f"{path}: wait_ms 不能为负")
    # 派生清场盒子：结构包围盒在 x/z 方向外扩 pad（y 不扩，免得把平台一起清掉）
    if clear_box is None:
        pts = [p for p, _ in blocks] + [p for p, _ in power]
        for a in asserts:
            if "box" in a:
                b = a["box"]
                pts += [(b[0], b[1], b[2]), (b[3], b[4], b[5])]
            elif "pos" in a:
                pts.append(a["pos"])
        if pts:
            pad = int(data.get("clear_pad", 2))
            clear_box = (min(p[0] for p in pts) - pad, min(p[1] for p in pts),
                         min(p[2] for p in pts) - pad,
                         max(p[0] for p in pts) + pad, max(p[1] for p in pts),
                         max(p[2] for p in pts) + pad)
    return {
        "name": name,
        "path": os.path.abspath(path),
        "origin": origin,
        "blocks": blocks,
        "power": power,
        "assert": asserts,
        "clear": clear_box,
        "wait_ms": wait_ms,
        "notes": data.get("notes", ""),
    }


def evaluate_asserts(rc, rig):
    """逐条求值，返回 [{type, ok, detail}]；不因单条失败中断（要一次看全）。"""
    results = []
    for a in rig["assert"]:
        t = a["type"]
        if t == "entity_min":
            ok, detail = rc.check_entity(a["entity"], min_=a["n"])
        elif t == "entity_max":
            ok, detail = rc.check_entity(a["entity"], max_=a["n"])
        elif t == "block_is":
            got = rc.probe_block(a["pos"], a["block"])
            ok = bool(got)
            detail = f"block {a['pos']} == {a['block']} ? {ok}"
        elif t == "block_min_in_box":
            got = rc.count_block_until(a["block"], a["box"], stop_at_or_above=a["n"])
            ok = got >= a["n"]
            detail = f"box {a['box']} 中 {a['block']} >= {a['n']} ? 实测 {got}"
        else:
            got = rc.count_block_until(a["block"], a["box"], stop_above=a["n"])
            ok = got <= a["n"]
            detail = f"box {a['box']} 中 {a['block']} <= {a['n']} ? 实测 {got}"
        results.append({"type": t, "ok": ok, "detail": detail})
    return results


def apply_power(rc, power):
    """放供电方块（永远最后放）。返回 (是否保证同 tick, 说明)。

    同 tick 只有在**只有一块**供电方块时才成立：一条 /setblock 一次 tick。
    多块时它们落在不同 tick，测出来的是命令延迟而不是更新顺序（docs/23 §P1 明确禁止），
    所以这里只报告、不假装 —— 调用方应把这个标志写进结果 JSON。
    """
    if not power:
        return True, "无 power 条目"
    for pos, block in power:
        rc.setblock(pos, block, verify=False, wait=WAIT_FAST)
    if len(power) == 1:
        return True, "单个供电块：一次 setblock，同一 tick 触发"
    return False, (f"{len(power)} 个供电块逐条 setblock，落在不同 tick；"
                   "同 tick 触发请合并为单块或改用 /fill")


def kill_targets(rig, kill_types=TRANSIENT_ENTITY_TYPES):
    """清实体集合 = 执行器默认的瞬时实体 + assert 里点名的实体类型（去重、保序）。

    为什么要把「assert 里出现的实体」也清：装置断言的实体类型，正是上一轮会残留的那一类。
    只清默认集合时，任何断言了新实体（armor_stand / item / 自定义）的装置都可能假 PASS；
    M2/M5 的 primed TNT 就是这个坑的第一个实例。kill_types 传空元组 = 完全不杀。
    """
    if kill_types is None:
        kill_types = TRANSIENT_ENTITY_TYPES
    if not kill_types:              # 空元组/空列表 = 用户明确关闭（--no-kill），一个都不杀
        return ()
    out = []
    for entity in list(kill_types) + [a.get("entity") for a in rig.get("assert", [])]:
        if entity and entity not in out:
            out.append(entity)
    return out


def run_rig(rig, rc, clear=True, settle_ms=None, kill_types=TRANSIENT_ENTITY_TYPES,
            forceload=True):
    """清实体 -> 清场 -> blocks -> power -> 等待稳定 -> 断言。返回结果 dict。

    kill_types 默认清 TRANSIENT_ENTITY_TYPES（primed TNT / 掉落物 / 下落方块 / 经验球）：
    不清实体时，上一台 M2/M5 残留的 primed TNT 会让下一轮的 entity_min 假 PASS，
    还会在别的装置旁边爆炸（引信 4 s）。传空元组即跳过（--no-kill）。
    注意 kill 只在 clear=True 时执行 —— --no-clear 是「看现场」，不该顺手清实体。
    """
    t0 = time.time()
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    same_tick = True
    power_note = ""
    killed = []
    chunks = []
    fl_errors = []
    if forceload and rig.get("clear"):
        # 先保证区块加载：否则 setblock 会报 "That position is not loaded"（26.3 出生点不在 0,0）
        chunks, fl_errors = rc.ensure_loaded(*rig["clear"])
        for (cx, cz), err in fl_errors:
            print(f"   警告：forceload chunk {cx},{cz} 失败：{err}", file=sys.stderr)
    if clear:
        # 清场 = 清方块 + 清实体（docs/23 §5.3：每次重跑前两者都要做）。
        # 顺序：**先杀引信再动方块** —— primed TNT 的引信是 80 tick，留着它去 /fill
        # 等于拿装置赌博；实体是动的，方块是静的。集合见 kill_targets()。
        for entity in kill_targets(rig, kill_types):
            rc.kill_entities(entity)
            killed.append(entity)
        if rig.get("clear"):
            rc.clear_area(*rig["clear"])
    rc.setblocks(rig["blocks"], verify=True)
    if rig["power"]:
        same_tick, power_note = apply_power(rc, rig["power"])
    wait_ms = rig["wait_ms"] if settle_ms is None else settle_ms
    if wait_ms:
        time.sleep(wait_ms / 1000.0)
    checks = evaluate_asserts(rc, rig)
    ok = all(c["ok"] for c in checks)
    return {
        "rig": rig["name"],
        "path": rig["path"],
        "ok": ok,
        "checks": checks,
        "same_tick_power": same_tick,
        "power_note": power_note,
        "clear_box": list(rig["clear"]) if rig.get("clear") else None,
        "killed_entities": killed,
        "chunks": [list(c) for c in chunks],
        "forceload_errors": [[list(c), e] for c, e in fl_errors],
        "wait_ms": wait_ms,
        "started_at": started,
        "elapsed_s": round(time.time() - t0, 3),
        "commands": rc.commands,
    }


def load_rig(path, rc=None, clear=True, settle_ms=None,
             kill_types=TRANSIENT_ENTITY_TYPES):
    """读 JSON 并（在给了 rc 时）执行：这是装置脚本的统一入口。"""
    rig = load_rig_json(path)
    if rc is None:
        return rig
    return run_rig(rig, rc, clear=clear, settle_ms=settle_ms, kill_types=kill_types)


# ── P0 最小检查（docs/23 §4）──────────────────────────────────
def p0_check(rc, pos=(0, -50, 0), clear_box=(-2, -50, -2, 2, -50, 2)):
    """P0：三档基线都能发同一条命令、都能被同一份脚本驱动。

    验收要求（docs/23 §4）：/setblock 0 -50 0 stone 之后
    /execute if block 0 -50 0 stone 必须得到 Test passed。
    """
    rows = []
    t0 = time.time()

    def row(name, ok, detail=""):
        rows.append({"check": name, "ok": bool(ok), "detail": detail})
        print(f"  {'OK  ' if ok else 'FAIL'} {name}" + (f"   [{detail}]" if detail else ""))

    out = rc.cmd("list", wait=WAIT_MEDIUM)
    row("RCON list 有响应", bool((out or "").strip()), (out or "").strip()[:90])

    # 26.3 起 gamerule 改名成 snake_case（doDaylightCycle 已不存在，tntExplodes -> tnt_explodes），
    # 这里对两代命名都试一遍，只要求"命令层能回答"，不绑定具体规则名。
    gamerule_cmd, gamerule_out = None, ""
    for cand in ("gamerule tnt_explodes", "gamerule tntExplodes",
                 "gamerule random_tick_speed", "gamerule randomTickSpeed"):
        got = rc.cmd(cand, wait=WAIT_FAST) or ""
        if "currently set to" in got.lower():
            gamerule_cmd, gamerule_out = cand, got.strip()
            break
    row("gamerule 查询可用（26.3 的规则名是 snake_case）",
        gamerule_cmd is not None, gamerule_out[:90] or "四个候选全部失败")

    # 判据是「测试区能被读」，不是「forceload 回了字」：
    # vanilla 的 forceload 会回 "Marked chunk"，Folia(CeruLean) 上这条命令**静默无回显**
    # （但区块照样可用），拿回显判 FAIL 会把基线差异误记成失败。
    chunks, fl_errors = rc.ensure_loaded(*clear_box)
    x, y, z = pos
    readable = rc.probe_block((x, y, z), "minecraft:air")
    row("测试区区块可用（区块可读；Folia 上 forceload 无回显）",
        readable is not None,
        f"{len(chunks)} 个区块 / forceload 异常 {len(fl_errors)} 条 / 区块可读={readable}")

    # **先清场再测 setblock**：上一轮 M1 可能在同一坐标留下"已供电的活塞装置"，
    # 直接 setblock 会触发它的残余 block event，把刚放的方块又改掉（实测踩过：
    # 响应 Changed、紧接着 execute if block 却失败）。P0 必须自带卫生动作。
    rc.clear_area(*clear_box)
    row("fill air 清场后该位置是 air", rc.assert_block(pos, "minecraft:air"), str(clear_box))

    x, y, z = pos
    out = rc.cmd(f"setblock {x} {y} {z} minecraft:stone", wait=WAIT_FAST)
    rc._raise_on_error(out, "setblock")
    passed = rc.assert_block(pos, "minecraft:stone")
    row(f"setblock {pos} stone -> execute if block 通过（Test passed）", passed,
        "响应 %r" % ((out or "").strip()[:80],))

    exists = rc.probe_entity("minecraft:armor_stand")
    row("execute if entity 可用（armor_stand 期望不存在）", exists is not None,
        f"存在={exists}")

    snap = rc.snapshot((x - 1, y, z - 1, x + 1, y, z + 1),
                       candidates=("minecraft:air", "minecraft:stone"))
    row("snapshot 能读回刚才清掉的 3x1x3 盒（候选匹配）", len(snap) == 9,
        ", ".join(sorted(set(snap.values()))))

    ok = all(r["ok"] for r in rows)
    return {"ok": ok, "checks": rows, "elapsed_s": round(time.time() - t0, 2),
            "commands": rc.commands}


# ── CLI ───────────────────────────────────────────────────────
def _make_rc(args):
    return Rcon(host=args.host, port=args.port, password=args.password,
                timeout=args.timeout, wait=args.wait, quiet=not args.verbose)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="CeruLean 装置库：RCON 封装 / 断言 / rig JSON 执行（docs/23 P0+P1）")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--wait", type=float, default=WAIT_FAST,
                    help="每条命令收到首个响应后再等的窗口（秒）")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--p0", action="store_true", help="跑 P0 最小检查")
    ap.add_argument("--rig", action="append", default=[], help="执行装置 JSON（可重复）")
    ap.add_argument("--check-json", action="append", default=[],
                    help="只校验装置 JSON 契约，不连服（可重复）")
    ap.add_argument("--no-clear", action="store_true",
                    help="执行装置前不清场（也跳过清实体：那是看现场，不是重跑）")
    ap.add_argument("--kill-types", default=None,
                    help="每台装置前先 kill 的实体类型，逗号分隔；默认 "
                         + ",".join(TRANSIENT_ENTITY_TYPES) + "；空串=不 kill")
    ap.add_argument("--no-kill", action="store_true", help="跳过清实体（只清场）")
    ap.add_argument("--settle-ms", type=int, default=None)
    ap.add_argument("-c", "--command", action="append", default=[])
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    # 1) 纯校验模式：不连服
    if args.check_json:
        bad = 0
        for path in args.check_json:
            try:
                rig = load_rig_json(path)
                print(f"  OK   {path}: name={rig['name']} blocks={len(rig['blocks'])} "
                      f"power={len(rig['power'])} assert={len(rig['assert'])} "
                      f"clear={rig['clear']}")
            except (RigError, OSError, json.JSONDecodeError) as exc:
                bad += 1
                print(f"  FAIL {path}: {exc}")
        if not (args.command or args.rig or args.p0):
            return EXIT_FAIL if bad else EXIT_PASS
        if bad:
            return EXIT_FAIL

    rc = _make_rc(args)
    try:
        if args.command:
            for c in args.command:
                out = rc.cmd(c, wait=args.wait)
                print(f"> {c}")
                if (out or "").strip():
                    print(f"< {out.strip()}")
        if args.p0:
            print("== P0 最小检查（list / gamerule / setblock + execute if block）")
            report = p0_check(rc)
            if args.json:
                print(json.dumps(report, ensure_ascii=False, indent=2))
            print("== P0 " + ("通过" if report["ok"] else "失败"))
            if not report["ok"]:
                return EXIT_FAIL
        if args.rig:
            if args.no_kill:
                kill_types = ()
            elif args.kill_types is None:
                kill_types = TRANSIENT_ENTITY_TYPES
            else:
                kill_types = tuple(t.strip() for t in args.kill_types.split(",") if t.strip())
            results = []
            for path in args.rig:
                rig = load_rig(path, rc, clear=not args.no_clear,
                               settle_ms=args.settle_ms, kill_types=kill_types)
                results.append(rig)
                print(f"== 装置 {rig['rig']} -> {'PASS' if rig['ok'] else 'FAIL'}"
                      f"（{rig['elapsed_s']}s，{rig['commands']} 条命令）")
                for chk in rig["checks"]:
                    print(f"  {'OK  ' if chk['ok'] else 'FAIL'} {chk['type']}: {chk['detail']}")
                if not rig["same_tick_power"]:
                    print("  注意：" + rig["power_note"])
            if args.json:
                print(json.dumps(results, ensure_ascii=False, indent=2))
            if not all(r["ok"] for r in results):
                return EXIT_FAIL
        return EXIT_PASS
    except RigUnavailable as exc:
        print(f"[SKIP] {exc}", file=sys.stderr)
        return EXIT_SKIP
    except RigError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())

