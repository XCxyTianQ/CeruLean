# tools/smoke/rigs · 机器矩阵装置定义（docs/23 P2）

本目录只放 **装置定义**（JSON）与说明。执行器是 `../rigs/run_rigs.py`（由 probe-rigs 提供），
本文件**不**描述实现细节，只描述**契约**、**装置来源**、**依赖的不变量**与**有效性判定**。

> 记录时间 2026-10-18。对应交接单：`docs/23-活塞更新语义探针-交接指南.md` §3 / §4 P2 / §5.4 / §6 / §7。

---

## 1. JSON 契约（与 run_rigs.py 严格对齐，不得扩展）

```json
{
  "name": "m6_control_push",
  "origin": [72, -50, 8],
  "blocks": [[0,0,0,"minecraft:stone"], [1,0,0,"minecraft:piston[facing=west]"]],
  "power":  [[1,-1,0,"minecraft:redstone_block"]],
  "assert": [{"type":"block_is","pos":[-1,0,0],"block":"minecraft:stone"}]
}
```

| 字段 | 语义 |
|---|---|
| `name` | 必须等于文件名（去掉 `.json`） |
| `origin` | 装置锚点，**世界绝对坐标**；`blocks` / `power` / `assert.box` 一律是相对 origin 的偏移 |
| `blocks` | `[dx,dy,dz,blockstate]`，按数组顺序逐条 `/setblock` |
| `power` | 同上；**最后放**，一次放完、同一 tick 触发（中间不要插查询） |
| `assert` | 只允许五种，见下表 |

断言（**只有这五种**）：

| type | 参数 | 语义 |
|---|---|---|
| `entity_min` | `entity`, `n` | 该实体数 ≥ n |
| `entity_max` | `entity`, `n` | 该实体数 ≤ n |
| `block_is` | `pos`, `block` | 该坐标方块匹配 `block`（**方块谓词语义**，允许带属性，如 `minecraft:piston[facing=west,extended=true]`） |
| `block_min_in_box` | `block`, `n`, `box` | 盒内该方块数 ≥ n |
| `block_max_in_box` | `block`, `n`, `box` | 盒内该方块数 ≤ n |

`box` = `[dx1,dy1,dz1,dx2,dy2,dz2]`，闭区间、相对 origin，与 `/fill` 的双角点含义一致。

执行器必须保证的三件事（否则本目录的预期全部失效）：

1. **每轮先清场再重建**（`/fill air` + `/kill @e[type=...]`，**方块和实体都要清**）：M2 的 TNT 会爆炸并炸掉装置，
   残留的 primed TNT 还会让下一轮 `entity_min` 假 PASS。清实体集合见 **附录 C.1**（默认瞬时实体 + 本装置 `assert` 点名的实体）；
2. **稳定窗口**：`power` 放完后等够再断言（活塞 2 tick 落地，S5）。本目录每台都显式写了 `wait_ms`：M6/M7/M3/M4 = **600 ms**（12 tick），M2/M5 = **400 ms**（必须 **< 1 s**：TNT 引信 80 tick ≈ 4 s，别等到爆炸）；
3. `gamerule tnt_explodes` 必须为 `true`（`TntBlock.prime` 第一件事就是判它；false 时 TNT **根本点不着**）。
   **26.3 的 gamerule 名是 snake_case**：`gamerule tntExplodes` 会报 `Incorrect argument`（已实测），正确写法是 `gamerule tnt_explodes`，默认即为 `true`。

### 1.1 执行器实现对齐（已读 `run_rigs.py` / `rig_common.py` 源码核对）

| 契约点 | 实现事实（行号指 `rig_common.py`） | 对本目录的影响 |
|---|---|---|
| `block_is` | `probe_block()` 发 `execute if block <x> <y> <z> <block>`（299-324） | **是方块谓词语义**，`piston[facing=west,extended=true]` 合法 |
| 盒子断言 | `count_block_until()` 逐格 `execute if block`（403-429） | `box` 要小（每格一条命令）；本目录最大盒子 24 格 |
| `entity_*` | `@e[type=<entity>]`（326-392）；`max=0` 与 `min<=1` 走 `execute if/unless entity`，不计数 | `minecraft:tnt` 命名空间写法可用 |
| `power` 同 tick | `apply_power()`（630-644）：**只有 1 块供电方块才保证同 tick**，多块逐条 setblock → 不同 tick | 本目录六台**都只有 1 块** power |
| 清场 | `clear` 未给时按 blocks/power/assert 派生，x/z 各外扩 `clear_pad`（默认 2），**y 不外扩**（577-591） | 不会把 y=-61 的平台清掉 |
| 清实体 | **已实现**：`run_rig` 清场阶段先 `/kill`、再 `/fill air`；集合 = 默认瞬时实体（tnt / item / falling_block / experience_orb）**+ 本装置 `assert` 里点名的实体**；`--kill-types` / `--no-kill` 可改（见附录 C.1） | M2/M5 不再有「上一轮 TNT 残留 → 假 PASS」；每台的 `killed_entities` 写进结果 JSON 可核对 |
| 稳定窗口 | `wait_ms`（可选键，574）默认 300；`--settle-ms` 可覆盖 | 本目录显式给 400 / 600 |
| 顺序 | 控制组（`m6`/`m7` 前缀）**先跑**（63、98-103） | M6/M7 失败时后面的结果没有解释价值 |
| 退出码 | 0 PASS / 1 FAIL（含 JSON 非法、断言不成立、命令失败）/ 2 SKIP（无装置目录、RCON 连不上） | 与交接单 §6.1 一致 |

> `wait_ms` 是 `load_rig_json` 已实现的**可选键**，不是契约扩展；没写它的装置按 300 ms 跑。

---

## 2. 坐标与环境约束（H2：边界污染）

| 约束 | 取值 | 理由 |
|---|---|---|
| `origin.x` / `origin.z` | **≡ 8 (mod 16)** | 区块中心。冒烟世界种子固定、区块边界固定，装置放边界上会被跨区块的加载/更新时机污染（H2） |
| `origin.y` | **-50** | 超平坦地面 y≈-61，装置离开地面避免与地形互相更新；单台竖直跨度 ≤ 8 |
| 包围盒 | **≤ 8×8×8** | 装置本体 + 断言盒都在盒内 |
| 装置间距 | 16 格 | 六台 x 依次 8/24/40/56/72/88，互不重叠、互不供电 |

