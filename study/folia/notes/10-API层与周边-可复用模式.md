# 10 · API 层与周边：`FoliaRegionScheduler`、`TeleportUtils`、`RegionShutdownThread`

> 源码：`scheduler/FoliaRegionScheduler.java`（427 行）、`TeleportUtils.java`（82 行）、`RegionShutdownThread.java`（229 行）。
> 本课除了收尾周边，还要把前面九课里**反复出现的模式**提炼出来 —— 那是我们真正要借鉴的东西。

## 一、`FoliaRegionScheduler`：API 如何落到第 09 课的队列上

### 1. 两条路径：on-region 直接做，off-region 先"跳"过去

```java
public void execute(plugin, world, chunkX, chunkZ, run) {
    RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(     // ← 第 09 课的原语
        ((CraftWorld) world).getHandle(), chunkX, chunkZ, wrap(plugin, world, chunkX, chunkZ, run)
    );
}

public ScheduledTask runDelayed(plugin, world, chunkX, chunkZ, task, delayTicks) {
    final LocationScheduledTask ret = new LocationScheduledTask(...);
    if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
        scheduleInternalOnRegion(ret, delayTicks);      // 我拥有这块 → 直接登记
    } else {
        scheduleInternalOffRegion(ret, delayTicks);     // 否则：排一个任务，到那边再登记
    }
    return ret;
}

private static void scheduleInternalOffRegion(task, delay) {
    RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(world, task.chunkX, task.chunkZ, () -> {
        scheduleInternalOnRegion(task, delay);          // ← 到了目标的 tick 线程上再执行"直接登记"
    });
}
```

**这是"跨区域操作"的标准写法**：*能直接做就直接做；不能就先投递一个任务，让拥有者在它自己的线程上做那件"直接的事"。* TeleportUtils 用的是同一套思路（第 3 节）。

### 2. `SCHEDULER_DATA` 用 `RegionizedData` 挂载（印证 05 课的规则）

```java
private static final RegionizedData<Scheduler> SCHEDULER_DATA =
        new RegionizedData<>(null, Scheduler::new, Scheduler.REGIONISER_CALLBACK);
```

注意第一个参数是 **`null`**：按 05 课 Javadoc 的判定标准 —— **"只与 region 绑定、与 world 无关的数据就该传 null（关闭 world 检查）"**。
调度器就是这种数据（一个区域一个调度器，不隶属某个 world）。**这是对 05 课规则的一次实战印证。**

### 3. ★ `Scheduler` 是我们自己状态应该照抄的"迷你范例"

```java
private long tickCount = 0L;
// map of region section -> map of deadline -> list of tasks
private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<List<LocationScheduledTask>>> tasksByDeadlineBySection
        = new Long2ObjectOpenHashMap<>();
```

**数据结构直接按"段"索引**，于是 merge/split 都退化成"段的搬运"：

```java
// merge：把每个段里每个 deadline 平移到目标区域的时间基准上
for (section : from.tasksByDeadlineBySection) {
    for ((deadline, tasks) : section) {
        sectionAdjusted.put(deadline + fromTickOffset, tasks);   // ★ 05 课那条 tick 偏移公式的具体落地
    }
    into.tasksByDeadlineBySection.put(sectionKey, sectionAdjusted);
}

// split：新区域 tickCount 对齐 → 然后按段 key 查归属，整段搬过去（不需要逐任务遍历！）
for (into : dataSet) into.tickCount = from.tickCount;
for (section : from.tasksByDeadlineBySection) {
    regionToData.get(sectionKey).tasksByDeadlineBySection.put(sectionKey, section);
}
```

三个要点：

1. **按段索引 ⇒ 分裂是 O(段数) 的 map 搬运**，而不是 06 课 `RegionizedWorldData` 那种"逐元素算坐标再分流"。**代价小、易证明正确**；
2. **merge 用 `deadline + fromTickOffset` 平移绝对时间**（05 课公式的第一次实见）；
3. **split 时把新区域的 `tickCount` 设为源区域的值** —— 因为"新区域与原区域从同一 tick 开始"（05 课 Javadoc），deadline 无需平移。

