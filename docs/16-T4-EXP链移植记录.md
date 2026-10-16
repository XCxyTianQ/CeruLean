# 16 · T4：EXP 可挂起命令方块链移植到 Folia 26.3（执行记录）

> 记录时间 2026-10-16，分支 `folia-base`。前置：`docs/13`（可行性）、`docs/14`（T1–T7 清单）、
> `docs/15`（T1–T3 验收与二级根因）。只读分析全文见 `../reports/T4-EXP链移植-只读分析报告.md`。

## 1. 结论

T4 **完成并实测通过**：补丁 `0018`（配置初始化）/`0019`（模式开关）/`0020`（链本体）/`0021`（命令侧接线），
验收驱动器 `tools/smoke/exp_chain_acceptance.py` + 探针 `tools/smoke/exp_chain_probe.py`：
**SAFE 11/0、EXP 20/0，驱动器 exit 0**。

## 2. 三条硬前置（缺一不可，只读分析定出、实测确认）

| # | 问题 | 证据 | 补丁 |
|---|---|---|---|
| 0 | `AzureBranchesConfig` **从未被初始化** | 全仓无 `init` 调用点；`CommandBlockSupport.mode()` 未初始化时捕获 `IllegalStateException` 返回 `SAFE` ⇒ `mode()==EXP` 永远为假，**静默降级不报错** | 0018（锚点同 upstream 0009：`MinecraftServer` 构造里 `paperConfigurations` 赋值之后） |
| 1 | 命令方块被 Folia **硬关** | `BaseCommandBlock.performCommand` 第一行 `if (true) return false;` ⇒ walker 第一次 `performCommand` 就 break | 0019（换 `CommandBlockSupport.shouldRun()` + `containFailure`） |
| 2 | 链本体不存在 | 26.3 的 `CommandBlock` 只有原版 `executeChain` | 0020 |

## 3. 补丁 0020 的移植方式（不是照抄 upstream 补丁）

upstream 的 `0012-AzureBranches-exp-chain.patch` 是**整文件替换的单 hunk**
（`@@ -1,228 +1,500 @@`），对 26.3 的 215 行文件没有任何可定位上下文 ⇒ `git apply` 必然全失败。

核对后确认 26.1.2 与 26.3 的 `CommandBlock` **只差两处、且都与 Folia 无关**：

1. 26.1.2 有 `CODEC`/`codec()`；26.3 的 `Block` 已废除 ⇒ 照抄会编译失败，**必须丢弃**；
2. 26.3 把 `useWithoutItem` / `getAnalogOutputSignal` 改成模式匹配。

因此移植 = 以 **26.3 的 215 行骨架**为基准，挂上 EXP 的「分流头 + 4 个静态方法」
（`executeChainExp` / `walkExpChain` / `dispatchAndSuspend` / `aggregateAndResume`）。
方法体由脚本从补丁 `+` 侧**机械提取后原样拼接**（未手工转写），并核对方法签名与 EXP 版逐一对应。
库侧零改动：`cerulean-regions` 的 10 个 `com/azurebranches` 类与 upstream `azurebranches-common`
逐字节相同，所调用的每个 API 都存在且签名一致。

## 4. 补丁 0021：命令侧接线（= T5 要补的差额）

不接它，`ExpChainSupport.isContextActive()` 在全仓**没有任何调用者** ⇒ walker 的
`ctx.deferredByRegion` 恒空、批边界永不触发、`dispatchAndSuspend` 永不执行 ——
"可挂起链"形同未接。

同时修掉 upstream `0013` 的一处判据隐患：它用「来源坐标所在区域 == 目标区域」算 `sameRegion`，
而 **RCON/控制台命令跑在 global region tick 线程上、来源坐标固定在出生点**（docs/15 §3）
⇒ 跨区域写会被误判成同区域，进而在非拥有线程上内联写世界。
本补丁改用 `TickThread.isTickThreadFor(level, cx, cz)`，语义就是"这次会不会内联执行"。

## 5. 验收判别器（踩过的坑，全是实测）

