# 01 · ThreadedRegionizer：类型模型、设计参数、锁纪律、回调契约

> 源码：`study/folia/src/io/papermc/paper/threadedregions/ThreadedRegionizer.java`（1405 行，Folia `ver/26.2.x`）
> 本轮精读范围：`1–275`（类型/参数/锁/查找）与 `1248–1405`（回调契约）；算法部分留到第 02 课

## 1. 三个核心概念

| 概念 | 定义 | 代码位置 |
|---|---|---|
| **Section（区域段）** | 区域的最小归属单位：段坐标 = 区块坐标 `>> sectionChunkShift`，用 `CoordinateUtils.getChunkKey(x,z)` 打包成 `long` 作为键。**一个段整体且只属于一个区域** | `getSectionCoordinate`/`getSectionKey` L172–187 |
| **Region（区域）** | 一组相邻段的集合，是**调度单位**（tick 在区域上并行执行）。有 id、状态、区域数据 `R`、每段数据 `S` | `ThreadedRegion` 内部类 L674 |
| **Regioniser** | 管理 `section → region` 映射与区域生命周期（创建/分裂/合并/销毁），**不持有任何世界状态** | 本类 |

泛型参数设计值得一提：`ThreadedRegionizer<R extends ThreadedRegionData<R,S>, S extends ThreadedRegionSectionData>` —— 用 **F-bounded 泛型**把"区域数据/段数据"的具体类型交给接入方（Folia 里是 `TickRegions.TickRegionData` / `TickRegionSectionData`），regioniser 本身对 Minecraft 一无所知（只 import 了 `BlockPos`/`ChunkPos`/`Entity`/`ServerLevel` 用于坐标与构造）。

## 2. 设计参数（构造函数 L56–75，全部是行为旋钮）

```java
public ThreadedRegionizer(int minSectionRecalcCount, double maxDeadRegionPercent,
                          int emptySectionCreateRadius, int regionSectionMergeRadius,
                          int regionSectionChunkShift, ServerLevel world, RegionCallbacks<R,S> callbacks)
```

| 参数 | 作用（从用法推断） | 约束/默认 |
|---|---|---|
| `regionSectionChunkShift` | 段 = `1 << shift` 个区块。主线 `TickRegions.getRegionChunkShift()` 直接取 **Moonrise 的 `SECTION_SHIFT`** → 段与 Moonrise 的调度单元对齐 | — |
| `minSectionRecalcCount` | 触发"重新计算区域归属"的最小段数阈值（合并/分裂的迟钝度） | `max(2, x)` |
| `maxDeadRegionPercent` | 允许的"死区域"比例（合并遗留的空壳区域占比上限） | — |
| `emptySectionCreateRadius` | 空段创建半径：**相邻但空的段之间不会立即合并**，用来避免区域抖动 | 必须 > 0，否则抛异常 |
| `regionSectionMergeRadius` | 合并半径：距离小于该半径的区域会被合并 | 必须 > 0，否则抛异常 |

> **给移植的启示**：这些值决定"多大的 world 会切成几个 region、什么时候合并"。任何区域化语义差异（尤其是"两个相邻区域何时合并成一个大区域"）**很可能就藏在这几个值的来源上**——第 10 课读 hook 点时要确认 `ServerLevel` 里传进来的默认值。

## 3. 锁纪律（这一节是全类最值得学的部分）

```
regionLock      : StampedLock          （支持读锁、写锁、乐观读）
writeLockOwner  : Thread               （写锁持有者标记）
```

| 方法 | 纪律 |
|---|---|
| `acquireReadLock` / `releaseReadLock` | 读锁；释放用 `tryUnlockRead()`（**不是** `unlockRead()`） |
| `acquireWriteLock` | **禁止递归**：若 `writeLockOwner == 当前线程` 直接 `IllegalStateException("Cannot recursively operate in the regioniser")`（L144–151）。这是防"回调里又调回 regioniser"造成死锁的硬保险 |
| `getRegionAtSynchronised` | **StampedLock 乐观读**：先 `tryOptimisticRead()` 取快照 → `validate()` 成功就直接返回；失败才退回读锁（L240–265）。热点路径零锁开销 |
| `getRegionAtUnsynchronised` | 命名即契约：**调用者自己保证同步**（L230） |
| `computeForAllRegions` | 读锁内遍历 |
| `computeForAllRegionsUnsynchronised` | 不加锁遍历，同样靠命名提示危险 |
| `computeForRegions(from..to, consumer)` | **写锁**内遍历目标范围内的段，把遇到的区域集合交给回调，返回区域数（L202–228） |

