# regionizer-core（Phase 2 · C1）

区域化拓扑内核：**零 Minecraft 依赖、零第三方依赖**，用纯 JDK 直接编译运行。

```sh
./run-tests.sh          # 编译 main（-Xlint:all -Werror）+ 编译测试 + 运行全部断言
JAVA_HOME=/path/to/jdk25 ./run-tests.sh
```

## 为什么把它单独成层

上游把区域化内核直接写进 Minecraft 源码树，于是拓扑逻辑**只能靠端到端冒烟来验证**（见 `study/folia/notes/00`）。
这里把它抽成纯 Java 层：分裂/合并这类纯逻辑可以用**模型检验**（与朴素参考实现逐步比对）穷举验证，毫秒级、可回归。
MC 侧在 C2 只负责"接线"，不负责证明拓扑正确性。

## 语义（C1 冻结）

| 规则 | 内容 |
|---|---|
| **S1 存活（闭式、无状态）** | 存活段 = **有区块的段** ∪ **这些段的四邻**。为维持该集合，`addChunk` 会**物化**该段的四个四邻段（即使还没有区块）——否则相距 2 的两段之间缺少"桥接段"，区域会被不必要地切碎（这正是上游 `createRadius` 的作用；本实现固定为 1，一级存活规则下更大取值无意义）。 |
| **S2 划分** | 区域 = 存活段在 **Chebyshev 距离 ≤ `mergeRadius`** 邻接下的连通分量。 |
| **S3 变更时机** | 段集只在两处变化：`addChunk`（挂载/吸收）与 `markNotTicking`（**释放点**：收敛 → 回收死段 → 必要时分裂）。`removeChunk` **绝不**改变拓扑。 |
| **S4 TICKING 不可变** | TICKING 区域的**段集**不可变（不增段、不被吸收、不分裂）。区块**计数**可变（区块加载必须能发生）。 |

## 不变量与校验时机

| 不变量 | 内容 | 何时校验 |
|---|---|---|
| I1 | 段整体且只属于一个区域，反向指针一致 | 任何时刻 |
| I2 | TICKING 期间段集不变（用进入 TICKING 时的快照比对） | 任何时刻 |
| I5 | `nonEmptyNeighbours` == 四邻中"有区块"的实际个数（增量维护易错，故显式校验） | 任何时刻 |
| — | 区块总数 == 各段区块数之和；无"存活但无区域"的孤儿段 | 任何时刻 |
| **I3'** | 已收敛：READY 区域在 `mergeRadius` 内不存在属于其它区域的存活段 | **稳定态** |
| **I6** | 死段已回收，不再属于任何区域 | **稳定态** |

> 释放点之前"死段仍挂在区域上"是**正常**的（S3）：回收只发生在释放点。
> `verifyInvariants()` 校验任何时刻成立的部分；`verifyInvariants(true)` 额外校验 I3'/I6。

## 与上游实现的不同（协议修订）

> **原则（docs/06 附二）**：默认沿用上游已验证的机制，只有存在具体、可复核的证据时才偏离。
> 下面第 1 条是"暂缓采纳、待数据决定"，第 2 条是"有意偏离"，其余采纳项已列入 C3 清单。


1. **合并采用"谁释放谁吸收"**：释放点的邻接扫描 + 挂载时的即时吸收（等价于上游的 `killAndMergeInto` 即时路径），
   **不设 `mergeIntoLater` / `expectingMergeFrom` 握手集合**。
   **更正（2026-10-12 复核）**：早先我在笔记与提交信息里写过"上游此协议存在互相等待对方释放的结构性风险"——**该结论是错的**，我未读到
   `ThreadedRegion.killAndMergeInto` / `tryKill` 就下了判断。事实是：`killAndMergeInto` **只对 TICKING 区域返回 false**，
   因此"挂起合并"的目标**必然是一个 TICKING 区域**，而 TICKING 区域每 tick 都会释放，并在释放时把挂起源吞并掉（TRANSIENT 与 READY 都可被吞并）；
   链式情形由 `mergeInto` 的转发逻辑处理。**上游协议是有意设计的、无死锁的。**

   那么我们为什么仍然去掉握手状态？理由改为两条更实在的：
   ① 它把"协议正确性"寄托在 5 处断言共同维护的不变量上（`onRegionRelease` 断言 `mergeIntoLater` 必须为空、
      吞并后断言 `expectingMergeFrom` 必须清空、`mergeIntoLater` 断言两侧集合必须互为镜像…）；
   ② 我们的目标是"**稳定态划分 = 区块集合的纯函数**"——只有把协议简化到没有挂起状态，这条性质才成立，
      而它正是**模型检验可行的前提**。
   代价也要说清：本实现每次释放都要做一次邻域扫描（O(段数 × (2r+1)²)），比上游"只处理已登记的合并"更贵；
   上游另有"即将被吞并的区域不再 tick"这一省事优化，我们没有。两者都属 **C3 的性能优化**，引入时以模型检验为回归网。
