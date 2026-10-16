#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T1/T2/T3 验收探针：scoreboard 全子命令 + clone（同区域/跨区域真拷）+ 投递自检。

设计要点（都是踩过的坑）：
  * **每条命令一个独立 RCON 连接**：Folia 下部分命令异步执行、响应延后到达，
    共用连接会把"命令未注册 / 执行报错 / 响应错位"混为一谈（docs/14 §5）。
  * clone 必须**先 forceload 出真实载荷**再断言：只发一条 clone 到未加载处，
    只会打到 "That position is not loaded" 早退分支，压不到真正会碰世界的代码。
  * clone 修好后是 **fire-and-forget**（投递到目标区域后立即返回）⇒ 断言必须**轮询**，
    不能紧接着读一次就下结论。
  * 跨区域用例：两个 forceload 簇相隔 ~1000 格 ⇒ 必然落在不同区域。
  * 计分板参数顺序是 `<targets> <objective>`（写反了会得到 "Unknown scoreboard objective"）。

用法（需服务端已起且 RCON 就绪）：
    python3 tools/smoke/scoreboard_clone_probe.py
"""
import re
import sys
import time

sys.path.insert(0, 'tools/smoke')
from rcon import Rcon

FAIL = []
OK = []


def one(cmd, wait=3.0):
    """每条命令用**独立连接**发送并读取 ⇒ 结构上不可能发生响应交错。"""
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=60.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n', ' ⏎ ')


def check(label, cmd, expect, wait=3.0):
    got = one(cmd, wait=wait)
    ok = bool(expect(got))
    (OK if ok else FAIL).append((label, cmd, got))
    print(f"  [{'OK  ' if ok else 'FAIL'}] {label}\n         $ {cmd}\n         -> {got[:200]}")
    return got


def poll(label, cmd, expect, tries=14, delay=0.5):
    """fire-and-forget 的断言：轮询直到成立或超时。"""
    got = ''
    for _ in range(tries):
        got = one(cmd, wait=2.0)
        if expect(got):
            (OK).append((label, cmd, got))
            print(f"  [OK  ] {label}\n         $ {cmd}\n         -> {got[:200]}")
            return got
        time.sleep(delay)
    FAIL.append((label, cmd, got))
    print(f"  [FAIL] {label}（轮询 {tries} 次仍未成立）\n         $ {cmd}\n         -> {got[:200]}")
    return got


def not_unknown(got):
    return 'Unknown or incomplete command' not in got


def dispatch_blocking_calls():
    """读 /cerulean dispatch 自检里的 callBlocking 计数（拿不到返回 None）。"""
    m = re.search(r'callBlocking=(\d+)', one('/cerulean dispatch', wait=1.5))
    return int(m.group(1)) if m else None


print("== T1 scoreboard：全子命令（每条独立连接）")
check('objectives add', 'scoreboard objectives add ceruprobe dummy', not_unknown)
check('objectives list', 'scoreboard objectives list', not_unknown)
check('objectives modify displayname',
      'scoreboard objectives modify ceruprobe displayname {"text":"CeruProbe"}', not_unknown)
check('objectives modify rendertype',
      'scoreboard objectives modify ceruprobe rendertype hextime', not_unknown)
check('objectives modify displayautoupdate',
      'scoreboard objectives modify ceruprobe displayautoupdate true', not_unknown)
check('objectives modify numberformat blank',
      'scoreboard objectives modify ceruprobe numberformat blank', not_unknown)
check('players set', 'scoreboard players set holder ceruprobe 7', not_unknown)
check('players get', 'scoreboard players get holder ceruprobe', lambda g: '7' in g)
check('players add', 'scoreboard players add holder ceruprobe 3', lambda g: '10' in g)
check('players remove', 'scoreboard players remove holder ceruprobe 4', lambda g: '6' in g)
check('players operation', 'scoreboard players operation holder ceruprobe += holder ceruprobe', not_unknown)
check('players set (wildcard)', 'scoreboard players set * ceruprobe 1', not_unknown)
check('players display name',
      'scoreboard players display name holder ceruprobe {"text":"H"}', not_unknown)
check('players display numberformat blank',
      'scoreboard players display numberformat holder ceruprobe blank', not_unknown)
check('players list', 'scoreboard players list', not_unknown)
check('players list <target>', 'scoreboard players list holder', not_unknown)
# 实体选择器：ScoreHolderArgument 会去 level 里解析实体 ⇒ 计分板里唯一真正碰世界的入口
check('players set @e（实体选择器）',
      'scoreboard players set @e[limit=1] ceruprobe 3',
      lambda g: 'Unknown or incomplete' not in g)
check('players get @e（实体选择器）',
      'scoreboard players get @e[limit=1] ceruprobe',
      lambda g: 'Unknown or incomplete' not in g)
# setdisplay 会 broadcastAll 到所有在线实体（ServerScoreboard）
check('objectives setdisplay sidebar', 'scoreboard objectives setdisplay sidebar ceruprobe', not_unknown)
check('objectives setdisplay list', 'scoreboard objectives setdisplay list ceruprobe', not_unknown)
check('objectives setdisplay <slot> (clear)', 'scoreboard objectives setdisplay sidebar', not_unknown)
check('players reset', 'scoreboard players reset holder', not_unknown)
check('players enable', 'scoreboard players enable holder ceruprobe', not_unknown)
check('objectives remove', 'scoreboard objectives remove ceruprobe', not_unknown)

print()
print("== T2 clone：先造载荷，再断言真的拷到了")
check('forceload 源区域', 'forceload add 96 0 128 16', lambda g: True)
check('源 setblock（可读标记）', 'setblock 96 100 0 minecraft:command_block{Command:"say cloned"}', not_unknown)
poll('源内容确认', 'data get block 96 100 0 Command', lambda g: 'say cloned' in g)
# 同区域：源 chunk 6 / 目标 chunk 6
check('clone 同区域', 'clone 96 100 0 96 100 0 100 100 0', not_unknown)
poll('clone 同区域 · 真的拷到了', 'data get block 100 100 0 Command', lambda g: 'say cloned' in g)
# 跨区域：源 chunk 6（区域 A），目标 ~1000 格外（区域 B）
check('forceload 目标区域（相隔 ~1000 格）', 'forceload add 992 0 1008 16', lambda g: True)
check('clone 跨区域', 'clone 96 100 0 96 100 0 1000 100 0', not_unknown)
poll('clone 跨区域 · 真的拷到了', 'data get block 1000 100 0 Command', lambda g: 'say cloned' in g)
# 跨维度（R2）：两跳搬运后必须真的能拷
check('forceload nether 目标区域（execute in 切维度）',
      'execute in minecraft:the_nether run forceload add 96 0 128 16',
      lambda g: 'Unknown or incomplete' not in g)
check('clone 跨维度 overworld → nether',
      'clone 96 100 0 96 100 0 to minecraft:the_nether 100 100 0',
      lambda g: 'Unknown or incomplete' not in g)
poll('clone 跨维度 · 真的拷到了（nether 侧）',
     'execute in minecraft:the_nether run data get block 100 100 0 Command',
     lambda g: 'say cloned' in g)
check('clone 跨维度 nether → overworld',
      'clone from minecraft:the_nether 100 100 0 100 100 0 120 100 0',
      lambda g: 'Unknown or incomplete' not in g)
poll('clone 跨维度反向 · 真的拷到了（overworld 侧）',
     'data get block 120 100 0 Command', lambda g: 'say cloned' in g)

# 未加载/超限处必须仍是"业务响应"，不能退化成内部错误
check('clone 未加载处（业务响应）',
      'clone 5000 100 0 5000 100 0 5001 100 0',
      lambda g: 'not loaded' in g.lower())
check('clone 面积超限（业务响应）',
      'clone 96 100 0 5000 100 0 100 100 0',
      lambda g: 'too big' in g.lower() or 'not loaded' in g.lower())

print()
print("== T3 自检：写路径不阻塞")
before = dispatch_blocking_calls()
print(f"  callBlocking（clone 之前）= {before}")
check('再跑一次 clone（写路径）', 'clone 96 100 0 96 100 0 104 100 0', not_unknown)
check('再跑一次 setblock（写路径）', 'setblock 108 100 0 minecraft:stone', not_unknown)
after = dispatch_blocking_calls()
print(f"  callBlocking（写路径之后）= {after}")
if before is not None and after is not None and after == before:
    OK.append(('写路径未增加阻塞等待', '/cerulean dispatch', str(after)))
    print("  [OK  ] 写路径未增加阻塞等待（clone/setblock 走 dispatch，投递即返回）")
else:
    FAIL.append(('写路径未增加阻塞等待', '/cerulean dispatch', f"{before} -> {after}"))
    print(f"  [FAIL] 写路径增加了阻塞等待：{before} -> {after}")
check('读路径确实会等待（data get 的固有代价）', 'data get block 96 100 0 Command',
      lambda g: 'say cloned' in g)
after2 = dispatch_blocking_calls()
print(f"  callBlocking（data get 之后）= {after2}")

print()
print(f"== 小结：{len(OK)} 通过 / {len(FAIL)} 失败")
for label, cmd, got in FAIL:
    print(f"  FAIL {label}: {got[:160]}")
sys.exit(1 if FAIL else 0)
