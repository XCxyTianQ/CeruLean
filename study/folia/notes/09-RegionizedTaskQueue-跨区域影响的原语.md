# 09 · RegionizedTaskQueue：影响另一个区域的唯一正规路径

> 源码：`RegionizedTaskQueue.java` 精读范围 `1–700`（入口 API、票据与引用计数、区域队列、优先队列、任务所有权协议）。
> **这是与我们（跨区域命令方块链 / OCC）最直接相关的一课**：它回答"一个区域想影响另一个区域时，到底走什么路径"。

## 1. 两种任务、三个入口（L25–88）

| 入口 | 语义 |
|---|---|
| `createChunkTask` / `createTickTaskQueue` | 只创建任务对象（未排队） |
| `queueChunkTask` / `queueTickTaskQueue` | 创建并立即排队 |
| **`queueOrExecuteTickTask`** | **若当前线程已经是 (world, chunkX, chunkZ) 的 tick 线程 → 直接内联执行；否则排队** |

`ChunkBasedPriorityTask` 上有个 `isChunkTask` 布尔（L491）：

- **chunk task**：进 `chunkQueue`；
- **tick task**：进 `tickTaskQueue`。

两者分别对应"由拥有该区块的线程执行"与"作为该区域 tick 的一部分执行"（见第 5 节的排空逻辑）。

**★ `queueOrExecuteTickTask` 就是跨区域跳转的原语**：

```java
if (TickThread.isTickThreadFor(world, chunkX, chunkZ)) {   // Moonrise 的判定
    run.run();                                            // 我本来就拥有这块 → 直接干
    return null;
}
return this.queueTickTaskQueue(world, chunkX, chunkZ, run, priority);   // 否则交给拥有者
```

**推论（很重要）**：**Folia 不允许一个区域直接触碰另一个区域的世界状态**。所有跨区域的影响都必须变成"投递给目标区块所在区域的任务"，由目标区域在自己的 tick 里执行。

## 2. ★ 任务会给区块上票据（这是让跨区域安全的关键）

```java
private static final TicketType<Long> TASK_QUEUE_TICKET =
        ChunkSystemTicketType.create("task_queue_ticket", Long::compareTo);
…
addTicketAtLevel(TASK_QUEUE_TICKET, coord, ChunkHolderManager.MAX_TICKET_LEVEL, id);
```

**每个已排队但尚未执行的任务，都会为它所属的"区域段"持有一张票据（最高级别）**——因此在任务被真正执行之前，**目标区块不会被卸载、目标区域也不会消失**。这解决了"投递之后目标没了"的问题。

三个细节：

1. **票据粒度是"区域段"，不是单个区块**（L509–512）：
   ```java
   final int regionMask = (1 << regionShift) - 1;
   this.sectionLowerLeftCoord = CoordinateUtils.getChunkKey(chunkX & ~regionMask, chunkZ & ~regionMask);
   ```
   即"任务所在段的左下角区块坐标"——因为区域由段组成，保住段就等于保住区域。
2. **引用计数**：同一段上可能有很多任务，所以用 `ReferenceCountData`（`AtomicLong referenceCount` + 唯一 id + `addedTicket` 标志）做计数；**从 1→0 时才撤票据**（L158–181）。计数用 CAS 循环 + `Thread.onSpinWait()` 退避；负计数直接抛异常。
3. **加票据后立刻 `processTicketUpdates(coord)`**（L153）——不等全局 tick，立即推动该段的票据处理（08 课里全局 tick 也会对整 world 做一次）。

## 3. 任务怎么找到"该去哪个区域的队列"（L121–130）

```java
final ThreadedRegion<...> region = synchronise
        ? regioniser.getRegionAtSynchronised(chunkX, chunkZ)      // 乐观读 + 失败退锁（01 课）
        : regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
if (region == null) return null;                                   // 没有区域 → 无法排队
final RegionTaskQueueData taskQueueData = region.getData().getTaskQueueData();
return isChunkTask ? taskQueueData.chunkQueue : taskQueueData.tickTaskQueue;
```

**路由完全由"目标区块当前属于哪个区域"决定**——任务本身不指定区域，而是按坐标查当前归属。这意味着：**区域分裂/合并之后，还没执行的任务必须重新落到新区域**（见第 4 节）。

## 4. 区域合并/分裂时，队列怎么办（L259–262、350–425）

| 事件 | 处理 | 关键细节 |
|---|---|---|
| **合并** | `RegionTaskQueueData.mergeInto(into)` → 两个队列各自 `PrioritisedQueue.mergeInto` | 顺序保护：用 `descendingIterator()` + `addFirst()` 把源队列整体插到目标队首，注释解释"必须用降序迭代器，否则会把源队列内部顺序颠倒"；同时置 `isDestroyed = true` |
| **分裂** | `PrioritisedQueue.split(isChunkData, regioniser, into)` | 逐任务按自己的 `chunkX/chunkZ` 算段 key → 查 `into`（段→新区域）→ 投到新区域对应队列；**查不到就抛 `IllegalStateException`**（L399–401） |

**注意分裂是"逐任务重新路由"**：任务被搬运的依据是**任务自己的坐标**，而不是它原来在哪个队列 —— 与 06 课世界状态迁移的做法完全同构（"按坐标分流"）。

## 5. 排空逻辑：有界的、防活锁（L282–303）