六台 origin：`m2 [8,-50,8]`、`m3 [24,-50,8]`、`m4 [40,-50,8]`、`m5 [56,-50,8]`、`m6 [72,-50,8]`、`m7 [88,-50,8]`。
P3 跨区域时**故意**把 M6/M2 挪到边界两侧，那一步另建 origin，不改本目录的默认值。

---

## 3. 装置清单

| # | 文件 | 装置 | 依赖的不变量（§3） | 预期 vanilla 结果 | 验证状态 |
|---|---|---|---|---|---|
| # | 文件 | 装置 | 依赖的不变量（§3） | 预期 vanilla 现象 | 状态（Lead 决策已定） |
|---|---|---|---|---|---|
| M2 | `m2_tnt_duper.json` | TNT：被推入“已有点燃位”后点燃 | S1 + S3 + S5 | `entity_min tnt 1` ✅；`block_max_in_box tnt 0` ✅（点燃即消耗，不留块） | vanilla **PASS**（3/3）；机制前置，**不是复制判据** |
| M3 | `m3_carpet_duper.json` | 地毯：依附方块被活塞推动 | S3 + S5 | 地毯到目的地、全场景仅 1 块 | vanilla **PASS**（3/3）；机制前置，**不是复制判据** |
| M4 | `m4_rail_duper.json` | 铁轨：红石元件类依附方块被推动 | S1 + S3 | 铁轨到目的地、仅 1 条 | vanilla **PASS**（3/3）；机制前置，**不是复制判据** |
| M5 | `m5_suppression.json` | 更新抑制**代理**（移动 tick 不更新 / 落地 tick 才更新） | 原定 S1+S2+S6；本代理实测 S1 + S5 | `entity_min tnt 1` ✅、盒内 tnt 0 ✅ | vanilla **PASS**（3/3，S5 代理）；CCE 判据 **SKIP(2)**（§5.1） |
| M6 | `m6_control_push.json` | 对照 A：普通活塞推方块 | S1 + S3 + S4 + S5 | 方块到 (-1,0,0)、活塞 `extended=true` | vanilla **PASS**（3/3，自检闸门） |
| M7 | `m7_control_no_dupe.json` | 对照 B：普通方块不得复制 | S1 + S3 | 石方块 ≤1、无 TNT 实体 | vanilla **PASS**（3/3） |

### 3.1 预期现象 → 依赖不变量 → 可验证性

| # | 预期现象（= 断言） | 依赖的不变量 | vanilla 可验证性 | 是复制判据吗 |
|---|---|---|---|---|
| M2 | TNT 被活塞推进“已有点燃位”后，在**落地 tick** 被点燃（`entity_min tnt 1`），且**不留 TNT 方块** | S1 更新顺序 + S3 源清理**不带** `UPDATE_NEIGHBORS` + S5 落地再触发 `onPlace` | **可验证**：单 tick 事件，400 ms 窗口内 | ❌ 不是。复制判据＝实体与方块**同时**存在（附录 A） |
| M3 | 地毯被推 1 格后仍在目的地，且全场景只有 1 块 | S3 + S5（`updateFromNeighbourShapes` → `CarpetBlock.canSurvive`） | **可验证** | ❌ 不是。复制判据＝数量 ≥2 |
| M4 | 铁轨被推 1 格后仍在目的地，且只有 1 条 | S1（`BaseRailBlock.affectNeighborsAfterRemoval`）+ S3 | **可验证**；三台里最可能暴露顺序差异 | ❌ 不是 |
| M5 | 移动 tick **不**点燃、落地 tick 才点燃（`entity_min tnt 1`） | S1 + S5（原定 S1+S2+S6 **未覆盖**） | **可验证（代理）** | ❌ 不是。CCE 判据不可表达 |
| M6 | 方块被推到目标格、活塞 `extended=true` | S1 + S3 + S4 + S5 | **可验证（先跑，平台自检）** | ❌ 对照 |
| M7 | 同一套方块下石方块 ≤1、无 TNT 实体 | S1 + S3 | **可验证** | ✅ 反向判据（“不复制”） |

> 验收表口径（Lead 2026-10-18 决定）：**M5 记 SKIP(2)**「契约不可表达」；**M2–M4 的复制判据记 SKIP**「需 tick 时序或自激时钟」，
> 但它们测到的 S1/S3/S5 现象按 PASS / FAIL 记。

### 3.2 vanilla 实测记录（2026-10-18 · mojang_26.3.jar · 每台 3 轮）

| # | vanilla 观测 | 结论 |
|---|---|---|
| M6 | 石方块到 `(-1,0,0)`、活塞 `extended=true`（`piston_head` 在源位） | ✅ 与代码推导一致（S1/S3/S4/S5） |
| M7 | 石方块数 = 1、无 primed TNT | ✅ 不复制 |
| M2 | `execute if entity @e[type=minecraft:tnt]` = **True**（被推的 TNT 在**落地 tick** 点燃）；盒内 TNT 方块 = **0** | ✅ 点燃即消耗，**复制判据不成立** |
| M3 | 地毯在目的地、全场景 1 块 | ✅ 推动正确、无复制 |
| M4 | 铁轨在目的地、全场景 1 条 | ✅ 推动正确、无复制 |
| M5 | 移动 tick 不点燃、落地 tick 点燃（entity = True），TNT 方块 = 0 | ✅ S5 代理成立（CCE 判据仍 SKIP） |

结果文件：`$HOME/cerulean-scratch/rigs-vanilla.json`（第 2 轮）、`$HOME/cerulean-scratch/rigs-vanilla-run3.json`（第 3 轮）；第 1 轮是分两次跑的控制组 + 机制前置。

> **环境前提实测**：未关空服暂停（`pause-when-empty-seconds=60`）时，M6/M7 会整台 FAIL（活塞原地不动、`setblock` 全部返回成功）——那是环境前提缺失，**不是装置无效**。
> 改成 `0` 并重启后，空服 90 秒仍能正常推出，六台 3 轮全 PASS。
>
> **CeruLean 列（21:01 重建 jar，含 `0035`）：6/6 PASS × 2 轮，逐条断言细节与 vanilla 完全一致**（`rigs-cerulean.json`、`rigs-cerulean-run2.json`）。
> 修复前该列是 SKIP（RCON 方块读命令抛 NPE），问题记录与修复过程见 **§10**。
>
> ⚠️ **已知不一致（未定位，2026-10-18）**：`m2_tnt_duper` 在 **L3 联跑上下文**里曾 **2/2 失败**，而**单独跑与本目录全量跑都是 3/3 通过**（vanilla 3 轮 + CeruLean 2 轮）。
> 因此发布闸门 `tools/smoke/rigs/expected/gate_rigs.txt` 只收 **m6/m7**；m2–m5 不进闸门、仍可单独跑。
> 最可能的解释是 400 ms 断言窗口在联跑负载下不够（活塞落地 tick 被拖后），但**未证实**；在拿到 L3 失败现场的 `checks` 与日志前，不要改断言或 `wait_ms`。

