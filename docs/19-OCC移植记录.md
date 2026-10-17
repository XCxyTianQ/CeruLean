# 19 · OCC 移植记录（对齐 AzureBranches EXP6Plus）

> 记录时间 2026-10-17。分支 `main`。目标：把此前**明确排除**的 OCC 接回来，向 EXP6Plus 对齐。
> 前置：`docs/16`（T4 EXP v2 移植）、`docs/17`（并发/跨区域）、`docs/18`（版本与发布）。

## 1. 结论摘要

| 项 | 状态 |
|---|---|
| CommandBlock 的 OCC / EXP6Plus **逻辑**（读集校验、回滚重试、补偿、chain-future 屏障） | ✅ 已落（`0024`，583 行注入 + `RegionCommandExecutor`） |
| **数据面**（让读集真的有内容）：方块读集 / 计分读写 / 实体读集 | ✅ 已落（`0025`/`0026`） |
| OCC **真的在跑**的可观测证据 | ✅ `读集记录 = 64`、`validationPassed ≥ 1`、`挂起/恢复 = 2/2` |
| `/data` 的 NBT 数据域（EXP6 的 NbtPath 级捕获） | ✅ 已落（`0027`），**NBT写拦截=1** 实测 |
| `/data` `/tag` `/trigger` 等命令的覆盖层 | ⬜ 未做（与 OCC 闭环无因果关系，见 §6） |
| 以 `exp6/exp6plus-smoke.py` 为参照的完整用例 | ⬜ 未做（§6） |

**一句话**：OCC 的"判定 + 数据面 + 可观测"闭环已通；EXP6Plus 还差 `/data` 的 NBT 域与验收用例。

## 2. 移植方式：重放 upstream 的注入，而不是手抄

AzureBranches 的 EXP 实现**不在补丁里**，而在 `folia-server/build.gradle.kts` 的
`transformSource(...)` 构建期注入中（`CommandBlock` 两个块共 15 个替换对 / 583 行，
其中 554 行是一个 Kotlin 三引号原始字符串）。

做法：写解析器把这些 `(anchor, replacement)` 对求值出来，在 CeruLean 已物化的
**26.3 `CommandBlock.java`** 上**重放**同一组替换，与 upstream 一样逐锚点断言"必须恰好命中一次"。
**15/15 全部命中** —— 因为 `0020` 当初就是把同一份 EXP v2 代码逐字拼进来的。

解析器的两个坑（都踩过，已修）：

1. **三引号原始字符串**：注入体在一个 `"""..."""` 里，内部 `"` 不转义。按普通字符串切边界会把
   注入体切碎（症状：`System.err.println( + t.getMessage())` 里字符串字面量消失）。
2. **不能用 `re.split(" to ")` 切 anchor/replacement**：**锚点自身可能含 " to "**
   （`Level` 的锚点里有 `manually inline to reduce hops`），会把替换体截断。

## 3. 26.1.2 → 26.3 的漂移（实测修正）

| 处 | 26.1.2 | 26.3 | 处理 |
|---|---|---|---|
| `NbtPredicate` 包名 | `net.minecraft.advancements.criterion` | `net.minecraft.advancements.predicates` | 替换（2 处） |
| `Level.getBlockState` 结构 | 有 `} else {` 分支 | 无（bounds 检查 + 直接取 chunk） | 钩子改挂 bounds 之后、取 chunk 之前 |
| `Scoreboard` 写/删锚点 | `ScoreboardAccess.set` 内 `} else { boolean hasChanged…` | 被本项目 R1（`0022`）重写成 `mutate()` + `stateLock` | 钩子改落 `mutate()` 锁内、写之前；reset 两处锁内移除之前 |
| `Scoreboard` 队伍容器 + null 守卫 | upstream 同一批里也做了 | 本项目 `0022`/`0023` 已做 | 跳过，不重复 |

其余（`ProblemReporter.ScopedCollector(2参)`、`TagValueInput.create`、`Entity.load/kill/teleportTo`、
`RegionizedServer`/`RegionizedTaskQueue`/`EntityScheduler`）实测 26.3 均存在，无需改。

## 4. 数据面：三个钩子（本次的关键）

只落 `0024` 的话，`PhaseValidator` 只在**空读集**上通过 —— 等于 OCC 没生效。必须接上数据面：

| 域 | 钩子位置 | 作用 |
|---|---|---|
| 方块（EXP4） | `Level.getBlockState` | 缓存命中即返回（同 Phase"读己写"）；否则 `recordRead(pos, state, gameTime)` |
| 计分读（EXP5Plus） | `Scoreboard.getPlayerScoreInfo` | 缓存命中返回匿名 `ReadOnlyScoreInfo`；否则 `recordScoreRead` |
| 计分写 | `Scoreboard.mutate()` 锁内 | 写前 `putScore(key, newValue, 旧值)` + `markPendingScore`（供回滚） |
| 计分删 | `resetAllPlayerScores` / `resetSinglePlayerScore` 锁内 | 移除前捕获旧值 |
| 实体（EXP6Plus） | `ScoreHolderArgument.getNames` | 实体型 holder 逐个 `recordEntityRead`（防幽灵读、跳过已死亡 holder） |

