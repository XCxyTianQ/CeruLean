# 22 · 向 EXP7Plus 对齐：进度与存储引擎评估

> 2026-10-18，Build 3。前置：`docs/19`（OCC/EXP6Plus）、`docs/20`（命令覆盖层）。

## 1. EXP7Plus 由两部分组成

| 部分 | 内容 | 本仓库状态 |
|---|---|---|
| **非存储** | `Entity.getTeam()` 解禁、`PlayerList` 登录计分板同步、`/team` `/teammsg` 全局 tick 化 | ✅ 已落（`0033`；`/team` `/teammsg` 见 `0031`） |
| **存储引擎（EXP7）** | bLinear v4：`IRegionFile` 抽象 + `BufferedLinearRegionFile`(1577 行) + flusher + `RegionFormat` + `RegionFileAdapter`，共 **1970 行新类**；`RegionFileStorage` **476 行整文件覆盖**；4 处 `transformSource`；2 个新依赖（`zstd-jni`、`zero-allocation-hashing`） | ⬜ **未做**（见 §3） |

## 2. 已完成的非存储部分（`0033`）

1. **`Entity.getTeam()` 解禁**：Folia 用 `if (true) return null;` 把实体→队伍查询整个禁掉。
   本仓库的 `Scoreboard` 在 `0022` 之后已是并发容器 + `stateLock`，`PlayerTeam.players` 本身也是
   `ConcurrentHashMap.newKeySet()`，所以任意区域线程上的队伍查询是安全的，整体放开。
2. **`PlayerList.updateEntireScoreboard` 恢复**：计分板是服务端全局数据，改为经
   `RegionCommandExecutor.onGlobalAsync(...)` 在全局 tick 上做。

**验证限制（如实）**：这两处只在**真实客户端加入**时体现。本机冒烟环境连不上客户端 ——
服务端上报协议 **777**，而 `minecraft-data` 最新版（3.117.0）也没有该协议定义，mineflayer 无法握手
（已写好 `tools/smoke/player_join_probe.js`，协议定义可用时可直接跑）。
因此这两处的依据是代码路径审查 + 回归套件（R1 计分板压力、存档往返）无回退，**不是**运行时实证。

## 3. 存储引擎（EXP7）：为什么单独拆一轮

风险等级与其他补丁不是一个量级：**它改的是世界存档格式**。一旦出错，后果是世界数据损坏，
而不是"某条命令不可用"。需要：

1. 新增 `com.azurebranches.storage.*` 5 个类 + `RegionFileAdapter`，先只求**能编译**（验证它们依赖的
   26.3 API 是否仍在：`RegionFile`/`ChunkSystemRegionFileStorage`/`MoonriseRegionFileIO`/`ChunkSystemChunkBuffer`）；
2. `cerulean-server/build.gradle.kts` 加 `zstd-jni` + `zero-allocation-hashing`；
3. 4 处 `transformSource` 的锚点在 26.3 上的命中率评估（`RegionFile`/`ChunkSystemRegionFileStorage` 等
   是 Moonrise 内部的类，26.1.2 → 26.3 漂移未知）；
4. **最后**才启用 `RegionFileStorage` 的 476 行覆盖（切换读写路径）；
5. 每一层都要过 **L2 R1 存档往返** + 反复起服/存档/读档，并保留回退开关（配置项决定用旧 `RegionFile`
   还是 bLinear）。

我的建议是把它拆成"编译层 → 不启用 → 小世界启用 → 压力"四步，每步独立验证；
直接一把梭的风险与收益不成比例。

## 4. 本轮实测

- `0033` 应用后 `:cerulean-server:compileJava` 零错误、出包成功；
- 命令矩阵 16/0（`docs/20 §5.1`）；
- 回归套件见提交说明。

## 5. 工具链副作用（可清理）

为了让"真实玩家加入"能测，装了 `mineflayer` 依赖到
`03-服务端插件/AzureTPA/smoke/node_modules`（91 个包），并在 `~/.occ/mcdata` 试了最新
`minecraft-data`。两者都不在 CeruLean 仓库内，不影响仓库；不需要时可删除。
