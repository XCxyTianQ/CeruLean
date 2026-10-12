# 08 · RegionizedServer：全局 tick 与"哪些事必须全局做"

> 源码：`RegionizedServer.java`（427 行，全读）。
> 本课回答：区域化之后，**还剩哪些事情不能按区域做**（必须全局串行），以及它们是怎么被安排的。

## 1. 结构：单例 + 四张表 + 一个"全局区域 handle"（L34–52）

```java
private static final RegionizedServer INSTANCE = new RegionizedServer();

public  final RegionizedTaskQueue taskQueue = new RegionizedTaskQueue();
private final CopyOnWriteArrayList<ServerLevel> worlds      = ...;   // 全部 world
private final CopyOnWriteArrayList<Connection>  connections = ...;   // 全部连接
private final MultiThreadedQueue<Runnable> globalTickQueue  = ...;   // 全局任务队列
private final GlobalTickTickHandle tickHandle = new GlobalTickTickHandle(this);
public  final PacketProcessor packetProcessor = new PacketProcessor(null);
```

**最关键的一行设计**（L150–160）：

```java
private static final class GlobalTickTickHandle extends TickRegionScheduler.RegionScheduleHandle {
    public GlobalTickTickHandle(final RegionizedServer server) {
        super(null, TimeUtil.DEADLINE_NOT_SET);   // ← region == null
        …
    }
```

> **"全局区域"就是一个 `region == null` 的区域调度 handle。**

它复用了 07 课那套调度机制（`tryMarkTicking`/`runTick`/`runTasks`），差别只在两处：
- 没有 regioniser 参与 → `tryMarkTicking`/`markNotTicking` 用 `AtomicBoolean.getAndSet` 实现，不碰写锁；
- `RegionScheduleHandle` 里所有设上下文的地方都写成 `if (this.region != null) setTickingRegion(...)` —— 于是**全局 tick 期间"当前区域"为 null**，`RegionizedData.get()` 会返回 null（05 课）。

这解释了一个容易困惑的现象：**全局 tick 里不能通过 `RegionizedData.get()` 取区域化数据**（没有区域上下文），只能走别的入口。

## 2. 启动顺序（`init()`，L66–74）

```java
public void init() {
    new RegionizedServerInitEvent().callEvent();                    // ① 先发事件
    this.tickHandle.setInitialStart(System.nanoTime() + TIME_BETWEEN_TICKS);
    TickRegions.getScheduler().scheduleRegion(this.tickHandle);     // ② 再把全局区域排进调度
    TickRegions.start();                                            // ③ 最后启动调度器
}
```

三点值得记：

1. **`RegionizedServerInitEvent` 必须在任何调度之前触发**（注释 "call init event _before_ scheduling anything"）——这是插件/接入方在"区域还没开始跑"时做初始化的唯一窗口；
2. 全局 tick 的**首次起点是 `now + 50ms`**（不是立即），保证第一个 tick 有完整的时间预算；
3. 调用时机的注释（在 00 课看到的 hook 点）：**"only after loading worlds"** —— 必须等 world 都加载完。

## 3. "有活儿"的通知机制（L80–106、226–228）

```java
public void addTaskWithoutNotify(Runnable run) { this.globalTickQueue.add(run); }
public void addTask(Runnable run)              { this.addTaskWithoutNotify(run); this.notifyTasks(); }
public void notifyTasks()                      { TickRegions.getScheduler().setHasTasks(this.tickHandle); }

@Override protected boolean hasIntermediateTasks() {
    return !this.server.globalTickQueue.isEmpty()
        || this.server.packetProcessor.hasPackets()
        || this.server.hasAnyGlobalChunkTasks();     // 各 world 的 taskQueueRegionData
}
```

**设计意图**：调度器平时按 tick 节奏调度；一旦有"中间任务"（队列里有东西），就通过 `setHasTasks` 让调度器**尽快再跑一次**（07 课的 `runTasks`）。这样任务延迟与 tick 周期解耦，又不至于空转。

