# 补丁栈（三方分层）

顺序即应用顺序，冲突面自下而上收窄：

| 层 | 目录 | 内容 | Phase 0 状态 |
|---|---|---|---|
| L0 | —（不存储） | Mojang 原版 `server.jar`，由 `download-context` 哈希锁定 | 由校验工具复现 |
| L1 | 上游自带 | Paper 的 941 个逐文件补丁 + 37 个 features 补丁 | 从 Paper 发行物取用，不落库 |
| L2 | `minecraft-patches/features/` | Folia 式区域化补丁（对 Minecraft 源码） | 待 Phase 2 移植 |
| L3 | `paper-patches/features/` | 对 Paper 自有源码的挂钩补丁 | 待 Phase 2 移植 |
| L4 | `cerulean-patches/features/`、`cerulean-patches/src/` | CeruLean 自有功能与命令语义补丁 | 待 Phase 1 填充 |

约定：

- **features/**：整体特性补丁，按 `NNNN-描述.patch` 命名，编号即应用顺序（对齐 Paper/Folia 惯例）。
- **src/**：逐文件补丁，路径与被改文件一一对应（对齐 Paper `patches/sources` 惯例）。
- 每个补丁文件头部保留来源与许可证署名（GPLv3 派生声明，见 `docs/00-立项与约束.md`）。
- 任何新增补丁必须同时回答一个问题：**它在上游漂移时靠什么被检出？**（答案通常是"有对应的回归用例"）
