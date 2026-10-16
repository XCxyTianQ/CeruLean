#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R1 存档往返检查：证明 `storeToSaveDataIfDirty` 的临界区改动没把持久化改坏。

为什么要单独一个驱动器：触发存档的命令（`save-all`）是 **Bukkit 命令、不经 Brigadier**，
RCON 的 `performPrefixedCommand` 派发不到它（实测 Unknown or incomplete command）。
可用的触发点是**优雅关服**（RegionShutdownThread → saveGlobalData → ServerScoreboard.store
→ packPlayerScores/packObjectives）。所以往返必须跨一次起停。

用法： python3 tools/smoke/scoreboard_persist_check.py [jar] [rundir]
"""
import glob
import os
import re
import subprocess
import sys
import time

def _resolve_jar(explicit=None):
    """按 glob 找最新 paperclip jar（版本串会随 channel 变，不要写死文件名）。"""
    if explicit:
        return explicit
    import glob
    cands = glob.glob('cerulean-server/build/libs/*paperclip*.jar')
    if not cands:
        raise SystemExit('找不到 paperclip jar：先跑 :cerulean-server:createPaperclipJar')
    return max(cands, key=os.path.getmtime)


JAR = _resolve_jar(sys.argv[1] if len(sys.argv) > 1 else None)
RUN = sys.argv[2] if len(sys.argv) > 2 else './smoke-persist'
ENV = dict(os.environ, CERULEAN_EULA='TRUE', RCON_PORT='25575', RCON_PASSWORD='test1234')
OBJ, HOLDER, VALUE = 'cerupersist', 'persistholder', 424242

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon


def one(cmd, wait=2.0):
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=90.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def sh(*args):
    return subprocess.run(args, env=ENV, text=True, capture_output=True)


def boot():
    sh('tools/smoke/server-down.sh', RUN)
    pid = os.path.join(RUN, 'server.pid')
    if os.path.exists(pid):
        os.remove(pid)
    up = sh('tools/smoke/server-up.sh', JAR, RUN)
    if up.returncode != 0:
        print("起服失败：", (up.stdout or up.stderr)[-400:])
        sys.exit(1)
    # 环境前提：调度器 >=2 线程，否则同步跨区域读取会自锁（docs/17）
    for _ in range(20):
        m = re.search(r'调度线程 (\d+)', one('/cerulean regions'))
        if m and int(m.group(1)) >= 2:
            return
        time.sleep(1)
    print("[SKIP] 环境前提不满足：调度器线程数 < 2，同步跨区域读取会自锁 ⇒ 本次记作未验证")
    sys.exit(2)


def stop():
    sh('tools/smoke/server-down.sh', RUN)
    time.sleep(2)


print("== R1 存档往返：写入 → 优雅关服 → 重启 → 读回")
os.makedirs(RUN, exist_ok=True)
boot()
one(f'scoreboard objectives remove {OBJ}')
one(f'scoreboard objectives add {OBJ} dummy')
one(f'scoreboard players set {HOLDER} {OBJ} {VALUE}')
got = one(f'scoreboard players get {HOLDER} {OBJ}')
print(f"  写入后：{got[:110]}")
stop()

boot()
got = one(f'scoreboard players get {HOLDER} {OBJ}')
print(f"  重启后：{got[:110]}")
ok = str(VALUE) in got
one(f'scoreboard objectives remove {OBJ}')
stop()
print(f"\n== 小结：{'通过' if ok else '失败'}（存档往返）")
sys.exit(0 if ok else 1)
