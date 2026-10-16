# 11 · 把 Folia 26.3 作为上游：事实、冲突分析、切换方案与取舍

> 触发：上游 Folia 的 26.3 分支已出。用户指示："立刻拉取源码并作为上游"。
> 本文件记录**已核实的事实**、**切换方案**、以及一个必须由项目所有者拍板的**身份取舍**。

## 1. 已核实的事实（2026-10-16）

| 项 | 值 |
|---|---|
| 仓库/分支 | `https://github.com/PaperMC/Folia.git`，`refs/heads/ver/26.3.x` |
| 提交 | `597e41371802648e0455fc1571192ee1c83fca2b` |
| MC 版本 | `mcVersion=26.3`（与我们一致 ✓） |
| 它自身依赖的 Paper 基座 | **`paperRef=c927bd34ab4b544cc125367ea62c7ea2f1a2196d`**（≠ 我们现在的 `bd9daa8f…`） |
| 克隆体积 | 浅克隆仅 **3.5 MB**（`--depth 1 --single-branch`） |
| 补丁规模 | `minecraft-patches` **11 个** + `paper-patches` **6 个** |
| 目录结构 | `folia-api` / `folia-server` / `folia-checkstyle`（与我们 `cerulean-*` 镜像） |

### 1.1 ★ 区域线程核心与我们的 26.2 还原版**几乎逐字节相同**

| 文件 | 26.2（我们还原） | 26.3（官方） | 差异行 |
|---|---|---|---|
| `ThreadedRegionizer.java` | 1405 | 1405 | **0** |
| `TickRegionScheduler.java` | 562 | 562 | **0** |
| `TickRegions.java` | 479 | 479 | **0** |
| `RegionizedTaskQueue.java` | 863 | 863 | **0** |
| `RegionizedServer.java` | 427 | 428 | 1 |
| `RegionizedWorldData.java` | 787 | 787 | 4 |

⇒ **结论一**：我们 13 份研究笔记（notes 00–12）对 26.3 **依然成立**，无需重做；
官方 26.3 源码已解出到 `study/folia-26.3/src/`（区域线程核心 6124 行），此后以它为准。

## 2. 冲突分析（更正版）

**我们一共只碰了 6 个文件**，而 Folia 碰了 **427 个**。关键在于：

| 我们改的文件 | Folia 是否也改 | 结论 |
|---|---|---|
| `net/minecraft/server/level/ServerLevel.java` | ✅ | 我们的接线被官方版本取代 |
| `ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java` | ✅ | 同上 |
| `paper-server/build.gradle.kts` | ✅（另一层） | 需重整 |
| `ca/spottedleaf/moonrise/common/util/TickThread.java` | ✅（Paper 侧补丁） | **我们的 B2 归属闸门被官方 region-aware 实现取代** |
| `io/papermc/paper/command/PaperCommands.java` | ✅ | 命令注册需在我们层重做 |
| `io/papermc/paper/configuration/GlobalConfiguration.java` | ✅ | 我们的 `cerulean-regions` 配置节被 `threaded-regions` 取代 |

> 更正：我第一版分析说"只有 3 个重叠"，是因为 Folia 的 Paper 侧补丁用 `src/main/java/...` 前缀，
> 而我们用 `paper-server/src/main/java/...`，路径字符串不同导致漏判。**真实重叠是 6/6。**

⇒ **结论二**：这不是"把我们的东西叠在 Folia 上"，而是**用上游实现替换我们的基座**。
我们为 B1/B2/B5 写的那一层（调度骨架、归属闸门、区域锁尝试、MC 接线）在采纳后**基本都不需要了**。

## 3. 切换方案（已在独立 worktree 中搭起实验）

`CeruLean-folia-base`（分支 `folia-base`，工作树在 `../CeruLean-folia-base`，**不动 main**）：

1. `gradle.properties`：`paperRef` → `c927bd34ab4b544cc125367ea62c7ea2f1a2196d`（Folia 的基座）；
2. 把 Folia 的 11 + 6 个补丁**按序置于我们之前**（重编号为 `000N-Folia-*` / `000N-CeruLean-*`）；
3. 把我们被取代的 4 个补丁挪到 `cerulean-server/_ours-pending/`（可回退）；
4. 后台跑 `applyAllPatches`（要拉新的 Paper 提交 + 重新反编译，预计 30–60 分钟；日志
   `~/.cerulean-cache/folia-base-apply.log`）。

**成功判据**：`applyAllPatches` 退出码 0 ⇒ 证明"Folia 补丁栈 + 我们的工具链"可行，
再往下才是逐件处理我们的模块（`cerulean-regions` 的去留、我们的配置节、命令、诊断工具）。

## 4. 取舍（需要拍板）

### 采纳 Folia 基座能拿到什么
- **B5 直接解决**：`scheduleChunkTaskEventually`（区块任务投递给拥有者）+ 区域 tick 驱动
  `processTicketUpdates` + `mainThreadExecutor` 消亡——也就是我们上一轮用实测证明"必须整体对齐"的那一层；
- **B2 归属闸门由官方取代**：Folia 的 region-aware `TickThread` 是"身份的天然保证"，
  比我们的 11 处委托 + 软/硬模式更根本；
- 研究结论：B1 调度骨架、B4 投递原语也都不必自己写。

### 代价与变化（必须说实话）
1. **我们的引擎从"运行时"变成"参考实现"**：`regionizer-core`（C1 的精确语义 + 192 项断言）
   与 `cerulean-regions`（调度/归属/命令）在采纳后不再是运行路径。
   可保留用途：**自洽差分参照物**（docs/06 §2 早已规划）、A/B 对照、教学与回归基线。
2. **Folia 会删掉一部分上游测试**（`paper-patches/0003-Fix-tests-by-removing-them.patch`），
   而我们这段时间一直在**依赖 Paper 的 1 万条测试**当回归闸门 ⇒ 采纳后回归面会变窄，
   需要把我们自己的验收（C2/config/B2/B1/fail-stop 那套脚本）补成主要闸门。
3. **验证基线要重建**：`paperRef` 变了 ⇒ 之前所有"退出码 0"的记录都属于旧基座，
   必须在 `folia-base` 上重跑一遍才能再次声称"已验证"。
4. **CeruLean 的差异化位置改变**：基座交给上游，我们的价值集中在
   **数据/状态/持久化层**（AzLinear、区域状态迁移、OCC 等）与我们刻意保留的分歧点。

### 三条可选路线
| 路线 | 做法 | 代价 |
|---|---|---|
| **A（推荐）** | 采纳 Folia 基座；我们的 4 个被取代补丁删除；`cerulean-regions` 收缩为"我们的配置/命令/诊断 + A/B 引擎开关" | 上面 4 条代价；但 B5 立刻可跑 |
| B | 维持 Paper 基座，继续自研 B5 | 实测已证"半途不可行"；B5 = 52 文件量级的手工移植 |
| C | 双轨：A 为主线，另开分支继续把自研引擎做成可切换的 A/B | 双份维护成本 |

## 5. 下一步（等实验回来）

1. 看 `applyAllPatches` 结果：成功 ⇒ 在 `folia-base` 上跑我们的 C2/config/冒烟验收，确认基座可用；
   失败 ⇒ 按报错逐个 rebase（预期冲突集中在 `build.gradle.kts.patch` 与我们的模块接线）。
2. 决定路线 A/B/C 之后，再动 `main`（当前 main 保持"Paper 基座 + 自研引擎"的已验证状态不动）。
