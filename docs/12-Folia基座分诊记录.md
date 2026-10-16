# 12 · Folia 基座上的 6 项冒烟失败：分诊记录与结论

> 触发：切到 Folia 26.3 基座后，旧 Paper 冒烟基线出现 `PASS 2 · FAIL 6`。
> 结论（先给）：**不是我们的 RCON 客户端，也不是 Folia 的"语义变化"泛泛之谈**，
> 而是**区域化基座下"命令触碰非本线程拥有的内容"这一已知缺口**——
> 而修复**已经存在于 AzureBranches 的 EXP 工作**中（见 §3）。

## 1. 方法：先排除客户端嫌疑（结论是否定）

假设：Folia 下命令往返更慢，短超时会让响应滞留缓冲造成**协议错位**（我们在 Paper 基座上就踩过）。

决定性实验：同一 `data get block 100 100 0 Command` 分别用
① 短首包超时（0.3 s，复现冒烟脚本写法）② 长首包超时（30 s）各发一次：

```
[short] 'Unknown or incomplete command. See below for error\ndata get block 100 100 0 Command<--[HERE]'
[long]  'Unknown or incomplete command. See below for error\ndata get block 100 100 0 Command<--[HERE]'
```

**两者完全一致**，且报错文本把服务端**实际收到的命令原样回显**（`<--[HERE]` 指向行尾）
⇒ 服务端收到的是正确命令，是**命令调度器拒绝**它，而不是响应错位。
**假设被否定**：给客户端加长超时**不能**修好这些用例（已实测）。

## 2. 失败清单与共同特征

| 用例 | 触及的内容 |
|---|---|
| `setblock_data_nbt`（`data get block … Command`） | 方块实体 NBT |
| `目标方块未出现（命令方块未触发）` | 命令方块 + 红石 |
| `execute_if_conditional` / `条件成立时分数` | `execute if …` + scoreboard |
| `scoreboard players get` | scoreboard |
| `3 格玻璃已铺`（`fill`） | 方块批量修改 |
| `克隆到 +5 格处`（`clone`） | 方块批量修改 |

共同特征：**都需要在"某个位置的拥有线程"上执行**，而我们是从 **RCON/控制台线程**
（非任何区域的拥有者）发出的 ✗。在单主线程的 Paper 上这从来不是问题；在区域化基座上，
"命令处理线程 ≠ 内容拥有线程"就是本质困难。

## 3. 修复在哪里：AzureBranches 的 EXP（用户指出的位置，已核实）

`01-服务端核心/AzureBranches`（**它本身也是 Folia 基座**，`mcVersion=26.1.2`）：

| 文件 | 作用 |
|---|---|
| `folia-server/minecraft-patches/features/0012-AzureBranches-exp-chain.patch` | 重写 `CommandBlock.java`：EXP 模式命令方块的可挂起/可恢复链 + **真正的跨区域结果语义** |
| `folia-server/minecraft-patches/features/0013-AzureBranches-exp-awaitable-setblock.patch` | 重写 `SetBlockCommand.java`（159→244 行）：`setblock` **可等待**——跨区域时投递到拥有者并等待 |
| `azurebranches-common/.../command/ExpChainSupport.java` | 线程契约：home region thread 调用 open/close/register；future 可由任意区域线程完成；延迟工作按区域批量派发 |

配套还有他们的验收脚本 `exp6-smoke.py` / `exp6plus-smoke.py` / `exp7proxy-smoke.py` ✓
——可直接作为我们重建验收套件的参照。

## 4. 对验收套件的影响与计划

1. **旧 Paper 基线在 Folia 基座上不可直接复用**：那 6 项不是"要修到通过"的回归，
   而是**基座语义缺口**的暴露点；修复属于 EXP 特性（跨区域等待语义）。
2. 验收套件分层重建（新 suite 的目标）：
   - **L0 基座活性**：调度线程数、各 world 区域数随区块加载变化、`/cerulean regions|regionat` 可用
     （已由 `tools/smoke/folia_base_probe.py` 覆盖，5 项中 4 项直接通过、第 5 项是我探针的期望错误已修）；
   - **L1 基座语义**：直接采用 Folia 自带的可观测面（Server Health Report、region profiler、watchdog）；
   - **L2 命令语义（EXP 相关）**：以 AzureBranches 的 `exp*_smoke.py` 为参照重写，
     目标是把 §2 的 6 项变成"移植 EXP 补丁后必须通过"的用例。
3. 移植前提：AzureBranches 的基座是 **26.1.2**，我们是 **26.3** ⇒ 三个补丁需按版本适配
   （`SetBlockCommand`/`CommandBlock` 在 26.3 的差异需先比对）。

## 5. 本轮结论（一句话）

分诊把"看起来像客户端抖动"的 6 项失败，落到了**基座语义缺口 + 现成修复（AzureBranches EXP）**上；
我们据此把验收套件从"Paper 语义基线"改为"Folia 基座活性 + EXP 命令语义"两层，并明确了移植路径。
