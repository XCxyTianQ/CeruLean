<div align="center">
  <img src="assets/icon.png" width="200" alt="CeruLean Cores">
  <h1>CeruLean Cores</h1>
  <p><b>面向「半投入生产」的 Minecraft 服务端发行版</b><br>
  以 Folia 26.3 为基座 · 区域化并行 tick · 目标：区域化下的命令方块语义完整性</p>
  <p>
    <code>Minecraft 26.3</code> · <code>Java 25</code> · <code>GPLv3</code> · 构建形态：paperweight patch-only fork
  </p>
  <p>
    <b>本期版本：<code>26.3-SNAPSHOT</code>（Snapshot，按 pre-release 发布，不建议用于生产）</b><br>
    发布 tag 约定 <code>v26.3-build.N</code> · 详见 <a href="docs/18-版本与发布约定.md">docs/18</a>
  </p>
</div>

---

## 这是什么

> **⚠️ 版本通道（本期）**：服务端核心为 **Snapshot**（`channel=SNAPSHOT`），
> 版本串 `26.3-SNAPSHOT`（发布构建 `26.3-build.<N>-SNAPSHOT`），**按 GitHub pre-release 发布**
> 且不会被标成 Latest。发布链路的验收闸门就是本仓库自己的分层套件。见 [docs/18](docs/18-版本与发布约定.md)。
>
> **⚠️ 基座身份变更（2026-10-16）**：本项目基座已从 **Paper 主线** 换成 **Folia 26.3**。
> 区域化运行时不再自研（原 B1/B2/C2 路线已按路线 A 收缩掉，`cerulean-regions` 只剩诊断命令）；
> 上游 Folia 已提供 `ThreadedRegionizer` / `TickRegionScheduler` / region-aware `TickThread`。
> 当前进度与验收记录见 `docs/14`（T1–T7 交接）、`docs/15`（T1–T3）、`docs/16`（T4）、`docs/17`（R1/R2）。
> **下文 §"为什么自己做区域化" 属历史路线，已被上述变更取代，保留以便追溯决策。**


CeruLean Cores 是一个 Minecraft Java 版服务端发行版。它**不是**又一个"加了一堆优化补丁"的 Paper 下游，而是想解决一个具体而难的问题：

> **当世界被切成多个区域、每个区域在自己的线程上独立推进 tick 时，跨区域的游戏语义（尤其是命令方块链）如何保持正确。**

为此本项目的路线是：**以 Paper 主线为基座**（拿到它已经具备的区块系统与新调度 API），**自己实现区域化**（而不是直接跟随 Folia 分支），并在其之上重建命令方块语义保障层。

## 为什么自己做区域化

| 事实（均经实测核对） | 含义 |
|---|---|
| Folia 上游**没有 `ver/26.3.x` 分支**；26.2 只有 6 个 BETA 构建，最后一次功能性提交停留在 2026-08-25 | 跟随 Folia 分支 = 把路线图交给一个停摆的上游 |
| Paper 主线**已包含** Moonrise 区块系统（309 个类）、`TickThread`、Folia 调度 API 面（含回退实现），且 `TickRegions` 里留着 `// placeholder class for Folia` | 区域化最难的几块**不用自己写**，上游甚至为 Folia 预留了坑位 |
| 主线**0 命中** `RegionizedServer` / `ThreadedRegionizer` | 真正要自己做的：区域执行体 + 跨区域语义 + 生产加固 |
| 上游对"区块生命周期 → 区域拓扑"的连接点只有 **1 个文件 2 行**（Moonrise 的 `BaseChunkSystemHooks`） | 接入面很窄，自研是可控的 |
| 区域化补丁的 **84.8%（26.1.2→26.3）/ 75.8%（26.2→26.3）hunk** 落在 Paper 已改动的文件上 | 直接搬运上游补丁是"移植工程"而非 rebase；这也是选择自研的理由之一 |

## 当前状态

**当前状态（2026-10-16）**：基座已是 **Folia 26.3**（区域化由上游提供），命令复活（`data`/`scoreboard`/`clone`）与 **EXP 可挂起命令方块链**均已落地并实测通过；验收套件分层为 L0/L1/L2（`tools/smoke/run_suite.py`）。

| 能力 | 状态 |
|---|---|
| 构建链路 | ✅ 实测通过：`applyAllPatches` + `:cerulean-server:createPaperclipJar` 可在 Java 25 上产出分发 jar |
| 发行物可复现校验 | ✅ 自带工具可复现上游/自家分发链并逐字节校验（含篡改自检） |
| 语义基线冒烟 | ✅ RCON 驱动的用例集，可对原生 Paper 与自家产物运行 |
| 品牌与配置 | ✅ 产物的 MANIFEST 为 `Brand-Id: cerulean:cores`（已实测） |
| **区域化拓扑接线（C2）** | ✅ **已接入真实服务端**：区域可切分/查询/串行 tick，行为与 vanilla 逐项一致（见 [docs/08](docs/08-C2-验收记录.md)） |
| **区域化并行 tick** | ❌ 尚未实现（C3：区域→线程调度、段级票据、跨区域派发） |
| 命令方块语义层 | ❌ 尚未开始（Phase 2 之后） |

> 换句话说：现在构建出来的东西，行为**等同于 Paper 26.3**，只是换成了 CeruLean 的身份与构建管线。这一点我们不会含糊过去。

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| **Phase 0** | 上游 pin 登记、可复现校验工具、补丁栈目录、CI | ✅ 完成 |
| **Phase 1** | paperweight 构建接入、参数化语义基线 | ✅ 完成 |
| **Phase 2** | **区域化（自研）**：C0 设计 → C1 纯 Java 核心 → C2 MC 接线 → C3 内容并行 → C4 生产化 | 🔄 C0/C1/C2 完成，C3 待开始 |
| **Phase 3** | 命令语义 / 命令方块 EXP 链 | ⏳ 未开始 |