## 5. 验收：怎么证明 OCC 真的在跑

`tools/smoke/exp_chain_acceptance.py`（SAFE + EXP 两轮起服）→ **SAFE 11/0、EXP 22/0，exit 0**。

新增两条硬断言，都不看"代码是否存在"，只看"运行时是否发生"：

```
OCC 数据面 · Level.getBlockState 钩子被走到（读集记录非空）  → 读集记录=64   ✅
OCC 校验 · 链尾读集校验真的跑过（validationPassed ≥ 1）      → 通过=2        ✅
```

**一处曾经的误判（记下来）**：最初用 `/cerulean exp` 的 `方块读` 作判据，得到 0 就判失败 ——
但那个计数只在**缓存命中**时 +1（未命中才是常态）。为此新增 `onBlockReadRecorded()`
（每次读数真的记进读集时 +1）作为 EXP3 是否接线的唯一直接判据（`0026`）。

**副作用（正向）**：`挂起/恢复` 从 `0/0` 变成 **`2/2`**。EXP6Plus 的 chain-future 屏障让链
在批边界真的挂起并恢复；此前单区域环境下 `sameRegion` 恒 true、不产生延迟批次，是死路径。

## 5.1 `command_blocks.exp.validation.*` 开关的运行时验证

目标要求"接好 `command_blocks.exp.validation.*` 配置开关"。代码链路是
`PhaseValidator.isEnabled()/maxRetries()/maxReadSetSize()` → `AzureBranchesConfig` →
键 `command_blocks.exp.validation.enabled / max_retries / max_read_set`。
运行时用两次起服对比确认（`/cerulean exp` 现在会输出 `PhaseValidator.describe()`）：

```
enabled = true  → EXP3 OCC validation: maxReadSet=128, maxRetries=5 (+ EXP4 score read-set, EXP6 NBT read-set)
enabled = false → EXP3 validation disabled (config: validation.enabled=false)
```

注意 `maxReadSet=128 / maxRetries=5` 是**测试配置里特意写的非默认值**（默认 256/3）——
这证明配置文件真的被读取，而不只是"默认值恰好一致"。

## 5.2 EXP6Plus 的 OCC 冲突用例（C1/C2/C3）

以 `AzureBranches/exp6plus-smoke.py` 为参照改写为 `tools/smoke/exp6plus_occ_probe.py`，
并从 `exp_chain_acceptance.py` 的 EXP 阶段触发。**断言比上游更硬**：不只数日志标记，
还直接读 `/cerulean exp` 的 OCC 计数器。

| 用例 | 结果 | 证据 |
|---|---|---|
| **C2** 实体在挂起期间死亡 → RETRY | ✅ | `retry 3→4 (Δ1)`，`PHASE-START=1`（重放的是 Phase 不是头块） |
| **C3** 幽灵分数防护 | ✅ | `NO-ENTRY=1`、`GHOST-5=0`、`GHOST-7=0` |
| **C1** 实体存活 → COMMIT | ⚠️ **已知缺口** | 出现 `retry Δ3`（耗尽）⇒ `P1-SET-OK`/`P1-COMMIT-KEEP-5` 缺失 |

**C1 的根因（已定位，未修）**：链里 `setblock 10 64 10` 会先 `getBlockState` 把
`(10,64,10)=air` 记进读集，然后自己把它写成 stone；链尾校验时
`verifyReadSetAndResume` 把这处"自己写的"当成**外部修改** ⇒ 误判冲突 ⇒ 重试 3 次耗尽。
修法：外部检查阶段必须把本 Phase 自己写过的位置 / 分数键（`pendingWritePositions` /
`pendingScoreKeys`）从"外部修改"里**排除**。计分域同理（本 Phase 写过的键不应再比旧值）。

### 调试过程中的两个"假线索"（记下来，避免重复排查）

1. **链内 `@e` 选择器其实可用**。一次 A/B 里 `scoreboard players set @e[...]` 失败、而
   `set @<UUID>` 成功，看起来像"选择器在链里解析不到实体"（差点去补已排除的
   `EntitySelector` 补丁）。真相是**僵尸在白天烧死了**。改成
   `Invulnerable:1b` + `time set midnight` 后，链内选择器写分数**完全正常**
   （实测值 33、`计分写 1`）。所以 `EntitySelector` 跨区域补丁与本主题无关。
2. **`execute if entity @e[...]` 未触发 ≠ 选择器坏**：同上，实体已死。

## 5.3 C1 的排查进展（未结案）

C1（实体存活 → COMMIT）仍然失败：`retry 0→3`、`SET-OK=0`。本轮排掉了两个环境问题、补了一处真实缺失，
但**根因尚未定位**，如实记录到这里。

### 已排除

