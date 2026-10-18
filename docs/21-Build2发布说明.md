# 21 · Build 2 发布说明（26.3-build.2-SNAPSHOT）

> 发布基线：`v26.3-build.1`（`29d68d6`）→ 本次 `v26.3-build.2`。
> 区间：30 个文件、+3354 / −118 行；20 个提交。

## 1. 一句话

**EXP 链从"只能跑通 v2"补齐到对齐 AzureBranches 的 EXP6Plus（OCC 读集校验 + 回滚重试 + 三个数据域）**，
同时修掉一个"玩家加入即崩"的回归，并把命令覆盖层按实测结果补到可用、纳入压力测试。

## 2. 本版内容

### 2.1 OCC / EXP6Plus 移植（主体）

| 补丁 | 内容 |
|---|---|
| `0024` | CommandBlock 的 OCC/EXP6Plus 全量逻辑（**612 行**）+ 新增 `RegionCommandExecutor`（240 行） |
| `0025` | 数据面三钩子：`Level.getBlockState` 读集、`Scoreboard` 读/写/删、`ScoreHolderArgument` 实体读集 |
| `0026` | `onBlockReadRecorded()`：EXP3 是否真接线的直接判据 |
| `0027` | `/data` 实体 NBT 的 NbtPath 级读/写集（第四个数据域） |
| `0028` | `Level.setBlock` 写钩子（回滚基线 + 本 Phase 写集）+ 校验排除自写项 |
| `0029` | 计分回滚补偿改**锁内 CAS**，收窄"没有全局 tick 独占"的并发窗口 |
| `0030` | NBT 域的自写自读排除（与方块/计分对称） |

移植方式：**重放** upstream `build.gradle.kts` 的 `transformSource` 替换对（15 对 / 612 行，
逐锚点断言"恰好命中一次"），不是手抄。26.1.2→26.3 的漂移实测修正：
`NbtPredicate` 包名迁移、`Level.getBlockState` 结构变化、`Scoreboard` 写钩子被本项目 R1 重写掉。

**四个数据域**：方块（读+写）/ 计分（读+写+删）/ 实体 NBT（读+写）/ 实体存在性（读）。
三者对"自写自读"的处理一致（`0028` + `0030`）。

### 2.2 修掉一个回归：玩家加入即崩（issue #1）

`R1`（`0022`）把 `Scoreboard` 的容器换成 `ConcurrentHashMap` 后，`PlayerList.placeNewPlayer`
传入的 `null` holder 从"返回 null"变成"抛 NPE" ⇒ 任何玩家加入都会打死区域 tick。
`0023` 恢复 null 容忍（保留并发加固）。详见 issue #1 与 `docs/17 §2.2`。

### 2.3 区域化命令覆盖层（`0031`）

**实测驱动**：先只恢复注册，再用矩阵探针在 RCON（全局区域线程）与链内（区域线程）两处逐条验证。

- 恢复且实测正常：`/function` `/tag` `/team` `/teammsg` `/trigger`
- 修复：`/loot spawn`（全局线程 `addFreshEntity` → `getCurrentWorldData()` NPE）
- 修复：`MinecraftServer.reloadResources` 的 `managedBlock` 在 tick 线程上会**打死全局区域并关服**
- 暂缓（宁可不注册）：`/reload`、`/datapack`、`/spreadplayers` —— 原因见 `docs/20 §3`

### 2.4 可观测性与验收

- `/cerulean exp` 新增：OCC 校验（通过/重试/耗尽/冲突率）、数据面（方块读/计分读/计分写/缓存命中/读集记录/NBT写拦截）、
  补偿（计分/失败/NBT/**竞争**）、`PhaseValidator.describe()`、**最近一次校验**的规模摘要。
- `command_blocks.exp.validation.*` 开关：运行时两次起服验证（`maxReadSet=128, maxRetries=5` 是测试里特意写的非默认值）。
- 新增探针：`exp6plus_occ_probe.py`（C1/C2/C3/C4）、`region_commands_probe.py`、`command_stress_probe.py`、
  驱动器 `command_acceptance.py`。

## 3. 验收证据（本版实测）

回归闸门 `tools/smoke/run_suite.py` —— **7 PASS / 0 FAIL / 0 SKIP**：

```
PASS  L0 基座活性 (10s)
PASS  L1 可观测面 (19s)
PASS  L2 T1/T2/T3 scoreboard+clone (132s)       43/0
PASS  L2 R1 scoreboard 压力 (421s)              7/0
PASS  L2 T4 EXP 链（SAFE+EXP 两轮起服）(842s)   SAFE 11/0、EXP 28/0、EXP6Plus OCC 13/0
PASS  L2 R1 存档往返 (57s)
PASS  L2 区域化命令（矩阵 + 混合压力）(558s)    命令 12/0、混合压力 4/0
```

EXP6Plus OCC 用例：C1 存活→COMMIT（`retry Δ0`）、C2 挂起期间死亡→RETRY（`retry Δ1`）、
C3 无幽灵分数、C4 NBT 自写自读（`retry Δ0`）。

## 4. 已知限制（如实）

1. **没有全局 tick 独占**：`/scoreboard` 走区域线程 + `stateLock`（不是 upstream 的独占）。
   `0029` 用锁内 CAS 精确还原、必要时退回增量减法并计数"竞争"，但窗口未彻底消除。
2. **多区域真并发未验证**：单区域冒烟构造不出跨区域冲突；C2 用"相位内打死实体"触发 RETRY，
   属同类冲突但不是跨区域冲突（`docs/17 §4`）。
3. **`/reload` `/datapack` `/spreadplayers` 未提供**：分别需要改 `SimpleReloadInstance` 的等待方式、
   以及 async 传送重写。
4. **`/loot` 从全局线程走阻塞桥**（`RegionCommandExecutor` 的 2s 阻塞 API），会打印性能告警；
   upstream 同样如此。

## 5. 升级提示

- 产物：`cerulean-paperclip-26.3-build.2-SNAPSHOT.jar`
- 若你之前在 `config/paper-global.yml` 里为了绕过 issue #1 把 `collisions.enable-player-collisions`
  改成了 `false`，**可以改回 `true`** —— `0023` 已修掉那个 NPE。
- 回归闸门总时长约 34 分钟（本地），CI 只做构建、不跑闸门（见 `docs/18 §3.2`）。