2. **死段回收与分裂暂用精确语义**（每次释放都重算）；上游的阈值门控（`minSectionRecalcCount` / `maxDeadRegionPercent`）
   与 `deadSections` 集合属**已决定采纳的优化项**，落点 C3——因为它是大区域下的主要每 tick 成本项。
   引入方式：**以本层精确实现为参照物做自洽差分**（同一条操作序列，只在阈值触发点比对划分），
   参照物已由 113 项断言与模型检验背书，无需转写上游代码。

另：原设计规格里的 I3/I4（READY 必须无挂起合并 / 握手集合互为镜像）随握手移除，由 **I3'（已收敛）** 取代。

## 状态迁移契约（docs/06 §7）

```java
default void mergeState(D from, D into, long tickOffset)   // tickOffset = into.tickCount - from.tickCount
default void splitState(D from, int regionShift, Map<Long, D> sectionKeyToData, Collection<D> newData)
```

`splitState` 的映射表**覆盖所有存活段**：留在原区域的段映射到 `from` 自身，被迁走的映射到对应新状态；
**表中不存在 = 该段已消亡（死段被回收）⇒ 它的状态必须丢弃**。
回调全部在区域化器临界区内调用：不得阻塞、不得回调进区域化器、`mergeState`/`splitState` **不得抛异常**。

## 结构

| 文件 | 职责 |
|---|---|
| `SectionKey` | 段坐标 ↔ 打包 key 的纯数学（含负数取整、Chebyshev 距离） |
| `Section` | 段：区块计数、非空邻居计数、存活判据、所属区域指针 |
| `Region` | 区域：状态、段集、tick 基准、区域自有状态 `data` |
| `RegionState` | TRANSIENT / READY / TICKING / DEAD |
| `RegionizerConfig` | `regionShift`、`mergeRadius`（**语义参数**，非性能旋钮） |
| `RegionizerCallbacks` | 生命周期与状态迁移回调 |
| `Regionizer` | 引擎：`addChunk` / `removeChunk` / `getRegionAt` / `tryMarkTicking` / `markNotTicking` / `verifyInvariants` |
| `test/ReferenceModel` | 朴素参考实现（oracle）：全量重算闭式存活集合与连通分量 |
| `test/Harness` | 稳定化（让每个区域经历释放点）与划分提取 |
| `test/InvariantTests` | 定向用例：闭式存活、S3、桥接、分裂、合并、TICKING 不可变、状态迁移 |
| `test/ModelCheckTests` | 模型检验：随机操作序列逐步与 oracle 比对 |

## 测试覆盖与结果

- **113 项断言全部通过**（证据：`evidence/test-run.txt`）。
- 模型检验：`shift ∈ {4,2}` × `mergeRadius ∈ {1,2,3}` × 6 个随机种子 × 150 步随机 add/remove = **36 轮 / 5,400 次操作**，
  每步都校验不变量，并在**稳定化后**与 oracle 比对区域划分与区块总数。
- 失败时会打印操作历史，便于最小化复现。

## 线程安全现状（诚实说明）

当前实现是**线程安全但非无锁**：变更走 `StampedLock` 写锁，查询走读锁，内部 `HashMap` 只在锁内访问。
乐观读（`tryOptimisticRead` + 校验）需要配合**无锁段表**，属 **C3**，与区域调度、段级票据一并引入。

## C2 的接线面（来自 study/folia/notes/11 的施工图）

| 接线点 | 内容 |
|---|---|
| `BaseChunkSystemHooks.onChunkHolderCreate/Delete` | → `addChunk` / `removeChunk`（全部拓扑驱动的源头，2 行） |
| `ServerLevel` 构造 | 持有 `Regionizer`（每 world 一个实例，替代上游的静态单例） |
| `Level.getCurrentWorldData()` | "当前区域"解析（由 tick 线程上下文提供），用于取区域自有状态 |
| 其余 187 个 hook 点 | 属 C3 的内容并行（线程归属校验 52 / 状态改道 23 / 跨区域派发 23 / …） |
| 仍需新写 | 区域→线程调度、当前区域上下文、`CrossRegion.dispatch` facade、段级票据 |

## 明确未做（避免误解）

- 多 world（一个 `Regionizer` 实例对应一个 world，但 C1 未做 world 级联调）；
- 阈值化重算、`createRadius > 1`、乐观读、无锁段表；
- 并发压力测试：C1 全程单线程验证语义，并发正确性由 C3 的结构约束 + 压力测试保证。