1. **方块"自写自读"误判**：确实是缺失 —— `PhaseSnapshot.putBlock/markPendingWrite/captureOldState`
   全仓库**无调用方**（方块写的数据面从未接线，连 EXP4 回滚基线也是空的）。已补（`0028`）：
   `Level.setBlock` 记旧值 + 登记 pendingWrite + captureOldState，并在
   `verifyReadSetAndResume` 里排除 `hasPendingWrite/hasPendingScore` 的位置与分数键。
   **但 C1 结果不变** ⇒ 这不是 C1 的主因（不过这处修复本身是必需的，保留）。
2. **实体被环境杀死**：冒烟服是**超平坦**世界，地面在 y≈-61，在 y=64 直接 `summon` 的僵尸会**摔死**，
   于是链里 `@e[type=zombie,limit=1]` 解析不到实体，表现为"分数写不进去"。已补落点支撑
   （`setblock 4 63 4 stone`）。**但 C1 仍失败** ⇒ 也不是主因。

### 已定位到的确切事实

- `重试` 计数只由 `CommandBlock.java:1054` 的 `case RETRY ->` 递增（全仓仅此一处调用
  `onValidationRetry`）⇒ `PhaseValidator.validate(...)` **确实返回了 RETRY**；
- 但给 `PhaseValidator` 的 4 个"外部检查"分支都加了 `System.err` 诊断后，**一条都没打印**；
- `PhaseValidator` 里有 **4 个 `validate` 重载**（不委托关系待确认），返回 RETRY 的语句共 7 处，
  我只插桩了 4 处 ⇒ **RETRY 出自未插桩的那几处**（很可能在 6 参重载 `validate(...)` 自己的分支里）。

### 下一步（下一轮第一件事）

把 `PhaseValidator` 的**全部** `return ValidationResult.RETRY;` 站点插桩（或在 6 参重载入口
打印 `retryCount/readSet.size()/各 modified map 是否含 TRUE`），复跑
`tools/smoke/exp6plus_occ_probe.py` 单独用例，直接读出是哪个分支判定冲突。
拿到分支名后再决定是"排除条件不够"还是"该检查本身有 bug"。

## 6. 还没做（如实留档）

1. **`/data` 的 NBT 数据域（EXP6 的核心之一）**：需要在 `/data` 的六条流（get/getNumeric/
   resolveSourcePath/modify/merge/remove）里按 **NbtPath 粒度**做 `recordReadValue`/`interceptWrite`，
   并给 `EntityDataAccessor` 暴露实体 id。upstream 是 801 行的整文件覆盖（26.1.2 快照），
   直接覆盖到 26.3 的漂移面很大 ⇒ 应改为在 CeruLean 现有 `DataCommands` 上做**小步注入**。
2. **`/data` `/tag` `/trigger` `/loot` 等覆盖层**：它们让这些命令在区域线程上安全执行，
   是 `RegionCommandExecutor` 的另一批使用者。与 OCC 闭环**无因果关系**，属另一条工作线。
3. **EXP6Plus 完整验收用例**：`exp6-smoke.py` / `exp6plus-smoke.py` 依赖"多区域 + 外部并发写"
   才能触发 RETRY；单区域冒烟环境构造不出来（同 `docs/17` §4 的限制）。目前能证明的是
   "读集非空 + 校验跑过 + 链挂起/恢复"，**不能证明冲突重试与补偿的真实效果**。
4. **路线 B 的设计债**：`/scoreboard` 仍走 CeruLean 的 `stateLock` 直读（不是 upstream 的
   "全局 tick 独占"），因此回滚补偿是多条 `putScore` 的批操作，若有玩家区域线程并发 `add`，
   `netDelta` 语义仍会漂。upstream 用独占规避，本项目没有等价保证。

## 7. 本轮改动清单

| 文件 | 说明 |
|---|---|
| `…/features/0024-CeruLean-exp-occ.patch` | `RegionCommandExecutor`（新文件 240 行）+ `CommandBlock` 583 行注入 |
| `…/features/0025-CeruLean-exp-occ-data-plane.patch` | `Level` / `Scoreboard` / `ScoreHolderArgument` 三个数据面钩子 |
| `…/features/0026-CeruLean-exp-occ-read-count.patch` | `onBlockReadRecorded()` 读数计数（EXP3 接线的直接判据） |
| `cerulean-regions/…/ExpChainSupport.java` | 新增 `onBlockReadRecorded` / `blockReadRecordedCount` |
| `cerulean-regions/…/CeruLeanCommand.java` | `/cerulean exp` 增输出：OCC 校验（通过/重试/耗尽/冲突率）、数据面（方块读/计分读/计分写/缓存命中/读集记录）、补偿（计分/NBT） |
| `…/features/0027-CeruLean-exp-occ-nbt-domain.patch` | EXP6：`/data` 实体 NBT 读/写集的 NbtPath 级捕获 |
| `tools/smoke/exp_chain_probe.py` | 新增 OCC 运行时断言 + EXP6 NBT 域用例（`/data merge entity`） |