---

## 4. 逐台说明

### M6 · 对照 A（自检：装置没搭错）
`stone (0,0,0)`、`piston[facing=west] (1,0,0)`、红石块 `(1,-1,0)` 放在活塞正下方，最后放。
代码依据：`PistonBaseBlock.getNeighborSignal` 对非推方向的邻居逐向取信号，红石块 `isSignalSource` 恒 15；
`isPushable` 对 `PUSH_PULL`（stone 默认）返回 `!hasBlockEntity()` = true；
`PistonMovingBlockEntity.tick` 在 progress 到 1 时 `setBlock(..., UPDATE_ALL|UPDATE_MOVE_BY_PISTON)` 落地（S5，2 tick）。
断言 `piston[facing=west,extended=true]` 走**方块谓词**语义（只匹配列出的属性），不是整串字符串相等。

### M7 · 对照 B（不得复制）
与 M6 **同一套方块**（最简、最确定），断言改为负向：盒内石方块 ≤ 1、`entity_max tnt 0`。
“不复制”的判据用 `block_max_in_box` 表达，不用 `block_min_in_box` 的反面描述。

### M2 · TNT
结构：`tnt (0,0,0)`、`piston[facing=west] (1,0,0)`、目的地旁常驻 `redstone_block (-2,0,0)`；触发用 `(1,-1,0)` 的红石块
（只给活塞供电，不与 TNT 相邻 → 放置那一刻不会点燃 TNT）。

时序（代码可推）：`moveBlocks` 把 TNT 移到 `(-1,0,0)`（目的地紧邻红石块）→ 下一 tick `PistonMovingBlockEntity.tick`
做 `setBlock(newState, UPDATE_ALL|UPDATE_MOVE_BY_PISTON)` → `TntBlock.onPlace` 里 `hasNeighborSignal(pos)` 为真
→ `prime()` 生成 `PrimedTnt` **并** `removeBlock(pos,false)`。
⇒ 实体 +1、**方块 -1**：这是“点燃即消耗”，**不是复制**。本 rig 断言的正是这个代码预测；
> ⚠️ 该判据依赖**落地 tick**：单独跑/全量跑稳定（vanilla 3/3、CeruLean 2/2），但在 **L3 联跑**上下文里 2/2 失败（原因未定位，见 §3.2 注）。所以本台**不进发布闸门**（`expected/gate_rigs.txt` 只收 m6/m7）。
> 重力注意（probe-rigs 提示）：装置在 y=-50 悬空，`PrimedTnt` 是受重力实体，点燃后会下落约 11 格到地面。
> `entity_min` 只看“存在”因此不受影响，`block_max_in_box` 数的是方块不是实体；但**不要**用实体坐标
> 或“弹坑/周边方块存活”这类判据去断言它。
要测“复制是否发生”用附录 A 的 `block_min_in_box tnt 1` 版（预期 FAIL，见 §5.2 / §7）。

### M3 · 地毯
`white_carpet (0,0,0)`、支撑 `stone (0,-1,0)`、目的地支撑 `stone (-1,-1,0)`、活塞 `(1,0,0)` 向西推。
地毯的 `pushReaction` 是默认 `PUSH_PULL`（`Blocks.java` 的 `WOOL_CARPETS` 没覆盖），`isPushable` 返回 true；
落地时 `Block.updateFromNeighbourShapes` 调 `CarpetBlock.canSurvive`（“下面不是空气”），目的地有支撑 → 存活。
**这验证的是 S3/S5 的“依附方块被推”分支，不是 1→2 的复制**（真复制结构见 §5.2）。

### M4 · 铁轨
同 M3，方块换成 `minecraft:rail`（`RAIL` 同样默认 `PUSH_PULL`）。
铁轨是“红石元件类”：`BaseRailBlock` 覆盖了 `affectNeighborsAfterRemoval`（被替换后会发更新，见 §7），
所以它比地毯更可能暴露 S1 的顺序差异——M2/M3/M4 里最可能出差异的一台。

### M5 · 更新抑制（**代理**，契约缺口见 §5.1）
结构：`tnt (0,0,0)`；活塞链 `piston[facing=north] (1,0,3)` ← `stone (1,0,2)` ← `redstone_block (1,0,1)`，触发用 `(1,-1,3)`。
活塞把红石块推进 `(1,0,0)`（TNT 的邻位）。**移动中的 `MOVING_PISTON` 不供电、也不发邻居更新**，
所以 TNT 在移动 tick 不被点燃；下一 tick 红石块落地（`UPDATE_ALL` + `neighborChanged`）才点燃。
断言：`entity_min tnt 1` + 盒内 TNT 方块 0（点燃即消耗）。**只用实体存在性 + 方块计数**：
同 M2，primed TNT 会从 y=-50 下落到地面（≈11 格），实体坐标类判据一律不用。
**这不是 CCE 更新抑制**（见 §5.1）——判据被替换过，不能当成 M5 的 PASS。
**Lead 决策（2026-10-18）：M5 验收表记 SKIP(2)，原因「契约不可表达」**；本装置只作为 S5 代理证据保留。

---

## 5. 契约缺口（重要，需 Lead 决策）

### 5.1 CCE 更新抑制在本契约下**不可表达**
真正的 CCE 更新抑制（命名潜影盒 → `ClassCastException` 打断邻居更新链）需要：

1. 带 NBT 的方块实体（`/setblock` 只吃 blockstate，**写不了 NBT**）→ 契约本身表达不了；
2. Paper/CeruLean 侧要有对应开关（Lophine 的 `OrgUpdateSuppression` 默认触发名是“更新抑制器 / updateSuppression”）；
3. 深度型抑制（`neighbourUpdateMax` = 512，S2）需要 >512 层更新链——8×8×8 装不下。

⇒ `m5_suppression.json` 只是“移动中不供电/不更新、落地才点燃”的 **S5 代理**。
**Lead 已定（2026-10-18）：M5 记 SKIP(2)，理由「契约不可表达」**；`m5_suppression.json` 仅作「移动 tick 不点燃 / 落地 tick 才点燃」的 S5 代理证据。将来若给契约加 `nbt` 字段，再按 CCE 重做。

