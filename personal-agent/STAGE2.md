# 第二课：在 Trae 里调试真实模型 Agent

这一课把第一课的 `ModelClient` 接口接到 DeepSeek。你可以沿着一次请求观察：Java 消息 → API 请求 JSON → 模型响应 JSON → 工具参数 → 文件内容 → 下一轮消息 → 最终回答。

## 本机环境与准备

本机已确认安装 Trae CN 3.4.14、Java 语言扩展和 Java Debugger 0.60.0。项目内的 `.vscode/launch.json` 按该扩展声明的 Launch / Attach 字段生成。JDK 21 路径已按本机实际位置填写在 `.vscode/settings.json`。

**在 Trae 中打开这个目录作为工作区：**

```text
/Users/bytedance/Documents/git_repo/langgraph4j-deepagents/personal-agent
```

请直接打开 `personal-agent`，这样配置里的 `${workspaceFolder}` 对应的就是本课项目目录。等待 Java 扩展完成项目识别。`pom.xml` 提供 Maven 项目和 Jackson 依赖信息；调试前任务直接调用本机 javac，不需要你全局安装 Maven。

第二阶段新增 Jackson 2.18.2 作为 JSON 库。准备脚本优先使用项目 `.deps/`，缺失时复制本机已有的 Maven 缓存。若换机器没有缓存，先用 IDE 解析依赖，或使用该机器的 Maven 执行：

```sh
mvn dependency:copy-dependencies -DoutputDirectory=.deps
```

## 先练一次完整回放

打开 Trae 的“运行和调试”面板，选择 **Stage 2 · Replay · Step by step**，点击绿色调试按钮。配置启用了 `stopOnEntry`，程序会先停在入口，调试前会自动编译并携带局部变量信息。

这个模式不需要 Key，不访问网络；它生成 DeepSeek 协议格式的确定性响应。真实的 JSON 编码、解析、工具执行和消息回填都会走一遍，适合先熟悉断点。输出会明确标记 `replay`。

常用操作可从调试工具栏执行；默认键位通常是：

| 操作 | 默认快捷键 | 用途 |
| --- | --- | --- |
| Continue / Resume | F5 | 跑到下一个断点 |
| Step Over | F10 | 执行当前行，不进入被调用方法 |
| Step Into | F11 | 进入你关心的方法 |
| Step Out | Shift+F11 | 从当前方法返回调用方 |
| Stop | Shift+F5 | 结束本次调试进程 |

macOS 功能键设置可能需要按住 Fn；以 Trae 工具栏显示的快捷键为准。默认跳过 JDK 和 Jackson 内部实现，便于专注自己的代码。

## 按执行顺序设置断点

在源码行号左侧点击设置断点。优先停在下表所列的可执行语句上，注释中的 `DEBUG S2-*` 用来帮助定位。