区域化的设计规格见 **[docs/06-区域化设计规格.md](docs/06-区域化设计规格.md)**：其中包含 9 条不变量、状态机、锁纪律、状态挂载与迁移契约、跨区域访问的唯一通道，以及**明确列出的、我们与上游实现不同的 6 处取舍**。

## 快速开始

### 要求

- **JDK 25**（Minecraft/Paper 26.x 的 class 主版本为 69，无降级空间）
- Git、约 3 GB 磁盘（反编译源码 + Gradle 缓存）
- 首次构建需要网络（拉取上游仓库与 Gradle 发行包）

### 构建

```sh
# 1) 拉取上游 Paper 到 pin 的提交 + 反编译 + 应用补丁
./gradlew applyAllPatches

# 2) 产出 Paperclip 分发 jar（对外发行物）
./gradlew :cerulean-server:createPaperclipJar

# 3) 运行
java -Xmx4G -jar cerulean-server/build/libs/cerulean-paperclip-*.jar --nogui
```

**在 HarmonyOS PC 上**（本项目的主要开发机）请改用 `tools/gradle-local.sh`：该环境的 `gradlew` 脚本会把默认 JVM 参数错当主类、且 Java 侧无法完成 Gradle 发行包的下载重定向。细节与绕行方式见 [docs/03-构建接入.md](docs/03-构建接入.md)。

## 项目结构

```
CeruLean/
├── build.gradle.kts / settings.gradle.kts     paperweight patcher 配置（patch-only fork）
├── gradle.properties                          上游 pin（版本 / 构建号 / sha256 锁定）
├── regionizer-core/                           纯 Java 区域化内核（C1；零 MC 依赖，含模型检验）
├── cerulean-regions/                          MC 侧接线与诊断命令（C2）
├── cerulean-server/                           服务端侧补丁（minecraft-patches / paper-patches）
├── cerulean-api/                              API 侧补丁
├── cerulean-checkstyle/                       代码风格模块适配（Paper 26.3 新增了该模块）
├── tools/
│   ├── verify/                                上游复现与漂移校验（零依赖，单文件 Java）
│   ├── smoke/                                 语义基线冒烟 + 测试服务器启停（RCON 驱动）
│   ├── icon/                                  图标：SVG 生成器 + SVG→PNG 栅格化器
│   └── gradle-local.sh                        本机 Gradle 启动器（HarmonyOS 绕行）
├── docs/                                      设计、约束、手册、分析报告
└── study/folia/                               Folia 源码阅读区（12 课笔记 + 参考源码副本）
```

## 验证工具

```sh
# 复现上游发行物并逐字节校验（容器 → 原版 → 内层 jar → 补丁 → 输出）
tools/verify/cerulean-verify upstream --version 26.3

# 证明校验器不是空转：篡改补丁/依赖库必须被检出
tools/verify/cerulean-verify selftest --version 26.3

# 类级漂移报告（用于评估上游改动影响面）
tools/verify/cerulean-verify diff <jarA> <jarB>

# 语义基线冒烟（对任意 Paper/本项目 jar）
CERULEAN_EULA=TRUE tools/smoke/run-smoke.sh <paperclip.jar> ./smoke-report
```

## 文档与阅读笔记

| 文档 | 内容 |
|---|---|
| [docs/00-立项与约束.md](docs/00-立项与约束.md) | 定位、三条硬约束（GPLv3 / Java 25 / 上游节奏） |
| [docs/01-上游层叠与pin策略.md](docs/01-上游层叠与pin策略.md) | 层叠关系、pin 策略与升级流程 |
| [docs/02-复现校验手册.md](docs/02-复现校验手册.md) | 为什么需要复现校验、校验链说明 |
| [docs/03-构建接入.md](docs/03-构建接入.md) | paperweight fork 形态、补丁格式规则、本机环境坑 |
| [docs/05-命令方块语义基线.md](docs/05-命令方块语义基线.md) | 命令方块语义的源码级基线（含实测记录） |
| [docs/06-区域化设计规格.md](docs/06-区域化设计规格.md) | **区域化自研设计（当前主干文档）** |
| [study/folia/](study/folia/) | Folia 源码阅读区：00–11 课（接入机制 / 拓扑 / 调度 / 跨区域 / hook 施工图） |
| [docs/AzureBranches-vs-Folia-改动对比.md](docs/AzureBranches-vs-Folia-改动对比.md) | 上一代项目相对上游改了什么（实测） |
| [docs/EXP链移植可行性分析.md](docs/EXP链移植可行性分析.md) | 命令方块 EXP 链的移植可行性、风险与优化建议 |

## 与上游的关系

本项目是 [PaperMC/Paper](https://github.com/PaperMC/Paper) 的下游，采用与 [PaperMC/Folia](https://github.com/PaperMC/Folia) 相同的补丁式构建形态；
区域化设计以 Folia 为**参考与反面教材**（其源码阅读副本位于 `study/folia/src/`，**不参与构建**）。
命令方块语义层的目标继承自同作者的上一代项目 [AzureBranches](https://github.com/XCxyTianQ/AzureBranches)。

完整的衍生关系、许可证与 Minecraft EULA 声明见 **[NOTICE.md](NOTICE.md)**。

## 许可证

**GPLv3** —— 继承链：`Bukkit / CraftBukkit → Spigot → Paper → (Folia) → AzureBranches → CeruLean`。
分发时必须附完整对应源码（含全部补丁）并保留 [NOTICE.md](NOTICE.md)。详见 [LICENSE](LICENSE)。