### 5.2 复制机（M2–M4）的“真复制”也不在本契约内
公开设计（见 §7）里 TNT/地毯/铁轨复制都要求 **BUD 状态 + 同 tick 的移动与更新**，而：

- BUD 需要“已供电但没收到更新”，本契约只有 `power` **一次性脉冲**，没有 tick 级时序字段（没有延迟/时钟）；
- 一次 `/setblock` 放红石块会走 `SetBlockCommand` → `updateNeighboursOnBlockSet` → `updateNeighborsAt`，
  **立刻**把相邻 TNT 点燃，拿不到“已供电但未更新”的状态；
- 复制依赖“源位置先快照、后清空”的时序（`PistonBaseBlock.moveBlocks` 的 `toPushShapes` 快照），这在**同一次**
  `moveBlocks` 内完成；同 tick 双活塞的先后又会退化成 S1 的顺序问题。

⇒ M2–M4 目前是**机制前置观察装置**（各自钉住一条 §3 不变量），不是完整复制机。
附录 A 给出“复制判据版”的 assert 集合：真要做复制结论时**另建 rig**（建议加 `ticks`/`pulse` 字段，或直接用观察者/中继器自激时钟），
而**不是**把现在这三台改判成 FAIL。

**Lead 决策（2026-10-18）：M2–M4 定位为「机制前置观察装置」，不改成复制结论**；验收表里「复制判据」记 SKIP「需 tick 时序或自激时钟」，装置测到的 S1/S3/S5 现象按 PASS/FAIL 记。

### 5.3 真复制观测在三档之间必然不同

配置门控的实测证据与结论见 **§6「H3 配置门控（实测）」**。

---

## 6. H3 配置门控（实测）

**结论（直接写进验收表）**：`allow-piston-duplication` 在 **vanilla 默认允许**、**Paper/CeruLean 默认禁止**
⇒ 真复制的观测在三档之间**必然不同**，属**配置差异**，**不得记成语义 FAIL**。

按交接单 §0.2 / §2.2：探针**一律跑默认配置**，**不要**为了让三档“看起来一致”去改这个开关。

证据（本仓库源码，行号已逐个核对）：

| # | 位置 | 内容 |
|---|---|---|
| 1 | `paper-server/src/main/java/io/papermc/paper/configuration/GlobalConfiguration.java:178` | `public boolean allowPistonDuplication = false;`（旧名 `allow-tnt-duplication`；rename 见 `LegacyPaperConfig.java:80`） |
| 2 | `cerulean-server/src/minecraft/java/net/minecraft/world/level/block/piston/PistonBaseBlock.java:375-395` | `boolean allowDesync = ...allowPistonDuplication;` —— `allowDesync=false` 时在**建 `PistonMovingBlockEntity` 之前重读源位置实时状态**（386-388），并立刻把源位置置 air（392-394）；vanilla 路径用的是方法开头 `toPushShapes` 的**快照**（301-306）⇒「快照 vs 实时」不一致处就是复制发生点 |
| 3 | `cerulean-server/src/minecraft/java/net/minecraft/world/level/block/piston/PistonMovingBlockEntity.java:320` | 落地分支 `setBlock(..., UPDATE_NONE\|UPDATE_MOVE_BY_PISTON\|UPDATE_KNOWN_SHAPE) \| (allowPistonDuplication ? 0 : UPDATE_CLIENTS)` —— 禁止复制时额外带 `UPDATE_CLIENTS`，防 desync |

运行目录证据：现有各 `smoke-*/config/paper-global.yml:138-140` 均为 `allow-piston-duplication: false`（已实测多个目录一致）。

**对本矩阵的影响**：M2–M4 的“复制判据”在 Paper/CeruLean 上**预期本来就不可复制**。
所以本目录只钉 S1/S3/S5 现象（§3.1），复制判据单列 SKIP；附录 A 的写法只在“将来显式开闸门做对照实验”时使用。

---

## 7. 装置来源（公开设计 + 本仓库代码路径）

复制机**不是现场发明**的。采用的公开机制描述：

| 来源 | 关键原文 / 结论 |
|---|---|
| Minecraft Wiki · *Tutorial: Block and item duplication* | “TNT duplication works in a similar way as rail duplication. A TNT block is **BUD-powered**, such that when the contraption is **pushed by a piston, the TNT is updated and ignites** into a primed TNT entity.” |
| technical-minecraft wiki · *Tnt duping without entities* | “both the tnt and the rail (or other block) have to be **moved by the same piston while being BUD powered**.” |
| 中文教程《[Java版1.13.1-1.16] TNT/地毯/铁轨复制BUG》 | “当 TNT 处于激活位但未被激活（BUD），类似铁轨和羊毛毯，**只要在恰当时间给予更新**，就能复制出一个已激活的 TNT。” |
| TIS `tntDupingFix` 原文（Lophine `carpet/upstream/tis/en_us.yml:1523`） | “**Attachment block update based** dupers will do nothing and **redstone component update based** dupers can no longer keep their duped block.” |

本仓库里对应的代码路径（已逐行读过，用于设计断言与解释“为什么没复制”）：

| 文件 | 关键点 |
|---|---|
| `world/level/block/TntBlock.java` | `onPlace`/`neighborChanged`：`hasNeighborSignal` → `prime()`（先判 `TNT_EXPLODES`）→ `removeBlock(pos,false)`；`prime` 里 `addFreshEntity(PrimedTnt)` |
| `world/level/block/piston/PistonBaseBlock.java:287-448` | `moveBlocks`：`toPushShapes` 在方法开头**快照**（301-306）；目的地 `setBlock(MOVING_PISTON, UPDATE_NONE\|UPDATE_MOVE_BY_PISTON)`（383）；源位置清理**不带 `UPDATE_NEIGHBORS`**（393 / 413-415）；最后才统一 `updateNeighborsAt`（428-445） |
| `world/level/chunk/LevelChunk.java:425-437` | `movedByPiston` 时仍调 `oldState.affectNeighborsAfterRemoval`；`onPlace` 由 `UPDATE_SKIP_ON_PLACE` 门控 |
| `block/piston/PistonMovingBlockEntity.java:308-341` | 落地：`updateFromNeighbourShapes` + `setBlock(...UPDATE_ALL\|UPDATE_MOVE_BY_PISTON)` + `neighborChanged`（S5 的“再触发一次更新”） |
| `block/CarpetBlock.java` | `canSurvive` = 下面非空气；`updateShape` 不满足 → AIR；**不覆盖 `neighborChanged`**（只吃 shape 更新） |
| `block/BaseRailBlock.java` | 覆盖 `affectNeighborsAfterRemoval`（红石元件类：被替换后会发更新） |
| `world/level/material/PushReaction.java` | 枚举只有 `PUSH_PULL / PUSH / POPPED / IMMOVEABLE / IGNORE_ENTITY`；地毯、铁轨、TNT、石头都用默认 `PUSH_PULL` → 可推 |

