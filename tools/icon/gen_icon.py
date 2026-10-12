#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 CeruLean 的图标 SVG。

设计（与 AzureBranches 图标同族、但身份不同）:
  AzureBranches = 浅青底 + 深藏青线条的"分支/迷宫"环 + 字标
  CeruLean     = 青蓝底 + 深藏青线条的"分区残片"环（区域划分）+ C 字徽 + 字标
  共同点: 圆形徽记 + 粗体字标 + 深色描边，保证一眼看出是同一系列。
"""
import math, os

W = H = 1024
CX, CY, R = 512.0, 400.0, 300.0          # 徽记圆心与半径
BG      = "#F8FBFE"
LIGHT   = "#DCEBF7"                       # 区域填充（青蓝）
MID     = "#7FB6E0"                       # 区域底色（次青蓝）
DEEP    = "#0E2A47"                       # 深藏青：描边/字标
ACCENT  = "#1E6FB8"                       # 强调色：核点
SEAM_W  = 30                              # 分区缝隙宽度
RIM_W   = 14                              # 外圈描边宽度

def pol(r, deg):
    t = math.radians(deg)
    return (CX + r * math.cos(t), CY + r * math.sin(t))

def fmt(p):
    return f"{p[0]:.2f} {p[1]:.2f}"

def arc_path(cx, cy, r_out, r_in, gap_half_deg, sweep_through_deg):
    """生成一个"粗环缺一口"的 C 形（填充路径，非描边）"""
    a0 = gap_half_deg                      # 起点角
    a1 = sweep_through_deg - gap_half_deg  # 终点角（顺时针经过 180°）
    large = 1
    po0 = (cx + r_out * math.cos(math.radians(a0)), cy + r_out * math.sin(math.radians(a0)))
    po1 = (cx + r_out * math.cos(math.radians(a1)), cy + r_out * math.sin(math.radians(a1)))
    pi1 = (cx + r_in  * math.cos(math.radians(a1)), cy + r_in  * math.sin(math.radians(a1)))
    pi0 = (cx + r_in  * math.cos(math.radians(a0)), cy + r_in  * math.sin(math.radians(a0)))
    return (f"M {fmt(po0)} "
            f"A {r_out} {r_out} 0 {large} 1 {fmt(po1)} "
            f"L {fmt(pi1)} "
            f"A {r_in} {r_in} 0 {large} 0 {fmt(pi0)} Z")

# ── 构图 ──────────────────────────────────────────────────────────
SEAM_ANGLES   = (30, 150, 270)            # 三条缝 → 三个区域
SECTOR_BISECT = (90, 210, 330)            # 三个区域的中线角（放核点）

parts = []
parts.append(f'<!-- CeruLean Cores · 图标（由 tools/icon/gen_icon.py 生成，勿手改） -->')
parts.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
# 背景
parts.append(f'  <rect x="0" y="0" width="{W}" height="{H}" fill="{BG}"/>')
# 徽记底盘
parts.append(f'  <circle cx="{CX:.0f}" cy="{CY:.0f}" r="{R:.0f}" fill="{MID}" stroke="{DEEP}" stroke-width="{RIM_W}"/>')
# 内圈（浅色区域，制造层次）
parts.append(f'  <circle cx="{CX:.0f}" cy="{CY:.0f}" r="{R-56:.0f}" fill="{LIGHT}" stroke="none"/>')
# 三条分区缝（用背景色切开，形成"区域"）
for a in SEAM_ANGLES:
    p = pol(R + RIM_W / 2 + 4, a)
    parts.append(f'  <line x1="{CX:.0f}" y1="{CY:.0f}" x2="{fmt(p).split()[0]}" y2="{fmt(p).split()[1]}" '
                 f'stroke="{LIGHT}" stroke-width="{SEAM_W}" stroke-linecap="butt"/>')
# 三个区域的核点
for a in SECTOR_BISECT:
    p = pol(196, a)
    parts.append(f'  <circle cx="{p[0]:.2f}" cy="{p[1]:.2f}" r="26" fill="{ACCENT}" stroke="{DEEP}" stroke-width="8"/>')
# 中心：深色圆 + 白色 C 字徽
parts.append(f'  <circle cx="{CX:.0f}" cy="{CY:.0f}" r="92" fill="{DEEP}" stroke="{LIGHT}" stroke-width="10"/>')
parts.append(f'  <path d="{arc_path(CX, CY, 66, 38, 46, 360)}" fill="#FFFFFF" stroke="none"/>')
# 字标
parts.append(f'  <text x="{W/2:.0f}" y="888" font-family="HarmonyOS Sans, Noto Sans, DejaVu Sans, sans-serif" '
             f'font-size="166" font-weight="700" text-anchor="middle" fill="{DEEP}">CeruLean</text>')
parts.append('</svg>')

out = os.path.join('assets', 'icon.svg')
open(out, 'w', encoding='utf-8').write('\n'.join(parts) + '\n')
print('已生成', out, f'({os.path.getsize(out)} 字节)')
