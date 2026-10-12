# 04 · ThreadedRegionSection：段的生命周期与"空段为什么必须存在"

> 源码：`ThreadedRegionizer.java` 精读范围 `1023–1246`。
> 本课回答两个问题：① 一个段内部怎么表示"我有哪些区块"；② 段的"生/死"由什么决定，以及**为什么空段不能被立刻删掉**。

## 1. 段的内部表示（L1023–1040）

```java
public  final int sectionX, sectionZ;
public  final long sectionKey;          // CoordinateUtils.getChunkKey(sectionX, sectionZ)
private final long[] chunksBitset;      // 段内区块的位图
private int chunkCount;                 // 段内区块数（与位图冗余，但 O(1) 判空）
private int nonEmptyNeighbours;         // 有多少个"非空邻居段"
private ThreadedRegion<R,S> region;     // 当前归属区域
private static final VarHandle REGION_HANDLE = ConcurrentUtil.getVarHandle(...);
private final S data;                   // 接入方的段数据
private final int regionChunkShift, regionChunkMask;
```

位图容量 = `max(1, regionSectionChunkSize² / 64)` 个 long。以 `gridExponent = 4`（段 = 16×16 区块）为例：256 bit = **4 个 long**。
段内线性索引：`getChunkIndex(x, z) = (x & mask) | ((z & mask) << shift)`（L1143）。

**`region` 字段用 `VarHandle` 的 release/acquire 语义**访问（L1042–1052）：

| 方法 | 语义 |
|---|---|
| `getRegionPlain()` | 普通读（仅在持锁路径使用） |
| `getRegionAcquire()` | acquire 读 —— 供 `getRegion()` 使用 |
| `setRegionRelease(v)` | release 写 —— 变更归属时使用 |

`getRegion()` 的注释很诚实：**"Unsynchronised access may produce outdated or transient results."** 即从外部读到的归属可能是过期或过渡态的值 —— 与 02 课"分裂/合并只在释放点发生"的结论一致。

## 2. 三个构造函数（L1055–1083）

| 构造 | 用途 |
|---|---|
| `(sectionX, sectionZ, regioniser)` | 空段，邻居计数 0 —— 由 `addChunk` 的邻居扫描创建 |
| `…, chunkXInit, chunkZInit)` | 带一个初始区块（`chunkCount = 1` 并置位） |
| `…, nonEmptyNeighbours)` | 空段但邻居计数为 N |

三个构造都会通过 `callbacks.createNewSectionData(...)` 创建 `S` —— 同样是在持锁路径上（段只在写锁路径里被创建）。

## 3. 生/死判定：`chunkCount` 与 `nonEmptyNeighbours` 的联动

```java
incrementNonEmptyNeighbours():   // 邻居变多
    if (++n == 1 && chunkCount == 0) markAlive();      // 空段重新"活着"
    if (n >= (2r+1)²) throw …                          // 上限保护（r = emptySectionCreateRadius）

decrementNonEmptyNeighbours():   // 邻居变少
    if (--n == 0 && chunkCount == 0) markDead();       // 空且无邻居 → 标记死亡
    if (n < 0) throw …

addChunk(...):   // 置位；若 0→1 且 nonEmptyNeighbours == 0 → markAlive()
removeChunk(...): // 清位；若 1→0 且 nonEmptyNeighbours == 0 → markDead()
```

由此得到段的核心不变量：

> **一个段"活着" ⟺ `chunkCount > 0` 或 `nonEmptyNeighbours > 0`。**
> 换句话说：**段内没有区块、但只要旁边还有非空段，它就必须活在图里。**

`markAlive()`/`markDead()` 只是把自己加进/移出所属区域的 `deadSections` 集合（L1147–1153）—— 真正决定"删不删"的是第 02 课 `onRegionRelease` 里的回收条件。

### 为什么空段不能立刻删？——这是 `emptySectionCreateRadius` 的真正含义