### 4. 调度器也持有票据：`REGION_SCHEDULER_API_HOLD`

```java
private void queueTask(task, delay) {
    section = tasksByDeadlineBySection.computeIfAbsent(sectionKey, …);
    if (section.isEmpty()) {
        // need to keep the scheduler loaded for this location in order for tick() to be called...
        this.addTicket(sectionX, sectionZ);
    }
    section.computeIfAbsent(this.tickCount + delay, …).add(task);
}
```

- 票据类型 `TicketType.REGION_SCHEDULER_API_HOLD`、级别 `MAX_TICKET_LEVEL`、**id 为 null 不做引用计数**（因为一个段只需要一张，段空了就撤）；
- 注释点明目的：**保住这段所在的区域，让它的 `tick()` 会继续被调用**，否则排定的任务永远不会到期执行；
- `tick()` 里：`++tickCount` → 找 deadline == 当前 tick 的任务 → 执行；若某段空了 → 撤票并移除该段（L268–271）。

> 与 09 课对比：**任务队列**用引用计数（同一段可能堆积很多任务）；**调度器**用"段非空即持票"（更简单）。同一个问题两种粒度都出现了，按需要选。

### 5. 插件异常在这里被兜住

```java
private static Runnable wrap(plugin, world, chunkX, chunkZ, run) {
    return () -> { try { run.run(); } catch (Throwable t) {
        plugin.getLogger().log(WARNING, "Location task for … generated an exception", t); } };
}
```

**注意与 07 课的反差**：内部区域 tick 抛异常 = **fail-stop 停服**；而**插件任务抛异常只记日志**。
→ 这说明 fail-stop 的边界是**"Folia 自己的状态机"**，不含第三方插件代码。**我们若要引入自定义的跨区域逻辑，必须自己决定它属于哪一侧**（我倾向：属于状态机一侧 → 必须不抛，或者抛出也必须能安全停服）。

## 二、`LocationScheduledTask`（L280–427，略读）

实现了 API 的 `ScheduledTask`：状态用 `volatile int` + CAS（`getStateVolatile`/`compareAndExchangeStateVolatile`/`setStateVolatile`），`cancel()` 返回 `CancelledState`，`getExecutionState()` 报告执行状态。`run()` 里根据状态决定是"执行一次/重复/已取消"。

**要点**：任务取消是**状态机**而不是"从队列里摘掉"，这与 09 课"执行或取消只有一个赢家"的无锁所有权协议配套。

## 三、`TeleportUtils`：跨区域编排的范本（82 行全读）

一次跨区域传送被拆成**两次跨区域跳转 + 一个 completable**：

```java
// ① 先跳到【目标实体】所在区域，读它的坐标
to.getBukkitEntity().taskScheduler.schedule((target) -> {
        positionCompletable.complete(target.getBukkitEntity().getLocation());
    }, (retired) -> onComplete.accept(null), 1L);        // retired = 实体已消失

// ② 拿到坐标后，跳回【源实体】所在区域执行传送
positionCompletable.addWaiter((loc, thr) -> {
    from.getBukkitEntity().taskScheduler.schedule((realFrom) -> {
            if (preTeleport != null && !preTeleport.test(realFrom)) { onComplete.accept(null); return; }   // ★ 在正确的线程上重新校验前置条件
            (useFromRootVehicle ? realFrom.getRootVehicle() : realFrom).teleportAsync(…);
        }, (retired) -> onComplete.accept(null), 1L);
});
```

四个可复用要点：

