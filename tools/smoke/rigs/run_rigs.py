#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跑整个装置目录：docs/23 P2「机器矩阵」的通用 runner（不绑定任何具体装置）。

为什么要单独一个 runner
-----------------------
P2 要跑 M2–M7 六台装置 × 三档基线。每台装置的差异只在 JSON 里（docs/23 §5.4），
执行动作完全一样：清场 → blocks → power → 按 assert 判定。于是把「跑一台」留在
rig_common.run_rig()，这里只负责「发现、排序、汇总、退出码」，装置 JSON 变了不用改代码。

行为（逐台串行）
----------------
    rig JSON -> clear_area（装置自带的 clear 或按包围盒派生）
             -> setblocks(blocks)【逐条 + execute if block 复核】
             -> setblocks(power)【永远最后；>1 块会标记 same_tick_power=false】
             -> 等 wait_ms -> 按 assert 列表判定
每台输出**一行**：[PASS|FAIL|SKIP] <rig_name> <细节>；--verbose 才逐条打印断言。

排序：控制组（名字以 m6 / m7 开头）先跑。它们失败说明世界 / 平台 / 清场出了问题，
后面的复制类装置结果没有解释价值（docs/23 §P2 的 M6/M7 就是「装置没搭错」的自检）。

退出码（与 tools/smoke/run_suite.py 一致）
------------------------------------------
    0 = 选中的装置全部 PASS
    1 = 有 FAIL（含 JSON 契约不合法、断言不成立、装置命令失败）
    2 = 前提不满足：--rigs-dir 不存在 / 没有匹配的 JSON / --require 点名的装置缺失 /
        RCON 连不上或中途断开

用法
----
    # 跑目录里全部装置
    python3 tools/smoke/rigs/run_rigs.py --port 25576 --password test1234

    # 只跑两台（逗号分隔，可重复；支持通配 m*）
    python3 tools/smoke/rigs/run_rigs.py --port 25576 --password test1234 \
        --only m6_control_push,m7_control_no_dupe

    # 开发期：要求控制组必须在场，缺席直接 SKIP(2)
    python3 tools/smoke/rigs/run_rigs.py --port 25576 --password test1234 \
        --require m6_control_push,m7_control_no_dupe --out $HOME/cerulean-scratch/m6m7.json
