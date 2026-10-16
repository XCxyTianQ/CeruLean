# NOTICE · 衍生与致谢声明

CeruLean Cores（下称"本项目"）是一个 Minecraft 服务端发行版。本项目**不是** Mojang、Microsoft、PaperMC 或任何上游项目的官方项目。

## 1. 许可证

本项目以 **GNU General Public License v3.0（GPLv3）** 发布，见 [LICENSE](LICENSE)。

该许可证来自上游继承链：`Bukkit / CraftBukkit → Spigot → Paper → (Folia) → AzureBranches → CeruLean`。
因此本项目分发时必须附完整对应源码（含全部补丁），并保留本声明。

## 2. 上游衍生关系

| 上游 | 关系 | 许可 |
|---|---|---|
| [PaperMC/Paper](https://github.com/PaperMC/Paper) | **上游层叠的第一层**：本项目采用 paperweight patcher 的 patch-only fork 形态，按 `gradle.properties` 中的 `paperRef`/`paperBuild` 拉取并构建 Paper；Folia 再叠在其上 | GPLv3 |
| [PaperMC/Folia](https://github.com/PaperMC/Folia) | **当前基座（2026-10-16 起）**：区域化（threaded regions）运行时由 Folia 26.3 提供，其补丁已 vendor 进本仓库（`cerulean-server/minecraft-patches/features/0001–0011`）。`study/folia/src/` 另有**从 Folia 补丁还原的参考源码副本**（仅用于阅读与对照），阅读笔记见 `study/folia/notes/` | GPLv3 |
| [AzureBranches](https://github.com/XCxyTianQ/AzureBranches) | **同作者上一代项目**：命令方块 EXP 链（OCC）与存储引擎的资产来源；本项目规划中继承其语义目标，但实现将重做 | GPLv3 |
| [Moonrise](https://github.com/PaperMC/Paper)（已并入 Paper 主线） | 区块系统与 `TickThread`：本项目的区域拓扑接线点（`BaseChunkSystemHooks`）来自它 | GPLv3 |
| [leafpile / concurrentutil](https://github.com/PaperMC)（Paper 依赖） | 并发容器与调度器实现，随 Paper 依赖引入 | GPLv3 |

## 3. Minecraft 相关声明

本项目包含或依赖 Mojang 的 Minecraft 服务端代码，其使用受 [Minecraft EULA](https://aka.ms/MinecraftEULA) 与 Microsoft 服务协议约束。
相关代码与资源的所有权归 Mojang AB 及其关联方。

## 4. 当前状态特别说明

- 本项目基座为 **Folia 26.3**（2026-10-16 起）：`cerulean-server/minecraft-patches/features/0001–0011`
  是 vendor 进来的 Folia 补丁，`0012` 起是本项目自己的补丁；
- 原「自研区域化」路线（Phase 2，设计规格见 [docs/06-区域化设计规格.md](docs/06-区域化设计规格.md)）
  已按路线 A 收缩掉：上游 Folia 已提供 `ThreadedRegionizer` / `TickRegionScheduler` / region-aware `TickThread`，
  `cerulean-regions` 现在只剩只读诊断命令与 EXP 链支撑库；
- `study/folia/src/` 仍是**阅读用参考副本**，不参与构建。

## 5. 图标

`assets/icon.svg` 由本项目自行设计并生成（生成脚本 [tools/icon/gen_icon.py](tools/icon/gen_icon.py)，栅格化工具 [tools/icon/svg2png.c](tools/icon/svg2png.c)），与 AzureBranches 的图标为同一视觉家族但为独立设计。