自检式断言贯穿全类：`onRegionCreate` 里 `putIfAbsent` 冲突即抛；`onRegionDestroy` 删除的对象不匹配即抛（L158–170）——**宁可炸掉也不静默错**。

## 4. 回调契约（RegionCallbacks，L1283–1404）

| 回调 | 时机 |
|---|---|
| `createNewSectionData(sectionX, sectionZ, sectionShift)` | 新建段数据 |
| `createNewData(forRegion)` | 新建区域数据 |
| `onRegionCreate(region)` | 区域**创建完成后**（此时数据与所属段已可安全读取） |
| `onRegionDestroy(region)` | 区域**销毁前**（此时仍可安全读取） |
| `onRegionActive(region)` | 区域变为"活跃"：**不与任何其它区域相邻**（按 empty section radius 判定）→ **可以 tick** |
| `onRegionInactive(region)` | 区域变为"不活跃"：与邻居相邻 → **不能 tick，必须合并** |
| `preMerge(from, into)` | 合并**之前**（`from` 仍存活且是独立区域） |
| `preSplit(from, into[])` | 分裂**之前**（`from` 仍存活，目标区域尚未初始化） |

**八个回调的 Javadoc 都在重复同一句话**（这不是啰嗦，是整个设计的合同）：

> "This function is always called while holding critical locks and as such should not attempt to block on anything, and should NOT retrieve or modify ANY world state."

即：**锁内只做拓扑与生命周期通知，绝不碰世界状态、绝不阻塞。** `ThreadedRegionData.split/mergeInto`（L1248–1279）同样受此约束。

这套设计的精髓：**把"区域拓扑"与"世界内容"彻底解耦**。regioniser 只回答"哪些区块属于哪个区域、区域何时生死"，而"区域里的实体/方块实体/连接如何迁移"由接入方在**锁外**通过 `RegionizedData`/`RegionizedWorldData` 完成。
→ 这也解释了第 05/06 课那两个类为什么存在，以及**为什么移植时最容易出事的地方就是有人忍不住在回调里访问世界**。

## 5. 状态机常量（本课只记名字，细节留第 03 课）

`ThreadedRegion.STATE_READY` / `STATE_TRANSIENT` / `STATE_TICKING` / `STATE_DEAD`
（出现位置示例：L377、L420、L522、L560、L572、L639、L665；`STATE_TICKING` 出现在被注释的测试代码里 L122）

从用法可以看出：区域在"合并/分裂进行中"必须处于 `TRANSIENT`（不可 tick），完成后回 `READY`，销毁后 `DEAD`。`STATE_TICKING` 由 `tryMarkTicking()` / `markNotTicking()` 驱动，与
"active → 可 tick"的判定相互配合。

## 6. 工程风格观察（值得借鉴 / 值得警惕）

**值得借鉴**
- 名字即契约：`...Unsynchronised`、`...Synchronised` 后缀把锁责任写在方法名上；
- 乐观读 + 失败退锁的模式用在最高频查询上；
- 所有"不该发生"的情况都抛异常而不是修复；
- 锁内回调的约束在每个方法上重复声明（而不是只在类注释里写一次）。

**值得警惕（读的时候要带着怀疑）**
- 文件里保留了**大段被注释掉的调试代码**（`loadTestData()`、`Operation`/`MultiThreadedQueue`、L47–50、L77–134）——说明这是长期迭代的生产代码，不是"设计好再写"的样本；
- 类注释把"完整细节"指向 `REGION_LOGIC.md`，而该文件在我们拿到的仓库里**只是一个指向 docs.papermc.io 的跳转占位**（我们此前在 AzureBranches 里也见过同样的三行占位）——**意味着关键设计意图没有随代码一起发布**，只能靠读代码推断。这正是"必须自己读源码"的原因。
- `ServerLevel` 与 regioniser 双向引用（`world.regioniser` ↔ 构造参数 `world`）：耦合方向值得在第 10 课确认是否可能做成单向。

## 7. 下一步（第 02 课预告）

读 `addChunk`（L276–450）与 `removeChunk`（L451–673）：这两段是**区域分裂与合并的全部算法**，共约 400 行，包含：
- 何时触发"重新计算"（`minSectionRecalcCount`、`maxDeadRegionPercent` 的实际用法）；
- 合并的顺序与"先声明后执行"（`preMerge` → 迁移数据 → `onRegionDestroy`）；
- 分裂时如何决定新边界（`preSplit` 传出的 `into` 列表从哪来）。

读完后应当能回答一个关键问题：**一条命令方块链跨区域时，它跨越的边界是由谁、在什么时刻决定的** —— 这正是 AzureBranches 那 9 个类要处理的地方。
