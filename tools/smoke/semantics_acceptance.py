#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L3 语义层验收驱动器（docs/23 P5）：起一次服，跑 P0 + M1 + 装置矩阵，可选 P3 跨区域。

为什么单起一次服：L3 会炸方块（TNT）、会 forceload 远处区块（P3），
放在 L0/L1/L2 共用的那个服上会污染 T2/T3 的计分板与数据面断言。
退出码沿用全局约定：0=PASS / 1=FAIL / **2=SKIP（核心项的前提不满足）**。

用法：
    python3 tools/smoke/semantics_acceptance.py [jar] [rundir]
环境变量：MC_PORT / RCON_PORT / RCON_PASSWORD（默认 25565 / 25575 / test1234）

核心项（决定退出码）：
  1. P0 最小检查（rig_common.py --p0）
  2. M1 双活塞竞争 20 轮（有基线文件时按分布比对，允许抖动但主导胜者必须一致）
  3. 装置矩阵 m6/m7 对照（run_rigs.py --require，缺装置即 SKIP 而不是 PASS）
非核心项（只记录，不改变退出码；FAIL 除外）：
  4. P3 跨区域（没有 >=2 个区域，或没有 p3_cross_region.py → SKIP）
"""
import glob
import os
import re
import subprocess
import sys

PY = sys.executable
RIGS = 'tools/smoke/rigs'
M1 = os.path.join(RIGS, 'm1_piston_race.py')
MATRIX = os.path.join(RIGS, 'run_rigs.py')
P3 = os.path.join(RIGS, 'p3_cross_region.py')
EXPECTED = os.path.join(RIGS, 'expected', 'm1_cerulean.json')
# 闸门要求哪些装置必须 PASS：默认只要求两台对照（m6/m7），
# 经 vanilla 验证过的装置由 expected/gate_rigs.txt 逐行列出（写进版本控制，
# 这样"哪些装置够格当闸门"是有据可查的，而不是在代码里改常量）。
GATE = os.path.join(RIGS, 'expected', 'gate_rigs.txt')
DEFAULT_GATE = 'm6_control_push,m7_control_no_dupe'


def required_rigs():
    if os.path.exists(GATE):
        names = []
        with open(GATE, encoding='utf-8') as fh:
            for line in fh:
                name = line.split('#', 1)[0].strip()
                if name:
                    names.append(name)
        if names:
            return ','.join(names)
    return DEFAULT_GATE


def _resolve_jar(explicit=None):
    """按 glob 找最新 paperclip jar —— 版本串随 channel 变，不要写死文件名。"""
    if explicit:
        return explicit
    cands = glob.glob('cerulean-server/build/libs/*paperclip*.jar')
    if not cands:
        raise SystemExit('找不到 paperclip jar：先跑 :cerulean-server:createPaperclipJar')
    return max(cands, key=os.path.getmtime)


JAR = _resolve_jar(sys.argv[1] if len(sys.argv) > 1 else None)
RUN = sys.argv[2] if len(sys.argv) > 2 else './smoke-sem'
MC_PORT = os.environ.get('MC_PORT', '25565')
RCON_PORT = os.environ.get('RCON_PORT', '25575')
RCON_PASSWORD = os.environ.get('RCON_PASSWORD', 'test1234')
ENV = dict(os.environ, CERULEAN_EULA='TRUE', MC_PORT=MC_PORT,
           RCON_PORT=RCON_PORT, RCON_PASSWORD=RCON_PASSWORD)

RESULTS = []   # (label, rc, detail)


def note(label, rc, detail=''):
    RESULTS.append((label, rc, detail))
    tag = {0: 'PASS', 1: 'FAIL', 2: 'SKIP'}.get(rc, f'ERR({rc})')
    print(f"  [{tag}] {label}" + (f"  {detail}" if detail else ''))
    return rc


def sh(*args, **kw):
    return subprocess.run(args, env=ENV, text=True, capture_output=True, **kw)


def regions_info():
    """解析 /cerulean regions：调度线程数 + overworld 区域数 / 段大小。"""
    sys.path.insert(0, RIGS)
    try:
        from rig_common import Rcon          # noqa: E402
        out = Rcon(port=int(RCON_PORT), password=RCON_PASSWORD).cmd('/cerulean regions', wait=0.5) or ''
    except Exception as exc:                  # 连接失败不算 L3 失败，按未知处理
        print(f"  [/cerulean regions 读取失败] {exc}")
        return None
    txt = out.replace('\n', ' | ')
    print(f"  /cerulean regions -> {txt[:400]}")
    threads = re.search(r'调度线程 (\d+)', out)
    parts = re.search(r'overworld: 区域 (\d+) / 段 (\d+)', out)
    return {
        'threads': int(threads.group(1)) if threads else 0,
        'regions': int(parts.group(1)) if parts else 0,
        'section_chunks': int(parts.group(2)) if parts else 0,
    }


def main():
    print(f"== L3 语义层验收（jar={JAR}，rundir={RUN}）")
    if not (os.path.exists(M1) and os.path.exists(MATRIX)):
        print("  缺少探针脚本（tools/smoke/rigs/*.py）⇒ 未验证")
        return 2

    sh('tools/smoke/server-down.sh', RUN)
    pid = os.path.join(RUN, 'server.pid')
    if os.path.exists(pid):
        os.remove(pid)
    up = sh('tools/smoke/server-up.sh', JAR, RUN)
    if up.returncode != 0:
        print("  起服失败：" + (up.stdout or up.stderr)[-500:])
        return 1
    print("  " + (up.stdout.strip().splitlines()[-1] if up.stdout.strip() else '起服完成'))

    # 稳定期：全新世界首启时区域任务队列可能不排空（见 exp_chain_acceptance 的同款处理）
    settle = subprocess.run([PY, 'tools/smoke/folia_base_probe.py'], env=ENV,
                            capture_output=True, text=True)
    print("  稳定期：L0 探针 " + ("通过" if settle.returncode == 0 else f"未通过({settle.returncode})"))

    info = regions_info()
    if info:
        note('环境前提：调度线程 >=2', 0 if info['threads'] >= 2 else 2,
             f"调度线程={info['threads']} 区域={info['regions']} 段={info['section_chunks']} 区块")

    # 装置区必须先被加载才能读/tick：M1 覆盖 (-8..8)，矩阵在 x 0..112 / z 0..32
    # （/forceload 的参数是**区块**坐标，不是方块坐标）
    try:
        sys.path.insert(0, RIGS)
        from rig_common import Rcon as _Rcon  # noqa: E402
        _r = _Rcon(port=int(RCON_PORT), password=RCON_PASSWORD)
        for box in ('-1 -1 1 1', '0 0 7 2'):
            _r.cmd('/forceload add ' + box, wait=0.5)
        print("  已 forceload 装置区（M1: -1 -1 1 1；矩阵: 0 0 7 2）")
    except Exception as exc:
        print(f"  forceload 失败（继续，可能只是已加载）：{exc}")

    # 1) P0 最小检查
    p0 = sh(PY, os.path.join(RIGS, 'rig_common.py'), '--p0',
            '--port', RCON_PORT, '--password', RCON_PASSWORD)
    print((p0.stdout or '')[-1200:])
    note('P0 最小检查（list/gamerule/setblock/清场/实体/快照）', p0.returncode)

    # 2) M1 双活塞竞争
    m1args = [PY, M1, '--port', RCON_PORT, '--password', RCON_PASSWORD,
              '--rounds', '20', '--baseline', 'cerulean',
              '--out', os.path.join(RUN, 'm1-cerulean.json'), '--allow-jitter']
    if os.path.exists(EXPECTED):
        m1args += ['--expect', EXPECTED]
        print(f"  M1 基线：{EXPECTED}（严格分布 + 抖动容忍：主导胜者必须一致）")
    else:
        print("  M1 基线：无（本次只做自洽性检查；把结果落到 tools/smoke/rigs/expected/ 作为回归基线）")
    m1 = subprocess.run(m1args, env=ENV, text=True, capture_output=True)
    print((m1.stdout or '')[-1200:])
    note('M1 双活塞竞争（20 轮）', m1.returncode)

    # 3) 装置矩阵：对照必须成功（装置没搭错的自检），复制机按是否已验证决定是否要求
    require = required_rigs()
    print(f"  闸门要求的装置：{require}")
    # 闸门**只跑点名的装置**：m2–m5 是"机制前置观察装置"（docs/23 §11.3），
    # 其中 m2 的点燃判据依赖落地 tick，实测约 1/7 概率抖动 —— 放进发布闸门会把
    # 版本判红在"装置时序"而不是"代码坏了"上。它们仍可用 run_rigs.py 单独跑。
    mx = sh(PY, MATRIX, '--port', RCON_PORT, '--password', RCON_PASSWORD,
            '--only', require, '--require', require,
            '--out', os.path.join(RUN, 'rigs.json'))
    print((mx.stdout or '')[-1600:])
    note('装置矩阵（对照 M6/M7 必过）', mx.returncode)

    # 4) P3 跨区域（非核心项）
    if not os.path.exists(P3):
        note('P3 跨区域', 2, '未提供 p3_cross_region.py（本版不做，见 docs/23 §4 P3）')
    elif not info or info['regions'] < 2:
        note('P3 跨区域', 2, '只有 1 个区域，构造不出边界（SKIP，不是 PASS）')
    else:
        p3 = sh(PY, P3, '--port', RCON_PORT, '--password', RCON_PASSWORD,
                '--out', os.path.join(RUN, 'p3.json'))
        print((p3.stdout or '')[-1200:])
        note('P3 跨区域', p3.returncode)

    sh('tools/smoke/server-down.sh', RUN)

    core = [r for r in RESULTS if r[0].startswith(('P0', 'M1', '装置矩阵', '环境前提'))]
    fails = [r for r in core if r[1] == 1]
    skips = [r for r in core if r[1] == 2]
    p3r = [r for r in RESULTS if r[0].startswith('P3')]
    if p3r and p3r[0][1] == 1:
        fails.append(p3r[0])
    print(f"\n{'=' * 70}")
    print(f"== L3 小结：核心 {len(core)} 项，通过 {len([r for r in core if r[1] == 0])}"
          f" / 失败 {len(fails)} / 未验证 {len(skips)}")
    for label, rc, detail in RESULTS:
        print(f"  {({0: 'PASS', 1: 'FAIL', 2: 'SKIP'}.get(rc, 'ERR ')):4}  {label}  {detail}")
    print('=' * 70)
    if fails:
        return 1
    if skips:
        return 2
    return 0


sys.exit(main())