---

## 8. GO 之后的验证流程（vanilla 基线）

**前提**：Lead 说 GO，且后台回归套件已结束。本会话的端口（**Lead 2026-10-18 已确认**，与 probe-rigs 的 25566/25576 · 25567/25577 互不冲突）：

| 基线 | MC 端口 | RCON 端口 | 何时跑 |
|---|---|---|---|
| vanilla | **25568** | **25578** | 本节，GO 后立刻 |
| CeruLean | **25569** | **25579** | vanilla 跑完、**原地等 Lead 指示**再跑 |

运行目录 `$HOME/cerulean-scratch/smoke-rigs-vanilla`；vanilla jar `/storage/Users/currentUser/cerulean-scratch/cache/mojang_26.3.jar`。

**必须先关掉空服暂停（实测坑）**：vanilla 26.3 的 `server.properties` 默认 `pause-when-empty-seconds=60`。纯 RCON 探针服没有玩家，
**启动 60 秒后整个世界停止 tick**——活塞不推、灯不亮、`setblock` 照样返回成功、`/tick query` 还显示 `running normally`（它不反映空服暂停）。
表现就是 M6/M7 直接 FAIL（活塞原地不动），极易误判成“装置无效”。跑装置前：把运行目录的 `server.properties` 设为 `pause-when-empty-seconds=0` 再起服
（`server-up.sh` 每次都会重写这个文件，所以要么改它的模板、要么手动起 java）。本机实测：改后空服 90 秒仍然正常推出。

```sh
cd /storage/Users/currentUser/Desktop/AzureCores/CeruLean
export CERULEAN_EULA=TRUE RCON_PASSWORD=test1234
MC_PORT=25568 RCON_PORT=25578 tools/smoke/server-up.sh \
    /storage/Users/currentUser/cerulean-scratch/cache/mojang_26.3.jar \
    "$HOME/cerulean-scratch/smoke-rigs-vanilla"

python3 tools/smoke/rcon.py --port 25578 --command "gamerule tnt_explodes true"   # 26.3 是 snake_case
python3 tools/smoke/rcon.py --port 25578 --command "kill @e[type=minecraft:tnt]"   # 开跑前先清残留实体

# ① 控制组：先确认平台 / 稳定窗口 / 清场可用
python3 tools/smoke/rigs/run_rigs.py --port 25578 --password test1234 \
    --only m6_control_push,m7_control_no_dupe --verbose

# ② 机制前置（M2–M5）
python3 tools/smoke/rigs/run_rigs.py --port 25578 --password test1234 \
    --only 'm2*,m3*,m4*,m5*' --verbose

# ③ 全量复核，结果落盘
python3 tools/smoke/rigs/run_rigs.py --port 25578 --password test1234 \
    --rigs-dir tools/smoke/rigs/rigs --out "$HOME/cerulean-scratch/rigs-vanilla.json"

# CeruLean 那轮（等 Lead 指示）：MC_PORT=25569 RCON_PORT=25579，--port 25579，结果落 rigs-cerulean.json
```

**连跑多轮的两个注意点**

1. 每台开跑前执行器会先 `/kill` 再 `/fill air`（附录 C.1），但**上一台残留的已供电活塞 / 排队中的 block event** 仍可能改写本台的 setblock（probe-rigs 在 M1→P0 上踩过）。本目录六台 origin 间距 16、互不重叠，实测 vanilla 3 轮 + CeruLean 2 轮无串扰；自己写脚本仍要**先清场再断言**。
2. M2/M5 点燃的 primed TNT 在断言之后约 4 s 才爆炸，落点在 y≈-61 地面，不触及 y=-50 的装置；它不影响下一轮，但**不要**把断言窗口拉到爆炸之后。

**判定顺序（先跑通再下结论）**

1. **先看 M6**：方块到 `(-1,0,0)` 且活塞 `extended=true`。M6 不过＝执行器 / 坐标 / 稳定窗口有问题，先修这个，**不要往后跑**。
2. **M7**：同一套方块下石方块 ≤1、无 TNT 实体。
3. **M2 / M5**：先看 `entity_min tnt 1`（“点燃”现象），再看 `block_max_in_box tnt 0`（“不留块”现象）。
4. **M3 / M4**：目的地方块是否到位、数量是否 ≤1。
5. 任一台在 vanilla 就不过 ⇒ **该装置判定“无效”**：写进验收表备注（原因 + 原始观测 + `server-console.log` 片段），
   **从矩阵排除**；**不要**改断言凑 PASS。
6. vanilla 跑完**原地等 Lead 指示**再跑 CeruLean（MC **25569** / RCON **25579**；运行目录另建，别复用 vanilla 的）。

---

## 9. 风险对照（交接单 §7）

| 编号 | 在本目录里的体现 | 处置 |
|---|---|---|
| H1 | 只观测到一条子路径（依附块类好、红石元件类坏） | M2/M3/M4 **成组**跑；M4（红石元件类）与 M3（依附块类）分开记，别互相替代 |
| H2 | 换坐标结果就变 | 全部 `origin` 取 `x,z ≡ 8 (mod 16)`；P3 故意跨边界时另建 origin。**实证**：probe-rigs 的 M1 胜者确实位置敏感（同装置平移一格就翻转、两档翻转点还不同，见附录 **C.8**）⇒ 单点 20 轮不能当 S1 等值闸门。本目录断言的是方块位置/数量，不依赖更新排空顺序，M6/M7 不受此影响；但**不要**把装置挪到区块角（x,z ≡ 0 mod 16）去“复现” |
| H3 | `allow-piston-duplication: false`（Paper/CeruLean 默认）让复制消失 | 见 §6（实测）：**不改配置、一律默认**；三档之间的复制差异记「配置差异」，不得记成语义 FAIL；另留意 `max-tnt-per-tick`、`allow-permanent-block-break-exploits` |
| H4 | 上游升级语义漂移 | M6/M7 是最便宜的两条闸门，P5 接进 `run_suite.py` 的 L3 时优先它们 |

