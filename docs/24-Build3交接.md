# 24 · Build 3 交接（给下一个 Session）

> 交接时间 2026-10-18。仓库 `CeruLean`，分支 `main`，HEAD = `2a386e7`（本文件所在提交），
> **工程基线 = `d5cf7e9`**（本文所有验证结论都以它为对象），工作树干净、与 `origin/main` 同步。
> 上一个交接文档是 `docs/14`（T1–T7 时代），本文件只覆盖 Build 3。
> ⚠️ `docs/23-活塞更新语义探针-交接指南.md` 是**另一条并行工作线**（活塞更新语义），与本文件无关。

## 0. 三十秒摘要

Build 3 已完成 **OCC/EXP6Plus 之后的第二阶段**：修好 EOC 遗留的三条命令（`/reload` `/datapack` `/spreadplayers`）、
落地 EXP7Plus 的非存储部分、修掉一个负载敏感的 `/loot` 阻塞桥、并把回归套件的"随机红"治掉。
**Build 3 尚未发布**（最近发布的仍是 `v26.3-build.2`）。
**唯一的大块剩余工作是 EXP7 存储引擎（bLinear v4）**，见 §4。

## 1. Build 3 的补丁清单与验证依据

| 补丁 | 内容 | 验证依据 |
|---|---|---|
| `0032` | `/reload`、`/datapack`、`/spreadplayers` 修好 | 命令矩阵 16/0；`/reload` 看日志 `Loaded N advancements` 增量（1→2）；`/spreadplayers` 看实体位置真的变化 |
| `0033` | EXP7Plus 非存储：`Entity.getTeam()` 解禁、`PlayerList` 登录计分板同步 | **代码审查 + 回归无回退**；运行时实证缺席（见 §5.1 协议 777） |
| `0034` | `/loot spawn` 改非阻塞派发 | 命令验收驱动器 2/2；修掉负载下 `cross-region command timed out` |
| （测试基建） | `exp_chain_probe` 接入调度器线程数前提 | 1 线程时 SKIP、2 线程时全绿（§3.2） |

此前的 `0024`–`0031` 属 OCC/EXP6Plus 与命令覆盖层，见 `docs/19`、`docs/20`、`docs/21`。

## 2. 已完成且**有运行时证据**的项

| 项 | 证据 |
|---|---|
| `/reload` 真的完成 | 连续两次 `/reload`，日志 `Loaded 1866 advancements` 出现 1→2 次 |
| `/datapack list` | 正常回显 |
| `/spreadplayers` | 僵尸 `(4.5,64.0,8.5)` → `(2.5,-60.0,1.5)` |
| `/loot spawn` | `Dropped 8 items`，且不再打 "blocking a tick/region thread" 告警 |
| EXP 链 / OCC | SAFE 11/0、EXP 28/0、EXP6Plus OCC 13/0（2 个调度线程时） |
| 命令覆盖层 + 混合压力 | 16/0（矩阵）、4/0（压力） |
| 存档往返 | PASS |

## 3. 当前验证状态（**精确表述，别过度声称**）

### 3.1 全量套件的两次全绿

- `48a14d2`（Build 2 发布点之前）：**7 PASS / 0 FAIL / 0 SKIP**
- `534df9b`（`0032` 之后）：**7 PASS / 0 FAIL / 0 SKIP**

### 3.2 `0033`/`0034`/`d5cf7e9` 之后的状态

**尚未在"一次全量套件"里同时全绿**。逐项事实：

- 一次全量跑（`184fee1` 前的树）：命令用例 **PASS**（16/0 + 4/0）、存档往返 **PASS**，
  但 T4 报 FAIL —— 查日志是那一次 Folia 只起了 **1 个调度线程**，
  `data get block` 同步跨区域读自交锁，探针观测手段失效（**环境**，非代码）。
- 修掉线程数前提后单独复测 T4（同一台机器、2 个调度线程）：**SAFE 11/0、EXP 28/0、OCC 13/0，exit 0**。

⇒ **发版前请在"空闲且 ≥2 调度线程"的机器上再跑一次完整套件**（命令见 §6.2）。

### 3.3 一个必须记住的判据

`/cerulean regions` 或日志里 `Initialised EDF Folia scheduler with initial N target thread(s)`
决定了很多断言的前提。**< 2 线程时，任何依赖同步跨区域读的断言都只能记 SKIP**（`docs/17 §4`）。
本机实测出现过 1 / 2 / 3 三种取值，取决于机器负载。

