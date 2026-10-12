# 11 · Hook 施工图：187 个接线点的分布、分类与优先级

> 数据来源：Folia `ver/26.2.x` 的 `minecraft-patches/0001-Region-Threading-Base.patch`（20,221 行）
> 交叉比对：Paper 26.3 的 941 个 sources 补丁（`tree.json`）+ Paper 26.2/26.3 两版成品 jar 的类级 CRC。
> 脚本产物：`paperjar/hookmap.json`（每文件：hunk 数、增删行、接线类型、是否与上游补丁重叠、26.3 漂移）。

## 1. 总量与分布

| 指标 | 值 |
|---|---|
| 被修改的既有文件 | **187** |
| 总 hunk | **694** |
| 总插入行 | **5,867** |
| 其中被 **Paper 26.3 自己也在打补丁**的文件 | **135 / 187**（→ 直接冲突面） |
| 26.2→26.3 之间**类字节已变**的文件 | **116 / 187**（→ 已漂移面） |

## 2. 按"接线类型"分类（一个文件可能多类）

| 接线类型 | 文件数 |
|---|---|
| 线程归属校验 | 52 |
| 跨区域派发 | 23 |
| 区域化状态字段 | 23 |
| 调度/tick 主循环 | 17 |
| 区域查询/归属 | 16 |
| 票据/加载 | 11 |
| 区域上下文设置 | 10 |
| 失败/关服 | 2 |
| 区块增删→拓扑 | 1 |

**读法**：数量最多的是「线程归属校验」（52 个文件）—— 这类是**并发阶段（C3）**的工作；
而真正决定"区域拓扑能不能动起来"的只有极少数文件（见第 3 节）。

## 3. ★ 核心发现：拓扑接线只有 **1 个文件 / 2 行**

整条"区块生命周期 → 区域拓扑"的连接，全部落在 Moonrise 的钩子接口里：

```java
// ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java
@Override public void onChunkHolderCreate(ServerLevel level, ChunkHolder holder) {
    level.regioniser.addChunk(holder.getPos().x(), holder.getPos().z());      // ← 就这两行
}
@Override public void onChunkHolderDelete(ServerLevel level, ChunkHolder holder) {
    level.regioniser.removeChunk(holder.getPos().x(), holder.getPos().z());
}
```

同一文件里另有 **6 处**改动，性质是"区块该记住哪个列表"——从 world 级列表改为**当前区域**的 world data：

```java
- ((ChunkSystemServerLevel)…).moonrise$getLoadedChunks().add(chunk);
+ chunk.getLevel().getCurrentWorldData().addChunk(chunk);            // 同理：ticking / entityTicking 各一对
```

**含义**：Moonrise 已经把"区块何时创建/删除/进入某种 tick 状态"抽象成了 `BaseChunkSystemHooks` 接口，
我们**不需要改 Moonrise 内部**，只要把自己的 regioniser 插进这个接口即可。这是自研路线上第一个好消息。

## 4. MC 侧最小接线面（自研 regioniser 需要提供的 API，反向确定 C1 的接口）

| # | 位置 | 内容 | 出处 |
|---|---|---|---|
| 1 | `ServerLevel` 构造 | `this.regioniser = new ThreadedRegionizer<>(5 个旋钮, world, callbacks)` | patch：`net/minecraft/server/level/ServerLevel.java` |
| 2 | `Level` | `public RegionizedWorldData getCurrentWorldData()` —— 返回**当前 tick 区域**的 world data | `net/minecraft/world/level/Level.java` |
| 3 | `BaseChunkSystemHooks` | `addChunk`/`removeChunk` 两行 + 6 处区块列表改道 | `ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java` |
| 4 | 全域替换模式 | 所有原本直接访问 world 级字段的地方 → `world.getCurrentWorldData().xxx` | 遍布 187 个文件 |

**⇒ C1 的 core 必须对外提供的接口（最小集）**：
`addChunk(world, cx, cz)` / `removeChunk(world, cx, cz)` / `getRegionAt(cx, cz)` /
区域生命周期回调（区域创建/销毁/激活/去激活/合并前/分裂前）/
"当前区域"的解析（由 MC 侧线程上下文提供）/ 区域粒度 shift 的显式暴露。

## 5. Top 25 施工点（按 hunk 数）