| 现象 | 真因 | 处理 |
|---|---|---|
| `/setblock` 放下的**循环**命令方块不执行 | 首次 tick 只来自 `setPlacedBy`（玩家放置）或红石边沿；AUTO 模式在 `neighborChanged` 里对自动方块**直接 return** | 用**冲激块 + 相邻红石块**触发 |
| 链块被 walker 跳过 | `CommandBlockEntity.isPowered()` 读的是**方块实体字段**（默认 false） | **先给链块通电，再触发头块** |
| 新放的方块完全不触发 | `setblock` 放下的方块**不会收到 `neighborChanged`**（只通知它的邻居）；残留的红石块制造假象 | 试验台必须**连红石块一起清** |
| 探针把"预期不执行"记成失败 | SAFE 分支里跑了 EXP 期望的轮询 | 分支内分别断言 |
| 命令解析失败却判成通过 | 只挡 `Unknown or incomplete command`，漏掉 Brigadier 的 `<--[HERE]` 指针 | 统一 `ok_parse` 判据 |

**EXP 的结构性指纹**：`walkExpChain` 里 `pos` 先 `move(direction)` 再判定
（`CommandBlock.java:261-267`）⇒ **walker 永不执行头块**，只沿链块走。

实测（`data get` 读链块写出的目标命令方块）：

```
SAFE：头块 SuccessCount=0、目标方块不出现、无 EXP 提示   → Folia 行为保持
EXP ：头块 SuccessCount=1、链块执行并写出 "chain-ran"、
      控制台出现 command_blocks.mode=EXP、本轮新增非预期 ERROR=0
```

## 6. 已知限制（诚实记录）

1. **只有 EXP v2 语义**。upstream 真实成品另有 `build.gradle.kts` **构建期注入的 554 行**
   （OCC 读集校验 / 回滚重试 / EXP6Plus 链 future 屏障），依赖 CeruLean 没有的
   `net.minecraft.server.commands.RegionCommandExecutor`；OCC 已按用户指示整块排除
   ⇒ **不追求** upstream `exp6/exp6plus` 全绿，验收按本文件的窄口径执行。
2. **链挂起/恢复无法在本环境构造**：force-load 的两个区块簇被并入**同一个区域**
   （实测 `/cerulean regions` 报 overworld 只有 1 个区域）⇒ `sameRegion` 恒 true、
   不产生跨区域延迟批次。探针改为**拓扑感知**：区域数 < 2 时显式打印 SKIP 与原因，
   **不计为通过**。可确定性验证的替代判据是 `registerDeferred` 的登记计数
   （`/cerulean exp`：内联 1 → 2 ⇒ 命令侧接线确实被走到）。
3. `dispatchAndSuspend` 的 `allOf(...).orTimeout(...)` 在**超时后不取消**已投递的远程任务
   （upstream 原样）——潜在重复执行风险，本会话未构造出该路径，留给后续。
4. 命令方块矿车（`MinecartCommandBlock`）随 0019 一并放开，**本轮未覆盖其回归**。
5. `Level.getGameTime()` 在区域线程上是**区域本地** tick，而 `BaseCommandBlock` 用它做同 tick 去重、
   `walkExpChain` 用它建 `PhaseSnapshot` ⇒ 跨区域挂起/恢复两侧可能不同值（未实测）。

## 7. T5 的结论（差额比对）

**T5 不需要改 `SetBlockCommand`**：上游 26.3 已自带投递（
`0001-Folia-Region-Threading-Base.patch` 里 `Spottedleaf` 写的 `queueOrExecuteTickTask`，同区域内联、
跨区域入队、恒定返回 1、异常经 `sendMessage` 不外传）。`0013` 相对 26.3 的真实增量只有一个 84 行块
（有效代码 61 行），且**全部挂在 T4 上**——已由本文件的 `0021` 落实。
唯一可独立交付的候选（同区域用内联返回值拿真实计数）**未做**：它偏离上游行为，
且现有 `SetBlockCommand` 的返回值已由 EXP 上下文承担，收益不清。
