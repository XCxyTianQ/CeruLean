# 命令方块 EXP 链移植到 CeruLean：可行性、概率与优化建议

> 分析对象：AzureBranches 的命令方块 EXP 系统（`0009`–`0013` 补丁 + `azurebranches-common` 的 9 个命令类 + 17 个源码覆盖层）。
> 目标底座：CeruLean（Paper 26.3 主线 + 我们自己的区域化）。
> **本文只做分析，不含任何实现改动。**

---

## 一、先纠正一个印象：它"不臃肿"是有原因的，但工作量藏在别处

| 层面 | 规模 | 观感 |
|---|---|---|
| Folia 原有补丁（8 minecraft + 7 paper） | 15 个，**零改动** | 看起来很干净 |
| AzureBranches **自建补丁** | **5 个**（0009–0013） | 数量少 → "不臃肿" |
| ↳ 但其中 2 个是**单 hunk 整文件重写** | `0012`：`CommandBlock.java` `@@ -1,228 +1,500 @@`；`0013`：`SetBlockCommand.java` `@@ -1,159 +1,244 @@` | **"1 个补丁"= 把整个文件换掉** |
| `azurebranches-common` 命令类 | **9 个类 / 3,357 行**（PhaseSnapshot 777、EntityLayer 792、ExpChainSupport 579、PhaseValidator 314、ChainHead 246、DeferredAction 228、ScoreLayer 179、Continuation 142、CommandBlockSupport 99） | 不在补丁里，所以不显眼 |
| `azurepatches-src` 覆盖层 | 17 个文件 ≈ 8,300 行（16 个是命令系统） | 整文件覆盖，diff 不可见 |

**换句话说：补丁目录很干净，是因为大量改动被放进了"自有模块"和"全文件覆盖层"。** 这一点在评估移植时必须按真实体量算。

---

## 二、依赖面：整条 EXP 链对 Folia API 的耦合只有 **≈8 个调用点**

实测（逐个符号扫描，非估计）：

| 位置 | 用到的 Folia API | 次数 |
|---|---|---|
| `0012` CommandBlock | `RegionizedServer.getInstance().taskQueue` | 2 |
| `0013` SetBlockCommand | `TickRegions.getRegionChunkShift()` | 1 |
| `0013` SetBlockCommand | `RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(...)` | 1 |
| `0010` ServerLevel（限流） | `TickThread` / `moonrise` | 1 / 1 |
| 17 个覆盖层 | `RegionizedServer` | 2 |
| **`azurebranches-common` 9 个命令类（3,357 行）** | **零外部 import（纯 JDK + 自有 config）** | **0** |

**结论：OCC 协议本体是纯 Java，外部耦合集中在极少数几个点上。** 这是"可复现"的最强证据。

对照 CeruLean 现状（Paper 26.3 主线）：

| 需要的 API | Paper 26.3 主线 | 结论 |
|---|---|---|
| `TickRegions.getRegionChunkShift()` | **已有**（`// placeholder class for Folia`，方法签名一致） | ✅ 直接可用 |
| Folia 调度 API（`RegionScheduler` 等） | **已有**（含 Fallback 实现） | ✅ |
| `TickThread` / Moonrise | **已有** | ✅ |
| `RegionizedServer` | **无** | ❌ 需 Phase 2 自建 |
| `RegionizedTaskQueue.queueOrExecuteTickTask` | **无** | ❌ 需 Phase 2 自建 |
| `ThreadedRegionizer` | **无** | ❌ 需 Phase 2 自建 |

---

## 三、复现可能性

**判断（我的估算，非实测）**：

| 目标 | 可能性 | 依据 |
|---|---|---|
| 语义复现（单线程底座跑通，行为等价） | **≈85%** | 协议本体纯 Java + 外部耦合仅 8 点 + Folia 补丁零改动（无需反推上游改动） |
| 完整跨区域语义（区域分裂/合并下仍正确） | **≈60–70%** | 除区域化器外，还需重做状态作用域（见第四节风险 1） |
| 代码可直接复用比例 | 纯 Java 部分 ~70% 可照搬；ThreadLocal 状态 / 覆盖层 / 补丁钩子 ~30% 需重写 | 同上 |

### ★ 关键判断：**可以解耦成两步走，因此"能不能复现"的风险比看上去低得多**

理由：那 8 个调用点全都是同一件事——*"我要么直接做，要么把这个活儿投递给拥有那块地的区域"*（第 09 课的原语）。因此可以：

1. **把这 8 个点收敛成一个 facade**（例如 `CrossRegion.dispatch(world, cx, cz, run)`）；
2. **Phase 1（单线程 CeruLean）**：facade 永远内联执行 —— 因为只有一个 tick 线程，"同区域"恒为真。
   → **命令方块语义（可挂起链、阶段快照、延迟动作、回滚）现在就能落地并验证**，不需要区域化器；
3. **Phase 2（真区域化）**：只替换 facade 的实现。

**这条路径的额外好处**：Phase 1 落地时正好可以顺手验证我们在第 05 课发现的"命令方块链传播语义未定位"的问题 —— 那本来就是命令方块语义层的事。