"""
import argparse
import fnmatch
import glob
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rig_common import (  # noqa: E402
    Rcon, RigError, RigUnavailable,
    load_rig_json, run_rig,
    EXIT_PASS, EXIT_FAIL, EXIT_SKIP,
    DEFAULT_HOST, DEFAULT_PORT, DEFAULT_PASSWORD,
    TRANSIENT_ENTITY_TYPES, WAIT_FAST,
)

# 默认装置目录 = 本文件同级的 rigs/（绝对化：从任何 cwd 调用都能找到）
DEFAULT_RIGS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rigs")

# 控制组名前缀：先跑，用来证明世界 / 平台 / 清场是可用的
CONTROL_PREFIXES = ("m6", "m7")


# ── 发现与排序 ────────────────────────────────────────────────
def _match(name, token):
    return name == token or fnmatch.fnmatch(name, token)


def split_tokens(values):
    """--only/--require 既接受逗号分隔，也接受重复出现。"""
    out = []
    for raw in values or []:
        for part in str(raw).split(","):
            part = part.strip()
            if part:
                out.append(part)
    return out


def discover(rigs_dir, tokens=None):
    """返回 [(stem, path)]；按文件名排序，再按「控制组优先」重排。"""
    if not rigs_dir or not os.path.isdir(rigs_dir):
        return []
    entries = []
    for path in sorted(glob.glob(os.path.join(rigs_dir, "*.json"))):
        stem = os.path.splitext(os.path.basename(path))[0]
        if stem.startswith(".") or stem.startswith("_"):
            continue                       # 约定：下划线开头是草稿/模板，不参与矩阵
        if tokens and not any(_match(stem, t) or _match(os.path.basename(path), t)
                              for t in tokens):
            continue
        entries.append((stem, path))
    return order(entries)


def order(entries):
    def key(item):
        stem = item[0].lower()
        return (0 if stem.startswith(CONTROL_PREFIXES) else 1, stem)

    return sorted(entries, key=key)


# ── 组装输出 ──────────────────────────────────────────────────
def _detail(res):
    """一行的细节部分：通过时给规模，失败时给前 3 条失败断言的原文。"""
    checks = res["checks"]
    bad = [c for c in checks if not c["ok"]]
    extra = "" if res.get("same_tick_power", True) else " power_same_tick=false"
    if not bad:
        return (f"checks={len(checks)}/{len(checks)} ok "
                f"{res['elapsed_s']}s cmd={res['commands']}{extra}")
    shown = "; ".join(f"{c['type']}:{c['detail']}" for c in bad[:3])
    more = f" (+{len(bad) - 3})" if len(bad) > 3 else ""
    return (f"checks={len(checks) - len(bad)}/{len(checks)} 失败 {shown}{more}{extra}")


def _line(verdict, name, detail):
    return f"[{verdict}] {name} {detail}"


def _make_rc(args):
    return Rcon(host=args.host, port=args.port, password=args.password,
                timeout=args.timeout, wait=args.wait)


# ── main ──────────────────────────────────────────────────────
def main(argv=None):
    ap = argparse.ArgumentParser(
        description="装置矩阵通用 runner：逐台跑 rig JSON 并汇总（docs/23 P2）")
    ap.add_argument("--host", default=DEFAULT_HOST)
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--password", default=DEFAULT_PASSWORD)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--wait", type=float, default=WAIT_FAST,
                    help="每条命令收到首个响应后再等的窗口（秒）")
    ap.add_argument("--rigs-dir", default=DEFAULT_RIGS_DIR,
                    help="装置 JSON 目录（默认 = 本文件同级的 rigs/）")
    ap.add_argument("--only", action="append", default=[],
                    help="只跑点名装置（逗号分隔 / 可重复 / 支持通配）")
    ap.add_argument("--require", action="append", default=[],
                    help="这些装置必须在本次选中的集合里，否则退出码 2")
    ap.add_argument("--no-clear", action="store_true",
                    help="执行装置前不清场（也跳过清实体：那是看现场，不是重跑）")
    ap.add_argument("--kill-types", default=None,
                    help="每台装置前先 kill 的实体类型，逗号分隔；默认 "
                         + ",".join(TRANSIENT_ENTITY_TYPES) + "；空串=不 kill")
    ap.add_argument("--no-kill", action="store_true", help="跳过清实体（只清场）")
    ap.add_argument("--settle-ms", type=int, default=None,
                    help="覆盖装置 JSON 的 wait_ms")
    ap.add_argument("--out", default=None, help="结果 JSON 路径")
    ap.add_argument("--json", action="store_true", help="把结果 JSON 打到 stdout")
    ap.add_argument("--verbose", action="store_true", help="每台额外逐条打印断言")
    args = ap.parse_args(argv)

    only = split_tokens(args.only)
    require = split_tokens(args.require)
    if args.no_kill:
        kill_types = ()
    elif args.kill_types is None:
        kill_types = TRANSIENT_ENTITY_TYPES
    else:
        kill_types = tuple(t.strip() for t in args.kill_types.split(",") if t.strip())
    entries = discover(args.rigs_dir, only)
    started = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    t0 = time.time()

    print(f"== 装置矩阵：{os.path.abspath(args.rigs_dir)}（选中 {len(entries)} 台）")
    if kill_types:
        print(f"   每台先 kill：{', '.join(kill_types)}（--no-kill / --kill-types 可改）")
    if not entries:
        print(f"[SKIP] 没有可跑的装置：{args.rigs_dir}"
              + (f"（--only {','.join(only)}）" if only else ""), file=sys.stderr)
        return EXIT_SKIP

    missing = [tok for tok in require
               if not any(_match(stem, tok) or _match(os.path.basename(p), tok)
                          for stem, p in entries)]
    for tok in missing:
        print(_line("SKIP", tok, "（--require 要求，但本次选中的集合里没有）"), file=sys.stderr)

    rc = _make_rc(args)
    env = {}
    try:
        env["list"] = (rc.cmd("list", wait=0.5) or "").strip()
        env["gamerule_tntExplodes"] = (rc.cmd("gamerule tntExplodes", wait=0.2) or "").strip()
    except RigUnavailable as exc:
        print(f"[SKIP] RCON 不可用：{exc}", file=sys.stderr)
        return EXIT_SKIP
    if "false" in env["gamerule_tntExplodes"].lower():
        print("   警告：gamerule tntExplodes=false —— TNT 类装置会直接失败（docs/23 §7）")

    results = []
    aborted = None
    for stem, path in entries:
        try:
            rig = load_rig_json(path)
            res = run_rig(rig, rc, clear=not args.no_clear,
                          settle_ms=args.settle_ms, kill_types=kill_types)
            verdict = "PASS" if res["ok"] else "FAIL"
            detail = _detail(res)
            results.append({"rig": stem, "path": path, "verdict": verdict,
                            "detail": detail, "ok": res["ok"],
                            "checks": res["checks"],
                            "same_tick_power": res["same_tick_power"],
                            "killed_entities": res["killed_entities"],
                            "elapsed_s": res["elapsed_s"], "commands": res["commands"]})
            print(_line(verdict, stem, detail))
            if args.verbose:
                for chk in res["checks"]:
                    print(f"       {'ok  ' if chk['ok'] else 'FAIL'} {chk['type']}: {chk['detail']}")
                if not res["same_tick_power"]:
                    print("       注意：" + res["power_note"])
        except RigUnavailable as exc:
            aborted = str(exc)
            print(_line("SKIP", stem, f"RCON 断开：{exc}"), file=sys.stderr)
            results.append({"rig": stem, "path": path, "verdict": "SKIP",
                            "detail": str(exc), "ok": False, "checks": []})
            break
        except (RigError, OSError, json.JSONDecodeError) as exc:
            # JSON 契约不合法 / 装置命令失败：这是 FAIL，不是 SKIP —— 装置或契约要修
            print(_line("FAIL", stem, str(exc)))
            results.append({"rig": stem, "path": path, "verdict": "FAIL",
                            "detail": str(exc), "ok": False, "checks": []})

    n_pass = sum(1 for r in results if r["verdict"] == "PASS")
    n_fail = sum(1 for r in results if r["verdict"] == "FAIL")
    n_skip = sum(1 for r in results if r["verdict"] == "SKIP") + len(missing)
    print(f"\n== 汇总：PASS {n_pass} / FAIL {n_fail} / SKIP {n_skip}"
          f"（共选中 {len(entries)} 台，{round(time.time() - t0, 1)}s）")
    for r in results:
        if r["verdict"] == "FAIL":
            print(f"   FAIL {r['rig']}: {r['detail']}")

    verdict = "PASS"
    exit_code = EXIT_PASS
    if n_fail:
        verdict, exit_code = "FAIL", EXIT_FAIL
    elif aborted:
        verdict, exit_code = "SKIP", EXIT_SKIP
    elif missing:
        verdict, exit_code = "SKIP", EXIT_SKIP

    result = {
        "runner": "run_rigs",
        "doc": "docs/23 §P2（机器矩阵）",
        "started_at": started,
        "elapsed_s": round(time.time() - t0, 1),
        "rcon": {"host": rc.host, "port": rc.port, "commands": rc.commands},
        "rigs_dir": os.path.abspath(args.rigs_dir),
        "only": only,
        "require": require,
        "kill_types": list(kill_types),
        "environment": env,
        "results": results,
        "summary": {"selected": len(entries), "pass": n_pass,
                    "fail": n_fail, "skip": n_skip, "missing": missing},
        "aborted": aborted,
        "verdict": verdict,
    }
    if args.out:
        parent = os.path.dirname(os.path.abspath(args.out))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=2)
        print(f"   结果 JSON: {os.path.abspath(args.out)}")
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())

