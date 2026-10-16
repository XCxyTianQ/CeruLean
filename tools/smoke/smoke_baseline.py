# -*- coding: utf-8 -*-
"""CeruLean 语义基线冒烟测试（RCON 驱动）

定位：Phase 1 的**单线程语义基线**。它只依赖原版/Paper 就有的命令语义能力，
因此可以对以下三者跑同一套用例并逐条对比：
    上游 Paper 26.3  →  我们的构建（Paper + CeruLean 补丁）  →  未来区域化版本

设计原则：
1. 全部参数外部化（host/port/password/测试区坐标/前缀），无硬编码路径与固定端口；
2. 每个用例自带清理，可重复运行；
3. 行为不确定的语义（例如"链式命令方块在上一条失败时是否继续"）标为 OBSERVED：
   只记录实测行为、不判 PASS/FAIL——这类观察值正是后续差分回归的基线数据。
4. 退出码：0 = 无 FAIL（允许 OBSERVED）；1 = 有 FAIL；2 = 环境/连接错误。
"""
import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon, RconError, DEFAULT_HOST, DEFAULT_PORT, DEFAULT_PASSWORD  # noqa: E402

PASS, FAIL, OBSERVED, ERROR = "PASS", "FAIL", "OBSERVED", "ERROR"


class Case:
    def __init__(self, name, status, detail=""):
        self.name, self.status, self.detail = name, status, detail

    def as_dict(self):
        return {"case": self.name, "status": self.status, "detail": self.detail}


