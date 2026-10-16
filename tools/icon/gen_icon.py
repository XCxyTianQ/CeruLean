#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 CeruLean 的图标 SVG。

设计（2026-10-16 改版：用户定的构图）
------------------------------------
**两个直角三角形，直角顶点相对，分居左上与右下，右下略大。**

借的具体事物 = 一次**交接/投递**：两块楔形沿对角线互相指向对方，直角顶点几乎相接。
这正是本项目最核心的那件事在图形上的抽象——跨区域的工作交接
（`AwaitableDispatch` 投递、clone 两跳、EXP 链的挂起/恢复都在把"一块工作"从一个区域
交给另一个区域）。右下那块**略大**，让构图有方向与轻重，而不是中心对称的死板图形。

几何（1024 画布，全部参数可调）
-----------------------------
* 令 a = 左上腿长，b = 右下腿长（b = a × RATIO > a），g = 顶点间沿单轴的半间隙；
* 两块三角形的**直角边都与坐标轴平行**（于是外缘稳定、斜边自然形成主对角线）；
* 直角顶点 V1 = C − (g,g) + off，V2 = C + (g,g) + off，
  其中 C 是徽记中心，off = (−(b−a)/2, −(b−a)/2)；
* off 的作用：让**整体外接矩形仍以 C 为中心**（右下更大但不让画面偏向一边）。可自行验算：
      xmin = V1.x − a, xmax = V2.x + b  ⇒  (xmin+xmax)/2 = C.x
* 左上三角形 = (V1.x−a, V1.y) → (V1.x, V1.y−a) → V1  （水平腿、竖直腿、斜边朝左上）
  右下三角形 = V2 → (V2.x+b, V2.y) → (V2.x, V2.y+b)

配色沿用原图标家族（青蓝 + 深藏青描边），去掉原来的圆形底盘与分区缝 —— 上版被判"太随意"，
这版刻意只留两个形状 + 字标。

渲染：`tools/icon/rasterize.sh`（cairo，见 svg2png.c）。
注意 svg2png.c **只支持 rect/circle/line/path/text**：不能用 <g>/<polygon>/渐变，
所以三角形一律用 <path> 的 M/L/Z，颜色走扁平填充。
"""
import os

W = H = 1024

# ── 徽记中心与几何 ────────────────────────────────────────────────
CX, CY = 512.0, 400.0     # 徽记中心（下方 y=888 留给字标）
A = 200.0                 # 左上三角形腿长
RATIO = 1.18              # 右下比左上大的比例（"略大"）
B = A * RATIO
G = 18.0                  # 两直角顶点间沿单轴的半间隙（留白，让"相对"看得清）
OFF = -(B - A) / 2.0      # 让整体外接矩形仍居中

V1 = (CX - G + OFF, CY - G + OFF)   # 左上：直角顶点（朝右下）
V2 = (CX + G + OFF, CY + G + OFF)   # 右下：直角顶点（朝左上）

# ── 配色（与原图标同族）──────────────────────────────────────────
BG     = "#F8FBFE"
MID    = "#7FB6E0"        # 左上填充（次青蓝）
ACCENT = "#1E6FB8"        # 右下填充（强调青蓝；更大更深，压住视觉重心）
DEEP   = "#0E2A47"        # 深藏青：描边与字标
STROKE = 16               # 描边宽度（1024 画布；小尺寸下仍能分辨）

TL = [(V1[0] - A, V1[1]), (V1[0], V1[1] - A), V1]
BR = [V2, (V2[0] + B, V2[1]), (V2[0], V2[1] + B)]


def tri_path(pts):
    (x0, y0), (x1, y1), (x2, y2) = pts
    return f"M {x0:.2f} {y0:.2f} L {x1:.2f} {y1:.2f} L {x2:.2f} {y2:.2f} Z"


parts = [
    '<!-- CeruLean Cores · 图标（由 tools/icon/gen_icon.py 生成，勿手改）',
    '     构图：两个直角顶点相对的直角三角形，左上小 / 右下略大；直角边与坐标轴平行。 -->',
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
    f'  <rect x="0" y="0" width="{W}" height="{H}" fill="{BG}"/>',
    f'  <path d="{tri_path(TL)}" fill="{MID}" stroke="{DEEP}" stroke-width="{STROKE}"/>',
    f'  <path d="{tri_path(BR)}" fill="{ACCENT}" stroke="{DEEP}" stroke-width="{STROKE}"/>',
    f'  <text x="{W / 2:.0f}" y="888" font-family="HarmonyOS Sans, Noto Sans, DejaVu Sans, sans-serif" '
    f'font-size="166" font-weight="700" text-anchor="middle" fill="{DEEP}">CeruLean</text>',
    '</svg>',
]

out = os.path.join('assets', 'icon.svg')
open(out, 'w', encoding='utf-8').write('\n'.join(parts) + '\n')
print('已生成', out, f'({os.path.getsize(out)} 字节)')
print(f'  左上腿长 {A:.0f} / 右下腿长 {B:.0f}（×{RATIO}）/ 顶点间隙 {2 * G:.0f}')
print(f'  外接矩形 x {V1[0] - A:.0f}..{V2[0] + B:.0f}  y {V1[1] - A:.0f}..{V2[1] + B:.0f}'
      f'  （中心 {(V1[0] - A + V2[0] + B) / 2:.0f},{(V1[1] - A + V2[1] + B) / 2:.0f}）')
