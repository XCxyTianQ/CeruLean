# CeruLean Cores

> 从**试验性项目**转向**半投入生产的实验项目**的 Minecraft 服务端发行版。
> 定位：以 **Paper 主线 stable** 为基座，逐步移植 **Folia 的区域化多线程 tick**，并整体继承 **AzureBranches 的命令语义（OCC）与存储引擎**资产。

**当前阶段：Phase 1 — 基础工作（构建接入 + 语义基线）**
Phase 0（可复现校验流水线）已完成并通过实测；Phase 1 正在接入 paperweight 构建与参数化冒烟基线。

---

## 为什么是这条路（结论摘要）

| 事实（实测） | 含义 |
|---|---|
| Folia **没有 `ver/26.3.x` 分支**；26.2 只有 6 个 BETA 构建，最后一次功能性提交 2026-08-25 | 跟随 Folia 分支 = 把路线图交给一个停摆的上游 |
| Paper 主线**已包含** Moonrise 区块系统（309 类）、TickThread、Folia 调度 API + 回退实现 | 区域化最难的三块**不用自己写** |
| 主线**0 命中** `RegionizedServer`/`ThreadedRegionizer` | 要自己做的只有"区域化执行体 + 跨区域语义 + 生产加固" |
| Folia 区域化本体 = 13 个新文件 / 约 1 万行 / 21 个补丁 / 28,493 行 | 有参考实现可复用 |
| 区域化补丁 **84.8%**（26.1.2→26.3）或 **75.8%**（26.2→26.3）的 hunk 落在 Paper 已改动的文件上 | 这是**移植工程**，不是 `git rebase`；必须建回归网 |
| Mojang 26.3 原版**已不混淆**（7762 类 0 个短名类） | 写补丁/调试成本大幅下降 |

完整论证与量化数据见 `../reports/CeruLean-可行性分析.md` 与 `../reports/Paper-26.3.175-拆解.md`。

---

## 硬约束（不可协商）

1. **许可证 GPLv3**：继承链 Paper ← Spigot ← Bukkit/CraftBukkit，Folia/AzureBranches 同为 GPLv3。发行必须附源码与补丁、保留 NOTICE 派生声明，不可闭源。
2. **Java 25**：Paper/Minecraft 26.x 字节码主版本 69（本机 `~/.harmonybrew/opt/openjdk@25` 可用），无降级空间。
3. **上游节奏**：一切以"可复现 + 自动漂移告警"为前提，否则维护成本会失控。

---

## 目录结构

```
CeruLean/
├── build.gradle.kts                  paperweight patcher 配置（patch-only fork）
├── settings.gradle.kts               模块声明与版本串
├── gradle.properties                 上游 pin（Paper 26.3 build 175 / sha256 锁定）
├── cerulean-server/
│   ├── build.gradle.kts.patch        对 paper-server 构建文件的补丁（fork 注册/品牌/改名）
│   ├── minecraft-patches/features/   对反编译 Minecraft 源码的补丁
│   └── paper-patches/features/       对 Paper 自有源码的补丁
├── cerulean-api/
│   ├── build.gradle.kts.patch
│   └── paper-patches/features/
├── tools/
│   ├── verify/                       上游复现与漂移校验（Phase 0，已实测）
│   ├── smoke/                        参数化 RCON 冒烟基线 + 服务器启停
│   └── gradle-local.sh               本机 Gradle 启动器（绕开 gradlew 的 shell 问题）
├── docs/                             00 立项与约束 · 01 pin 策略 · 02 校验手册 · 03 构建接入 · 04 Phase1 范围
└── .github/workflows/                verify.yml（复现校验/漂移巡检）· build.yml（构建+冒烟）
```

---

## 快速开始

### 1) 构建（首次很慢：拉取上游 + 反编译 Minecraft）

```sh
tools/gradle-local.sh applyAllPatches      # 本机用这个；有真 bash 的环境直接 ./gradlew
./gradlew build
./gradlew :cerulean-server:createPaperclipJar   # 产出分发 jar
```

### 2) 验证"上游发行物可复现"（无需构建）

```sh
tools/verify/cerulean-verify upstream --version 26.3      # 全链哈希复现 → RESULT: PASS
tools/verify/cerulean-verify selftest --version 26.3      # 篡改必被检出 → SELFTEST: PASS
tools/verify/cerulean-verify diff <jarA> <jarB>           # 类级漂移报告
```

### 3) 语义基线冒烟（对任意 Paper/CeruLean jar）

```sh
CERULEAN_EULA=TRUE tools/smoke/run-smoke.sh <paperclip.jar> ./smoke-report
```

---

## 进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 基座 pin 登记、复现校验工具、三方补丁栈目录、CI | ✅ 完成（校验 `RESULT: PASS`、自测 `SELFTEST: PASS`） |
| Phase 1 | paperweight 构建接入、参数化冒烟基线、构建 CI | 🔄 进行中 |
| Phase 2 | 区域化移植（入口点：Paper 26.2 stable + Folia 26.2 补丁） | ⏳ 未开始 |
| Phase 3 | 生产加固（多 world、崩溃恢复、watchdog/profiler、soak） | ⏳ 未开始 |