## 4. 剩余工作：EXP7 存储引擎（bLinear v4）

### 4.1 规模与可行性（已实测）

- 新类：`BufferedLinearRegionFile`(1577) + `BufferedLinearRegionFileFlusher`(133) + `IRegionFile`(54)
  + `RegionFormat`(101) + `RegionFormatBootstrap`(53) + `RegionFileAdapter`(52)
  = **1970 行**（源：`01-服务端核心/AzureBranches/folia-server/azurepatches-new/`）。
- 整文件覆盖：`RegionFileStorage.java` **476 行**（源：同目录 `azurepatches-src/`）。
- 构建依赖：`com.github.luben:zstd-jni:1.5.4-1`、`net.openhft:zero-allocation-hashing:0.16`
  （AzureBranches 是在 `build.gradle.kts` 里临时注入的，见其 `:1331-1336`）。
- **注入锚点：4 个文件共 7 个锚点在 26.3 上全部唯一命中**（已实测）：
  `ChunkSystemRegionFileStorage`(2)、`MoonriseRegionFileIO`(2)、`ChunkSystemChunkBuffer`(1)、
  `RegionFile`(2)。⇒ 机械上可行，风险在**格式语义**，不在锚点。

### 4.2 建议的四步（每步都要能独立回退）

| 步 | 做什么 | 验收 | 回退 |
|---|---|---|---|
| S1 | 只把 6 个新类 + 2 个依赖加进来，**不启用**任何钩子 | `:cerulean-server:compileJava` 零错误；套件不变 | 删补丁 |
| S2 | 加 4 处注入 + `RegionFileAdapter`，仍然**保持默认走旧 `RegionFile`** | 套件全绿（证明"插了钩子但没切换"） | 删补丁 |
| S3 | 加一个配置开关（如 `[storage] engine = "vanilla" \| "blinear"`），**小世界**启用 bLinear | L2 R1 存档往返 + 反复起服/存档/读档 ×3 | 开关切回 `vanilla` |
| S4 | 压力：大世界 / 频繁保存 | 存档往返 + 压力用例 + 无 RegionFile 相关异常 | 开关切回 |

### 4.3 已知风险

- 这是**世界存档格式**的变更，失败后果是数据损坏，不是命令不可用。
- 26.1.2 → 26.3 的 Moonrise 内部类虽然锚点全中，但**语义**（`moonrise$getRegionFileIfLoaded` 的
  可见性/生命周期）未逐一核对。
- `RegionFileStorage` 的 476 行覆盖是 26.1.2 快照，直接覆盖到 26.3 可能带入旧细节；
  更稳的是**只做注入**（改 `RegionFile` → `IRegionFile` 的构造/调用点），不整文件替换。

## 5. 环境与工具链的坑（**必读**，都是本轮真实踩过的）

### 5.1 真实客户端测不了

服务端上报协议 **777**；`minecraft-data`（含最新 3.117.0）没有该协议定义 ⇒ mineflayer 无法握手。
已写好 `tools/smoke/player_join_probe.js`（真客户端加入 + 断言客户端收到计分板/队伍），
等协议定义可用时直接跑。**因此 `0033` 的两项只有代码审查层面的依据。**

### 5.2 Gradle

- **`BUILD_NUMBER` 必须配 `--no-daemon`**：`providers.environmentVariable` 读的是 daemon 启动时的环境；
  带 daemon 跑、加 `--no-configuration-cache` 都不生效（产物仍是 `26.3-SNAPSHOT`）。
- **构建命令不要把退出码交给管道**：`gradle ... | tail -3` 会吃掉失败，让你用旧 jar 继续跑
  （本项目已经因此误判过两次）。要显式检查 `error:` 与 `BUILD SUCCESSFUL`。
- `applyAllPatches` 会**重置物化源码树**：直接改物化文件而不落成补丁，改动会被覆盖掉。

### 5.3 进程与端口

- **`pkill -f '<pattern>'` 会杀掉承载该命令的 shell/job 自身**（命令行里含同一 pattern）。用 PID 杀。
- RCON 客户端是"读到超时才返回"⇒ **`wait` 就是每次调用的挂钟成本**。批量压测用 0.25s，
  否则 800 次调用要跑一小时。

### 5.4 发布

- 附件上传走 `uploads.github.com`，本机代理会拦成 HTTP 400；必须
  `env -u HTTPS_PROXY -u HTTP_PROXY -u ALL_PROXY NO_PROXY='*' gh release create ...`。
