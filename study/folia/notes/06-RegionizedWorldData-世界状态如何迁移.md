# 06 · RegionizedWorldData：世界状态到底怎么跨区域搬

> 源码：`RegionizedWorldData.java` 精读范围 `71–456`（`REGION_CALLBACK` 全文 + 字段清单 + 构造与 `checkWorld`/`updateTickData`）+ `TickRegions.java` `281–378`（区域数据层的分发）。
> **本课回答了那个最实际的问题**：分裂/合并时，实体、连接、区块、tick 列表……这些世界状态究竟怎么跟着走。

## 1. 先把整条分发链画出来（这是本课最值钱的一张图）

```
ThreadedRegionizer（拓扑与生命周期，全程持写锁）          ← 01–04 课
  └─ ThreadedRegionData.split / mergeInto                 ← TickRegions.TickRegionData（区域数据层）
       ├─ tickHandle.copyDeadlineAndTickCount / updateSchedulingToMax   ← 调度截止时间怎么搬
       ├─ hasPackets 合并
       └─ 遍历 regionizedData: RegionizedData<?> → T（每个挂载点）
            └─ RegioniserCallback.merge(from, to, fromTickOffset)
               RegioniserCallback.split(from, shift, regionToData, dataSet)   ← 05 课的契约
                 └─ RegionizedWorldData.REGION_CALLBACK                            ← 本课
                      └─ 逐个状态字段搬迁（实体/连接/区块/tick 列表/服务器 spawner…）
```

**关键认识**：Folia 里"世界状态"不是一个整体，而是**按区域复制的一整套可变状态**；分裂/合并时由每个状态自己声明"我按什么规则搬"。

## 2. 一个区域到底持有多少东西（字段清单，L322–442）

| 类别 | 字段（节选） |
|---|---|
| 连接/玩家 | `connections`、`localPlayers`、`nearbyPlayers`、`entitySchedulerTickList` |
| 实体 | `allEntities`、`loadedEntities`、`entityTickList`、`navigatingMobs`、`trackerEntities`（Moonrise 实体追踪） |
| 区块 | `entityTickingChunks`、`tickingChunks`、`chunks`、`chunkHoldersToBroadcast` |
| 方块/流体 tick | `blockEvents`、`blockLevelTicks`、`fluidLevelTicks` |
| 方块实体 | `pendingBlockEntityTickers`、`blockEntityTickers` |
| 时间 | `redstoneTime`（红石游戏时间，**不是**全局 tick） |
| 红石引擎 | `wireHandler`（Alternate Current）、`turbo`（Eigencraft RedstoneWireTurbo） |
| 其它状态 | `neighborUpdater`、`captureBlockStates`/`capturedTileEntities`/`captureDrops`、`lastSpawnState`、`explosionDensityCache`、`pathTypesByPosCache`、`spawnChunkTracker`、`villageSiegeState`、`wanderingTraderData`、`attributeSamplers`… |
| 生命周期 | `isHandlingTick`、`lagCompensationTick`、`tickData`、`regionData`（指回区域数据） |

两个值得单独点出来的：

1. **两套红石引擎都是"每区域一个实例"**（L437–438、构造函数 L450–451 里 `new WireHandler(world)` / `new RedstoneWireTurbo(...)`）——**红石求值状态天然是区域局部的**。这一点直接关系到我们关心的"红石在区域边界上的行为"。
2. **`redstoneTime` 是区域自己的红石游戏时间**（初值 1L，L364），与全局 tick 不同 —— 所以合并时要算 `fromRedstoneTimeOffset`。

## 3. `merge`：批量搬 + 两个偏移量（L77–157）

合并逻辑短而直接，但**两类偏移必须同时处理**：

```java
final long fromRedstoneTimeOffset = into.redstoneTime - from.redstoneTime;   // 红石时间偏移
…
for (Entity entity : from.allEntities) {
    into.allEntities.add(entity);
    entity.updateTicks(fromTickOffset, fromRedstoneTimeOffset);              // 游戏 tick 偏移 + 红石时间偏移
    entity.getBukkitEntity().taskScheduler.registerTo(into.entitySchedulerTickList);  // 重新登记到目标区域的调度器
}
for (ServerPlayer player : from.localPlayers) {
    into.localPlayers.add(player);
    player.getBukkitEntity().updateRegion(into);                             // 让 Bukkit 侧看到新区域
    into.nearbyPlayers.addPlayer(player);
}
from.blockLevelTicks.merge(into.blockLevelTicks, fromRedstoneTimeOffset);    // 方块/流体 tick 列表
from.fluidLevelTicks.merge(into.fluidLevelTicks, fromRedstoneTimeOffset);
new RedstoneTorchBlock.Toggle … info.offsetTime(fromRedstoneTimeOffset);     // 红石火把的延迟状态
into.catSpawnerNextTick = Math.max(from.catSpawnerNextTick, into.catSpawnerNextTick);  // 服务器 spawner 计数取最大
```