```java
int allowedTickTasks  = tickTaskQueue.getScheduledTasks();     // 先取快照计数
int allowedChunkTasks = chunkTaskQueue.getScheduledTasks();
do {
    executeTickTasks  = executeTickTasks  && allowedTickTasks--  > 0 && tickTaskQueue.executeTask();
    executeChunkTasks = executeChunkTasks && allowedChunkTasks-- > 0 && chunkQueue.executeTask();
    executeGlobalTasks = executeGlobalTasks && worldRegionTaskData.executeGlobalChunkTask();
} while (executeTickTasks | executeChunkTasks | executeGlobalTasks);

if (allowedChunkTasks > 0) {
    // 执行过 chunk 任务 → 尝试处理票据更新，让区块状态变更生效
    this.worldRegionTaskData.world.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates();
}
```

**关键**：一次排空**只处理"进入时快照到的那批任务"**（`allowed*` 是快照值）。执行期间新进来的任务留给下一轮 —— 这是**防活锁**（否则源源不断的新任务会让 tick 永不结束），也保证 tick 有界。

`executeChunkTask()` 还顺带先跑一个"全局 chunk 任务"（`executeGlobalChunkTask()`），因为有些区块任务没有区域可归属（见 08 课 `drainGlobalChunkTasks`）。

## 6. 任务的所有权协议（无锁的"只有一个赢家"）

任务的 `referenceCounter` 字段既是**票据凭据**又是**所有权令牌**（用 `VarHandle` 做 CAS）：

```java
queue() {                                    // 入队
    if (referenceCounter != NOT_SET) return false;                    // 已经排过
    refCounter = world.incrementReference(sectionLowerLeftCoord);      // ① 加票据（段级）
    if (CAS(referenceCounter, NOT_SET → refCounter) 失败) {            // ② 发布"我已入队"
        world.decrementReference(...); return false;                   //    别人抢先 → 退回
    }
    … 再找目标队列并 tryPush …
}

trySetCompleting(minPriority) {              // 出队执行时
    ① CAS priority → COMPLETING（若当前优先级低于 minPriority 则放弃，留给下一轮）
    ② CAS referenceCounter → null（谁把它置空，谁就获得执行权）
}
```

两个设计要点：

1. **优先级可以在排队后被改动**，所以出队时先看"当前优先级是否仍不低于本轮扫描的下限"，不满足就把任务留在队列里给下一轮（`trySetCompleting` 的 `minPriority` 参数）；
2. **同一把令牌保证"执行或取消，只有一个线程能赢"**（`tryComplete(cancel)` 也是同一套）；执行完/取消后统一 `decrementReference` 撤票据（L581–583）。

`PrioritisedQueue` 本体是**按优先级分层的一维 `ArrayDeque` 数组**（`Priority.TOTAL_SCHEDULABLE_PRIORITIES` 档），出入队都在 `synchronized (this)` 里；`isDestroyed` 之后 `executeTask` 直接抛"Attempting to poll from dead queue"。

## 7. ★ 对 CeruLean / OCC 的四条直接结论

1. **跨区域只能排队，不能直接改。** 这是唯一正规路径（`queueOrExecuteTickTask`），并且它**自带区块加载保证**（段级票据）。
2. **它不提供跨区域执行顺序。** 任务何时被执行，取决于目标区域"下一次 tick 或任务排空"；而 07 课我们知道**区域落后时 `tickCount` 可以 >1**（一次追多个 tick）、以及下一轮起点是 `max(now, 理想截止)`。所以：

   > **跨区域任务的延迟是不确定的**——可能是目标区域同一 tick，也可能跨越多个 tick。

   任何假设"下一步一定在下一 tick 发生"的协议都是错的。**OCC 必须支持任意延迟**，这正是 AzureBranches 那套"回执袋 / `registerRemote` / 阶段快照"存在的原因：链要等回执，而不是假定时间。
3. **任务搬运与区域分裂/合并自动衔接**：分裂时逐任务按坐标重路由（查不到就抛），合并时保序搬运。→ 我们的 OCC 任务若也走这个队列，就能自动获得这层支持；**若我们自己另建一套队列，就必须自己实现这两件事**（这正是"不走正规路径"的代价）。
4. **排空有界**：一次 tick 只处理快照批次的量。所以"投递大量跨区域任务"不会让某个区域卡死，但会**分散到多个 tick 完成**——设计 OCC 的重试/超时策略时必须考虑这一点。

## 8. 本课小结

| 问题 | 答案（含出处） |
|---|---|
| 怎么影响另一个区域 | `queueOrExecuteTickTask`（本类）→ 目标是目标区块所属区域的队列 |
| 目标会不会在执行前消失 | 不会：任务持有**段级最高级别票据**，引用计数归零才撤 |
| 区域分裂/合并时未执行的任务 | 分裂：按坐标重路由（找不到即抛）；合并：保序搬运并置 `isDestroyed` |
| 一次 tick 处理多少任务 | **进入时的快照数量**（防活锁），新任务留下一轮 |
| 跨区域延迟 | **不确定**（取决于目标区域的 tick 节奏与追帧行为） |

**下一课（10）**：`scheduler/FoliaRegionScheduler`（对插件暴露的 API 怎么落到这套队列上）、`TeleportUtils`、`RegionShutdownThread`、`util/*`、`commands/*` —— 把周边收尾；随后第 11 课进入 187 个 hook 点。
