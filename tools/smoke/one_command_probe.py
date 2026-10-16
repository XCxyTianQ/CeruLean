#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐条命令探针：**每条命令用一个独立 RCON 连接**发送并读取。

为什么必须独立连接：Folia 下部分命令异步执行、响应延后到达，若共用连接会与下一条命令的响应交错，
从而把"命令未注册"与"响应错位"混为一谈（本会话踩过）。用法：
    python3 tools/smoke/one_command_probe.py        # 需服务端已起且 RCON 就绪
"""
import sys
sys.path.insert(0,'tools/smoke')
from rcon import Rcon
def one(cmd, wait=3.0):
    """每条命令用**独立连接**发送并读取 ⇒ 结构上不可能发生响应交错。"""
    with Rcon(host='127.0.0.1', port=25575, password='test1234', timeout=60.0, quiet=True) as r:
        return (r.cmd(cmd, wait=wait, first_wait=30.0) or '').strip().replace('\n',' ⏎ ')
CMDS = [
  "forceload add 96 0 128 16",
  'setblock 100 100 0 minecraft:command_block{Command:"say hello"}',
  "data get block 100 100 0 Command",
  "fill 200 100 0 201 100 0 minecraft:stone",
  "scoreboard objectives add ceruprobe dummy",
  "scoreboard players set ceruprobe ceruprobe 7",
  "clone 200 100 0 200 100 0 205 100 0",
]
for c in CMDS:
    print(f"  $ {c}\n      → {one(c)[:200]}")
