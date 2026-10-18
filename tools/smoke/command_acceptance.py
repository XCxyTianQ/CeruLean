#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""区域化命令覆盖层验收驱动器：起一台 EXP 模式的服务端，跑命令矩阵 + 混合压测。

为什么要驱动器：压测断言依赖 `/cerulean exp` 的 OCC 计数器（`通过 > 0`），
而 OCC 只在 EXP 模式下接线 ⇒ 必须显式写配置并起服，不能用套件共用那台 SAFE 服。

用法：python3 tools/smoke/command_acceptance.py [jar] [rundir]
"""
import glob
import os
import subprocess
import sys

JAR = (sys.argv[1] if len(sys.argv) > 1 else
       max(glob.glob('cerulean-server/build/libs/*paperclip*.jar'), key=os.path.getmtime))
RUN = sys.argv[2] if len(sys.argv) > 2 else './smoke-cmd'
CONF = os.path.join(RUN, 'azurebranches_global_config.toml')
LOG = os.path.join(RUN, 'server-console.log')
ENV = dict(os.environ, CERULEAN_EULA='TRUE', RCON_PORT='25575', RCON_PASSWORD='test1234')


def sh(*args):
    return subprocess.run(args, env=ENV, text=True, capture_output=True)


print(f"{'=' * 70}\n== 起服（mode=EXP，用于命令覆盖层验收）\n{'=' * 70}")
sh('tools/smoke/server-down.sh', RUN)
if os.path.exists(os.path.join(RUN, 'server.pid')):
    os.remove(os.path.join(RUN, 'server.pid'))
os.makedirs(RUN, exist_ok=True)
open(CONF, 'w', encoding='utf-8').write('# CeruLean 命令覆盖层验收：驱动器每次重写\n\n[command_blocks]\nmode = "EXP"\n')
up = sh('tools/smoke/server-up.sh', JAR, RUN)
print(up.stdout.strip().splitlines()[-1] if up.stdout.strip() else up.stderr[-400:])
if up.returncode != 0:
    print('起服失败')
    sys.exit(1)

rcs = {}
try:
    print(f"\n{'=' * 70}\n== 命令矩阵（两种上下文）\n{'=' * 70}")
    p = subprocess.run([sys.executable, 'tools/smoke/region_commands_probe.py', LOG], env=ENV)
    rcs['命令矩阵'] = p.returncode

    print(f"\n{'=' * 70}\n== 混合压力（3 轮 x 4 条链）\n{'=' * 70}")
    p = subprocess.run([sys.executable, 'tools/smoke/command_stress_probe.py', LOG, '3', '0.2'], env=ENV)
    rcs['混合压力'] = p.returncode
finally:
    sh('tools/smoke/server-down.sh', RUN)

print(f"\n{'=' * 70}\n== 命令覆盖层验收汇总\n{'=' * 70}")
for k, v in rcs.items():
    print(f"  {'PASS' if v == 0 else 'FAIL'}  {k}")
fails = [k for k, v in rcs.items() if v != 0]
print(f"\n  {'通过' if not fails else '失败'}: {len(rcs) - len(fails)} / {len(rcs)}")
sys.exit(1 if fails else 0)