---

## 10. CeruLean 档：命令层限制与修复（已解除，2026-10-18）

> **现状（21:01 重建）：CeruLean 列 6/6 PASS × 2 轮，与 vanilla 逐条断言细节一致。** 本节保留为问题记录与复跑前提。

**修复前的结论：六台全部 SKIP，原因不是装置语义，而是命令层。**
RCON/控制台命令跑在 **global region tick 线程**，`Level.getBlockState` 取 `getCurrentWorldData()` 得 `null` → 抛 NPE。
该限制在 `docs/14`（无区域 tick 上下文 → NPE）与 `docs/15` §3 的表格里已经记录过；本目录的三种 block 断言正好全都走这条路。

| 命令 | CeruLean 实测（`main@eb5aead`，`26.3-DEV-184fee1`） |
|---|---|
| `list` / `gamerule` / `cerulean regions` / `execute if entity`（含 Count） | ✅ 正常 |
| `execute if block`（＝ `block_is` / `block_min_in_box` / `block_max_in_box` 的实现） | ❌ `An unexpected error occurred`，控制台 NPE |
| `setblock` / `fill` / `forceload add` | ⚠️ 静默空响应：响应丢失，效果也无法用读命令复核 |
| `scoreboard`（objectives / players set/get） | ✅ 正常 |
| `data get block`（方块实体） | ✅ 本次实测可用（与 probe-rigs 的观察不一致，留档备查） |

栈（截断）：

```
java.lang.NullPointerException: Cannot read field "captureTreeGeneration"
  because the return value of "net.minecraft.world.level.Level.getCurrentWorldData()" is null
  at net.minecraft.world.level.Level.getBlockState(Level.java:1372)
  at net.minecraft.commands.arguments.blocks.BlockPredicateArgument$BlockPredicate.test(BlockPredicateArgument.java:82)
  at net.minecraft.server.commands.ExecuteCommand.lambda$addConditionals$0(ExecuteCommand.java:480)
  ... RegionizedServer$GlobalTickTickHandle.runOneTask(RegionizedServer.java:199)
```

环境记录：`MC_PORT=25569 / RCON_PORT=25579`，运行目录 `$HOME/cerulean-scratch/smoke-rigs-cerulean`；
`/cerulean regions` = **调度线程 2 / overworld 区域 0→1**（段 16 区块、合并半径 1、物化半径 1）。
证据文件：`$HOME/cerulean-scratch/cerulean-rig-blocker-20261018.txt`。

### 10.1 已排除的两个“假原因”

| 假原因 | 实测 | 结论 |
|---|---|---|
| 空服暂停（vanilla 的 `pause-when-empty-seconds`） | CeruLean 运行目录 `server.properties:50` = `pause-when-empty-seconds=0`；`server-console.log` 无 `pausing` / `Server empty` 行 | **排除**：这台没被暂停 |
| 世界不 tick / 写命令失败 | 全世界 `Command exception` 只有 **4 条**，全是 `execute if block`（`setblock`/`fill` 一条异常都没有）；`data get block 110 -50 20` 读到了我们刚 `setblock` 进去的命令方块 NBT；`summon item` 后 `data get entity … Age` 正常增长 | **排除**：世界在 tick、`setblock` 确实落地；静默只是 **Folia 对这些命令不回 RCON 反馈** |

> 由此，“命令方块不执行”更可能是**它所在区块没有 ticking 票据**（`forceload add` 静默无效）或命令块 NBT/供电写法问题，
> 而不是世界停了。复跑时不要再用“命令方块 + 记分板”当默认桥。

### 10.2 复跑前提（缺一不可，probe-rigs 复核过）

1. **`execute if block` 不再 NPE** —— 唯一硬前提。**修法已在树里**：`cerulean-server/minecraft-patches/features/0035-CeruLean-command-block-read-dispatch.patch`
   （untracked，mtime 20:57）把 `BlockInWorld.getState()` 的单格读取投递到拥有者区域线程，复用 `data get` 那套 `AwaitableDispatch.call`。
   物化源已应用，但**当前 jar（18:55）早于补丁** ⇒ 必须 `createPaperclipJar` 重建后 `execute if block` 才恢复。
   注意：`AwaitableDispatch.call` 从 tick 线程等 1500 ms 后快速失败，且要求 **≥2 调度线程**（本机实测 2 ✓）；若掉到 1 线程，读会快速失败而不是 NPE。
2. **起服时 `pause-when-empty-seconds=0`** —— CeruLean 这台无 pausing 行说明该值有效；vanilla 档则**必须**显式设（`server-up.sh` 模板已含）；
3. **先单独跑一台 M6**，用修好后的 `execute if block` 确认 `setblock` 真的落地 —— 在能读方块之前，任何“静默成功”都**不算证据**。

**不要**为了对齐而改断言或换坐标——vanilla 已经 3/3 PASS，改了就再也比不出差异。

### 10.3 0035 的版本控制状态（已入库）

`0035-CeruLean-command-block-read-dispatch.patch` 已随提交 **`9b0b5ea`**（“0035 命令方块读取派发 + 语义层探针线与 L3 闸门”）入库，`git ls-files` 可见；
本目录六台 JSON 与 `README.md` 也在同一批里被跟踪。**历史**：0035 曾是 untracked，重物化/清理前必须 `git add`，否则丢了 `execute if block` 会回到 NPE、本目录 CeruLean 列回到 SKIP。

---

### 10.4 修复后的实测（21:01 重建 jar，含 0035）

| 项 | 值 |
|---|---|
| `execute if block` | ✅ 恢复（`execute if block 8 -61 8 grass_block` → Test passed；无 NPE） |
| `Command exception` | **0 条**（修复前 4 条全是 `execute if block`） |
| `/cerulean regions` | **调度线程 2** / overworld 区域 1 |
| `AwaitableDispatch` | 1 条 WARN（首次 `call` 阻塞等待区域线程，非 fast-fail；1500 ms 上限未触发） |
| 矩阵 | **6/6 PASS × 2 轮**，逐条断言细节与 vanilla 一致 |
| `forceload add` | ⚠️ 仍无输出（执行器报 `forceload chunk x,z 失败`）；但读写已可用，不阻塞，属 Cosmetic |

结果文件：`$HOME/cerulean-scratch/rigs-cerulean.json`（第 1 轮）、`rigs-cerulean-run2.json`（第 2 轮）。

