# 25 · Build 3 结项与发布说明（26.3-build.3-SNAPSHOT）

> 发布基线：`v26.3-build.2` → 本次 `v26.3-build.3`。
> 工程基线：`d5cf7e9`（0034 之后）；本版另含 `0035` 与语义层探针线（docs/23）。
> 交接文档：docs/24（Build 3 范围）、docs/22（EXP7Plus）、docs/23（活塞/更新语义探针）。

## 1. 一句话

**Build 3 收口**：修好 EOC 遗留的三条命令、落地 EXP7Plus 的非存储部分、把 `/loot` 改非阻塞，
另外补上一条**一直存在但没人修的底层缺陷**——控制台/RCON 命令读方块必 NPE（新补丁 `0035`），
并第一次给"活塞 / 方块更新语义"建起可回归的探针线与 L3 闸门。
**EXP7 存储引擎（bLinear v4）按用户决定整体推迟到 Build 4。**

## 2. 本版内容

| 补丁 / 项 | 内容 | 验证依据 |
|---|---|---|
| `0032` | `/reload`、`/datapack`、`/spreadplayers` 修好 | 命令矩阵 16/0；`/reload` 日志 `Loaded N advancements` 1→2；`/spreadplayers` 实体位置变化 |
| `0033` | EXP7Plus 非存储：`Entity.getTeam()` 解禁 + `PlayerList` 登录计分板同步 | 代码审查 + 回归无回退（协议 777 挡住真客户端，见 §5.4） |
| `0034` | `/loot spawn` 改非阻塞派发 | 命令验收 2/2；负载下不再 `cross-region command timed out` |
| **`0035`（本版新增）** | **命令层方块读取派发**：`BlockInWorld.getState()` 投递到拥有该区块的区域线程（`AwaitableDispatch.call`） | CeruLean 上 `execute if block` 由 **NPE → Test passed**；M1/矩阵实测（docs/23 §11.2） |
| 语义层探针线 | `tools/smoke/rigs/`（`rig_common.py` / `m1_piston_race.py` / `run_rigs.py` / 6 台装置 JSON）+ 回归闸门 **L3** | docs/23 §11 |
| 测试基建 | `run_suite.py` 增 L3；`server-up.sh` 固定 `pause-when-empty-seconds=0`；`semantics_acceptance.py` | 见 §3 |

## 3. 验收证据

### 3.1 全量回归闸门（发布前本机实测）

基线树（`d5cf7e9` + 仅测试基建差异）**7 PASS / 0 FAIL / 0 SKIP**（exit 0）：

    PASS  L0 基座活性                                    (10s)
    PASS  L1 可观测面                                    (18s)
    PASS  L2 T1/T2/T3 scoreboard+clone                   (132s)  43/0
    PASS  L2 R1 scoreboard 压力                          (420s)  7/0
    PASS  L2 T4 EXP 链（SAFE+EXP 两轮起服）              (854s)  SAFE 11/0、EXP 28/0、OCC 13/0
    PASS  L2 R1 存档往返                                 (64s)
    PASS  L2 区域化命令（矩阵 + 混合压力）               (584s)  命令 16/0、混合压力 4/0

这是 `0033`/`0034` 之后**第一次**在一轮全量套件里同时全绿（docs/24 §3.2 的遗留问题就此关闭）。

### 3.1.1 发布版 jar 上的最终闸门（含新的 L3）

在 tag 提交上以 `BUILD_NUMBER=3` 构建的发布 jar（`Implementation-Version: 26.3-3-e2cd89b`）上再跑一次完整套件：

    PASS  L0 基座活性                                     (10s)
    PASS  L1 可观测面                                     (19s)
    PASS  L2 T1/T2/T3 scoreboard+clone                    (132s)
    PASS  L2 R1 scoreboard 压力                           (422s)
    PASS  L2 T4 EXP 链（SAFE+EXP 两轮起服）               (851s)
    PASS  L2 R1 存档往返                                  (59s)
    PASS  L2 区域化命令（矩阵 + 混合压力）                (578s)
    PASS  L3 语义层（活塞/更新语义：M1 + M6/M7 对照）      (161s)

**8 PASS / 0 FAIL / 0 SKIP**（exit 0）。日志留档：
`AzureCores/reports/evidence/build3-final/suite-final-26.3-build.3.log`。

### 3.2 语义层（docs/23）

| 项 | vanilla | CeruLean | 结论 |
|---|---|---|---|
| P0 最小检查 | 7/7 PASS | 7/7 PASS（`0035` 后） | 一致 |
| M1 双活塞竞争（20 轮） | A=20 / B=0 | **B=20 / A=0** | **不一致（FAIL）**，见 §4 |
| M2–M5 机制前置装置 | 6/6 PASS（3 轮） | 6/6 PASS（2 轮） | 逐条 detail 一致（**不进 L3 闸门**：m2 的落地 tick 判据在联跑里出现过抖动） |
| **真正的 TNT 复制** | — | — | **未实现**：装置只测"推到位置 + 落地点燃"，没有"无损失刷出"；BUD 态用 `/setblock` 造不出来（docs/23 §11.10 有五组实测） |
| M6/M7 对照 | PASS | PASS | 一致 |
| P3 跨区域 | — | — | SKIP（本机只有 1 个区域） |
| Paper 档 | — | — | SKIP（本机无 Paper 26.3 jar） |

