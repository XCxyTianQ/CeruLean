# Folia 源码阅读区

> 目的：在动 Phase 2（区域化移植）之前，**把 Folia 的区域化实现读懂、读透**，一步一个脚印。
> 这里保存从 Folia 补丁中还原出来的真实源码，以及逐课的阅读笔记。

## 来源与许可

| 项 | 值 |
|---|---|
| 上游 | `PaperMC/Folia`，分支 **`ver/26.2.x`**（HEAD `acf6733ddebcb4b1cd07360d11370c530b8125a2`，2026-08-25） |
| 补丁 | `folia-server/minecraft-patches/features/0001-Region-Threading-Base.patch`（20,221 行 / 1.09 MB） |
| 为什么用 26.2 而不是 26.1.x | 26.2 是最新分支；实测两版的**新增文件完全相同**（13 个），26.2 只多了少量细节 |
| 许可 | GPLv3（与 CeruLean 一致）。`src/` 是从上游补丁还原的**原始代码**，仅用于阅读与对照，**不作为我们的实现**；将来若直接复用必须保留署名与派生声明 |
| 还原方式 | 解析补丁中 `new file mode` 段落，剥离 `+` 前缀后按原路径落盘（脚本逻辑见 `patch-index.json`）；`TickRegions.java` 是从占位被改写的那份，单独按 hunk 还原 |

## 真实规模（纠正一个此前的不准确说法）

- 区域化**新增代码** = **13 个文件 / 5,644 行**（之前报告里说的"约 1 万行"是把在 vanilla 文件里的插入行也算进去了）；
- 此外还**修改 187 个既有文件**，其中插入/删除行合计约 4,400 行 —— 这部分才是"把区域化接进 Minecraft"的挂钩成本。

| 新增文件 | 行数 |
|---|---|
| `ThreadedRegionizer.java` | 1405 |
| `RegionizedTaskQueue.java` | 863 |
| `RegionizedWorldData.java` | 787 |
| `TickRegionScheduler.java` | 562 |
| `RegionizedServer.java` | 427 |
| `scheduler/FoliaRegionScheduler.java` | 427 |
| `commands/CommandServerHealth.java` | 355 |
| `RegionizedData.java` | 235 |
| `RegionShutdownThread.java` | 229 |
| `commands/CommandUtil.java` | 120 |
| `TeleportUtils.java` | 82 |
| `util/SimpleThreadLocalRandomSource.java` | 79 |
| `util/ThreadLocalRandomSource.java` | 73 |

## 结构发现（会影响 Phase 2 怎么放文件）

Paper 主线**已经为 Folia 预留了占位**。实测我们本地 materialize 出的主线源码里有：

```java
// cerulean-server/src/minecraft/java/io/papermc/paper/threadedregions/TickRegions.java
// placeholder class for Folia
public class TickRegions {
    public static int getRegionChunkShift() { ... }
}
```

而 `io/papermc/paper/threadedregions` 这个包**被拆在两个源根上**：

| 源根 | 内容 | 谁在改 |
|---|---|---|
| `paper-server/src/minecraft/java/`（Minecraft 树） | `TickRegions`（占位）+ Folia 的全部 13 个新增文件 | Folia 的 **minecraft-patches** |
| `paper-server/src/main/java/`（Paper 自有树） | `EntityScheduler`、`scheduler/{FallbackRegionScheduler,FoliaAsyncScheduler,FoliaEntityScheduler,FoliaGlobalRegionScheduler}` | Paper 主线自己 |

**同包不同源根**，编译期合并进同一个 jar —— 这意味着 CeruLean 移植时必须逐个类确认"该放哪棵树"，放错了会与主线同名类冲突或漏编译。

## 阅读顺序（按依赖，自底向上）

| 步骤 | 对象 | 行数 | 状态 |
|---|---|---|---|
| **00** | 接入机制总览：两份补丁的分工、`TickRegions` 枢纽、启动链、类归属 | — | ✅ 见 [notes/00](notes/00-接入机制总览.md) |
| **01** | `ThreadedRegionizer`：类型模型、设计参数、锁纪律、回调契约 | 1405（本轮读 1–275 + 1248–1405） | ✅ 见 [notes/01](notes/01-ThreadedRegionizer-类型与契约.md) |
| **02** | `addChunk/removeChunk/onRegionRelease`：分裂与合并算法 + 区域边界何时变 | 276–673、805–994 | ✅ 见 [notes/02](notes/02-分裂与合并算法.md) |
| **03** | `ThreadedRegion`：所有权查询、中心点启发式（**含一处验证过的 bug**） | 674–805 | ✅ 见 [notes/03](notes/03-ThreadedRegion-所有权与中心点.md) |
| **04** | `ThreadedRegionSection`：位图、生/死判定、空段为何必须存在 | 1023–1246 | ✅ 见 [notes/04](notes/04-ThreadedRegionSection-段的生命周期.md) |
| **05** | `RegionizedData` + `RegioniserCallback`：挂载机制、tick 偏移数学、失败语义 | 235 | ✅ 见 [notes/05](notes/05-RegionizedData-挂载与迁移契约.md) |
| **06** | `RegionizedWorldData`：世界状态如何跨区域迁移（含分发链全图） | 787 | ✅ 见 [notes/06](notes/06-RegionizedWorldData-世界状态如何迁移.md) |
| **07** | `TickRegionScheduler`：EDF/工作窃取、隐式上下文、fail-stop 失败模型 | 562 | ✅ 见 [notes/07](notes/07-TickRegionScheduler-区域如何被调度.md) |
| **08** | `RegionizedServer`：全局 tick 与职责边界（全局 vs 区域） | 427 | ✅ 见 [notes/08](notes/08-RegionizedServer-全局tick与职责边界.md) |
| **09** | `RegionizedTaskQueue`：**跨区域影响的原语**（段级票据 + 队列路由 + 有界排空） | 863 | ✅ 见 [notes/09](notes/09-RegionizedTaskQueue-跨区域影响的原语.md) |
| 10 | `scheduler/FoliaRegionScheduler` + `TeleportUtils` + `RegionShutdownThread` + util/commands | 931 | ⏳ |
| 11 | 187 个 hook 点（按 hunk 排序，先 `ServerLevel`(62)/`MinecraftServer`(40)/`Entity`(27)/`Level`(25)） | — | ⏳ |