1. **跨区域读** 也要跳过去读（不能跨区域读别人的状态）；
2. **前置条件在拥有者线程上重新校验**（`preTeleport` 谓词）—— 因为等待期间世界可能已经变了；
3. **每步都有 `retired` 回退**（实体已被移除/区域已变），回调统一以 `onComplete.accept(null)` 收尾 —— "失败"与"取消"走同一条收敛路径；
4. 默认 delay = 1 tick，即**不假设"立刻"**。

> 对 OCC 的意义：**这就是"跨区域命令方块链的下一步"应该长的样子** —— 跳过去、校验、执行、回报；且必须处理"对方已经不存在了"。

## 四、`RegionShutdownThread`：关服一致性

它是 `TickThread` 的子类，作为**伪区域上下文**（07 课里 `getCurrentRegion()` 对非 tick 线程的回退就指向它）。类注释把能力边界讲得很清楚：

> "The region shutdown thread bypasses all tick thread checks, which will allow us to execute global saves. **It will not however let us perform arbitrary sync loads, arbitrary world state lookups simply because the data required to do that is regionised, and we can only access it when we OWN the region, and we do not.**"

关服序列（`run()`，L153–212）：

```
1. halt 调度器（最多等 60s；超时则 dumpAliveThreadTraces 后继续）
2. stopServer() 第一步（踢玩家、插件清理等）
3. 先软 halt 各 world 的 chunk system，再硬 halt（阻断进行中的区块生成）
4. 逐 world、逐 region：finishTeleportations（把 pending 传送做完）
5. 逐 world、逐 region：closePlayerInventories → saveRegionChunks（最后一个 region 另作处理）→ saveLevelData
```

**每做一个 region 的操作，就把 `shuttingDown` 指向那个 region**，从而借用"当前区域"上下文；
但它**不拥有任何区域**，所以代码里凡是要真正访问区域化状态的地方都必须走"逐个区域、占用上下文"的方式。

→ 对 CeruLean 的启示（Phase 3 用得起）：**关服不是"把 tick 停掉然后存盘"，而是一段有明确阶段与超时退路的流程**；`halt` 超时也要继续关（保证能关掉），但会留下线程堆栈以便排障。

## 五、★ 提炼：贯穿整套实现的五条可复用模式

读到现在（00–10 课），同一批手法反复出现。**这五条是 CeruLean 最该继承的东西**：

| # | 模式 | 出现处 | 对我们的用法 |
|---|---|---|---|
| 1 | **一切按坐标/段归属重新归属** | 06 课世界状态、09 课任务与票据、10 课调度器 | 我们的 OCC 状态尽量**按段索引存储**，让分裂/合并退化成 map 搬运 |
| 2 | **需要"活到被执行"的东西就持票据** | 09 课任务队列（引用计数）、10 课调度器（段非空即持票） | 跨区域待办（命令方块链的续跑、OCC 回执等待）必须持票，否则目标区域会消失 |
| 3 | **能直接做就直接做，否则投递一跳** | 09 课 `queueOrExecuteTickTask`、10 课 on/off-region 两条路径、TeleportUtils | 统一的跨区域调用模板；不要自己发明"跨线程直接改" |
| 4 | **前置条件在拥有者线程上重新校验 + 处理"已消失"** | TeleportUtils 的 `preTeleport` 与 `retired` | OCC 的读集/写集校验必须发生在**目标区域的线程**上，并支持"目标没了"的收敛路径 |
| 5 | **不变量优先：宁可炸也不静默错；无锁快路径由不变量证明** | 01/02/04 课的断言与快路径、07 课 fail-stop | 我们的状态机同样应当"先写不变量，再据此划边界"，并把"哪个错误属于 fail-stop、哪个只记日志"明确下来 |

## 六、剩余进度

- **11**：187 个 hook 点（`ServerLevel` 62 hunk、`MinecraftServer` 40、`Entity` 27、`Level` 25、`ServerGamePacketListenerImpl` 31 …）
  以及 `util/*`（线程局部随机源）与 `commands/*`（`/paper` 系列诊断命令）——写完 11 课，区域化源码阅读即告完成。