如果区块被移除就立刻删掉空段，会出现两个问题：

1. **邻接图断裂**：区域是"段的连通分量"，段被删掉后，原本相邻的两个区域会在图上"断开"，从而被误判为可以分裂；
2. **抖动（thrashing）**：区块频繁加载/卸载时，区域会被反复合并-分裂，每次都要迁移世界状态。

保留"空壳段"起到两个作用：
- 维持**邻接关系**的连续性（这也是 `incrementNonEmptyNeighbours` 的意义：空段知道"我旁边有活的"）；
- 让**新出现的区块自动落回同一个区域**（而不是另立门户）。

而"什么时候才真的把空壳删掉"就交给两个旋钮：`minSectionRecalcCount` + `maxDeadRegionPercent`（02 课）。

## 4. 无锁快路径为什么安全（本课最有价值的推理）

02 课记过两处无锁快路径，现在可以完整证明它们**为什么不会破坏共享状态**：

| 快路径 | 进入条件 | 段状态变化 | 是否可能触碰区域状态 |
|---|---|---|---|
| `addChunk` 快路径 | 段已存在**且非空**（`!isEmpty()`，即 `chunkCount > 0`） | 只可能 `chunkCount` 从 ≥1 加到 ≥2 | `addChunk()` 内只有"0→1 且邻居为 0"才 `markAlive()` → **不可能发生** |
| `removeChunk` 快路径 | 段内区块数 > 1（`!hasOnlyOneChunk()`） | 只可能从 ≥2 减到 ≥1 | `removeChunk()` 内只有"1→0 且邻居为 0"才 `markDead()` → **不可能发生** |

也就是说：**快路径被允许的条件，恰好是"本操作不可能改变段的存活状态"**。因此它既不需要写锁、也不会调用 `markAlive/markDead`，更不会碰到 `region`。

这是"把一个并发优化做成可证明安全"的漂亮例子：**不是先写快路径再论证，而是先找出不变量，再用不变量划出快路径的边界。**

## 5. 防御性编程一览（读的时候可以对照 01 课的"宁可炸掉"）

| 场景 | 行为 |
|---|---|
| 往段里加已存在的区块 | 抛 `IllegalStateException`（附段与区块坐标） |
| 从段里移除不存在的区块 | 抛 `IllegalStateException` |
| `nonEmptyNeighbours` 降到负数 | 抛异常 |
| `nonEmptyNeighbours` 超过 `(2r+1)²` 上限 | 抛异常 |
| 段已有归属区域却又 `addSection` | 抛异常（`addSection` 里检查 `getRegionPlain() != null`） |
| `toString` 打印位图 | 手工十六进制零填充（没有用 `String.format`，热路径避免格式化开销） |

## 6. 阶段小结：区域与段，我们已知什么

| 维度 | 区域（Region） | 段（Section） |
|---|---|---|
| 角色 | **调度单位**（tick 在区域上并行） | **归属与邻接的最小单位** |
| 内部状态 | 4 态 + 两个合并握手集合 + `sectionByKey`/`deadSections` | 位图 + `chunkCount` + `nonEmptyNeighbours` + `region`(release/acquire) |
| 生命周期 | `TRANSIENT` 出生 → `READY` → `TICKING` ⇄ `READY` → `DEAD` | 活着 ⟺ 有区块或有非空邻居；死亡由区域在释放点回收 |
| 何时变化 | 只在 `addChunk` 与 `onRegionRelease`（**释放点**） | 随区块增删，但存活状态变化只可能在"非快路径"上发生 |

**下一课（05/06）**：`RegionizedData` + `RegionizedWorldData` —— 终于要回答那个最实际的问题：**分裂/合并时，实体、方块实体、连接这些世界状态到底怎么跟着走**。这既是 Folia 的实现细节，也是我们那条"Folia 提供分隔、不提供跨分隔一致性"结论的落点。