**合并从不"按位置分流"**——源区域整体并入目标区域，所以只需要偏移修正 + 整批 add；唯一取 `max` 的是那三个全服性 spawner 计时器。

## 4. `split`：一切按坐标分流（L160–319）

分裂是**逐元素分流**，模式高度统一：

```java
// 每个元素：算出自己所在段 → 查 regionToData → 放进那个新区域的数据里
final RegionizedWorldData into = regionToData.get(
        CoordinateUtils.getChunkKey(pos.x() >> chunkToRegionShift, pos.z() >> chunkToRegionShift));
into.xxx.add(element);
```

被这样处理的：`connections`（按玩家位置）、`localPlayers`、`allEntities`、`blockEvents`、`pendingBlockEntityTickers`、`blockEntityTickers`、`entityTickingChunks`、`tickingChunks`、`chunks`、`redstoneUpdateInfos`、`chunkHoldersToBroadcast`。

### 三个"子集关系"要显式重建（L188–202）

`allEntities` 的遍历里，对每个实体都要检查它是否**同时**属于其它几个集合：

```java
if (from.entityTickList.contains(entity))      into.entityTickList.add(entity);      // 子集：allEntities
if (from.loadedEntities.contains(entity))      into.loadedEntities.add(entity);      // 子集：allEntities
if (entity instanceof Mob mob && from.navigatingMobs.contains(mob)) into.navigatingMobs.add(mob);
if (from.trackerEntities.contains(entity))     into.trackerEntities.add(entity);
entity.getBukkitEntity().taskScheduler.registerTo(into.entitySchedulerTickList);     // 重新登记调度
```

注释把不变量直接写出来（"entityTickList is a subset of allEntities"）——**先声明集合关系，再据此重建**。

### 三种 `null` 处理策略，且每条都有理由（这是本课最值得学的部分）

| 数据 | 处理 | 代码注释给出的理由 |
|---|---|---|
| 实体 / 玩家 / 区块（entityTicking/ticking/all） | **不判空**（直接 `.get()` 后使用） | "It is impossible for an entity in the world to _not_ be in an entity chunk, which means the chunk holder must _exist_, and so the region section exists." |
| `blockEvents`、红石火把、`chunkHoldersToBroadcast` | **判空后跳过** | blockEvents："Unlike entities, the chunk holder is not guaranteed to exist for block events, because the block events is just some list. **So if it unloads, I guess it's just lost.**" / chunkHolders："Possible for get() to return null, as the chunk holder is not removed during unload" |
| 方块实体 ticker | **判空后跳过** | "when a chunk unloads, it does not actually _remove_ the tile entity from the list, it just gets marked as removed. **So if there is no section, it's probably removed!**" |

**这三条注释的价值高于代码本身**：它把"哪些失败是不可能发生的（可以直接信）、哪些是可接受的丢失（静默跳过）"写清楚了。移植时如果把它们统一成一种处理方式（比如全判空或全不判空），就会在边界场景上产生行为差异。

### 必须**复制给所有**新区域的状态（不按位置分流）

```java
for (RegionizedWorldData d : dataSet) {
    d.redstoneTime = from.redstoneTime;              // 红石时间基准：所有新区域从同一时刻开始
}
for (RegionizedWorldData d : dataSet) {
    d.catSpawnerNextTick = from.catSpawnerNextTick;  // 全服性计时器：复制
    d.patrolSpawnerNextTick = from.patrolSpawnerNextTick;
    d.phantomSpawnerNextTick = from.phantomSpawnerNextTick;
    d.villageSiegeState = new VillageSiegeState();   // ★ 直接重置：注释"the spawn pos will be invalid"
}
```

注意最后一行：**唯一被整体丢弃并重建的状态是村庄袭击（village siege）**，理由是"原坐标已经失效"。这是一个"宁可重来也不要带着错误坐标"的取舍样本。

### 专用迁移 API

`LevelTicks` 有自己的 `split(chunkToRegionShift, map)` / `merge(into, offset)`（L219–233、110–111）：方块/流体 tick 列表不是普通集合，需要按段重新分桶并由其自身实现处理。

## 5. 区域数据层的两个额外动作（`TickRegions.java` 281–378）

