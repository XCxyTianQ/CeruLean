#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M1 双活塞竞争：S1（邻居更新队列顺序）最便宜的分布型指纹（docs/23 §P1）。

装置（坐标**严格照 docs/23 §P1**；--offset-x/--offset-z 只是给 H2「边界污染」实验留的口子）：

    (0,-50,0)  目标方块 stone
    (1,-50,0)  活塞 A facing=west   （正面朝目标）
    (0,-50,1)  活塞 B facing=north  （正面朝目标）
    (1,-50,1)  红石块              ← 最后放，一块同时给 A、B 供电

为什么必须「最后只放一块红石块」：红石块与 A、B 都**共面相邻**，一次 /setblock 在
同一 tick 内触发两个活塞，谁先被邻居更新队列处理，谁就能把目标方块推走，另一个因
正面被活塞头占住而失败。分成两条命令放两个红石块会落在不同 tick，测到的就是命令
延迟而不是更新顺序 —— docs/23 §P1 明确禁止那样做。

观测与判定（每轮读 10 个位置；不依赖「第 N tick」的精确状态，见 §5.3）：

    A 胜   ：stone 在 (-1,-50,0) 且 A extended=true 且 B extended=false
    B 胜   ：stone 在 (0,-50,-1) 且 B extended=true 且 A extended=false
    无变化 ：stone 仍在 (0,-50,0) 且两个活塞都未伸出
    其它   ：unknown（把原始读数一起记下来，判 FAIL，不猜）

为什么先等两次一致再判：活塞的 moving_piston 只存在 1 tick（S5）。服务器有负载时
tick 会拉长，单次读可能正好落在移动中间态；连续两次分类相同（且不是 unknown）
才算场景定住。

用法：
    python3 tools/smoke/rigs/m1_piston_race.py --port 25576 --password test1234 \
        --baseline vanilla --out $HOME/cerulean-scratch/m1-vanilla.json

    # 与上一档的分布对比（可直接喂对方的 --out 文件）
    python3 tools/smoke/rigs/m1_piston_race.py --port 25577 --baseline cerulean \
        --expect $HOME/cerulean-scratch/m1-vanilla.json

退出码（与 tools/smoke/run_suite.py 一致）：
    0=PASS（有 --expect 时分布一致；没有 --expect 时表示本次观测自洽、无 unknown）
    1=FAIL（出现 unknown / 命令失败 / 与 --expect 不一致）
    2=SKIP（RCON 连不上等环境前提不满足）
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

PROBE = "m1_piston_race"
DOC = "docs/23 §P1（不变量 S1：邻居更新队列语义）"
WINNERS = ("A", "B", "none", "unknown")

TARGET_BLOCK = "minecraft:stone"
A_STATE = "minecraft:piston[facing=west]"
B_STATE = "minecraft:piston[facing=north]"
POWER_BLOCK = "minecraft:redstone_block"
HEAD_A = "minecraft:piston_head[facing=west]"
HEAD_B = "minecraft:piston_head[facing=north]"
MOVING = "minecraft:moving_piston"

CLEAR_HALF = 8          # docs/23 §P1：测试区 x/z ∈ [-8, 8]
DEFAULT_Y = -50         # 测试平面（平台在 y-1 = -51）


# ── 几何 ──────────────────────────────────────────────────────
def geometry(dx=0, dz=0, y=DEFAULT_Y):
    return {
        "origin": (dx, y, dz),
        "target": (0 + dx, y, 0 + dz),
        "a": (1 + dx, y, 0 + dz),
        "b": (0 + dx, y, 1 + dz),
        "power": (1 + dx, y, 1 + dz),
        "west": (-1 + dx, y, 0 + dz),
        "north": (0 + dx, y, -1 + dz),
    }


def clear_box(g):
    x, y, z = g["origin"]
    return (x - CLEAR_HALF, y, z - CLEAR_HALF, x + CLEAR_HALF, y, z + CLEAR_HALF)


# ── 装置动作 ──────────────────────────────────────────────────
def build(rc, g):
    """搭三块：目标 + 两个活塞（顺序无副作用，都在供电之前）。"""
    rc.setblocks([(g["target"], TARGET_BLOCK),
                  (g["a"], A_STATE),
                  (g["b"], B_STATE)])


def trigger(rc, g):
    """放红石块 —— **整个装置唯一的一条供电命令**，因此 A/B 落在同一 tick。"""
    rc.setblock(g["power"], POWER_BLOCK, verify=False)