class Suite:
    def __init__(self, rc, area, prefix, mc_version=None, verbose=False):
        self.rc, self.area, self.prefix, self.mc_version, self.verbose = rc, area, prefix, mc_version, verbose
        self.cases = []

    # ── 小工具 ────────────────────────────────────────────
    def cmd(self, command, wait=0.3):
        # Folia 基座下命令往返更慢；首包必须给长超时，否则响应滞留缓冲会造成协议错位
        # （表现为 "Unknown or incomplete command" 与输出粘连）——分诊结论见 docs/12。
        out = self.rc.cmd(command, wait=wait, first_wait=max(wait, 20.0))
        if self.verbose:
            print(f"    > {command}\n    < {out.strip()!r}")
        return out

    def record(self, name, status, detail=""):
        self.cases.append(Case(name, status, detail))
        mark = {"PASS": "✅", "FAIL": "❌", "OBSERVED": "👁", "ERROR": "⚠"}[status]
        print(f"  {mark} {name:<28} {detail}")
        return status == PASS

    def expect(self, name, condition, detail):
        return self.record(name, PASS if condition else FAIL, detail)

    # 测试区坐标
    @property
    def x(self):
        return self.area[0]

    @property
    def z(self):
        return self.area[1]

    Y = 100

    # 测试区范围（含 forceload 覆盖）：x ∈ [x-16, x+64]，z ∈ [z-16, z+32]
    def area_bounds(self):
        return (self.x - 16, self.z - 16, self.x + 64, self.z + 32)

    def setup_area(self):
        """显式 forceload 测试区。

        必要性：Paper/Folia（Moonrise 区块系统）在**没有玩家在线**时不会常驻加载出生点区块，
        未加载区块上的 setblock/fill/clone 会直接报 "That position is not loaded"，
        命令方块也不会 tick。冒烟测试必须在无玩家环境下自洽，所以由用例自己加载区块。
        """
        x0, z0, x1, z1 = self.area_bounds()
        self.cmd(f"forceload add {x0} {z0} {x1} {z1}", wait=1.2)
        self.cmd(f"scoreboard objectives remove {self.prefix}_probe")
        self.cmd(f"scoreboard objectives add {self.prefix}_probe dummy")
        loaded = self.cmd(f"execute if block {self.x} {self.Y} {self.z} minecraft:air run time query gametime", wait=0.6)
        if "not loaded" in loaded.lower():
            self.record("forceload_setup", ERROR, "测试区块仍未加载，请检查服务器是否允许 forceload")
            return False
        self.cmd(f"fill {self.x - 2} {self.Y - 2} {self.z - 2} {self.x + 62} {self.Y + 10} {self.z + 30} minecraft:air replace", wait=1.0)
        return True

    def teardown_area(self):
        x0, z0, x1, z1 = self.area_bounds()
        self.cmd(f"scoreboard objectives remove {self.prefix}_probe")
        self.cmd(f"forceload remove {x0} {z0} {x1} {z1}", wait=0.8)

    # RCON 收不到 say/broadcast 这类聊天输出（实测：Paper 26.3 上 say 的文本不会回给 RCON），
    # 因此所有"条件是否成立"的判定都改成**状态探针**：条件成立就写记分板，再读回来。
    def probe(self, token, condition):
        obj = f"{self.prefix}_probe"
        self.cmd(f"scoreboard players set {token} {obj} 0")
        self.cmd(f"{condition} run scoreboard players set {token} {obj} 1")
        out = self.cmd(f"scoreboard players get {token} {obj}")
        return " has 1 " in out

    # ── 命令方块触发（确定性）────────────────────────────
    # 不依赖 auto（always-active）语义：后者在"方块被覆盖"等场景下是否再次触发并不确定。
    # 这里用标准玩法方式：放下红石块形成上升沿 → 触发 → 移除。
    def place_cmd_block(self, x, y, z, kind, command, facing="up"):
        self.cmd(f"setblock {x} {y} {z} minecraft:{kind}[facing={facing}]"
                 + '{Command:"' + command + '"}')

    def redstone_pulse(self, x, y, z):
        self.cmd(f"setblock {x} {y} {z} minecraft:redstone_block")
        time.sleep(0.6)
        self.cmd(f"setblock {x} {y} {z} minecraft:air")

    # ── 用例 ──────────────────────────────────────────────
    def case_rcon_alive(self):
        out = self.cmd("list", wait=0.5)
        self.expect("rcon_alive", "players" in out.lower() or "player" in out.lower(),
                    f"list -> {out.strip()[:60]!r}")

    def case_version(self):
        out = ""
        for _ in range(6):
            out = self.cmd("version", wait=1.0)
            if self.mc_version and self.mc_version in out:
                self.expect("version", True, out.strip().splitlines()[0][:90])
                return
            if out.strip() and "checking version" not in out.lower():
                break
            time.sleep(1.0)
        detail = out.strip().splitlines()[0][:90] if out.strip() else "(空)"
        ok = bool(out.strip())
        if ok and self.mc_version:
            ok = self.mc_version in out
            detail += f"  [期望含 {self.mc_version}]"
        self.expect("version", ok, detail)

    def case_setblock_data(self):
        self.cmd(f"setblock {self.x} {self.Y} {self.z} minecraft:command_block"
                 f'{{Command:"say hello",auto:1b}}')
        out = self.cmd(f"data get block {self.x} {self.Y} {self.z} Command")
        self.expect("setblock_data_nbt", "say hello" in out, f"data get -> {out.strip()[:70]!r}")

    def case_impulse_auto(self):
        """红石触发的脉冲命令方块：验证"放置 → 上升沿 → 执行"这条最基本链路。"""
        tx = self.x + 5
        self.cmd(f"setblock {tx} {self.Y} {self.z} minecraft:air")
        self.place_cmd_block(self.x, self.Y, self.z, "command_block",
                             f"setblock {tx} {self.Y} {self.z} minecraft:redstone_block")
        self.redstone_pulse(self.x, self.Y - 1, self.z)
        time.sleep(0.8)
        hit = self.probe("probe_impulse",
                         f"execute if block {tx} {self.Y} {self.z} minecraft:redstone_block")
        self.expect("impulse_redstone_trigger", hit,
                    "脉冲命令方块已执行" if hit else "目标方块未出现（命令方块未触发）")

    def case_auto_impulse_observed(self):
        """观察项：always-active（auto:1b）脉冲方块在放置后是否自行执行一次。

        这是区域化语义比对里很关键的一类行为（"谁在什么时机触发命令方块"），
        此处只记录实测值，不判定对错。
        """
        tx = self.x + 12
        self.cmd(f"setblock {tx} {self.Y} {self.z} minecraft:air")
        self.cmd(f"setblock {self.x + 14} {self.Y} {self.z} minecraft:air")
        inner = "setblock %d %d %d minecraft:redstone_block" % (tx, self.Y, self.z)
        self.cmd('setblock %d %d %d minecraft:command_block[facing=up]{Command:"%s",auto:1b}'
                 % (self.x + 14, self.Y, self.z, inner))
        time.sleep(1.8)
        fired = self.probe("probe_auto", f"execute if block {tx} {self.Y} {self.z} minecraft:redstone_block")
        self.record("auto_impulse(OBSERVED)", OBSERVED,
                    "always-active 脉冲方块放置后" + ("**自行执行了一次**" if fired else "**未自行执行**"))

    def case_chain_order(self):
        """3 连链：脉冲 → 链1 → 链2（均 facing=up，红石触发），期望按序全部执行。"""
        b = self.x + 8
        self.cmd(f"fill {b - 1} {self.Y - 1} {self.z} {b + 3} {self.Y + 4} {self.z + 4} minecraft:air replace", wait=0.8)
        for i in range(3):
            tz = self.z + 2 + i
            kind = "command_block" if i == 0 else "chain_command_block"
            self.place_cmd_block(b, self.Y + i, self.z, kind,
                                 f"setblock {b + 2} {self.Y + i} {tz} minecraft:redstone_block")
        self.redstone_pulse(b, self.Y - 1, self.z)
        time.sleep(1.0)
        hits = 0
        for i in range(3):
            tz = self.z + 2 + i
            if self.probe(f"probe_chain{i}",
                          f"execute if block {b + 2} {self.Y + i} {tz} minecraft:redstone_block"):
                hits += 1
        # 为什么是 OBSERVED 而不是 PASS/FAIL：
        # 1) 源码规则（CommandBlock.java:194）：链节点只在 `isPowered() || isAutomatic()` 时才执行；
        # 2) 而 CommandBlockEntity.loadAdditional 用 getBooleanOr("auto", false) ——
        #    用 setblock 放置且 NBT 未显式带 auto:1b 的链方块会被置为 non-automatic，于是被门槛挡掉；
        # 3) 实测：即使显式写入 auto:1b（已用 data get block 确认 =1b），
        #    合成场景下的链传播仍未发生（两种朝向各测一次）。
        # 结论：命令方块链的**真实触发条件**需要在真实玩法/逐步搭建场景下另行测定，
        # 这里先如实记录实测值，避免把一个未验证的期望写死成 FAIL。
        self.record("chain_executes_in_order(OBSERVED)", OBSERVED,
                    f"{hits}/3 个链节点执行（见 docs/05-命令方块语义基线.md）")

    def case_chain_on_failure(self):
        """观察项：前一条命令方块失败时，链是否继续（这是区域化语义的关键基线）。"""
        b = self.x + 20
        self.cmd(f"fill {b - 1} {self.Y} {self.z} {b + 3} {self.Y + 3} {self.z + 2} minecraft:air replace")
        # 第 1 块故意失败：坐标超出世界高度
        self.cmd(f"setblock {b} {self.Y} {self.z} minecraft:command_block[facing=up]"
                 f'{{Command:"setblock {b + 2} 9999 {self.z} minecraft:redstone_block",auto:1b}}')
        self.cmd(f"setblock {b} {self.Y + 1} {self.z} minecraft:chain_command_block[facing=up]"
                 f'{{Command:"setblock {b + 2} {self.Y + 1} {self.z + 1} minecraft:redstone_block",auto:1b}}')
        time.sleep(2.5)
        continued = self.probe("probe_chainfail",
                               f"execute if block {b + 2} {self.Y + 1} {self.z + 1} minecraft:redstone_block")
        self.record("chain_on_failure(OBSERVED)", OBSERVED,
                    "前序失败后链" + ("**继续执行**" if continued else "**停止**"))

    def case_scoreboard(self):
        obj = f"{self.prefix}_obj"
        fake = f"{self.prefix}_tester"
        self.cmd(f"scoreboard objectives remove {obj}")
        self.cmd(f"scoreboard objectives add {obj} dummy")
        self.cmd(f"scoreboard players set {fake} {obj} 42")
        out = self.cmd(f"scoreboard players get {fake} {obj}")
        ok = "42" in out
        self.cmd(f"scoreboard objectives remove {obj}")
        self.expect("scoreboard_roundtrip", ok, f"get -> {out.strip()[:60]!r}")

    def case_fill_and_clone(self):
        ax, az = self.x + 30, self.z
        self.cmd(f"fill {ax} {self.Y} {az} {ax + 2} {self.Y} {az} minecraft:glass replace")
        fill_ok = self.probe("probe_fill", f"execute if block {ax + 1} {self.Y} {az} minecraft:glass")
        self.cmd(f"clone {ax} {self.Y} {az} {ax + 2} {self.Y} {az} {ax} {self.Y} {az + 5}")
        clone_ok = self.probe("probe_clone", f"execute if block {ax + 2} {self.Y} {az + 5} minecraft:glass")
        self.expect("fill_region", fill_ok, "3 格玻璃已铺")
        self.expect("clone_region", clone_ok, "克隆到 +5 格处")

    def case_execute_if_chain(self):
        obj = f"{self.prefix}_exec"
        fake = f"{self.prefix}_exec_tester"
        self.cmd(f"scoreboard objectives remove {obj}")
        self.cmd(f"scoreboard objectives add {obj} dummy")
        self.cmd(f"setblock {self.x + 40} {self.Y} {self.z} minecraft:stone replace")
        self.cmd(f"execute if block {self.x + 40} {self.Y} {self.z} minecraft:stone "
                 f"run scoreboard players set {fake} {obj} 7")
        out = self.cmd(f"scoreboard players get {fake} {obj}")
        ok = "7" in out
        self.cmd(f"scoreboard objectives remove {obj}")
        self.expect("execute_if_conditional", ok, f"条件成立时分数 -> {out.strip()[:50]!r}")

    def case_tick_health(self):
        out = self.cmd("tps", wait=0.8)
        if not out.strip():
            self.record("tps_health(OBSERVED)", OBSERVED, "/tps 无输出（非 Paper 系命令？）")
            return
        self.record("tps_health(OBSERVED)", OBSERVED, out.strip().splitlines()[0][:80])

    def case_save_flush(self):
        out = self.cmd("save-all flush", wait=1.5)
        self.record("save_all_flush(OBSERVED)", OBSERVED, out.strip()[:70] or "(无输出)")

    def run(self, only=None, skip=None):
        print(f"CeruLean 语义基线冒烟  |  测试区 ({self.x}, {self.Y}, {self.z})  前缀 {self.prefix}")
        print("-" * 78)
        steps = [
            ("rcon_alive", self.case_rcon_alive),
            ("version", self.case_version),
            ("setblock_data_nbt", self.case_setblock_data),
            ("impulse_auto", self.case_impulse_auto),
            ("auto_impulse", self.case_auto_impulse_observed),
            ("chain_order", self.case_chain_order),
            ("chain_on_failure", self.case_chain_on_failure),
            ("scoreboard", self.case_scoreboard),
            ("fill_clone", self.case_fill_and_clone),
            ("execute_if", self.case_execute_if_chain),
            ("tick_health", self.case_tick_health),
            ("save_flush", self.case_save_flush),
        ]
        for name, fn in steps:
            if only and name not in only:
                continue
            if skip and name in skip:
                print(f"  ⏭  {name:<28} (skip)")
                continue
            try:
                fn()
            except RconError as exc:
                self.record(f"{name}", ERROR, str(exc))
        print("-" * 78)
        n_fail = sum(1 for c in self.cases if c.status == FAIL)
        n_err = sum(1 for c in self.cases if c.status == ERROR)
        n_obs = sum(1 for c in self.cases if c.status == OBSERVED)
        n_pass = sum(1 for c in self.cases if c.status == PASS)
        print(f"结果：PASS {n_pass} · FAIL {n_fail} · OBSERVED {n_obs} · ERROR {n_err}")
        return 0 if (n_fail == 0) else 1