| 文件 | 路径 | hunk | 增行 | 上游也改 | 26.3 漂移 |
|---|---|---|---|---|---|
| `ServerLevel.java` | `net/minecraft/server/level/ServerLevel.java` | 62 | +325 | 是 | 已改 |
| `MinecraftServer.java` | `net/minecraft/server/MinecraftServer.java` | 40 | +276 | 是 | 已改 |
| `ServerGamePacketListenerImpl.java` | `net/minecraft/server/network/ServerGamePacketListenerImpl.java` | 31 | +117 | 是 | 已改 |
| `PlayerList.java` | `net/minecraft/server/players/PlayerList.java` | 27 | +124 | 是 | 已改 |
| `Entity.java` | `net/minecraft/world/entity/Entity.java` | 27 | +868 | 是 | 已改 |
| `Level.java` | `net/minecraft/world/level/Level.java` | 25 | +116 | 是 | 已改 |
| `Connection.java` | `net/minecraft/network/Connection.java` | 15 | +155 | 是 | 已改 |
| `ChunkHolderManager.java` | `ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkHolderManager.java` | 14 | +196 | 否 | 已改 |
| `ChunkMap.java` | `net/minecraft/server/level/ChunkMap.java` | 14 | +65 | 是 | 已改 |
| `LivingEntity.java` | `net/minecraft/world/entity/LivingEntity.java` | 13 | +40 | 是 | 已改 |
| `HopperBlockEntity.java` | `net/minecraft/world/level/block/entity/HopperBlockEntity.java` | 13 | +28 | 是 | 已改 |
| `MapItemSavedData.java` | `net/minecraft/world/level/saveddata/maps/MapItemSavedData.java` | 13 | +21 | 是 | 已改 |
| `ServerChunkCache.java` | `net/minecraft/server/level/ServerChunkCache.java` | 11 | +94 | 是 | 已改 |
| `ServerPlayer.java` | `net/minecraft/server/level/ServerPlayer.java` | 11 | +518 | 是 | 已改 |
| `LevelChunk.java` | `net/minecraft/world/level/chunk/LevelChunk.java` | 9 | +28 | 是 | 已改 |
| `Raids.java` | `net/minecraft/world/entity/raid/Raids.java` | 8 | +32 | 是 | 未改 |
| `ActivationRange.java` | `io/papermc/paper/entity/activation/ActivationRange.java` | 7 | +46 | 否 | 未改 |
| `WorldBorderCommand.java` | `net/minecraft/server/commands/WorldBorderCommand.java` | 7 | +86 | 否 | 未改 |
| `MapItem.java` | `net/minecraft/world/item/MapItem.java` | 7 | +8 | 是 | 已改 |
| `RedStoneWireBlock.java` | `net/minecraft/world/level/block/RedStoneWireBlock.java` | 7 | +16 | 否 | 缺类 |
| `ChunkTaskScheduler.java` | `ca/spottedleaf/moonrise/patches/chunk_system/scheduling/ChunkTaskScheduler.java` | 6 | +39 | 否 | 已改 |
| `TimeCommand.java` | `net/minecraft/server/commands/TimeCommand.java` | 6 | +37 | 是 | 已改 |
| `EntityReference.java` | `net/minecraft/world/entity/EntityReference.java` | 6 | +15 | 是 | 已改 |
| `EntityGetter.java` | `net/minecraft/world/level/EntityGetter.java` | 6 | +11 | 是 | 未改 |
| `SavedDataStorage.java` | `net/minecraft/world/level/storage/SavedDataStorage.java` | 6 | +93 | 是 | 未改 |

**规律**：hunk 数最多的 6 个文件（`ServerLevel`/`MinecraftServer`/`ServerGamePacketListenerImpl`/`PlayerList`/`Entity`/`Level`）**同时**是"上游也在打补丁"且"26.3 已漂移"的 —— 这与第 02 课的漂移测量完全一致（热点集中且持续变动）。

## 6. 接线面按阶段切分（对应设计规格的第 11 节）

| 阶段 | 需要接线的内容 | 涉及文件（估） | 说明 |
|---|---|---|---|
| **C2** 拓扑 + 串行 tick | ① `ServerLevel` 持有 regioniser；② `Level.getCurrentWorldData()`；③ `BaseChunkSystemHooks` 的 2 行 + 6 处改道；④ 关服/初始化最小接线；⑤ 一个我们自己的 `/cerulean regions` 诊断命令 | **≈5 个**（不是 19 个——`ServerLevel` 62 hunk 里大部分属于 C3 的内容并行） | 判据：与 vanilla Paper 行为逐项一致 |
| **C3** 内容并行 | ① 52 个文件的线程归属校验；② 23 个文件的跨区域派发；③ 23 个文件的状态字段改道；④ 10 个文件的区域上下文设置；⑤ 段级票据 | **≈150 个** | 真正的大工程 |
| **C4** 生产化 | 关服一致性、profiler、watchdog、失败处理（2 个"失败/关服"文件） | 少量 | |

**结论：C2 是"小手术"，C3 才是主体。** 而 C2 之所以能那么小，正是因为拓扑连接点在 Moonrise 里已经被隔离出来了。

## 7. 风险与建议施工顺序

**风险**
1. **135/187 与上游 Paper 补丁重叠** → 我们接的线若与 Paper 的同文件补丁冲突，必须逐处三方合并；
2. **116/187 已漂移到 26.3** → 不能照抄 Folia 的 hunk（同一位置的上游代码已经不同）；
3. **Moonrise 自身在演进**（第 02 课：`ChunkHolderManager` 26.3 仍在改）→ 与 Moonrise 对接的钩子要写薄、只做转发。

**建议顺序**
1. 先落 C1 的 core（纯 Java）+ 其对外接口，**接口按第 4 节的那 5 类设计**；
2. C2 只接 5 处线，把"拓扑正确 + 与 vanilla 行为一致"验证掉；
3. C3 按类型分批：先"线程归属校验"（机械、量大但简单），再"状态字段改道"（量大且需要理解），最后"跨区域派发"（最需要设计）；
4. 全程保持：每个接线点配套一个回归用例（与第 04 节"可复现校验"的约定一致）。