`blockOn(Runnable)`（L108–118）是配套的**同步逃生口**：

```java
if (isGlobalTickThread()) { run.run(); return; }     // 已在全局线程 → 直接跑
Completable.suppliedAsync(() -> { run.run(); return null; }, this::addTask).join();   // 否则投递并阻塞等待
```

即"其它线程想安全地修改全局状态"就用它（也是 Bukkit 兼容层的常用手段）。**注意：它会阻塞调用线程直到全局 tick 执行到**——在区域线程里滥用会显著拖慢该区域。

## 4. 全局 tick 都做什么（L234–267）

```
globalTick(tickCount):
  ++tickCount
  ├─ Adventure 点击回调过期处理（ClickCallbackProviderImpl，Paper 的点击命令/对话框）
  ├─ FoliaGlobalRegionScheduler.tick()        ← 全局区域调度器的 API 轮转
  ├─ tickClocks()                             ← 时间（受命中 Paper 的 per-world time 配置）
  ├─ 控制台输入处理（DedicatedServer.handleConsoleInputs）
  ├─ tickPlayerSample()                       ← 重算 server status（玩家样本），带过期判断
  ├─ 逐 world：globalTick(world, tickCount)   ← 见第 5 节
  ├─ tickConnections()                        ← 逐连接 tick
  └─ PlayerList.tick()
```

代码里那几行 **"needs …" 注释就是一份待办清单**（L249–252）：player ping sample / world global tick / connection tick —— 作者用注释把"这里本该有什么"留在原地。

## 5. 每个 world 的全局 tick（L337–377）——职责边界的说明书

先看那段**定调注释**：

> "A global tick only updates things like weather / worldborder, basically anything in the world that is **NOT tied to a specific region, but rather shared amongst all of them**."

具体顺序（L348–376）：

| 顺序 | 动作 | 为什么必须全局 |
|---|---|---|
| 1 | `environmentAttributes().invalidateTickCache()` | 环境属性缓存是 world 级共享 |
| 2 | `taskQueueRegionData.drainGlobalChunkTasks()` | **"Typically, we expect there to be a running region to drain a world's global chunk tasks. However, this may not be the case - and thus, only the global tick thread can do anything."** |
| 3 | `tickWorldBorder(world)` | 世界边界跨区域 |
| 4 | `advanceWeatherCycle(world)` | 天气是全 world 状态 |
| 5 | `checkNightSkip(world)`（`world.tickSleep()`） | 睡眠/跳夜是全体玩家决策 |
| 6 | `updateRaids(world)` | 袭击是 world 级事件 |
| 7 | `updateSkyBrightness(world)` | 天空亮度与时间相关 |
| 8 | `tickTime(world, tickCount)` | 游戏时间 `+= tickCount` |
| 9 | `world.updateTickData()` | 刷新每区域 tick 数据（06 课的 `WorldLevelData`） |
| 10 | **`chunkHolderManager.processTicketUpdates()`** | 注释："**required to eventually process ticket updates**" —— Moonrise 票据必须由全局 tick 推进 |
| 11 | `autoSaveMaps(world)` | 地图数据定期存盘 |

第 10 条与 06 课 `cleanUpConnection`（撤 `DISCONNECT_TICKET`）呼应：**区块票据体系的推进点在全局 tick**，而区域的存在与否由票据驱动 —— 于是形成闭环：

```
全局 tick 推进票据 (ticket updates)
      → 票据决定哪些区块该加载/该 tick
            → regioniser 的 addChunk/removeChunk（02 课）增删区块
                  → 区域在释放点被合并/分裂（02 课）
```

## 6. 连接 tick 的两处细节（L298–335）

