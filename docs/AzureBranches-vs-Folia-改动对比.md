# AzureBranches 相对 Folia 的改动盘点（实测）

> 对比基准：AzureBranches 仓库（HEAD `5c0fd0f`）vs 它自己 pin 的 Folia —— `foliaRef = 62dc0f257a4f5de1ef2eae8cf1627156a769c67f`（`ver/26.1.x`）。
> 方法：逐文件 sha256 比对 + 行数/hunk 统计 + 源码引用点计数。**每个数字都可复现**（命令见文末）。

---

## 一、总结论：**纯加法 fork**

| 检查项 | 结果 |
|---|---|
| Folia 的 8 个 `minecraft-patches/features/*` | **sha256 全部相同 → 一字节未改** |
| Folia 的 7 个 `paper-patches/features/*` | **sha256 全部相同 → 一字节未改** |
| AzureBranches 自建补丁 | 5 个（`0009`–`0013`，编号接在 Folia 的 0008 之后） |
| 另有源码覆盖层 | `azurepatches-src`（17 个 vanilla 文件整文件替换）、`azurepatches-new`（7 个新类） |
| 另有自有模块 | `azurebranches-common`（15 个类，最终合并进 jar） |

**即：AzureBranches 没有修改 Folia 的任何一行补丁**，全部改动都是"加在 Folia 之上"。
（这对 rebase 是好事——难点不在"他们改了什么"，而在"上游自己漂移了多少"，见 Phase 0 的漂移测量。）

---

## 二、按规模排序的改动清单

| # | 类别 | 规模 | 性质 |
|---|---|---|---|
| 1 | **`azurepatches-src` 全文件覆盖层** | **17 个文件 ≈ 8,300 行** | 整文件替换（不是补丁），施加方式：构建时直接覆盖到 Folia 生成源码上 |
| 2 | **`azurebranches-common`** | **15 个类 / 4,625 行** | 自有模块，编译后合并进 jar |
| 3 | **`azurepatches-new`** | **7 个文件 / 2,210 行** | 新增类（存储引擎 + 一个命令执行器） |
| 4 | **自建补丁 `0012`–`0013`** | `CommandBlock.java` **+501 行**、`SetBlockCommand.java` **+245 行**（各 1 个 hunk） | **侵入核心语义**，但走的是补丁路线 |
| 5 | 自建补丁 `0010`/`0011`/`0009` | `ServerLevel` +24、`BaseCommandBlock` +10、`MinecraftServer` +5 | 挂点（实体限流 / 命令方块模式开关 / 配置初始化） |
| 6 | 构建脚本 `folia-server/build.gradle.kts` | **1,392 行** | clone Folia → 覆盖层 → transformSource 锚点 → 品牌改写 → paperclip |

### 2.1 覆盖面（17 个覆盖层文件里，16 个是"命令系统"）

| 文件 | 行数 | 自有引用点 | 文件 | 行数 | 自有引用点 |
|---|---|---|---|---|---|
| `server/commands/ScoreboardCommand` | 1140 | 9 | `server/commands/TeamCommand` | 617 | 7 |
| `server/commands/data/DataCommands` | 801 | 23 | `server/commands/LootCommand` | 530 | 0 |
| `commands/Commands` | 737 | 13 | `chunk/storage/RegionFileStorage` | 476 | **34** |
| `commands/CommandSourceStack` | 657 | 1 | `server/commands/CloneCommands` | 467 | 0 |
| `server/commands/SpreadPlayersCommand` | 378 | 0 | `commands/arguments/ScoreHolderArgument` | 261 | 4 |
| `server/commands/TagCommand` | 180 | 1 | `server/commands/TeamMsgCommand` | 144 | 3 |
| `server/commands/data/EntityDataAccessor` | 139 | 4 | `server/commands/TriggerCommand` | 137 | 3 |
| `server/commands/data/BlockDataAccessor` | 129 | 3 | `server/commands/ReloadCommand` | 77 | 2 |
| `server/commands/data/DataAccessor` | 49 | 2 | | | |

**语义重心一目了然：命令系统（`commands/**`、`server/commands/**`、`data/**`）**，唯一例外是 `RegionFileStorage`（存储）。

### 2.2 `azurebranches-common`（OCC 与并发）

