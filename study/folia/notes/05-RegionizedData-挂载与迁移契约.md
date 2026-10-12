# 05 · RegionizedData：区域化数据的挂载方式与迁移契约

> 源码：`RegionizedData.java`（236 行，全读）。
> 这个类的 **Javadoc 本身就是一份设计文档**——正好补上 `REGION_LOGIC.md` 只是跳转占位留下的空缺。

## 1. 定位：它相当于"按区域划分的 ThreadLocal"

类注释第一句就是它的自我定位：**"Use to manage data that needs to be regionised."**
与 `ThreadLocal` 的关键差别被明确写出来：

> 不像 `ThreadLocal`，区域化数据**不会**因为 `RegionizedData` 对象被 GC 而消失 —— 数据被它所在的 world/region **持有引用**。

三个字段把它的语义说完（L99–101）：

```java
private final ServerLevel world;                                  // 可为 null → 关闭 world 检查
private final Function<TickRegions.TickRegionData, T> initialValueSupplier;  // 每区域懒初始化
private final RegioniserCallback<T> callback;                     // 自己怎么被 merge/split
```

## 2. 取数路径（L143–156）

```java
public @Nullable T get() {
    final ThreadedRegion<...> region = TickRegionScheduler.getCurrentRegion();
    if (region == null) return null;                                   // 不在区域 tick 上下文里
    if (this.world != null && this.world != region.getData().world)
        throw new IllegalStateException("World check failed: …");       // world 检查
    return region.getData().getOrCreateRegionizedData(this);
}
```

三个要点：

1. **"当前区域"是隐式上下文**（`TickRegionScheduler.getCurrentRegion()`）——所以这类数据只能在区域 tick 线程里取，取不到就返回 `null`；
2. **world 检查是可选的**，由构造时是否传 `world` 决定。注释给了判定标准（L93–94）：**"凡是按 world 实例化的数据，就应该开启 world 检查"**；
3. **挂载点是 `TickRegionData.getOrCreateRegionizedData(this)`**：以 `RegionizedData` 实例为 key，在该区域的数据表里懒创建（见第 4 节）。

## 3. 什么时候可以用 `world = null`（注释里的两个例子）

Javadoc 给了一组对照示例，判定标准是**数据的归属粒度**：

| 数据 | 应否按 world 实例化 | 理由 |
|---|---|---|
| 实体 tick 列表 | **必须**（传 world） | 实体同时绑定 world **和** region |
| "本 tick 结束时执行的任务" | 可以（传 null） | 只绑定 region，与 world 无关 |

还有一条容易被忽略的前提（L17–19）：

> **当区域化 tick 被关闭时，整个服务器被视为一个区域。**
> 也就是说数据可能跨 world ——因此该按 world 实例化的数据**必须**按 world 实例化。

这解释了一个此前没注意到的语义：**回退模式（单线程）下"区域"的粒度是整个服务器，不是单个 world**。移植时若把"区域 = world"当成前提，在关闭区域化时就会错。

## 4. 挂载机制（在 `TickRegions.TickRegionData` 里，L162–185）

```java
public static final class TickRegionData implements ThreadedRegionizer.ThreadedRegionData<TickRegionData, TickRegionSectionData> {
    private static final AtomicLong ID_GENERATOR = new AtomicLong();
    public final long id = ID_GENERATOR.incrementAndGet();   // 永不 0：0 保留给"全局区域"
    public final ServerLevel world;
    private final Reference2ReferenceOpenHashMap<RegionizedData<?>, Object> regionizedData = …;  // ← 挂载表
    private ConcreteRegionTickHandle tickHandle = …;         // 调度截止时间（EDF）
    private final RegionizedTaskQueue.RegionTaskQueueData taskQueueData;
    private final ChunkHolderManager.HolderManagerRegionData holderManagerRegionData;  // ← Moonrise 的每区域数据
    private final RegionStats regionStats;                    // 异步安全的只读统计（供 profiler）
}
```

**`regionizedData` 这张表就是扩展点**：任何"按区域划分的状态"都以 `RegionizedData<T>` 实例为 key 挂进来。
→ 对 CeruLean 的直接意义：**我们自己的区域化状态（比如 OCC 的阶段快照/读集）应该用同一个机制挂载**，才能自动获得下面的迁移契约。

## 5. 迁移契约：`RegioniserCallback<T>`（L164–234）

这是"数据怎么跟着区域走"的接口。两个方法，语义差别很关键：

### `merge(T from, T into, long fromTickOffset)`

Javadoc 里有一段**完整推导**（这是整个 Folia 源码里少见的"把数学写清楚"的地方）：

> 区域独立 tick → **绝对 tick 截止时间在不同区域之间不可比**。
> 设 from/to 区域的绝对截止时间为 `deadlineFrom`/`deadlineTo`，当前 tick 为 `currentTickFrom`/`currentTickTo`；
> 相对时间 `relFrom = deadlineFrom - currentTickFrom`；
> 要让它在新区域里保持同样的相对时间：`deadlineTo = relFrom + currentTickTo`
> 代入即得：**`deadlineTo = deadlineFrom + (currentTickTo - currentTickFrom)`**
> 也就是可以只用一个偏移量 `fromTickOffset = currentTickTo - currentTickFrom`。

失败语义写得更重（L188–190）：

> **"This function may not throw any exceptions, or the server will be left in an unrecoverable state."**

即：**合并过程抛异常 = 服务器进入不可恢复状态**。这解释了为什么 02 课里那些回调路径全是"宁可抛也不静默错"，而这里反过来——**在真正迁移数据时，必须保证不抛**（要在一个已经不可回退的临界区里把活儿干完）。

### `split(T from, int chunkToRegionShift, Long2ReferenceOpenHashMap<T> regionToData, ReferenceOpenHashSet<T> dataSet)`

- 参数 `regionToData`：**"段 key → 拥有该段的那个新区域的数据"** 的查表；
- 数据的迁移方式由此确定：**逐元素按自身坐标算出段 key，再查表决定该去哪个新区域**（06 课会看到它被具体怎么用）；
- Javadoc 明确说明**分裂不需要 tick 偏移**：新区域从同一个 tick 号开始。

## 6. 本课结论

| 问题 | 答案 |
|---|---|
| 区域化状态挂在哪 | 挂在区域数据 `TickRegionData.regionizedData` 表上，以 `RegionizedData` 实例为 key |
| 怎么取 | `RegionizedData.get()`：从当前 tick 区域的上下文取，可带 world 检查 |
| 怎么跟着区域走 | 实现 `RegioniserCallback.merge/split`，由区域数据层统一分发 |
| 合并时最危险的是什么 | **绝对不能抛异常**（数据已在迁移途中，无法回退）；另外 tick 截止时间必须按 `fromTickOffset` 平移 |
| 关闭区域化时是什么语义 | **整个服务器 = 一个区域** |

**下一课（06）**：看 `RegionizedWorldData` 这个"最大的 T"——把 world 的全部可变状态按区域拆开后，`merge`/`split` 具体怎么搬，以及**哪些查表可能失败、失败时数据是"丢掉"还是"不可能发生"**。