```java
final List<Connection> connections = new ArrayList<>(this.connections);
Collections.shuffle(connections);   // shuffle to prevent people from "gaming" the server by re-logging
for (conn : connections) {
    if (!conn.becomeActive()) continue;
    if (isNotOwnedByGlobalRegion(conn)) continue;   // 归属区域的连接由该区域负责移除
    if (!conn.isConnected()) { removeConnection(conn); conn.handleDisconnection(); continue; }
    try { conn.tick(); } catch (Exception e) {
        if (conn.isMemoryConnection()) throw new ReportedException(...);   // 内存连接 → 直接崩
        LOGGER.warn(...); conn.send(new ClientboundDisconnectPacket("Internal server error"), ...); conn.setReadOnly();
    }
}
```

1. **连接列表每个 tick 先打乱**，注释写明动机："防止玩家靠反复重登来给服务器排序做手脚"（避免固定顺序被利用）；
2. **连接的所有权是可转移的**：不属于全局区域的连接会被跳过，代码注释强调"**we actually require that the owning regions remove the connection for us, as it is possible that ownership is transferred back to us**" —— 即撤连接的责任在**拥有它的区域**，因为归属可能又转回全局；
3. 失败处理区分连接类型：内存连接直接抛 `ReportedException`（崩溃报告路径），真实连接则**只断开该玩家**并回 "Internal server error"。这是全服 fail-stop 模型里少数"局部降级"的地方。

## 7. 三处细节补记

1. **两套时间基准**（L411–415 + L426）：
   - `tickTime(world, tickCount)`：游戏时间 `setGameTime(getGameTime() + tickCount)` ← **全局推进**；
   - `WorldLevelData(ServerLevel world, long nonRedstoneGameTime)`：字段名明确写着 **nonRedstoneGameTime**；
   - 而 06 课的 `RegionizedWorldData.redstoneTime` 是**每区域**的。
   → **"游戏时间"全局一致，"红石时间"按区域各自走**。任何依赖红石时序的语义（命令方块链正是红石时序）在跨区域时天然不可比 —— 这是 05 课 tick offset 存在的根本原因，也是我们做 OCC 时必须处理的时间基准问题。
2. **`getCurrentTick()`**（L124–134）：取当前区域 tick；**关服线程返回 0**；否则抛 `IllegalStateException("No currently ticking region")` —— 又一次"隐式上下文必须存在"的体现。
3. **`isGlobalTickThread()` 用"当前 tick 任务是否等于全局 handle"判定**（L136–138），而不是靠线程名/线程 ID。这比字符串判断可靠，也说明"是不是全局线程"在语义上等于"当前正在跑全局区域的 tick/tasks"。

## 8. 本课小结：全局 vs 区域的职责线

| 类别 | 归属 | 例子 |
|---|---|---|
| 跨区域共享、必须串行 | **全局 tick** | 天气、世界边界、睡眠跳夜、袭击、天空亮度、游戏时间、控制台输入、玩家列表、server status |
| 与区块加载强相关 | **全局 tick（推进票据）+ 区域（消费）** | Moonrise `processTicketUpdates` / 各 world 的 global chunk tasks |
| 世界内容本身 | **区域 tick** | 实体、方块、方块实体、红石、连接（归属该区域的） |

**对 CeruLean 的结论**：区域化不是"把整个世界并行化"，而是**把世界内容并行化、把世界级共享状态留在全局串行**。我们要做的 OCC 语义补全，正好落在"同一时刻有两个区域各自在推自己那部分世界"这条缝上——而**时间基准（红石时间）与票据/加载时序都由上面这套全局机制驱动**，这是设计 OCC 时必须先确定的两条约束。

## 9. 进度

00–08 课完成。剩余：
- **09** `RegionizedTaskQueue`（863 行）：跨区域任务队列与区块任务优先级
- **10** `scheduler/FoliaRegionScheduler`、`TeleportUtils`、`RegionShutdownThread`、`util/*`、`commands/*`
- **11** 187 个 hook 点（`ServerLevel` 62 hunk、`MinecraftServer` 40、`Entity` 27、`Level` 25 …）