| 顺序 | 文件与语句 | 观察变量 | 预期理解 |
| --- | --- | --- | --- |
| 1 | `Main.java`：`var runtime = new AgentRuntime(...)` | `options`、`modelClient`、`tools` | 配置选择模型和工具，运行时只依赖接口 |
| 2 | `AgentRuntime.java`：`reply = model.reply(...)` | `turn`、`messages` | 第一轮只有一条 USER 消息 |
| 3 | `DeepSeekCodec.java`：`return write(requestTree)` | `requestTree`、`wireMessages`、`tools` | API 层在运行时历史前加入 SYSTEM，并生成工具 JSON Schema |
| 4 | `DeepSeekModelClient.java`：`transport.post(requestJson)` | `requestJson`、`transport` | 查看完整 JSON body；此处不包含认证头 |
| 5 | `DeepSeekModelClient.java`：`codec.decode(responseBody)` | `statusCode`、`responseBody` | 先检查 HTTP 状态，再解析模型协议 |
| 6 | `DeepSeekCodec.java`：判断 `finishReason` | `responseTree`、`finishReason`、`assistantMessage` | 回放第一轮为 tool_calls，第二轮为 stop |
| 7 | `DeepSeekCodec.java`：`decodedCalls.add(...)` | `argumentsNode`、`arguments`、`call` | arguments 在 HTTP 响应中是 JSON 字符串，解析后才变成 Java Map |
| 8 | `AgentRuntime.java`：`tools.execute(call)` | `batch`、`call`、`messages` | 模型响应已进入历史，但工具还未执行 |
| 9 | `ReadTextTool.java`：`Files.newInputStream(...)` | `arguments`、`relative`、`real` | 文件内容由 Java 程序读取，先检查工作目录边界 |
| 10 | `AgentRuntime.java`：追加 tool 消息后的 `trace.accept(...)` | `result`、`messages` | 真实读取内容已回填，此时历史有三条消息 |
| 11 | 第二次命中 `model.reply(...)` | `turn`、`messages` | 看清工具调用 ID 与 toolCallId 怎样关联 |
| 12 | 第二次命中 `codec.encode(...)` | `requestTree.messages` | API 请求有四条：SYSTEM + USER + ASSISTANT(tool_calls) + TOOL |
| 13 | `AgentRuntime.java`：`messages.add(Message.answer(...))` | `answer`、`messages` | 模型返回最终响应，运行时结束 |

建议第一遍只设 2、4、5、9、10 五个断点；第二遍再进入 Codec 展开所有字段。避免一开始把每个方法都断住，导致难以看清一轮任务。

在 Variables 面板展开 `messages`、`toolCalls`、`arguments`；在 Watch 中按当前栈帧添加适用表达式：

```java
messages.size()
messages.getLast().role()
messages.getLast().toolCallId()
messages.getLast().text()
requestJson
statusCode
responseBody
finishReason
arguments.get("path")
```

变量只在所在方法的作用域内可见。某个表达式在当前栈帧显示 unavailable 时，切换到对应的 Call Stack 帧，或移到它声明之后。停在一行时，这一行通常尚未执行；要查看赋值结果，先 Step Over 一次。

## 配置 .env，再切换到真实 DeepSeek

打开项目根目录的 `.env`，在本机填写：

```dotenv
DEEPSEEK_API_KEY=在这里填写你自己的Key
DEEPSEEK_MODEL=deepseek-flash
DEEPSEEK_TIMEOUT_SECONDS=60
```

`.env` 已加入 `.gitignore`；可提交的 `.env.example` 只包含空 Key 模板。终端和 Trae 都由 Java 的 `EnvConfig` 读取同一份 `.env`，不把它作为 shell 脚本执行。已存在的进程环境变量优先于文件值。

在同一个调试面板选择 **Stage 2 · DeepSeek · .env**，即可从配置文件启动真实模型。这个配置使用 Debug Console，不把 Key 放进可见的终端启动命令。修改 `.env` 后重新启动进程生效。