```java
// split 时：调度状态复制给每个新区域
data.tickHandle.copyDeadlineAndTickCount(this.tickHandle);

// merge 时：
data.tickHandle.updateSchedulingToMax(this.tickHandle);
```

以及 merge 里那段**罕见的坦诚注释**（L358–361）：

> "here we can access tickHandle because the target (into) is the region being released, so it is not actually scheduled"
> **"there's not really a great solution to the tick problem, no matter what it'll be messed up"**
> "we will pick the greatest time delay so that tps will not exceed TICK_RATE"

**含义**：两个独立 tick 的区域合并后，"下一次什么时候 tick"没有正确答案，Folia 的选择是**取较大延迟**以避免超过 TICK_RATE。也就是说 **合并瞬间存在一次可观测的调度扰动** —— 这属于"设计上承认的缺陷"，不是 bug，但任何要逐 tick 对齐语义的移植（包括我们的 OCC）都必须知道这一点。

## 6. 另外两个值得记的细节

1. **`lagCompensationTick` 由墙钟推出**（L474–481）：`(System.nanoTime() - SERVER_INIT) / TIME_BETWEEN_TICKS`。
   因为各区域独立 tick，"当前 tick 号"不能全局一致；滞后补偿用**从服务器启动算起的墙钟刻度**来近似。`updateTickData()` 还会顺便重算 `hasPhysicsEvent`/`hasEntityMoveEvent`/`skipHopperEvents`（按已注册监听器数量决定是否触发事件）——**事件开关是全局的，但每区域 tick 时各自读取**。
2. **区域存活与 Moonrise 区块票据绑定**（`cleanUpConnection`，L487–496）：连接被移除时，会移除该玩家的 `DISCONNECT_TICKET` 级区块票据，注释写着 **"now that the connection is removed, we can allow this region to die"**。这说明**区域的存亡最终由区块票据（ticket）体系驱动**，而不是有人在"关区域"。

## 7. 对 CeruLean 的直接结论（本课的目的）

1. **"跨区域状态"有统一的挂载与迁移机制**：`RegionizedData` + `RegioniserCallback`。任何我们自己新增的按区域存在的状态（**尤其是 OCC 的阶段快照、读集/写集、补偿队列**）都必须：
   - 以 `RegionizedData<T>` 形式挂在区域数据上；
   - 实现 `merge`/`split`，并在 `split` 里**按坐标把条目分流到正确的区域**；
   - 在 `merge` 里**保证不抛异常**（05 课：抛了就是不可恢复状态），并正确处理 tick/红石时间偏移。
   **不这样做的后果**：区域分裂后，原本挂在旧区域上的 OCC 状态会跟着旧区域一起被销毁或错配——表现为"命令方块链在某次区域分裂后行为突变"，而且极难复现。
2. **红石求值是区域局部的**（两套引擎都是每区域实例，`redstoneTime` 也是每区域的）——所以"红石跨区域"和"命令方块跨区域"是**同一类问题**，可以共用一套验证方法。
3. **存在"设计上承认的扰动"**：合并时的调度取最大延迟（tick 对齐无解）、村庄袭击状态被重置、卸载区块相关的 blockEvents/ticker 会静默丢弃。**这些必须在基线文档里列为"允许的差异"，而不是当成 bug 去追**。
4. **区域粒度在关闭区域化时会退化为整个服务器**（05 课）——我们若要做"关闭区域化"的兼容模式，必须明确这个语义。

## 8. 阶段小结：源码阅读进度

| 课 | 主题 | 结论一句话 |
|---|---|---|
| 00 | 接入机制 | 13 个新文件全在 Minecraft 树；`TickRegions` 是唯一对接面（Paper 留了占位） |
| 01 | 类型与契约 | 三概念 + 五旋钮 + StampedLock 纪律 + "回调内不得碰世界状态" |
| 02 | 分裂与合并 | 拓扑只在**释放点**变化；`STATE_TICKING` 期间区块集合冻结 |
| 03 | 区域所有权 | 窄接口；**发现一处验证过的 bug**（`getCenterSection` 比较器） |
| 04 | 段的生命周期 | 段活着 ⟺ 有区块或有非空邻居；无锁快路径的可证明安全性 |
| 05 | 数据挂载与契约 | `RegionizedData` + `RegioniserCallback`；合并**绝不能抛异常** |
| **06** | **世界状态迁移** | **逐元素按坐标分流；三类 null 策略各有理由；OCC 状态必须接入同一机制** |

**下一课（07/08）**：`TickRegionScheduler`（区域怎么被排到线程上、EDF 调度）与 `RegionizedServer`/`RegionizedTaskQueue`（全局 tick、跨区域任务队列）——把"区域如何真正跑起来"补完。
