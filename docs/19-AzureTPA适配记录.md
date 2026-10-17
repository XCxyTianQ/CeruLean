# 19 · AzureTPA 在 CeruLean 26.3 上的适配与验收

日期：2026-10-17 · 插件版本：**AzureTPA 1.2.0** · 服务端：本地 paperclip（Folia 26.3-DEV-main@b5fd848）

## 一句话

同作者的传送插件 **AzureTPA**（独立仓库 `03-服务端插件/AzureTPA`，分支 `cerulean-26.3`）
已按 **MC 26.3 / Folia 26.3** 的 API 与线程模型完成适配：在 CeruLean 上装载、
10 个命令全部注册并执行，控制台级 12 项验收 **12/12 通过**。玩家交互用例需要真实 26.3 客户端，
清单见插件仓库 README 与 [完整记录](../../reports/AzureTPA-CeruLean-26.3-适配.md)。

## 适配要点（与 CeruLean 直接相关）

| 点 | 做法 |
|---|---|
| API 对齐 | `paper-api:26.3.build.175-stable`（= `gradle.properties` 的 `paperBuild=175`）；`plugin.yml` `api-version: "26.3"` |
| 跨区域读 | `/tpahere` 接受时，请求方与执行命令的目标不在同一区域：改为 `anchor.getScheduler().run(...)` 在 anchor 自己的 region 线程上读 `getLocation()` |
| 跨区域搬迁 | 一律 `Entity#teleportAsync`；不自己切线程、不阻塞等待 |
| 异步回调 | 到期/落盘走 `AsyncScheduler`；配置在 `onEnable` 时快照成不可变 Map，异步线程不读 `YamlConfiguration` |
| 控制台 | `/delwarp` 只改仓库、不需要玩家位置，控制台/RCON 可执行（修正了旧版与 README 的不一致） |

插件没有使用任何 CeruLean 专属 API，也没有往 `cerulean-server/minecraft-patches` 里加补丁：
它保持"按上游 `paper-api` 编译的独立插件"这一形态，服务端补丁层不需要为它改动。

## 实测

- 装载日志：`AzureTPA v1.2.0 enabled | Folia (MC 26.3) | request timeout 120s | max homes 5`
- 控制台探针：`python3 ../../03-服务端插件/AzureTPA/smoke/cerulean_probe.py --port 25576 --password test1234`
  → `12 通过 / 0 失败`
- 持久化：预置 `warps.json` → 启动 `Warps: 1` → `/delwarp testwarp` → 文件 `{}`

## 复现

```sh
# CeruLean 侧：本地 jar + 插件 jar
cd CeruLean
CERULEAN_EULA=TRUE MC_PORT=25599 RCON_PORT=25576 RCON_PASSWORD=test1234 \
  tools/smoke/server-up.sh cerulean-server/build/libs/cerulean-paperclip-26.3-SNAPSHOT.jar smoke-azuretpa

# 插件侧：26.3 控制台验收
cd ../03-服务端插件/AzureTPA
python3 smoke/cerulean_probe.py --port 25576 --password test1234
```

> `tools/smoke/server-up.sh` 自本次起显式写 `white-list=false`：Paper 26.3 生成的服务器配置
> 默认 `white-list=true`，会让冒烟/验收用的客户端登录即被踢（与插件无关）。