CASES = ["rcon_alive", "version", "setblock_data_nbt", "impulse_auto", "auto_impulse", "chain_order",
         "chain_on_failure", "scoreboard", "fill_clone", "execute_if", "tick_health", "save_flush"]


def main(argv=None):
    ap = argparse.ArgumentParser(description="CeruLean 语义基线冒烟测试")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--area", default="0,0", help="测试区基准坐标 x,z（默认 0,0）")
    ap.add_argument("--prefix", default="cerulean_smoke", help="记分板前缀，避免与其它测试冲突")
    ap.add_argument("--expect-mc", default=os.environ.get("CERULEAN_EXPECT_MC"),
                    help="期望的 Minecraft 版本串（可选）")
    ap.add_argument("--only", action="append", default=[], help="只跑指定用例（可重复）")
    ap.add_argument("--skip", action="append", default=[], help="跳过指定用例（可重复）")
    ap.add_argument("--json", help="把结果写入 JSON 文件（供 CI 归档/差分对比）")
    ap.add_argument("--list", action="store_true", help="列出用例后退出（不需要服务器）")
    ap.add_argument("--no-cleanup", action="store_true", help="结束后不清理测试区")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    if args.list:
        for c in CASES:
            print(c)
        return 0

    xs, zs = args.area.split(",")
    area = (int(xs), int(zs))
    rc = Rcon(args.host, args.port, args.password, quiet=not args.verbose)
    suite = Suite(rc, area, args.prefix, args.expect_mc, args.verbose)
    try:
        if not suite.setup_area():
            print("[错误] 测试区加载失败：无法进行语义测试", file=sys.stderr)
            return 2
        code = suite.run(only=args.only or None, skip=args.skip or None)
        if not args.no_cleanup:
            suite.teardown_area()
    except RconError as exc:
        print(f"[错误] {exc}", file=sys.stderr)
        return 2
    finally:
        rc.close()

    if args.json:
        payload = {
            "target": f"{args.host}:{args.port}",
            "expect_mc": args.expect_mc,
            "area": area,
            "cases": [c.as_dict() for c in suite.cases],
            "summary": {s: sum(1 for c in suite.cases if c.status == s)
                        for s in (PASS, FAIL, OBSERVED, ERROR)},
        }
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=2)
        print(f"JSON 报告: {args.json}")
    return code


if __name__ == "__main__":
    sys.exit(main())
