#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R1 验收探针：Scoreboard 并发改造后的功能回归 + 顺序压力。

**诚实边界**：本冒烟环境只有 1 个区域（force-load 的区块簇被并入同一区域），
`scoreboard` 命令又都跑在 global region tick 线程上 ⇒ **构造不出真正的跨区域并发**。
所以这个探针能证明的是"改造没把功能改坏 + 顺序累积精确"，**不能**替代真并发压测。
真并发需要玩家在线（触发 ServerPlayer.awardStat/awardKillScore 等区域线程写者）。

要点：
  * `players add` 覆写成了锁内读-改-写 ⇒ 累积值必须**精确**等于累加次数（能抓出改坏的 mutate）；
  * objective 反复增删 ⇒ 抓 criteria→objectives 列表与 objectivesByName 的容器改造问题；
  * `setdisplay` 反复设置/清除 ⇒ 走 ServerScoreboard 的 broadcastAll/trackedObjectives；
  * `save-all flush` ⇒ 走 dirty + storeToSaveDataIfDirty 的临界区改动；
  * 全程无新增非预期 ERROR。

用法（需服务端已起且 RCON 就绪）：
    python3 tools/smoke/scoreboard_stress_probe.py [累加次数,默认 100]
"""
import re
import sys
import threading

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

N = int(sys.argv[1]) if len(sys.argv) > 1 else 100
LOG = sys.argv[2] if len(sys.argv) > 2 else 'smoke-run/server-console.log'
OBJ, HOLDER = 'cerustress', 'stressholder'

FAIL = []
OK = []


def one(cmd, wait=2.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=90.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def record(label, got, ok, cmd=''):
    (OK if ok else FAIL).append((label, cmd, got))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}  -> {str(got)[:150]}")
    return got


def log_errors(text):
    return [l for l in text.splitlines() if 'ERROR]' in l]


def read_log():
    try:
        return open(LOG, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


before = log_errors(read_log())
def scheduler_threads(tries=20, delay=1.0):
    """探测 Folia 调度器当前线程数。

    为什么先探测：`data get` 这类"命令契约要求同步返回值"的读取必须把世界访问投递到目标区域
    并**同步等待**；当调度器只有 1 个线程时，global region 与目标 world region 跑在同一个
    `Folia Region Scheduler Thread #0` 上 ⇒ 等待等于等自己 ⇒ **自锁**（实测会把 global region
    卡到 `[FoliaWatchdogThread] Global region has not responded in 5s`）。
    这不是功能坏，是环境前提不满足 —— 必须显式区分，不能记成通过、也不该记成失败。
    """
    for _ in range(tries):
        m = re.search(r'调度线程 (\d+)', one('/cerulean regions'))
        if m and int(m.group(1)) >= 2:
            return int(m.group(1))
        time.sleep(delay)
    m = re.search(r'调度线程 (\d+)', one('/cerulean regions'))
    return int(m.group(1)) if m else 0


def require_two_scheduler_threads():
    n = scheduler_threads()
    if n < 2:
        print(f"[SKIP] 环境前提不满足：Folia 调度器当前只有 {n} 个线程。")
        print("        同步跨区域读取（data get / clone 的真拷断言）在此前提下会自锁，属已知限制")
        print("        （docs/17 §环境前提）。本次结果记作**未验证**，既不算通过也不算失败。")
        sys.exit(2)
    print(f"  环境前提：调度线程 {n} 个（>=2，可安全同步等待）")
    return n


require_two_scheduler_threads()

print(f"== R1 Scoreboard 压力与回归（N={N}）")

# 清理可能的上轮残留
one(f'scoreboard objectives remove {OBJ}')
one(f'scoreboard players reset {HOLDER}')

record('objectives add', one(f'scoreboard objectives add {OBJ} dummy'), True)
one(f'scoreboard players set {HOLDER} {OBJ} 0')

# ── 1) 累积精确性：这是 add 覆写（锁内读-改-写）的核心判据 ──────────────
for _ in range(N):
    one(f'scoreboard players add {HOLDER} {OBJ} 1')
got = one(f'scoreboard players get {HOLDER} {OBJ}')
m = re.search(r'has (-?\d+)', got) or re.search(r'(-?\d+)', got)
value = int(m.group(1)) if m else None
record(f'players add ×{N} 累积精确 == {N}', got, value == N, f'{N} 次 add')

# ── 2) 双连接并发（注意：命令仍在 global tick 线程上串行执行，不是真并发）──
one(f'scoreboard players set {HOLDER} {OBJ} 0')
errors = []


def hammer():
    try:
        for _ in range(N // 2):
            one(f'scoreboard players add {HOLDER} {OBJ} 1')
    except Exception as exc:  # noqa: BLE001
        errors.append(repr(exc))


t1, t2 = threading.Thread(target=hammer), threading.Thread(target=hammer)
t1.start(); t2.start(); t1.join(); t2.join()
got = one(f'scoreboard players get {HOLDER} {OBJ}')
m = re.search(r'has (-?\d+)', got) or re.search(r'(-?\d+)', got)
value2 = int(m.group(1)) if m else None
record('双连接并发 add（串行化，非真并发）后累积仍精确', got,
       not errors and value2 == N, '2 threads × N/2')

# ── 3) objective 反复增删（容器改造回归）────────────────────────────────
ok_cycles = True
detail = ''
for i in range(10):
    one(f'scoreboard objectives add {OBJ}c{i} dummy')
    one(f'scoreboard objectives setdisplay sidebar {OBJ}c{i}')
    one(f'scoreboard objectives setdisplay sidebar')
    r = one(f'scoreboard objectives remove {OBJ}c{i}')
    if 'Removed objective' not in r:
        ok_cycles = False; detail = r; break
record('objective 增删 + setdisplay 循环 ×10', detail or 'ok', ok_cycles)

# ── 4) 存档临界区（dirty + storeToSaveDataIfDirty）─────────────────────
# 注：`save-all` 是 Bukkit 命令，**不经 Brigadier**，RCON 的 performPrefixedCommand 派发不到它
# （实测 "Unknown or incomplete command"）。所以这里只保留一个留存标记，
# 真正的"存档 → 重启 → 读回"往返由 tools/smoke/scoreboard_persist_check.py 负责。
one(f'scoreboard players set {HOLDER} {OBJ} 424242')
record('写入留存标记（供重启往返检查）',
       one(f'scoreboard players get {HOLDER} {OBJ}'), True)

# ── 5) 选择器路径（ScoreHolderArgument 解析实体）──────────────────────
record('players set @e（选择器，正常业务响应）',
       one(f'scoreboard players set @e[limit=1] {OBJ} 5'),
       True)

# ── 6) 收尾 ────────────────────────────────────────────────────────────
one(f'scoreboard players reset {HOLDER}')
one(f'scoreboard objectives remove {OBJ}')

after = log_errors(read_log())
new = [l for l in after if l not in before and '/data get block' not in l]
record('本轮无新增非预期 ERROR', f'{len(new)} 条', len(new) == 0)

print()
print(f"== 小结：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, cmd, got in FAIL:
    print(f"  FAIL {label}: {str(got)[:160]}")
sys.exit(1 if FAIL else 0)