## 4. 本版最重要的发现：活塞更新顺序是**位置敏感**的，两档在部分坐标上确定性相反

§P1 字面坐标 (0,-50,0) 上，同一台机器、同一套装置、都是 20/20 全胜、0 unknown：

- **vanilla 26.3**：朝西的活塞 A 胜（A=20）
- **CeruLean 26.3**：朝北的活塞 B 胜（B=20）

**但把装置整体平移，胜者会跟着坐标翻转**（每格 100/0、无抖动）：

| origin | vanilla | CeruLean | 一致 |
|---|---|---|---|
| (0,-50,0) | A（20/20） | B（20/20） | ✗ |
| (2,-50,2) | B | B | ✓ |
| (4,-50,4) | B | B | ✓ |
| (8,-50,8) 区块中心 | B（20/20） | A（20/20） | ✗ |
| (12,-50,12) | B | B | ✓ |

所以准确的说法是：**M1 是"位置敏感的更新顺序指纹"，不是与坐标无关的不变量**。
5 个采样点里 3 点一致、2 点确定性相反 ⇒ 按 docs/23 §6.1，**相反的两个点记 FAIL**
（不是 SKIP：不是环境缺失），但**不能**概括成"CeruLean 整体反了"。
控制实验排除了放置顺序、单活塞行为与读路径；候选机制与下一步见 docs/23 §11.4 / §11.9。

**这条不影响本版其余验收**：L3 闸门比对的是 **CeruLean 自身的回归基线**（`expected/m1_cerulean.json`，
单点 (0,-50,0) 的 B=20），作用是"行为一旦再变就红"；跨基线的差异由 docs/23 §11.4 的位置扫描持续跟踪。
要用 M1 当跨基线闸门，需要先做**多点签名**（固定坐标组 × 每点 N 轮 × 比位图），这是 Build 4 的待办。

## 5. 未做 / 未验证（如实）

1. **EXP7 存储引擎（bLinear v4）**：按用户决定推迟到 Build 4（docs/24 §4 的四步方案、docs/22 §3 的风险评估保持有效）。
2. **P4（`/tick freeze` / `/tick step`）**：未移植，因此 §4 的差异只能定位到"处理顺序"这一层。
3. **P3 跨区域**：本机只能构造 1 个区域 ⇒ 永远 SKIP，不得伪装成 PASS。
4. **TNT / 地毯 / 铁轨复制都没有实现**（装置只测机制前置）。用 `/setblock` 造不出 BUD 态 TNT，
   五组实测见 docs/23 §11.10；真复制需要实体 + 多 tick 预置的独立装置，留 Build 4。
   另：Paper/CeruLean 默认 `allow-piston-duplication=false`（H3），即使装置做出来，三档也会分叉——
   那属配置差异，不是语义 FAIL。
5. **M5 更新抑制（CCE）**：契约不可表达（NBT / >512 层），只保留 S5 代理证据。
6. **`0033` 的两项无运行时证据**：服务端上报协议 777，`minecraft-data` 无定义 ⇒ mineflayer 无法握手
   （`tools/smoke/player_join_probe.js` 已备好）。
7. **多区域真并发**：单区域冒烟构造不出跨区域冲突（docs/17 §4 同结论）。

## 6. 已知限制

1. `/scoreboard` 仍无全局 tick 独占（0029 用锁内 CAS 收窄窗口，`/cerulean exp` 的"竞争"计数是量化暴露）。
2. `execute if block` 这类同步读现在会**投递并等待**目标区域（1500 ms 上限）：调度线程 < 2 时会快速失败，
   属"未验证"而不是"坏"。`/cerulean regions` 可看线程数。
3. `forceload add` 在 CeruLean 上无回显（cosmetic），读写本身可用。

## 7. 升级提示

- 产物：`cerulean-paperclip-26.3-build.3-SNAPSHOT.jar`（+ `.sha256`）
- **冒烟/探针服**：26.3 默认 `pause-when-empty-seconds=60` 会让"无玩家 + 纯 RCON"的服在 60 秒后停 tick
  （症状：setblock 成功但活塞不动）。`tools/smoke/server-up.sh` 已固定为 `0`；自己的运行目录请自行检查。
- **gamerule 命名**：26.3 是 snake_case（`tnt_explodes`）。
- 本版不需要改任何玩家侧配置；`0035` 只影响命令读方块这一条路径。

## 8. 复现与发布

    cd /storage/Users/currentUser/Desktop/AzureCores/CeruLean
    tools/gradle-local.sh applyAllPatches
    BUILD_NUMBER=3 tools/gradle-local.sh --no-daemon --no-configuration-cache :cerulean-server:createPaperclipJar
    export CERULEAN_EULA=TRUE RCON_PORT=25575 RCON_PASSWORD=test1234
    python3 tools/smoke/run_suite.py            # L0/L1/L2/L3，约 55-65 分钟

发布（附件上传必须绕过代理，docs/18 §3.3）：

    env -u HTTPS_PROXY -u HTTP_PROXY -u ALL_PROXY NO_PROXY='*' \
      gh release create v26.3-build.3 --prerelease --latest=false \
      --title "26.3-build.3-SNAPSHOT" --notes-file notes.md <jar> <jar>.sha256
