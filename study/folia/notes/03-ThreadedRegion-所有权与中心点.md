# 03 · ThreadedRegion：所有权查询、中心点启发式，与一处真实 bug

> 源码：`ThreadedRegionizer.java` 精读范围 `674–805`（第 02 课已覆盖 `805–994` 的生命周期部分）。
> 本课补完区域"对外能回答什么"，并记录一处**在阅读中发现、经数值验证的缺陷**。

## 1. 字段与构造（L674–702）

```java
private static final AtomicLong REGION_ID_GENERATOR = new AtomicLong();   // 全局单调 id
public  final long id;
private int state;                                                        // 4 态（见 02 课）
private final Long2ReferenceOpenHashMap<ThreadedRegionSection<R,S>> sectionByKey;  // 本区域拥有的段
private final ReferenceOpenHashSet<ThreadedRegionSection<R,S>> deadSections;       // 其中已死待回收的段
public  final ThreadedRegionizer<R,S> regioniser;
private final R data;                                                     // 接入方的区域数据
private final ReferenceOpenHashSet<ThreadedRegion<R,S>> mergeIntoLater;    // 合并握手（见 02 课）
private final ReferenceOpenHashSet<ThreadedRegion<R,S>> expectingMergeFrom;
```

构造里的两个细节值得注意：

```java
this.state = STATE_TRANSIENT;                          // 出生即 TRANSIENT，不是 READY
this.data = regioniser.callbacks.createNewData(this);   // 数据在构造期创建（此时持写锁）
```

- **新区域初始是 `TRANSIENT`**：只有 `addChunk`/`onRegionRelease` 走完全部流程后，才会被置为 `READY` 并回调 `onRegionActive`。也就是说"创建"≠"可用"，中间必须经过显式的状态提升。
- **`createNewData` 回调在构造函数里发起**——因为构造函数只会在持写锁的路径上被调用（`addChunk` 慢路径 / `onRegionRelease` 分裂），这正好满足 01 课那条"回调在锁内、不得碰世界状态"的契约。

## 2. 所有权查询：一个"自我感知锁"的小技巧（L704–746）

```java
public LongArrayList getOwnedSections() {
    final boolean lock = this.regioniser.writeLockOwner != Thread.currentThread();  // ← 关键
    if (lock) { this.regioniser.regionLock.readLock(); }
    try { ... return this.sectionByKey.keySet() 的快照 ... }
    finally { if (lock) { this.regioniser.regionLock.tryUnlockRead(); } }
}
```

`StampedLock` **不可重入**，所以内部类必须能判断"当前线程是否已经持有写锁"，否则会自己把自己锁死。做法是把 `writeLockOwner`（01 课见过的写锁持有者标记）暴露给内部类使用。
这类查询方法有三个，语义逐级放宽：

| 方法 | 同步性 | 用途 |
|---|---|---|
| `getOwnedSections()` | 若无写锁则加读锁 | 通用查询，返回段 key 的 `LongArrayList` |
| `getOwnedSectionsUnsynchronised()` | **完全不加锁**（直接给迭代器） | 注释明确：**只能由正在 tick 本区域的线程使用** |
| `getOwnedChunks()` | 同 `getOwnedSections()` | 把每个段展开成区块 key 列表 |

## 3. 中心点：一个"取中位数"的启发式（L748–803）

`getCenterSection()` / `getCenterChunk()` 的做法一致：把（段/区块）key 排序，然后取 `list.get(size >> 1)`——**中位数**。排序规则是 `(z, x)` 先比 z 再比 x。

这两个方法是**启发式**而不是精确几何中心：注释也承认"区域在某些时刻可能已经没有段/区块了（区域可能已被 kill）"，所以都会先判空再取中位数。

## 4. ★ 发现一处真实 bug：`getCenterSection()` 的比较器传错了参数

两个方法的比较器几乎逐字相同，但**关键那一行不一样**：

```java
// getCenterSection()  L751–764
final int x1 = CoordinateUtils.getChunkX(k1);
final int x2 = CoordinateUtils.getChunkX(k2);
final int z1 = CoordinateUtils.getChunkZ(x1);   // ← 传的是 x1（应为 k1）
final int z2 = CoordinateUtils.getChunkZ(x2);   // ← 传的是 x2（应为 k2）

// getCenterChunk()    L779–792
final int z1 = CoordinateUtils.getChunkZ(k1);   // ← 正确
final int z2 = CoordinateUtils.getChunkZ(k2);
```

**为什么这是错的**（依据 `ca/spottedleaf/moonrise/common/util/CoordinateUtils.java`）：

```java
getChunkKey(x, z) = ((long)z << 32) | (x & 0xFFFFFFFFL)
getChunkX(key)    = (int)key              // 低 32 位 = x
getChunkZ(key)    = (int)(key >>> 32)     // 高 32 位 = z
```

把已经提取出来的 `x1`（一个 int）再当 key 传给 `getChunkZ`：`x1` 被提升为 long 后 `>>> 32` ——
- `x1 ≥ 0` → 结果恒为 **0**
- `x1 < 0` → 结果恒为 **-1**

**数值验证**（用同一套位运算模拟）：

| 段坐标 | 正确 z | bug 写法算出的 z |
|---|---|---|
| (0, 0) | 0 | 0 |
| (1, 5) | 5 | **0** |
| (10, -3) | -3 | **0** |
| (-7, 9) | 9 | **-1** |
| (100, 100) | 100 | **0** |
| (3, -20) | -20 | **0** |

**结论**：`getCenterSection()` 的排序主键退化为常数，**整个排序实际只按 x 排**（z 变成并列），于是"中心段"退化为"按 x 中位数选出的段"。
`getCenterChunk()` 不受影响（它写对了），所以同一个类里两个几乎一样的方法行为不一致。

**影响评估（不夸大）**：
- 用途是**中心启发式**（用于挑一个代表性段/区块），不是关键正确性路径；
- 但任何依赖"区域中心"的行为（例如以区域中心为参照的判定/统计）在**跨 z 方向拉长的区域**上会与预期不同；
- 对 CeruLean 的直接意义：**这是一份人会写错、机器不会提醒的代码**——移植时若照抄这两个方法，务必顺手修掉；也说明"上游代码不一定对"这个判断是成立的。

## 5. 小结与下一步

- 区域"对外"的能力很有限：报 id、报状态、报拥有的段/区块、报中心点、访问 `data`——**其余一切都要通过回调或数据类**。这个窄接口正是 01 课"拓扑与世界解耦"设计的延续。
- 下一课（04）看段本身：位图、`nonEmptyNeighbours` 计数与"生/死"判定，以及**为什么空段也要留在图里**。
