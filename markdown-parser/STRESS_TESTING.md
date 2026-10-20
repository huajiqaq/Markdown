# 隔离进程压力测试

```bash
./gradlew :markdown-parser:stressTest
# 单独重放一类输入
./gradlew :markdown-parser:stressTest --tests '*when_delimitersAreDenseOrUnmatched'
```

普通 `jvmTest` 不重复执行这组测试；CI 的独立 `Parser Stress Tests` 作业在 PR、master 推送和手动触发时运行。
`stressTest` 每次调用都会启动新进程，不复用之前的测试结果或构建缓存。

## 输入与验证

| 子进程 | 输入规模 |
|---|---|
| `deep-nesting` | 512 层块引用、256 层强调嵌套 |
| `long-line` | 1,048,600 个 UTF-16 代码单元的单行，包含中文和代理对 |
| `dense-delimiters` | 16,384 组强调、代码、链接组合；131,072 个未闭合左方括号 |
| `unclosed-blocks` | 约 223 万字符的未闭合代码围栏和 HTML 注释；约 52 万字符的未闭合数学块 |

CommonMark、GFM、Extended、MarkdownExtra 分别执行完整解析、尾部编辑及 16 字符合并策略下的流式解析。
数学块只用于支持该语法的 Extended 方言。测试强制物化行内内容，并通过迭代遍历检查 AST 的父节点、坐标、
循环引用、真实嵌套深度和分隔符节点数量；纯文本及大块内容在编辑和流结束后核对完整性。

## 资源预算与失败判定

- 每类输入启动一个独立 JVM：`-Xms16m -Xmx256m -Xss1m`。
- 每个子进程限时 45 秒，包括启动、输入生成、所有方言及校验。超时强制终止进程及其后代，最多另等 5 秒清理。
- `-XX:+ExitOnOutOfMemoryError` 使堆耗尽直接退出；栈溢出、非零退出或缺少完成标记都会使父测试失败。
- Gradle 压力测试任务限时 8 分钟，CI 作业限时 10 分钟。
- 256 MiB 是 Java 堆上限，不是整个进程的 RSS 上限；输入规模与预算是这组测试的边界，不是生产解析器的输入限制。

另外四个框架自检主动产生非零退出、无完成标记的零退出、超时和 32 MiB 堆耗尽，验证父测试确实能拒绝这些情况。
这些 `fixture-*` 日志中的异常是预期结果，不代表真实压力场景失败。

## 诊断产物

- `build/reports/parser-stress/<case>.log`：输入规模、方言、阶段、AST 统计、阶段耗时及异常。
- `build/reports/parser-stress/<case>.txt`：堆、栈、超时预算、总耗时、退出码和超时标志。
- `build/reports/tests/stressTest/`、`build/test-results/stressTest/`：HTML 和 JUnit XML 报告。

CI 无论成功失败都上传 `parser-stress-reports`。父进程把子进程输出直接重定向到文件，避免管道堵塞；
断言只读取日志末尾 8 KiB，避免诊断输出反过来耗尽测试进程。
