#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CeruLean 验收套件（L0/L1/L2 分层）。

为什么分层：Folia 删了部分上游测试（docs/12），回归闸门只能靠我们自己的套件；
把"基座活着"（L0）、"基座把状态报出来且不报警"（L1）、"具体用例行为正确"（L2）分开，
失败时能立刻定位是哪一层的问题。

退出码约定（重要）：每个子项 0=通过、1=失败、**2=环境前提不满足（未验证）**。
"未验证"既不算通过也不算失败，会在汇总里单列 —— 不允许用 exit 0 假装通过。

用法：
    python3 tools/smoke/run_suite.py [--jar <path>]
"""
import glob
import os
import subprocess
import sys
import time

def resolve_jar(explicit=None):
    """按 glob 找最新的 paperclip jar —— **不要写死文件名**：版本串随 channel 变
    （本期 26.3-SNAPSHOT），写死会在换版本时静默指向旧产物。"""
    if explicit:
        return explicit
    cands = glob.glob('cerulean-server/build/libs/*paperclip*.jar')
    if not cands:
        raise SystemExit('找不到 paperclip jar：先跑 tools/gradle-local.sh :cerulean-server:createPaperclipJar')
    return max(cands, key=os.path.getmtime)


JAR = resolve_jar(sys.argv[sys.argv.index('--jar') + 1] if '--jar' in sys.argv else None)
print(f'== 使用 jar：{JAR}')
RUN = './smoke-suite'
ENV = dict(os.environ, CERULEAN_EULA='TRUE', RCON_PORT='25575', RCON_PASSWORD='test1234')
PY = sys.executable

L0 = [('L0 基座活性', ['tools/smoke/folia_base_probe.py'])]
L1 = [('L1 可观测面', ['tools/smoke/l1_observability_probe.py', os.path.join(RUN, 'server-console.log')])]
L2_LIVE = [
    ('L2 T1/T2/T3 scoreboard+clone', ['tools/smoke/scoreboard_clone_probe.py']),
    ('L2 R1 scoreboard 压力', ['tools/smoke/scoreboard_stress_probe.py', '100',
                               os.path.join(RUN, 'server-console.log')]),
]
L2_SELF = [
    ('L2 T4 EXP 链（SAFE+EXP 两轮起服）', ['tools/smoke/exp_chain_acceptance.py', JAR, './smoke-exp']),
    ('L2 R1 存档往返', ['tools/smoke/scoreboard_persist_check.py', JAR, './smoke-persist']),
]

results = []


def run(label, args):
    t0 = time.time()
    p = subprocess.run([PY] + args, env=ENV, capture_output=True, text=True)
    dt = time.time() - t0
    results.append((label, p.returncode, dt))
    tag = {0: 'PASS', 1: 'FAIL', 2: 'SKIP'}.get(p.returncode, f'ERR({p.returncode})')
    print(f"\n{'=' * 74}\n== {label}  ->  {tag}  ({dt:.0f}s)\n{'=' * 74}")
    tail = [l for l in (p.stdout or '').splitlines()
            if '小结' in l or l.strip().startswith('FAIL') or 'SKIP' in l or '验收通过' in l]
    print('\n'.join(tail[-12:]) if tail else (p.stdout or '')[-600:])
    if p.returncode not in (0, 2):
        print((p.stderr or '')[-600:])


def boot():
    subprocess.run(['tools/smoke/server-down.sh', RUN], env=ENV, capture_output=True)
    pid = os.path.join(RUN, 'server.pid')
    if os.path.exists(pid):
        os.remove(pid)
    up = subprocess.run(['tools/smoke/server-up.sh', JAR, RUN], env=ENV, capture_output=True, text=True)
    if up.returncode != 0:
        print("起服失败：", (up.stdout or up.stderr)[-500:])
        sys.exit(1)
    print((up.stdout or '').strip().splitlines()[-1])


print(f"== CeruLean 验收套件（jar={JAR}）")
print("-- 起服（L0/L1/L2-live 共用一次）")
boot()
for label, args in L0 + L1 + L2_LIVE:
    run(label, args)
subprocess.run(['tools/smoke/server-down.sh', RUN], env=ENV, capture_output=True)
for label, args in L2_SELF:
    run(label, args)

print(f"\n{'=' * 74}\n== 汇总\n{'=' * 74}")
for label, rc, dt in results:
    print(f"  {({0: 'PASS', 1: 'FAIL', 2: 'SKIP'}.get(rc, 'ERR ')):4}  {label}  ({dt:.0f}s)")
fails = [r for r in results if r[1] == 1]
skips = [r for r in results if r[1] == 2]
print(f"\n  通过 {len(results) - len(fails) - len(skips)} / 失败 {len(fails)} / 未验证 {len(skips)}")
for label, _, _ in fails:
    print(f"  FAIL: {label}")
for label, _, _ in skips:
    print(f"  SKIP（环境前提不满足，未验证）: {label}")
sys.exit(1 if fails else 0)