| 类 | 行数 | 类 | 行数 |
|---|---|---|---|
| `command/EntityLayer` | 792 | `command/ChainHead` | 246 |
| `command/PhaseSnapshot` | 777 | `command/DeferredAction` | 228 |
| `command/ExpChainSupport` | 579 | `entity/EntityLimiter` | 180 |
| `moonrise/concurrent/WorkerThreadPool` | 374 | `command/ScoreLayer` | 179 |
| `moonrise/concurrent/BalancedThreadPool` | 327 | `command/Continuation` | 142 |
| `command/PhaseValidator` | 314 | `command/CommandBlockSupport` | 99 |
| `config/AzureBranchesConfig` | 260 | `plugin/AzureBranchesPlugin` | 73 |
| `moonrise/concurrent/WorkerPoolManager` | 60 | | |

### 2.3 `azurepatches-new`（AzLinear v4 存储引擎）

`BufferedLinearRegionFile` **1577**（最大单文件）、`RegionCommandExecutor` 240、`BufferedLinearRegionFileFlusher` 133、`RegionFormat` 101、`IRegionFile` 54、`RegionFormatBootstrap` 53、`RegionFileAdapter` 52。

---

## 三、两处最硬的语义改动（有源码证据）

### 3.1 补丁 `0012`：把"链式推进"改成**可挂起链**

```java
// CommandBlock.executeChain 里新增分支（Folia 原本是同步 while 循环一路推到底）
// AzureBranches start - EXP suspendable chain (v2)
if (com.azurebranches.command.CommandBlockSupport.mode() == com.azurebranches.command.CommandBlockSupport.Mode.EXP) {
    ...
}
```

### 3.2 补丁 `0013`：`SetBlockCommand` 的**区域感知延迟派发 + OCC 回滚捕获**

```java
// AzureBranches start - EXP deferred dispatch (v2)
if (com.azurebranches.command.ExpChainSupport.isContextActive()) {
    final boolean sameRegion = (homeCx >> regionShift) == (targetCx >> regionShift);   // ★ 判断是否跨区域
    ...
    com.azurebranches.command.ExpChainSupport.registerDeferred(sameRegion, targetCx, targetCz, () -> {
        // AzureBranches - record old state for OCC rollback (EXP4)
        ... ExpChainSupport.getPhaseSnapshot(); ... ExpChainSupport.captureOldState(...);
    });
}
```

**这两处正是我们读 Folia 得出的结论的落点**：Folia 只保证"区域内串行"，跨区域的命令方块链没有单一时间线（第 02 课），跨区域影响只能靠投递任务（第 09 课），
所以 AzureBranches 必须在 `executeChain` 与关键命令（`setblock` 等）里插入"跨区域判断 + 延迟/回执 + 回滚快照"。

---

## 四、两个值得警惕的工程问题

1. **覆盖层 vs 补丁的取舍**
   构建脚本对覆盖层有 **fail-fast 校验**（很合理）：
   > "Fail fast if an overlay file has no counterpart in the freshly generated sources — otherwise an upstream layout change would be silently masked by our old snapshot."

   但整文件覆盖层的固有代价是：**改动无法三方合并**，MC 版本升级时要整体重做（与第 02 课测出的高漂移面互为因果）。
   相比之下，自建补丁 `0012`/`0013` 走的是补丁路线，升级时至少能尝试合并 → **CeruLean 应坚持补丁路线**。

2. **3 个覆盖层文件疑似"原样拷贝"**
   `CloneCommands.java`、`LootCommand.java`、`SpreadPlayersCommand.java`：**既无自有标记，也无任何区域化/自有类引用**。
   在覆盖层机制下这类文件是**隐形负担**（没人能一眼看出它到底改没改）。建议人工确认后删除或补上标记。

---

## 五、复现命令

```sh
AB=01-服务端核心/AzureBranches
FL=~/folia-src                      # git clone --depth 1 -b ver/26.1.x https://github.com/PaperMC/Folia
# 1) Folia 补丁是否被改
for f in "$AB"/folia-server/minecraft-patches/features/*.patch; do
  [ "$(sha256sum "$f" | cut -c1-12)" = "$(sha256sum "$FL/folia-server/minecraft-patches/features/$(basename "$f")" | cut -c1-12)" ] \
    && echo "相同 $(basename "$f")" || echo "被改 $(basename "$f")"
done
# 2) 规模统计
wc -l "$AB"/folia-server/azurepatches-src/**/*.java  # 覆盖层
find "$AB"/azurebranches-common -name '*.java' | xargs wc -l
# 3) 自有引用点计数
grep -cE 'com\.azurebranches|AzureBranches|BufferedLinear|RegionFormat' "$AB"/folia-server/azurepatches-src/**/*.java
```