---

## 附录 A · “复制判据版”断言（**预期 FAIL**，只在明确要测复制时使用）

把对应 rig 的 `assert` 换成下面几组之一，其它字段不动。**默认不要用**（见 §5.2 / §6）。

| 机器 | 复制判据（交接单 §4 P2 原文） | assert |
|---|---|---|
| M2 TNT | 实体 ≥1 **且** 源/目的地仍是 TNT | `entity_min tnt 1` + `block_min_in_box tnt 1 box [-2,0,0,1,0,1]` |
| M3 地毯 | 地毯数量增加且原方块仍在 | `block_min_in_box white_carpet 2 box [-1,0,0,1,0,0]` |
| M4 铁轨 | 铁轨数量增加 | `block_min_in_box rail 2 box [-1,0,0,1,0,0]` |

要用这些断言，**必须**同时满足：探针服 `allow-piston-duplication: true`、装置换成“BUD + 同 tick 移动”的真复制结构
（本目录没有，需契约扩展或自激时钟），并在验收表里注明“断言为复制判据版”。
**注意**：Lead 已定不改 `allow-piston-duplication`，所以本节只是“将来显式开闸门做对照实验”时的用法，**不是**当前矩阵的一部分。

---

## 附录 B · 本目录待办

- [x] 六台 JSON 落盘，契约字段与执行器一致
- [x] `origin` 全部 `≡ 8 (mod 16)`、`y=-50`、包围盒 ≤ 8×8×8
- [x] **GO 后**：vanilla 复跑（端口 25568/25578）完成——每台 3 轮、**6/6 PASS**（见 §3.2）；M5 的 CCE 判据记 SKIP(2)、S5 代理 PASS
- [x] M2–M4 定位为「机制前置观察装置」（Lead 已定，不改成复制结论）
- [x] H3 配置门控实测成节（§6），结论：vanilla 允许 / Paper·CeruLean 禁止 ⇒ 配置差异
- [ ] 真复制结构：**不**在 P2 做；若将来需要，先扩展契约（`nbt` / `ticks` / `pulse`）
- [ ] 填 `docs/23` §6.2 验收表：**vanilla 列已完成**（见 §3.2），Paper / CeruLean 列待跑
- [x] 环境前提「空服暂停」已定位并写进 §8（`pause-when-empty-seconds=0`）

---

## 附录 C · 执行器手册（probe-rigs 维护；**契约不变**，只描述实现与用法）

### C.1 清场 = 清方块 + 清实体

每台装置开始前，执行器（`rig_common.run_rig`）按固定顺序做两件事：

1. **先 `/kill`，再 `/fill ... air`**：primed TNT 的引信是 80 tick，留着它去清方块等于拿装置赌博；
   实体是动的、方块是静的，所以先杀。
2. kill 集合 = 执行器默认的瞬时实体
   `minecraft:tnt, minecraft:item, minecraft:falling_block, minecraft:experience_orb`
   **加上本装置 `assert` 里点名的实体类型**（例如 `entity_min armor_stand` 会顺带清 armor_stand）。
   这样任何断言了新实体的装置都不会被上一轮残留骗成假 PASS。

**这不是 JSON 契约的扩展**（`assert`/`blocks`/`power` 字段一个都没变）：

| 需求 | 用法 |
|---|---|
| 只清某几类实体 | `--kill-types minecraft:tnt`（逗号分隔，覆盖默认集合，仍会并上 assert 实体） |
| 完全不清实体 | `--no-kill` |
| 连方块都不清（看现场） | `--no-clear`（同时跳过清实体；这是排查用，不是重跑） |

结果 JSON 每台都有 `killed_entities`，可核对实际杀了哪几类、顺序如何。

### C.2 CLI

```
# P0 最小检查：list / gamerule / setblock+execute if block / fill air / execute if entity / snapshot
python3 tools/smoke/rigs/rig_common.py --p0 --port 25576 --password test1234

# 跑单台装置
python3 tools/smoke/rigs/rig_common.py --rig tools/smoke/rigs/rigs/m6_control_push.json --json

# 只校验 JSON 契约（不连服）
python3 tools/smoke/rigs/rig_common.py --check-json tools/smoke/rigs/rigs/m2_tnt_duper.json

# 矩阵（本目录）：控制组先跑，一行一台
python3 tools/smoke/rigs/run_rigs.py --port 25576 --password test1234 \
    --rigs-dir tools/smoke/rigs/rigs --out "$HOME/cerulean-scratch/rigs-vanilla.json" [--verbose]

# 只跑两台 / 要求某台必须在场（缺席 -> 退出 2）
python3 tools/smoke/rigs/run_rigs.py --only m6_control_push,m7_control_no_dupe
python3 tools/smoke/rigs/run_rigs.py --require m6_control_push,m7_control_no_dupe

# M1 双活塞竞争（S1 的分布指纹）
python3 tools/smoke/rigs/m1_piston_race.py --port 25576 --password test1234 \
    --rounds 20 --baseline vanilla --out "$HOME/cerulean-scratch/m1-vanilla.json"
```

`run_rigs.py` 的其余开关：`--host/--port/--password/--timeout/--wait`、`--no-clear`、`--settle-ms N`
（覆盖装置 JSON 的 `wait_ms`，**M2/M5 不要用它**：两者必须 < 1 s）、`--kill-types`、`--no-kill`、`--json`。
每台装置**恰好一行** `[PASS|FAIL|SKIP] <rig_name> <细节>`，失败时前 3 条失败断言塞在同一行；
`--verbose` 才逐条展开。runner 串行执行（不并发），两台装置同时改同一片世界会互相污染。

### C.3 五种断言的实现（语义与代价）

| type | 发出的命令 | 备注 |
|---|---|---|
| `entity_min` | `execute if entity @e[type=X]`（n≤1）或记分板求和 | n≤1 只答「存在/不存在」，不受 Folia 跨区影响 |
| `entity_max` | `execute if entity @e[type=X]`（n=0）或记分板求和 | n=0 是最便宜的负向判据 |
| `block_is` | `execute if block X Y Z <block>` | **方块谓词语义**：只比较写出来的属性，未写属性不参与；绝不整串比较 |
| `block_min_in_box` | 逐格 `execute if block`，够 n 即停 | 每格一条命令，盒子要小 |
| `block_max_in_box` | 逐格 `execute if block`，超过 n 即停 | 同上；本目录最大盒子 24 格 |

