#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T4 验收驱动器：SAFE → EXP 两种模式各起一次服，分别跑 exp_chain_probe.py。

为什么要驱动器：模式来自启动时读取的配置文件，改模式必须重启服务端。
用法：
    python3 tools/smoke/exp_chain_acceptance.py [jar] [rundir]
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
RUN = sys.argv[2] if len(sys.argv) > 2 else './smoke-exp'
CONF = os.path.join(RUN, 'azurebranches_global_config.toml')
ENV = dict(os.environ, CERULEAN_EULA='TRUE', RCON_PORT='25575', RCON_PASSWORD='test1234')


def sh(*args, **kw):
    return subprocess.run(args, env=ENV, text=True, capture_output=True, **kw)


MINIMAL = ('# CeruLean 冒烟：AzureBranches 配置（驱动器每次重写，保证模式确定）\n'
           '\n[command_blocks]\nmode = "{mode}"\n')


def set_mode(mode):
    """**每次起服前都写**一份最小配置。

    踩过的坑：早先只在第二次起服时才改 mode ⇒ "SAFE" 那一轮实际用的是上一轮残留的 EXP，
    整轮断言都建立在错误前提上。现在两轮都显式落地，模式不含糊。
    （写最小文件是安全的：AzureBranchesConfig.load 只在解析到 0 个键时才回写默认值。）"""
    os.makedirs(RUN, exist_ok=True)
    open(CONF, 'w', encoding='utf-8').write(MINIMAL.format(mode=mode))
    return 1


def run_mode(mode, first):
    print(f"\n{'=' * 70}\n== 起服（mode={mode}）\n{'=' * 70}")
    sh('tools/smoke/server-down.sh', RUN)
    if os.path.exists(os.path.join(RUN, 'server.pid')):
        os.remove(os.path.join(RUN, 'server.pid'))
    set_mode(mode)
    print(f"  配置已落地：mode={mode}")
    up = sh('tools/smoke/server-up.sh', JAR, RUN)
    print(up.stdout.strip().splitlines()[-1] if up.stdout.strip() else up.stderr[-400:])
    if up.returncode != 0:
        print(f"  起服失败（exit {up.returncode}）")
        return 1
    # 稳定期：**全新世界的首启**存在一个已复现的冷启动竞态——刚 forceload 出来的区块
    # 所在区域，其任务队列在开局一段时间内不排空（AwaitableDispatch 5s 超时、投递类命令
    # 无响应），实测可长达 2 分钟以上；世界已生成过的第二轮则立刻正常。
    # 这里先跑 L0 探针（它会加载区块、给区域调度器时间稳定）再进正式断言，
    # 把"环境未就绪"与"功能坏"分开。该竞态本身记在 docs/16 §T4 已知问题，未修。
    settle = subprocess.run([sys.executable, 'tools/smoke/folia_base_probe.py'], env=ENV,
                            capture_output=True, text=True)
    print("  稳定期：L0 探针 " + ("通过" if settle.returncode == 0 else f"未通过({settle.returncode})"))
    time.sleep(3)
    log = os.path.join(RUN, 'server-console.log')
    probe = subprocess.run([sys.executable, 'tools/smoke/exp_chain_probe.py', mode, log], env=ENV)
    occ = None
    if mode == 'EXP':
        # EXP6Plus 的 OCC 冲突用例（C1/C2/C3）只在 EXP 下有意义
        occ = subprocess.run([sys.executable, 'tools/smoke/exp6plus_occ_probe.py', log], env=ENV)
    sh('tools/smoke/server-down.sh', RUN)
    return probe.returncode or (occ.returncode if occ else 0)


rc_safe = run_mode('SAFE', first=True)
rc_exp = run_mode('EXP', first=False)

print(f"\n{'=' * 70}")
print(f"== T4 验收结果：SAFE exit={rc_safe} / EXP exit={rc_exp}")
print('=' * 70)
sys.exit(0 if rc_safe == 0 and rc_exp == 0 else 1)
