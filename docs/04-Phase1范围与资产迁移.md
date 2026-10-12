# 04 · Phase 1 范围与资产迁移

## 范围修正（基于阅读 AzureBranches 资产后的结论）

原计划把"命令方块 OCC 栈"放进 Phase 1。**读完资产后修正为：OCC 栈属 Phase 2。**

理由：`com.azurebranches.command.*`（9 个类）解决的是**区域化多线程下**命令方块链的语义断裂问题，它依赖 Folia 的区域/所有权模型与 `moonrise/concurrent` 的自建线程池。Phase 1 是单线程语义等价版，**没有多线程就没有它要解决的问题**；提前搬进来只会把两套风险叠在一起。

| 资产 | 归入 | 说明 |
|---|---|---|
| `AzLinear v4` 存储引擎（7 个类 + 规范） | **Phase 1（可选）** | 单线程下也成立；保持默认 `mca`，需补 kill-test 证据 |
| 冒烟测试（3 个 RCON 脚本） | **Phase 1** | 参数化后进 CI；其中 EXP 专项用例在 Phase 2 才有意义，先作为基线对照 |
| 文档生成器（5 个 `build-*-doc.py`） | Phase 1 | 保证技术文档与实现同步产出 |
| `command/` OCC 栈（9 个类） | Phase 2 | 与区域化一起移植 |
| `moonrise/concurrent/`（3 个类） | Phase 2（需重验） | Paper 主线已有自己的 Moonrise，自建线程池可能与之冲突 |
| `azurepatches-src`（17 个命令相关补丁） | Phase 2 | 与 Paper 上游命令补丁重叠最多，冲突集中区 |
| CI 分层补丁布局 | Phase 1 | 已落地为 paperweight fork 结构 |

## Phase 1 交付清单

| # | 事项 | 状态 | 证据 |
|---|---|---|---|
| 1 | paperweight patch-only fork 脚手架 | ✅ | `build.gradle.kts` / `settings.gradle.kts` / `cerulean-*/build.gradle.kts.patch`（`git apply --check` 通过） |
| 2 | 构建文件补丁（品牌 + 模块改名 + fork 注册） | ✅ | 两个 `.patch`，基于 Paper 26.3 真实文件生成 |
| 3 | 真实构建跑通（applyAllPatches） | 🔄 | 见第 5 节"构建实测" |
| 4 | 参数化 RCON 冒烟基线 | ✅ | `tools/smoke/`（rcon.py + smoke_baseline.py + server-up/down + run-smoke） |
| 5 | CI：复现校验 + 负向自测 + 构建 + 冒烟 | ✅ | `.github/workflows/verify.yml` + `build.yml` |
| 6 | 上游复现校验工具 | ✅（Phase 0） | `tools/verify/` |
| 7 | 发布渠道策略 | ⏳ | 待 Phase 1 收尾时定稿 |

## 冒烟基线的用法

```sh
# 对任意 Paper/CeruLean paperclip jar 一键跑：
CERULEAN_EULA=TRUE tools/smoke/run-smoke.sh <paperclip.jar> ./smoke-report
# 只连已开着的服务器（不负责启停）：
tools/smoke/smoke_baseline.py --host 127.0.0.1 --port 25575 --password xxx \
    --expect-mc 26.3 --json report.json
```

用例覆盖：RCON 连通、版本、`setblock`+NBT、always-active 脉冲命令方块、**3 连链按序执行**、链在失败后的行为（OBSERVED，只记录不判定）、记分板往返、`fill`/`clone`、`execute if` 条件链、`/tps`、`save-all flush`。

**OBSERVED 是刻意设计**：像"链式命令方块在前序失败时是否继续"这类语义，正是后续区域化必须逐条对齐的基线数据，先记录实测值而不是写死期望。

## 尚未做（明确不在 Phase 1 基础工作内）

1. `AzLinear v4` 的实际移植（需先有构建产物与回归基线）；
2. 面向玩家的发布、下载页、自动更新；
3. 区域化补丁的任何移植（Phase 2）。