---

## 四、三个真实风险（都有代码证据）

### 风险 1：OCC 协议状态用 `ThreadLocal`，**不是区域作用域**

```java
private static final ThreadLocal<List<CompletableFuture<Boolean>>> PENDING_FUTURES = ...
private static final ThreadLocal<Map<Long, List<DeferredEntry>>>    PENDING_DEFERRED = ...
private static final ThreadLocal<PhaseSnapshot>                     PHASE_SNAPSHOT = ...
private static final ThreadLocal<Map<Long, Object>>                 OLD_STATE_CAPTURE = ...
```

代码注释自己承认了这个问题：

> "Deferred remote tasks run on the target region thread, **where the home thread's PhaseSnapshot ThreadLocal is invisible**. The Walker sets this capture map on the remote thread before running the batch…"

于是必须手工"跨线程播种"（`setOldStateCapture`、`EXP5Plus: Seed the PhaseSnapshot onto the current thread`）。

**为什么这在区域化下是隐患**：第 07 课已经确认——**tick 线程是池化的，同一个线程会轮流服务不同区域**。所以 ThreadLocal 既不是"每链"也不是"每区域"作用域：状态可能跨区域泄漏，或在一个区域挂起后线程去服务别的区域时被误读。这也是为什么 EXP5Plus/EXP6/EXP6Plus 一路都在给 ThreadLocal 打补丁。

### 风险 2：两条核心补丁是**整文件重写**，且踩在上游常年改动的文件上

| 补丁 | 形态 | 上游 Paper 是否也改这个文件 |
|---|---|---|
| `0012` → `CommandBlock.java` | 单 hunk 整文件重写（228→500） | **是**（Paper 有 `CommandBlock.java.patch` 等 4 个同族补丁）→ **rebase 必冲突** |
| `0013` → `SetBlockCommand.java` | 单 hunk 整文件重写（159→244） | 否（但版本升级仍会因整文件重写而失效） |

### 风险 3：EXP 是**全局模式开关**

```java
CommandBlockSupport.mode() == CommandBlockSupport.Mode.EXP
```

服务器级开关意味着：同一个世界的行为取决于全局配置，**难以并行测试、难以灰度、难以局部推理**。

---

## 五、优化建议（按投入产出比排序）

| # | 建议 | 理由 | 代价 |
|---|---|---|---|
| **1** | **协议状态从 `ThreadLocal` 改为"区域归属对象"**（挂到 `RegionizedData`，形态照抄第 10 课 `Scheduler`：按**段**索引 + 持票） | ① 自动随区域分裂/合并迁移（第 06 课契约）；② 消除手工跨线程播种与一系列 EXP5Plus 式补丁；③ 状态是对象 → **可单元测试** | 中：需重写状态读写路径 |
| **2** | **跨区域调度 facade 化**（收敛那 8 个点） | 解耦 Folia 类名；单线程模式可内联；可 mock；**使 Phase 1 能先落地** | 低 |
| **3** | **整文件重写 → 真补丁**（只改必要行，保留上下文） | 降低 rebase 冲突面（上游常年改这些文件）；diff 可见 | 中：需要重新生成补丁 |
| **4** | **`executeChain` 只留小钩子**（EXP 逻辑抽独立类） | vanilla 循环保持可读，上游冲突集中在 3–5 行 | 低 |
| **5** | **全局 `mode()` → per-chain / per-block 配置** | 可灰度、可并行测试、行为可局部推理 | 低–中 |
| **6** | **给 3,357 行纯 Java 协议补单元测试** | 目前只有 3 个 RCON 冒烟脚本（端到端、慢、易受环境影响）；纯逻辑正是单测的最佳对象 | 低（但需要设计测试夹具） |
| **7** | **`sameRegion` 判定统一**（现在内联在 0013，依赖 `getRegionChunkShift()`） | 区域粒度一变判定就变 → 必须集中一处，并在变更时显式重测 | 低 |

**其中 #1 是最大的一处架构改进**：它把"协议状态"从"线程的临时上下文"变成"区域的持久状态"，正好落在 Folia 自己提供的挂载/迁移机制上 —— 我们读源码读出来的那套契约，在这里第一次有了直接用途。

---

## 六、结论

1. **能不能复现：能。** 协议本体 3,357 行是纯 Java，外部耦合只有 8 个调用点，且 Folia 补丁零改动（不需要反推上游行为）。
2. **概率：语义复现 ≈85%；完整跨区域语义 ≈60–70%。** 差距主要来自"状态作用域要重做"和"需要 Phase 2 的区域化器"。
3. **最佳路径不是"等 Phase 2 再移植"，而是解耦两步走**：facade + 纯 Java 协议 → **Phase 1 就能落地并验证命令语义**（还能顺带解决我们第 05 课留下的链传播语义问题）→ Phase 2 只换 facade。
4. **最值得优化的三件事**：状态改成区域归属（#1）、跨区域调用 facade 化（#2）、整文件重写改回真补丁（#3）。
5. **最不该照抄的两件事**：ThreadLocal 承载协议状态、单 hunk 整文件重写。
