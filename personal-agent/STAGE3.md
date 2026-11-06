# 第三课：源码研究

在本地仓库里追踪入口和调用链，并为分析附上能回到源码核对的行号。源码研究沿用前两课的 `AgentRuntime`，由 `--repo` 选择一组新的工具和研究提示。

## 运行

从 `personal-agent` 目录运行。默认使用离线回放，依次调用三个源码工具并生成一条可核对的引用。

```sh
bash run.sh --repo "$PWD" --messages
```

真实模型可以根据问题选择搜索词、文件与读取范围。下面的命令使用项目 `.env` 里的 Key。选择你希望让 DeepSeek 分析的目录，问题、工具结果和读取的源码片段会发送给服务端。

```sh
bash run-deepseek.sh --repo "$PWD" \
  --question 'Main 如何组装 AgentRuntime？请引用入口和工具调用的源码行。'
```

`--repo` 也接受其他本地目录的绝对路径。相对路径以运行命令时的工作目录为基准。源码模式默认最多请求模型 8 轮，使用 `--max-turns` 可修改。离线回放按固定顺序演示工具链，真实模型会根据问题选择工具，也可能一轮提出多个调用。

## 看懂一次运行

| 工具 | 输入 | 返回的数据 |
| --- | --- | --- |
| `list_sources` | 字面路径筛选词，空字符串表示列出任意匹配文件 | 最多 40 个仓库相对路径和截断标记 |
| `search_source` | 单行字面关键词，忽略大小写 | 最多 30 条命中，每条包含文件、行号和预览 |
| `read_source` | 相对路径、起始行和结束行，行号从 1 开始 | 最多 40 行源码，附实际返回的行号 |

搜索命中帮助模型选定下一步读取范围。读取工具把实际返回的行登记到本轮证据表。报告用 `[[路径:行号]]` 或 `[[路径:起始行-结束行]]` 引用源码。程序检查每个引用的文件和行是否已读，缺少引用或引用了未读范围时返回 `SOURCE_CITATION_INVALID`。引用校验确认来源位置，报告推理仍需结合原文判断。

单次扫描最多检查 3000 个目录项，深度上限为 20。搜索预算为 4 MiB，每个文件上限为 512 KiB，单行预览上限为 300 个字符。工具使用源码和常见文档类型白名单，跳过点开头的路径、符号链接及常见构建、依赖和凭据目录。结果中的 `truncated` 标记提醒你这次扫描的覆盖范围有限。

## 在 Trae 里逐步看状态

打开 `personal-agent`，选择 **Stage 3 · Source research · Replay**。也可以在终端启动挂起的 JVM，然后使用现有的 **Stage 2 · Attach localhost:5005**。

```sh
bash run.sh --debug --repo "$PWD" --messages
```

| 顺序 | 源码位置 | 观察内容 |
| --- | --- | --- |
| 1 | `Main.run` 中创建 `SourceResearch` 后 | `directory`、`research` 和 `tools.definitions()` |
| 2 | `AgentRuntime.run` 中调用 `tools.execute(call)` | `call.name()`、`call.arguments()` 和工具调用 ID |
| 3 | `SourceWorkspace.search` 的 `DEBUG S3-2` | `query`、`scan.files()`、`hits` |
| 4 | `SourceWorkspace.read` 的 `DEBUG S3-3` | `relative`、`start`、`end` 和 `excerpt` |
| 5 | `AgentRuntime.run` 中追加工具消息之后 | `result.text()`、`messages.getLast().toolCallId()` |
| 6 | `SourceResearch.verifyCitations` 的 `DEBUG S3-4` | `evidence`、当前引文的路径和起止行 |

第一遍观察每次工具调用如何决定下一步。第二遍在同一目录用真实模型提问，比较它选的搜索词和读到的片段。也可以用 `--max-turns 3` 跑回放，观察已经读取源码时循环如何因预算而停下。

## 验证

回放已在本仓库通过，四轮模型请求依次完成发现、检索、读取和带引用的报告。真实 DeepSeek 使用两个临时生成的 Java 文件分析 `GreetingApp.main → GreetingService.greet`，三轮请求完成，七处引文通过检查。真实调用只使用该合成样本。

本地测试通过 51 项，包含第三阶段的四组用例。使用 JDK 自带的 `jdb` 实际命中搜索、按行读取和引文校验三个断点，查看查询词、起始行和证据表，再继续运行至报告输出。Trae 的启动配置通过了 JSON 检查，图形界面的启动留待本机验证。
