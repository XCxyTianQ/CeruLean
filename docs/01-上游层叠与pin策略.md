# 01 · 上游层叠与 pin 策略

## 层叠关系（实测确认）

```
L0 Mojang 26.3            已去混淆的原版 server.jar（bundler：内层 server jar + 39 个官方库）
  └─ L1 PaperMC/Paper     26.3 线 · 981 个补丁（941 sources / 37 features / 3 resources）+ 1,164 个自有源文件
       └─ L2 PaperMC/Folia  patch-only 仓库 · 21 个补丁 · 0 个 Java 源文件（区域化实现在补丁正文里）
            └─ L3 AzureBranches  自有三层补丁（minecraft/paper/azurepatches）· 当前 pin 在 L0=26.1.2
                 └─ L4 CeruLean  本仓库：在 L1 之上重建 L2/L3 的能力
```

关键观察：**Folia 已经是"补丁仓库"而不是"源码仓库"**（全仓 49 个文件、21 个补丁、0 个 Java 源文件），AzureBranches 沿用了这个形态并加了自己的 `azurepatches-*` 层——CeruLean 继续沿用同一工艺，只是把基座从 Folia 换成 Paper 主线。

## pin 策略

| 对象 | pin 什么 | 为什么 |
|---|---|---|
| 基座 | Paper **stable 构建号 + sha256**（`paperBuild`/`paperSha256`） | 构建号会持续增长；只有哈希能证明"我们复现的是同一个东西" |
| 上游提交 | `paperCommit`（完整 sha）+ 提交时间 | 便于在 GitHub 上核对补丁集合的变化 |
| 参考实现 | `foliaRef26_2` / `foliaRef26_1_2` | 区域化移植的来源固定在具体提交上，避免"移动的分支头"破坏补丁应用 |
| Mojang 侧 | `mojangServerSha1` + world/protocol 版本 | 校验原版 bundler，并记录协议/世界版本以便回归 |

**升级流程（Phase 1 起执行）**：

1. 改 `gradle.properties` 中的 pin；
2. 跑 `tools/verify/cerulean-verify upstream --version <新版本>` 确保新基座可复现；
3. 跑 `tools/verify/cerulean-verify diff <旧成品> <新成品>` 产出漂移报告，评估补丁冲突面；
4. 依次应用补丁栈，**每个冲突文件单独提交**并在提交信息里记录"上游因何变化"；
5. 冒烟 + 差分回归全绿后才允许把 `channel` 置为 STABLE。

## 入口点决策（记录在案）

- **Phase 1 基座**：Paper **26.3 build 175**（最新 stable 主线）。
- **Phase 2 区域化起点**：**Paper 26.2 stable（build 133）+ Folia `ver/26.2.x` 补丁序列**，理由：26.1.2→26.2 的适配上游已完成，我们只承担 26.2→26.3（实测漂移：MC 侧 75.8% hunk、Paper 侧 36.5%，优于直跳的 84.8%/47.8%）。
- 26.2→26.3 作为**独立子项目**推进，验收标准 = 漂移报告的冲突文件全部有对应回归用例。