def read_state(rc, g):
    """读 10 个位置。probe_block 返回 True/False/None（None = 响应无法判定）。"""
    return {
        "stone_at_west": rc.probe_block(g["west"], TARGET_BLOCK),
        "stone_at_north": rc.probe_block(g["north"], TARGET_BLOCK),
        "stone_at_target": rc.probe_block(g["target"], TARGET_BLOCK),
        "a_extended": rc.probe_block(g["a"], "minecraft:piston[extended=true]"),
        "b_extended": rc.probe_block(g["b"], "minecraft:piston[extended=true]"),
        "a_head_at_target": rc.probe_block(g["target"], HEAD_A),
        "b_head_at_target": rc.probe_block(g["target"], HEAD_B),
        "moving_at_target": rc.probe_block(g["target"], MOVING),
        "moving_at_west": rc.probe_block(g["west"], MOVING),
        "moving_at_north": rc.probe_block(g["north"], MOVING),
    }


def classify(s):
    # 任何一项读数无法判定（None）就不猜：宁可记 unknown 让人看原始读数，
    # 也不能把「读不到」当成 False 而误判成 A/B/none（那是最坏的假通过）。
    if any(v is None for v in s.values()):
        return "unknown"
    if s["stone_at_west"] and s["a_extended"] and not s["b_extended"]:
        return "A"
    if s["stone_at_north"] and s["b_extended"] and not s["a_extended"]:
        return "B"
    if s["stone_at_target"] and not s["a_extended"] and not s["b_extended"]:
        return "none"
    return "unknown"


def observe(rc, g, timeout_ms=3000, interval_ms=80):
    """连续两次分类相同（且不是 unknown）才算稳定；超时则如实返回 unknown。"""
    t0 = time.time()
    prev_winner = None
    samples = 0
    last = None
    while True:
        last = read_state(rc, g)
        winner = classify(last)
        samples += 1
        if winner == prev_winner and winner != "unknown":
            return {"winner": winner, "state": last, "stable": True,
                    "samples": samples,
                    "settle_ms": round((time.time() - t0) * 1000.0, 1)}
        prev_winner = winner
        if (time.time() - t0) * 1000.0 >= timeout_ms:
            return {"winner": winner, "state": last, "stable": False,
                    "samples": samples,
                    "settle_ms": round((time.time() - t0) * 1000.0, 1)}
        time.sleep(interval_ms / 1000.0)


def run_round(rc, g, box, args, idx):
    """一轮：清场 -> 重建 -> 触发 -> 稳定读数。"""
    t0 = time.time()
    rc.clear_area(*box)
    build(rc, g)
    trigger(rc, g)
    res = observe(rc, g, timeout_ms=args.settle_timeout_ms,
                  interval_ms=args.settle_interval_ms)
    res["round"] = idx
    res["elapsed_ms"] = round((time.time() - t0) * 1000.0, 1)
    return res


# ── 分布与期望比对 ────────────────────────────────────────────
def distribution(rounds):
    d = {k: 0 for k in WINNERS}
    for r in rounds:
        d[r["winner"]] = d.get(r["winner"], 0) + 1
    return d


def dominant(dist):
    best, best_n = "none", -1
    for k in WINNERS:
        if dist.get(k, 0) > best_n:
            best, best_n = k, dist.get(k, 0)
    return best


def fmt_dist(dist):
    return " ".join(f"{k}={dist.get(k, 0)}" for k in WINNERS)


def _check_expect_keys(dist):
    """键名拼错（例如把 A 写成 a / 写出不存在的胜者名）会静默变成「永远不一致」，必须当场拒绝。"""
    for k in dist:
        if k not in WINNERS:
            raise RigError(f"--expect 里的键 {k!r} 不认识（只允许 {list(WINNERS)}）")
    return dist