- 出包要在 **tag 提交上** 构建，MANIFEST 的 `Git-Commit` 才会与 tag 一致（`docs/18 §3.3`）。

### 5.5 其他

- 本机没有真 bash（`/bin/sh` 是 mksh）；`/tmp` 只读；hmdfs 不支持硬链接、`chmod` 静默失效。
- 冒烟服是**超平坦世界**，地面在 y≈-61：在 y=64 直接 `summon` 的实体**会摔死**；
  实体高 1.95 格，站在 y=64 放到 (x,65,z) 的命令方块会把它**窒息死**（C1 用例踩过）。
- 并行工作线的副作用：`03-服务端插件/AzureTPA/smoke/node_modules`（91 包，为机器人探针装的）、
  `~/.occ/mcdata`（试最新 minecraft-data）。都不在 CeruLean 仓库内，可删。

## 6. 复现与验收入口

### 6.1 构建

```sh
cd /storage/Users/currentUser/Desktop/AzureCores/CeruLean
tools/gradle-local.sh applyAllPatches
tools/gradle-local.sh :cerulean-server:createPaperclipJar
# 发布版（tag 提交上）：
BUILD_NUMBER=<N> tools/gradle-local.sh --no-daemon --no-configuration-cache :cerulean-server:createPaperclipJar
```

### 6.2 回归闸门

```sh
export CERULEAN_EULA=TRUE RCON_PORT=25575 RCON_PASSWORD=test1234
rm -rf smoke-suite smoke-exp smoke-persist smoke-cmd
python3 tools/smoke/run_suite.py        # 约 35-50 分钟，取决于机器负载
```

七项：L0 基座活性 / L1 可观测面 / T1-T3 scoreboard+clone / R1 scoreboard 压力 /
T4 EXP 链（SAFE+EXP，含 EXP6Plus OCC + 命令矩阵 + 压缩） / R1 存档往返 / 区域化命令（矩阵+混合压力）。

**单跑某一项**（省时间）：

```sh
python3 tools/smoke/exp_chain_acceptance.py <jar> ./smoke-exp      # T4
python3 tools/smoke/command_acceptance.py  <jar> ./smoke-cmd       # 命令覆盖层
python3 tools/smoke/scoreboard_persist_check.py <jar> ./smoke-persist
```

### 6.3 可观测面

`/cerulean exp` 现在会打印：OCC 校验（通过/重试/耗尽/冲突率）、数据面（方块读/计分读/计分写/
缓存命中/读集记录/NBT写拦截/NBT缓存命中）、补偿（计分/失败/NBT/竞争）、`PhaseValidator.describe()`、
以及"最近一次校验"的规模摘要（`tick=… retry=… 方块读=… 方块写=… 计分读=… NBT读=… 实体读=…`）。
排障先看这一屏。

## 7. 诚实清单：未验证 / 已知限制

1. **`0033` 的登录同步与队伍查询没有运行时证据**（协议 777 挡住真客户端）。
2. **多区域真并发未验证**：单区域冒烟构造不出跨区域冲突；C2 用"相位内打死实体"触发 RETRY，
   属同类冲突但不是跨区域冲突（`docs/17 §4`）。
3. **`/spreadplayers` 跨多区域**：`maxRange` 大到跨区域时，候选位置的方块读仍发生在中心区域线程上。
4. **`/scoreboard` 没有全局 tick 独占**：`0029` 用锁内 CAS 收窄了补偿窗口，但未消除；
   `/cerulean exp` 的"竞争"计数是它的量化暴露。
5. **EXP7 存储引擎完全未开始**（§4）。
6. `0033`/`0034` 之后**尚未有一次全量套件同时全绿**（§3.2）。

## 8. 下一位接手时的建议顺序

1. 在空闲机器上跑一次完整套件，确认 `d5cf7e9` 基线全绿（≥2 调度线程）。
2. 若目标是"完成 Build 3"：按 §4.2 的 S1 → S2 推进（都能安全回退）。
3. 若目标是"发 Build 3"：当前 `d5cf7e9` 已具备发布条件，按 `docs/18 §3.3` 出 tag + pre-release，
   发布说明可基于 `docs/21` 增补 `0032`–`0034` 与 §3.2 的验证结论。
4. 接手前先读 `docs/19`（OCC）、`docs/20`（命令覆盖层）、`docs/22`（EXP7Plus 进度）。