当前官方默认模型为 `deepseek-flash`；使用官方 `POST https://api.deepseek.com/chat/completions`，显式 `thinking.type=disabled`、`stream=false`、`max_tokens=1024`、`tool_choice=auto`。这一课先观察非思考模式的工具循环。[官方入门](https://api-docs.deepseek.com/)、[工具调用](https://api-docs.deepseek.com/guides/tool_calls)、[请求协议](https://api-docs.deepseek.com/api/create-chat-completion)。

这次在断点处会看到真实模型选择的工具调用 ID、说明文字和参数；回答内容及调用轮次可能变化。默认提示要求先读取资料再解释，你也可以在 Launch 配置的 `args` 中增加 `--question` 和自己的问题。

真实模式会把问题、工具定义及读取到的示例资料发送到 DeepSeek；默认工作目录仍是自带 `examples/`。

**不要把 API Key 加到 Watch 或日志里，也不要展开认证头或分享包含 Key 的调试截图。** 需要观察的是 `requestJson` 与 `responseBody`；认证只在传输层添加。思考模式和 `reasoning_content` 的跨轮保留不在本课范围内。

## 终端启动，Trae 连接调试

如果编辑器启动 Java 项目还在导入，先用确定的终端入口，再用 Attach 配置连接，仍然能在源码中逐步调试。

在 Trae 集成终端执行：

```sh
bash run.sh --debug --provider replay --messages --wire
```

看到监听 `5005` 的提示后，选择 **Stage 2 · Attach localhost:5005** 并启动调试。JVM 启动时已挂起，所以有机会在程序运行前设置断点。

真实模式从 `.env` 读取配置：

```sh
bash run-deepseek.sh --debug --messages
```

脚本会定位项目自己的 `.env`，先启动并挂起 JVM，之后连接同一个 Attach 配置。若 Key 未填写，恢复执行后会给出配置提示。

不需要调试器时：

```sh
bash run.sh --provider replay --messages --wire
bash run-deepseek.sh --messages
```

`--wire` 显式打印请求和成功响应的 JSON body，不打印 Authorization。默认不打印这些 body；资料较私密时只在本机调试器中查看。成功响应的完整 body 和工具输出都会包含任务文本。

## 三个实验

1. **真实选择工具。** 在 DeepSeek 模式用 `--question "只回答你好，不需要读取文件"`，观察模型是否直接返回 stop；再换成要求阅读资料的问题，对比消息变化。真实模型行为有不确定性，以实际结果为准。
2. **观察错误反馈。** 用 `--file missing.md`，停在工具执行后，检查错误结果怎样变成 TOOL 消息。随后观察真实模型是解释失败还是尝试其他工具调用。最终响应不自动等于资料读取成功。
3. **观察预算停止。** 加上 `--max-turns 1`；若模型第一轮请求工具，文件读取结果会保留，但第二轮模型请求被预算阻止。对比它与 HTTP 超时的停止原因。

## 配置、错误和验证边界

| 设置 | 默认值 / 来源 |
| --- | --- |
| `--provider` | scripted；真实调用显式选择 deepseek |
| `--model` | `DEEPSEEK_MODEL`，未设则 deepseek-flash |
| `DEEPSEEK_API_KEY` | 项目 `.env`；已设置的进程环境变量优先 |
| `DEEPSEEK_TIMEOUT_SECONDS` | 60；上限 300 |
| `--max-turns` | 4 |
| 请求输出上限 | 1024 tokens，定义在 Codec 中 |

默认没有自动重试。401 提示检查 Key，402 提示检查账户余额，429 提示稍后手动重试；错误响应体不会写入日志。HTTP 超时是等待完整响应的上限，调试器暂停整个 JVM 时，外部连接仍可能因服务器时间限制而过期。建议先在回放里慢慢练习，真实请求发出后用 Step Over 跨过网络等待。

验证命令：

```sh
bash test.sh
bash test.sh --http
```

第二条多运行仅使用本机临时 HTTP 服务和假凭证的集成测试，不访问 DeepSeek。本次只创建了空 Key 的 `.env`，真实 DeepSeek 账户和模型响应质量需填写 Key 后确认。Trae 图形界面控制曾超时，未验证图形界面的 F5 启动；本机扩展及其配置字段已核实。协议回放的 JVM 断点已实际命中 9 次，观察了运行时 1→3 条消息及 API 层 2→4 条消息。

`.env` 支持 `KEY=value`、单/双引号、注释和可选 `export` 前缀；不进行变量插值、转义序列展开或命令替换。配置格式错误只报告行号，不回显原行或 Key。

## 本次验证记录（2026-11-04）

51 项行为与本地集成检查通过，包含第一阶段 25 项、DeepSeek 18 项、dotenv 4 组和 HTTP 4 项。实际 jdb 调试命中 9 个停点并查看数据，最终回放正常完成。API Key 格式校验与 Trae 启动方式的两项审查发现均已修正并复核。真实 API 调用与 Trae 图形界面 F5 的验证边界见上文。