def parse_expect(text):
    """--expect 支持三种写法：文件（含 distribution 字段或就是分布对象）、内联 JSON、A=20,B=0。"""
    if not text:
        return None, None
    raw = text.strip()
    if os.path.isfile(raw):
        with open(raw, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        dist = data.get("distribution") if isinstance(data, dict) else None
        if dist is None and isinstance(data, dict):
            dist = data
        if not isinstance(dist, dict):
            raise RigError(f"--expect 文件 {raw} 里找不到 distribution 对象")
        return _check_expect_keys({str(k): int(v) for k, v in dist.items()}), \
            f"file:{os.path.abspath(raw)}"
    if raw.startswith("{"):
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise RigError(f"--expect 内联 JSON 必须是对象，收到 {raw!r}")
        return _check_expect_keys({str(k): int(v) for k, v in data.items()}), "json-inline"
    dist = {}
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise RigError(f"--expect 应为 A=20,B=0 / JSON / 文件路径，收到 {part!r}")
        k, v = part.split("=", 1)
        dist[k.strip()] = int(v.strip())
    return _check_expect_keys(dist), "kv"


def compare(dist, expect, allow_jitter):
    for k in expect:
        if k not in WINNERS:
            raise RigError(f"--expect 里的键 {k!r} 不认识（只允许 {list(WINNERS)}）")
    if all(dist.get(k, 0) == expect.get(k, 0) for k in WINNERS):
        return True, "分布与 --expect 完全一致"
    obs_dom, exp_dom = dominant(dist), dominant(expect)
    if allow_jitter and obs_dom == exp_dom:
        return True, f"分布有抖动但主导胜者一致（{obs_dom}，--allow-jitter）"
    return False, (f"分布不一致：实测 [{fmt_dist(dist)}] vs 期望 [{fmt_dist(expect)}]，"
                   f"主导胜者 {obs_dom} vs {exp_dom}")


# ── main ──────────────────────────────────────────────────────
def main(argv=None):
    ap = argparse.ArgumentParser(
        description="M1 双活塞竞争：测邻居更新队列的处理顺序（docs/23 §P1）")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--wait", type=float, default=0.05,
                    help="每条命令收到首个响应后再等的窗口（秒）")
    ap.add_argument("--rounds", type=int, default=20, help="重建次数（docs 要求 >=20）")
    ap.add_argument("--offset-x", type=int, default=0,
                    help="整体平移 X（H2 边界污染实验用；默认 0 = docs 原始坐标）")
    ap.add_argument("--offset-z", type=int, default=0)
    ap.add_argument("--y", type=int, default=DEFAULT_Y, help="测试平面（平台在 y-1）")
    ap.add_argument("--no-forceload", action="store_true",
                    help="不 forceload 清场盒覆盖的区块（默认会做：26.3 出生点不在 0,0）")
    ap.add_argument("--platform-block", default="minecraft:stone")
    ap.add_argument("--no-platform", action="store_true",
                    help="不补平台（默认把 y-1 层的空气补成 --platform-block，幂等）")
    ap.add_argument("--settle-timeout-ms", type=int, default=3000)
    ap.add_argument("--settle-interval-ms", type=int, default=80)
    ap.add_argument("--baseline", default="",
                    help="基线名（vanilla/paper/cerulean），写进结果 JSON")
    ap.add_argument("--label", default="", help="附加标签（例如 jar 时间戳）")
    ap.add_argument("--expect", default=None,
                    help="期望分布：文件 / {\"A\":20} / A=20,B=0")
    ap.add_argument("--allow-jitter", action="store_true",
                    help="分布不完全一致但主导胜者一致时判 PASS（抖动容忍）")
    ap.add_argument("--out", default=None,
                    help="结果 JSON 路径；省略且给了 --baseline 时写 "
                         "$HOME/cerulean-scratch/m1-<baseline>.json")
    ap.add_argument("--print-json", action="store_true")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args(argv)
    if args.rounds < 1:
        # 不能用 ap.error：argparse 的用法错误退出码是 2，会和 SKIP 撞车
        print("[FAIL] --rounds 至少为 1", file=sys.stderr)
        return EXIT_FAIL

    # --expect 在跑之前就解析（跑完 20 轮再告诉你格式写错了，纯属浪费一次起服）
    try:
        expect, expect_src = parse_expect(args.expect)
    except (RigError, OSError, json.JSONDecodeError) as exc:
        print(f"[FAIL] --expect 解析失败：{exc}", file=sys.stderr)
        return EXIT_FAIL

    g = geometry(args.offset_x, args.offset_z, args.y)
    box = clear_box(g)
    rc = Rcon(host=args.host, port=args.port, password=args.password,
              timeout=args.timeout, wait=args.wait)
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    t0 = time.time()

    print(f"== M1 双活塞竞争（{DOC}）")
    print(f"   目标={g['target']}  A={g['a']} facing=west  B={g['b']} facing=north  "
          f"供电={g['power']}")
    print(f"   RCON {rc.host}:{rc.port}  轮数 {args.rounds}  清场盒 {box}")
    if args.rounds < 20:
        print(f"   注意：本轮样本 {args.rounds} < 20，只作诊断，不满足 docs/23 的样本要求")

    env = {}
    try:
        env["list"] = (rc.cmd("list", wait=0.5) or "").strip()
        # 26.3 起规则名是 snake_case（tnt_explodes），老版本是 tntExplodes；两代都记下来
        for key, cand in (("gamerule_tnt_explodes", "gamerule tnt_explodes"),
                          ("gamerule_tntExplodes", "gamerule tntExplodes")):
            got = (rc.cmd(cand, wait=0.2) or "").strip()
            if "currently set to" in got.lower():
                env[key] = got
    except RigUnavailable as exc:
        print(f"[SKIP] RCON 不可用：{exc}", file=sys.stderr)
        return EXIT_SKIP

    rounds = []
    aborted_kind = None
    aborted_msg = ""
    chunks = []
    try:
        # 先保证区块加载：26.3 的世界出生点不是固定的 (0,0)，新区块里 setblock 会报
        # "That position is not loaded"（本机 vanilla 实测）。forceload 也让 M1 的 tick 稳定，
        # 不依赖"某个玩家恰好站在附近"这种偶然条件。
        if not args.no_forceload:
            chunks, fl_errors = rc.ensure_loaded(*box)
            print(f"   区块加载：{len(chunks)} 个 {chunks[:4]}{' ...' if len(chunks) > 4 else ''}")
            for (cx, cz), err in fl_errors:
                print(f"   警告：forceload chunk {cx},{cz} 失败：{err}", file=sys.stderr)
        if not args.no_platform:
            platform = (box[0], args.y - 1, box[2], box[3], args.y - 1, box[5])
            rc.fill(platform, block=args.platform_block, replace="minecraft:air")
            env["platform"] = f"{args.platform_block} @ y={args.y - 1}（只补空气）"
        for i in range(1, args.rounds + 1):
            res = run_round(rc, g, box, args, i)
            rounds.append(res)
            if not args.quiet:
                st = res["state"]
                print(f"  第 {i:2d} 轮: {res['winner']:7s} "
                      f"A_ext={st['a_extended']} B_ext={st['b_extended']} "
                      f"stone@W={st['stone_at_west']} stone@N={st['stone_at_north']} "
                      f"stone@T={st['stone_at_target']} "
                      f"({res['elapsed_ms']}ms, {res['samples']} 次读, stable={res['stable']})")
    except RigUnavailable as exc:
        aborted_kind, aborted_msg = "unavailable", str(exc)
        print(f"[SKIP] 第 {len(rounds) + 1} 轮 RCON 断开：{exc}", file=sys.stderr)
    except RigError as exc:
        aborted_kind, aborted_msg = "error", str(exc)
        print(f"[FAIL] 第 {len(rounds) + 1} 轮装置命令失败：{exc}", file=sys.stderr)

    dist = distribution(rounds)
    dom = dominant(dist) if rounds else "unknown"
    unknown = dist.get("unknown", 0)

    verdict, reason = "PASS", ""
    if aborted_kind == "unavailable":
        verdict, reason = "SKIP", f"RCON 中断，仅完成 {len(rounds)} 轮：{aborted_msg}"
    elif aborted_kind == "error":
        verdict, reason = "FAIL", f"装置命令失败，仅完成 {len(rounds)} 轮：{aborted_msg}"
    elif len(rounds) < args.rounds:
        verdict, reason = "FAIL", f"只完成 {len(rounds)}/{args.rounds} 轮"
    elif unknown:
        verdict, reason = "FAIL", (f"有 {unknown} 轮落到 unknown（原始读数见 per_round），"
                                   "说明装置状态不自洽：先查清场盒 / 供电是否只放了一块")
    elif expect is not None:
        ok, detail = compare(dist, expect, args.allow_jitter)
        verdict, reason = ("PASS" if ok else "FAIL"), detail
    else:
        verdict, reason = "PASS", "未给 --expect：本次观测自洽且无 unknown（判定需与其他基线对比）"

    print()
    print(f"== 分布（{len(rounds)}/{args.rounds} 轮）: {fmt_dist(dist)}")
    print(f"   主导胜者: {dom}")
    if expect is not None:
        print(f"   期望（{expect_src}）: {fmt_dist(expect)}")
    print(f"== 判定 {verdict}: {reason}")

    result = {
        "probe": PROBE,
        "doc": DOC,
        "baseline": args.baseline or "unknown",
        "label": args.label,
        "started_at": started,
        "elapsed_s": round(time.time() - t0, 2),
        "rcon": {"host": rc.host, "port": rc.port, "commands": rc.commands},
        "environment": env,
        "geometry": {k: list(v) for k, v in g.items()},
        "clear_box": list(box),
        "chunks": [list(c) for c in chunks],
        "platform": env.get("platform"),
        "rounds": len(rounds),
        "rounds_requested": args.rounds,
        "distribution": dist,
        "dominant": dom,
        "expect": expect,
        "expect_source": expect_src,
        "allow_jitter": bool(args.allow_jitter),
        "verdict": verdict,
        "reason": reason,
        "per_round": rounds,
    }

    out = args.out
    if not out and args.baseline:
        out = os.path.join(os.path.expanduser("~"), "cerulean-scratch",
                           f"m1-{args.baseline}.json")
    if out:
        parent = os.path.dirname(os.path.abspath(out))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
        print(f"   结果 JSON: {os.path.abspath(out)}")
    if args.print_json:
        print(json.dumps(result, ensure_ascii=False, indent=2))

    if verdict == "SKIP":
        return EXIT_SKIP
    if verdict == "FAIL":
        return EXIT_FAIL
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())