`setblocks` 放的每一块也会用同一条 `execute if block` 复核（不信 fire-and-forget 响应）。

### C.4 退出码（与 tools/smoke/run_suite.py 一致）

| 入口 | 0 | 1 | 2 |
|---|---|---|---|
| `rig_common.py --p0` | 六项全过 | 有一项失败 | RCON 不可用 |
| `rig_common.py --rig` | 全部断言成立 | 有断言失败 / 命令失败 / 契约非法 | RCON 不可用 |
| `run_rigs.py` | 选中的装置全 PASS | 有 FAIL（含 JSON 契约非法） | 目录不存在、无匹配 JSON、`--require` 缺席、RCON 连不上或中途断开 |
| `m1_piston_race.py` | 有 `--expect` 时分布一致；无 `--expect` 时=观测自洽且无 unknown（**不构成跨基线判定**） | unknown / 命令失败 / 与期望不一致 | RCON 不可用或中途断开 |

**SKIP 不是通过**（交接单 §6.1）。

### C.5 机制限制（不是省事，是命令层没有更好的办法）

1. **一条命令一个连接**（交接单 §5.1）：命令数 = 连接数。盒子断言每格一条、`snapshot` 每格 × 候选数，
   别把盒子开大（默认上限：计数 4096 格 / 快照 1024 格）。
2. **`snapshot` 是候选匹配**：vanilla 没有「读某格是什么方块」的单条命令（`/data get block` 只对方块实体有效），
   只能对候选集合逐个试；候选外显示 `?`。
3. **`entity_count` 用记分板求和**（vanilla 没有返回实体数的命令）：`execute as @e[type=X] run scoreboard players add`。
   Folia 下能否看到跨区域实体**尚未实测**，P3 跨区装置不要直接信这个数。
4. **清场会连平台一起清**：`/fill air` 不管那里是什么。y 边界自己负责（本目录装置在 y=-50，平台 y=-51 不参与）。
5. **`wait_ms` 是硬等**，不是「等状态稳定」：M2/M5 的 400 ms 是引信约束下的上界，不要为了「更稳」调大。

### C.6 离线自检（不连服）

```
python3 $HOME/cerulean-scratch/rig_selftest.py        # 43 项：分类 / origin 平移 / 契约校验 / 全链路
python3 $HOME/cerulean-scratch/run_rigs_selftest.py   # 38 项：发现排序 / 一行一台 / 退出码 / 清实体
```

两者用 FakeRcon 模拟器，**只证明命令串与断言链路自洽，不证明真实服务器上的语义**——后者要等 GO 后的实测。


### C.7 26.3 环境适配（2026-10-18 实测，vanilla `mojang_26.3.jar`）

四条都是真实踩到的坑，**不处理会让探针整体假失败**：

| # | 现象 | 原因 / 处置 |
|---|---|---|
| 1 | 起服 >60 s 后活塞不推、沙子不落、中继器不亮；但 `setblock` 仍返回成功、`/tick query` 仍说 running normally | `server.properties` 默认 `pause-when-empty-seconds=60`：纯 RCON 探针服没有玩家 ⇒ 世界暂停（`server-console.log` 里有 `Server empty for 60 seconds, pausing`）。**改成 `0` 并用 java 直接起服**（`server-up.sh` 每次重写 properties 会把该行冲掉） |
| 2 | `gamerule tntExplodes` 报 `Incorrect argument` | 26.3 起规则名是 **snake_case**：`tnt_explodes` / `random_tick_speed` / `spawn_wardens` … P0 与 M1 已改成**两代命名都试**，只记录实际可用的那条 |
| 3 | `/setblock` 把方块设成「它已经是的样子」时返回 `Could not set the block` | no-op 被报成失败。执行器把它当 no-op，**一律再用 `execute if block` 复核**；`verify=False` 的供电块遇到 no-op 也复核一次，把「已是目标态」和「真没写进去」分开 |
| 4 | `execute if block 0 -50 0 ...` → `That position is not loaded` | 26.3 的世界出生点在启动时动态选（`Selecting global world spawn`），**(0,0) 不一定在常加载区**。执行器在每台装置 / 每次 P0 / M1 前自动 `/forceload add <chunk x*16+8> <chunk z*16+8>`（幂等，覆盖清场盒涉及的所有区块）；`--no-forceload` 可关 |

> 起服器：`$HOME/cerulean-scratch/probe_up.py`（阶段 2 用；模板与 `server-up.sh` 一致，
> 但强制 `pause-when-empty-seconds=0`）。等 Lead 把该行并进 `server-up.sh` 模板后，这个启动器就可以退休。


### C.8 M1 胜者的位置敏感性（2026-10-18 实测，两档同坐标对比）

M1 的胜者**不是与坐标无关的不变量**：同一装置平移到不同坐标，胜者会翻转，且两档的翻转点不同。
每一格都是 100/0 的确定结果（无 unknown、两次读数一致），所以**不是抖动**。

| 位置（origin） | vanilla | CeruLean |
|---|---|---|
| `(0,-50,0)` ← docs/23 §P1 字面坐标 | A（20/20，复跑 8/8 仍 A） | B（20/20） |
| `(2,-50,2)` | B（8/8） | B（8/8） |
| `(4,-50,4)` | B（10/10） | B（10/10） |
| `(8,-50,8)` ← 区块中心 x,z ≡ 8 (mod 16) | B（20/20） | A（20/20） |
| `(12,-50,12)` | B（8/8） | B（8/8） |

结论：

1. 「20 次分布」在单点上是 100/0，真正的自变量是**坐标**（活塞 block event / 调度 tick 的排空顺序与绝对坐标相关）。
2. docs/23 §P1 的字面坐标 `(0,-50,0)` 与 §2「装置固定在区块中心 x,z ≡ 8 mod 16」自相矛盾——(0,0) 是区块角。
3. 单点 20 轮的 M1 **不能当 S1 的等值闸门**：它只能记录「这个点上两档是否一致」。要当闸门，改为
   **多点签名**（固定一组坐标、逐点 N 轮、比较位图），或换成不依赖排空顺序的装置（需要 P4 的 `/tick step`）。
4. 矩阵装置（M2–M7）断言的是方块位置/数量，不是顺序，**不受此影响**。

复跑方式：`m1_piston_race.py --port P --offset-x dx --offset-z dz --rounds 20 --out ...`；
结果 JSON 里的 `geometry` 记录了该次运行的绝对坐标，便于逐点对照。
