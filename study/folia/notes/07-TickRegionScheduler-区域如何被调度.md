# 07 · TickRegionScheduler：区域怎么被排到线程上

> 源码：`TickRegionScheduler.java` 精读范围 `31–562`（全类）。本课把"区域"从数据结构变成"真的在跑"。

## 1. 时间常量与两种调度器（L43–85）

```java
public static final int  TICK_RATE = 20;
public static final long TIME_BETWEEN_TICKS = 1_000_000_000L / TICK_RATE;   // 50 ms
```

| `SchedulerType` | 实现 | 特点 |
|---|---|---|
| `EDF`（配置默认） | `EDFSchedulerThreadPool`（来自 leafpile 库） | 最早截止优先；构造时定线程数，`setThreads` 对它无效 |
| `WORK_STEALING` | `StealingScheduledThreadPool` | **NUMA 感知**；`FLAG_SCHEDULE_EVENLY`；窃取阈值 **3 ms**、任务时间片 **2 ms** |

线程工厂（L54–67）里有一条平台相关的注释值得记：

> "on Linux, thread affinity is copied from the parent thread - but we do not want that, so we need to adjust the thread affinity of child threads；the group allows the numa instance to accurately collect the child threads"

即：**线程亲和性要从父线程剥离，并用 `ThreadGroup` 包住**，好让 NUMA 实例能准确收集它们。这是"区域化 tick 线程"作为一类特殊线程的物理基础。

`setThreads()`（L93–119）：非 NUMA → 全部线程给节点 0；NUMA → 按节点均分（`max(1, threads/nodes)`），并打日志 "Folia is using N tick threads per NUMA node…"。

> 与 00 课的配置链对上：`GlobalConfiguration.threadedRegions.threads = -1` → `TickRegions.getTickThreads()` 自动算（cores/2，若 ≤4 则 1，否则再 /4）→ 传到这里。

## 2. 上下文机制：**用自定义 Thread 子类代替 ThreadLocal**（L260–266）

```java
// By using our own thread object, we can use a field for the current region rather than a ThreadLocal.
// This is much faster than a thread local, since the thread local has to use a map lookup.
private static final class TickThreadRunner extends TickThread {
    private ThreadedRegion<...> currentTickingRegion;
    private RegionizedWorldData currentTickingWorldRegionizedData;
    private SchedulableTick currentTickingTask;
}
```

于是三个静态访问器都是**直接读线程字段**（L162–193）：`getCurrentRegion()`、`getCurrentRegionizedWorldData()`、`getCurrentTickingTask()`。

两个细节：

- **非 tick 线程有回退**：`getCurrentRegion()` 在非 `TickThreadRunner` 上会返回 `RegionShutdownThread.getRegion()` —— 即**关服线程被当作一种伪区域上下文**（否则关服路径里大量"取当前区域"的代码会直接抛异常）；
- **set/unset 纪律严格**：重复 set、重复 unset 都抛异常（L130–135、L149–154），与 regioniser 的锁递归检查同一种风格。

> 这条设计对 CeruLean 有直接启示：**"当前区域"是隐式上下文**（05 课 `RegionizedData.get()` 就靠它）。任何在区域 tick 之外执行的代码都不会有区域上下文 —— 我们若写跨区域逻辑，必须先明确"我现在在哪个上下文里"。

## 3. 一次区域 tick 的完整流程（`runTick`，L393–481）

```
runTick()
├─ 若已标记不可调度 → 直接返回 false
├─ tickCount = max(1, tickSchedule.getPeriodsAhead(TIME_BETWEEN_TICKS, tickStart))   ← 落后就补 tick
├─ tryMarkTicking()   ← 走 regioniser 的写锁；要求 READY 且无挂起合并（02/03 课）
├─ 设置线程上下文（currentTickingTask / currentTickingRegion）
├─ currentTick++；组装 TickTime 采样
├─ tickRegion(tickCount, tickStart, scheduledEnd)     ← 真正的世界 tick
├─ finally:
│    · tickSchedule.advanceBy(tickCount, TIME_BETWEEN_TICKS)
│    · setScheduledStart(max(tickEnd, tickSchedule.getDeadline(...)))   ★
│    · 记录 tick 耗时（5s/15s/1m/5m/15m 五个窗口 + CPU 时间）
│    · 清空线程上下文
└─ return markNotTicking() && !cancelled      ★★ 区域在这里被"释放"
```

