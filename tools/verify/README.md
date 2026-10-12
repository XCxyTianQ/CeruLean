# tools/verify —— 复现与漂移校验

零第三方依赖的校验工具（单个 Java 源文件 + 一个 shell 入口）。

```sh
tools/verify/cerulean-verify upstream --version 26.3          # 上游复现全链校验
tools/verify/cerulean-verify selftest --version 26.3          # 负向自测（篡改必须被检出）
tools/verify/cerulean-verify inspect <jar>                    # 查看容器清单
tools/verify/cerulean-verify diff <jarA> <jarB> --top 15      # 类级漂移报告
```

- `JAVA=` 可指定 JDK（默认 `java`；本机推荐 `~/.harmonybrew/opt/openjdk@25/bin/java`）
- `CERULEAN_CACHE=` 可覆盖缓存目录（默认 `~/.cerulean-cache`）
- 依赖外部命令：`curl`

退出码：`0` = 全部校验通过；`1` = 有失败项；`2` = 用法/参数错误。

设计取舍、校验链说明与供应链加固待办见 `docs/02-复现校验手册.md`。
