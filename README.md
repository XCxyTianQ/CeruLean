# CeruLean Cores

> 从**试验性项目**转向**半投入生产的实验项目**的 Minecraft 服务端发行版。
> 定位：以 **Paper 主线 stable** 为基座，逐步移植 **Folia 的区域化多线程 tick**，并整体继承 **AzureBranches 的命令语义（OCC）与存储引擎**资产。

**当前阶段：Phase 0 — 基座与可复现流水线**（本仓库现阶段交付的是"骨架 + 校验能力"，尚不含 gradle 构建；构建在 Phase 1 接入 paperweight）

---

## 为什么是这条路（结论摘要）

| 事实（实测） | 含义 |
|---|---|
| Folia **没有 `ver/26.3.x` 分支**；26.2 只有 6 个 BETA 构建，最后一次功能性提交 2026-08-25 | 跟随 Folia 分支 = 把路线图交给一个停摆的上游 |
| Paper 主线**已包含** Moonrise 区块系统（309 类）、TickThread、Folia 调度 API + 回退实现 | 区域化最难的三块**不用自己写** |
| 主线**0 命中** `RegionizedServer`/`ThreadedRegionizer` | 要自己做的只有"区域化执行体 + 跨区域语义 + 生产加固" |
| Folia 区域化本体 = 13 个新文件 / 约 1 万行 / 21 个补丁 / 28,493 行 | 有参考实现可复用，不需要从零重写 |
| 区域化补丁 **84.8%**（26.1.2→26.3）或 **75.8%**（26.2→26.3）的 hunk 落在 Paper 已改动的文件上 | 这是**移植工程**，不是 `git rebase`；必须建回归网 |
| Mojang 26.3 原版**已不混淆**（7762 类 0 个短名类） | 写补丁/调试成本大幅下降 |

完整论证、量化数据与分期路线见 `../reports/CeruLean-可行性分析.md` 与 `../reports/Paper-26.3.175-拆解.md`。

---

## 硬约束（不可协商）

1. **许可证 GPLv3**：继承链 Paper ← Spigot ← Bukkit/CraftBukkit，Folia/AzureBranches 同为 GPLv3。发行必须附源码与补丁、保留 NOTICE 派生声明，不可闭源。
2. **Java 25**：Paper/Minecraft 26.x 字节码主版本 69（本机 `~/.harmonybrew/opt/openjdk@25` 可用），无降级空间。
3. **上游节奏**：Mojang 已到 26.4 快照、Paper 26.3 已出 175 个构建 —— 一切以"可复现 + 自动漂移告警"为前提，否则维护成本会失控。

---

## 目录结构

```
CeruLean/
├── README.md                  本文件
├── gradle.properties          上游 pin 登记（供 Phase 1 的 paperweight 构建读取）
├── patches/                   三方补丁栈（Phase 1 起填充）
│   ├── minecraft-patches/features/   对 Minecraft 源码的补丁
│   ├── paper-patches/features/       对 Paper 自有源码的补丁
│   └── cerulean-patches/             CeruLean 自有补丁（features 整体补丁 / src 逐文件补丁）
├── tools/verify/              复现与漂移校验工具（本阶段已可用，见下）
├── docs/                      立项与约束 / pin 策略 / 校验手册
└── .github/workflows/verify.yml   CI：上游复现校验 + 负向自测 + 漂移巡检
```

---

## 快速开始：验证"上游发行物是可复现的"

工具零第三方依赖（JDK 17+ 单文件源码运行），补丁器直接取自已通过哈希校验的官方容器（与 Paperclip 官方运行时一致）。

```sh
# 1) 复现上游 Paper 发行 jar 的全链哈希（容器 → 原版 bundler → 内层 server jar → 补丁 → 输出）
tools/verify/cerulean-verify upstream --version 26.3

# 2) 证明校验器不是空转：篡改补丁/依赖库必须被检出
tools/verify/cerulean-verify selftest --version 26.3

# 3) 检查一个 paperclip 容器（官方或我们自己的发行物）
tools/verify/cerulean-verify inspect <jar>

# 4) 类级漂移报告（例如 26.2 → 26.3，用于 rebase 影响面评估）
tools/verify/cerulean-verify diff <jarA> <jarB> --top 15 --json drift.json
```

缓存位于 `~/.cerulean-cache`（`CERULEAN_CACHE` 可覆盖）。首次运行会下载原版 bundler（约 62 MB）。

---

## Phase 0 验收（本阶段已达成）

| 验收项 | 状态 | 证据 |
|---|---|---|
| 有 pin 登记与层叠策略文档 | ✅ | `gradle.properties`、`docs/01-上游层叠与pin策略.md` |
| 能复现上游发行物并逐字节校验 | ✅ | `upstream --version 26.3` → **RESULT: PASS**（输出哈希 `7afb0563…` 与官方 `patches.list` 一致） |
| 校验器能检出篡改（非空转） | ✅ | `selftest` → 未篡改 PASS、篡改补丁 FAIL、篡改依赖库 FAIL → **SELFTEST: PASS** |
| 能产出"非预期漂移"报告 | ✅ | `diff` 输出新增/删除/改动类与体积变化排行 |
| CI 接入 | ✅ | `.github/workflows/verify.yml`（复现校验 + 自测 + 每日漂移巡检） |
| 三方补丁栈目录就位 | ✅ | `patches/**`（Phase 1 填充内容） |

## 下一步（Phase 1 预告）

1. 接入 paperweight 构建（`./gradlew` 一键产出 jar），消费 `patches/**`；
2. 迁移 AzureBranches 语义资产（命令方块 OCC 栈、`AzLinear v4` 保持默认 `mca`）；
3. 冒烟脚本参数化（去硬编码 Windows 路径/端口）后进 CI，作为**单线程语义基线**；
4. 发布渠道策略：只在 soak + 冒烟全绿后置 STABLE。