两处 ★ 是重点：

- **★ 防饿死**（L460–462 注释）：下一个起点取 `max(当前时刻, 理想截止时间)`，理由是
  "in order to ensure all regions get their chance at scheduling, we have to ensure that regions that exceed the max tick time are not always prioritised over everything else" —— 慢区域不能一直插队。
- **★★ `markNotTicking()` = 区域释放点**：它触发 02 课的 `onRegionRelease` → **合并/死段回收/分裂判定都发生在每次区域 tick 结束时**。也就是说：

> **区域拓扑的重算频率 = 该区域的 tick 频率**（不是"偶尔整理一次"）。

这一点此前只是从 regioniser 内部推断的，现在由调度器确认了。

`runTasks()`（L342–391）是同一套纪律用于"中间任务"（任务队列排空）：同样 `tryMarkTicking` → 设上下文 → `runRegionTasks(canContinue)` → 清上下文 → `markNotTicking()`。差别是没有 tick 采样、且 `canContinue` 由调度器与取消标记共同决定。

## 4. 失败模型：**fail-stop（区域出错就关服）**

| 位置 | 触发 | 行为 |
|---|---|---|
| `uncaughtException`（tick 线程的未捕获异常） | 线程级 | 记日志 → `scheduler.halt()` → `MinecraftServer.getServer().stopServer()` |
| `regionFailed(handle, executingTasks, thr)`（tick 或任务抛 Throwable） | 区域级 | 记日志（**区域 id + 中心区块 + world + 是 tick 还是任务**）→ `halt()` → **停服** |

两处注释都写了原因：**"we CANNOT sync, because WE ARE ON A SCHEDULER THREAD"**。

**含义（对 CeruLean 很重要）**：
- 区域化没有"某个区域坏了就隔离它"的机制 —— **一个区域抛异常 = 整服优雅停机**；
- 换言之，**区域内的任何 bug 都是全服可用性问题**。这提高了对"区域化代码不得抛异常"的要求（正好呼应 05 课 `merge` 的"不得抛"约束）；
- 日志里带上了 `region.getCenterChunk()`（03 课那个方法），所以定位区域位置依赖它 —— 也说明 03 课发现的 bug 虽不影响正确性，但**出现在排障路径上**。

## 5. `RegionScheduleHandle`：区域调度的抽象（L273–561）

每个区域（以及全局 tick）都有一个 handle，持有：`currentTick`/`lastTickStart`、五个窗口的 `TickData`、`intermediateTaskTime(+CPU)`、`currentTickingThread`、`region`、`cancelled`（`AtomicBoolean`）、`tickSchedule`（`ca.spottedleaf.common.time.Schedule`）。

子类必须实现（L326–334）：`tryMarkTicking` / `markNotTicking` / `tickRegion` / `runRegionTasks` / `hasIntermediateTasks`。

`tickSchedule` 与 `setScheduledStart` 必须同步推进，所以提供了 `updateScheduledStart()` 强制成对更新（L309–316 注释说明原因）。

TPS 报告（L514–542）按 5s/15s/1m/5m/15m 五个窗口生成 —— **`/tps` 输出的"last 1m, 5m, 15m"就来自这里，而且是每区域独立的**。

## 6. 本课小结

| 问题 | 答案 |
|---|---|
| 区域怎么跑起来 | 每区域一个 `RegionScheduleHandle`，交给 EDF 或工作窃取线程池 |
| "当前区域"怎么来的 | 自定义线程类的字段（比 ThreadLocal 快）；关服线程有伪上下文回退 |
| 拓扑何时重算 | **每次 tick 结束的 `markNotTicking()`**，即随 tick 频率发生 |
| 落后了怎么办 | `tickCount` 可 >1（一次追上多个 tick）；且下一轮起点用 `max(now, 理想)` 防饿死 |
| 出错怎么办 | **fail-stop**：记日志（区域 id/中心区块/world）→ 停服 |

**下一课（08）**：`RegionizedServer` —— 全局 tick 循环，以及"哪些事必须全局做、哪些必须按区域做"的分工。
