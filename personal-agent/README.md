# Java 个人 Agent 学习项目

当前已实现第一课的离线循环，以及第二课的 DeepSeek 适配、协议回放和 Trae 调试配置。第二课请先阅读 [在 Trae 中逐步调试](STAGE2.md)。真实 DeepSeek 调用需要你在本机 `.env` 填写 API Key；离线验证结果不代表真实账户已连通。

下文保留第一课的学习步骤。

本目录是独立的 Java 21 学习项目，目标逐步演进为源码研究助手（A）和 Markdown 知识助手（B）。第一课聚焦模型、工具与运行时怎样形成闭环。

**第一课默认使用 `ScriptedModelClient`：它按固定规则发出一次读取请求，再引用读取结果。** 第二课的 `DeepSeekModelClient` 实现同一个 `ModelClient` 接口，使用 `--provider deepseek` 选择真实调用。

## 运行

在仓库根目录执行：

```sh
bash personal-agent/run.sh
bash personal-agent/run.sh --messages
bash personal-agent/test.sh
```

从任意目录也可以用绝对路径运行：

```sh
bash /Users/bytedance/Documents/git_repo/langgraph4j-deepagents/personal-agent/run.sh --messages
```

使用 JDK 21；第二课新增 Jackson 解析 JSON，脚本复用本机已有缓存，将依赖准备到忽略的 `.deps/`。脚本使用 macOS 的 `/usr/bin/java` 与 `/usr/bin/javac`，带调试信息编译到临时目录，结束时清理编译输出。运行离线示例无需全局安装 Maven、Spring Boot 或配置 API Key。

正常演示的关键事件顺序是：

```text
model=scripted
[trace] model.request turn=1 messages=1
[trace] model.response type=tool_calls count=1
[trace] tool.start id=read-1 name=read_text
[trace] tool.result id=read-1 error=false chars=...
[trace] model.request turn=2 messages=3
[trace] model.response type=final_answer
[trace] run.stop status=COMPLETED modelTurns=2
```

这里的 `chars` 是实际读取结果的 Java 字符数，修改资料后会变化。

`--messages` 显示最终保留的四条消息：

1. `USER`：用户要求。
2. `ASSISTANT`：模型提出的工具调用，其中有 `id=read-1`。
3. `TOOL`：真正的文件读取结果，其中有 `toolCallId=read-1`。
4. `ASSISTANT`：模型在看到工具结果后返回的最终响应。

第二次模型请求发生时，第四条消息尚未产生，因此输入消息数是 3。工具结果必须放回模型消息历史；只在控制台打印读取结果，不会让模型自动看见它。

## 先读哪些代码

按下面顺序阅读，每一步都可直接在 IDE 中设置断点。

| 文件 | 观察点 |
| --- | --- |
| `Main.java` | 组装模型替身、工具、运行时与轮次限制 |
| `AgentRuntime.java` | `model.reply(...)` 前查看模型输入；响应后查看分支 |
| `ScriptedModelClient.java` | 第一轮返回工具调用，第二轮读取匹配的工具结果 |
| `ToolRegistry.java` | 按名称查找工具、把异常转换为工具错误 |
| `ReadTextTool.java` | 实际文件读取在程序侧发生 |
| `AgentRuntime.java` | `messages.add(Message.tool(...))` 后查看状态变化 |

四个最有价值的断点已在 `AgentRuntime` 中用 `Breakpoint 1` 至 `Breakpoint 4` 注释标记。读到这里可以回答：谁提出动作、谁执行动作、结果存在哪里、谁控制停止。

本课工具参数用 `Map<String, String>` 表达；第二课在 `DeepSeekCodec` 中把简单工具描述转换成 JSON Schema，并处理供应商协议。

## 调试

可以在 IntelliJ IDEA 中打开 `personal-agent/`，选择 JDK 21，将 `src/main/java` 标为 Sources Root、`src/test/java` 标为 Test Sources Root，运行 `personal.agent.Main`。运行配置工作目录设为 `personal-agent/`，参数可设为 `--messages`。

也可以由终端启动，再用 IDE 的 Remote JVM Debug 附加：

```sh
bash personal-agent/run.sh --debug --messages
```

进程会打印 `Listening for transport dt_socket at address: 5005` 并等待调试器。IDE 连接 `127.0.0.1:5005` 后继续执行即可；监听仅绑定本机回环地址。若端口占用，结束已有调试进程再运行。

连接后，在 `AgentRuntime.run` 设置断点，依次观察：

- 第一次调用模型前：历史里只有用户消息。
- 收到 `ModelReply.Tools`：此时尚未读取文件。
- `tools.execute(call)`：观察 `call` 和实际返回的 `ToolResult`。
- 追加工具消息后：历史变成三条，下一轮模型可以读到内容。

## 三个小实验

### 实验 1：修改资料

修改 `examples/agent-loop.md`，加入一句自己对 Agent Loop 的理解，再运行：

```sh
bash personal-agent/run.sh --messages
```

最后的引用原文应包含新增内容。这说明结果经过了真实的文件读取和消息回填。模型替身只引用资料，不对新内容作语义判断。

### 实验 2：读取不存在的文件

```sh
bash personal-agent/run.sh --file missing.md --messages
```

应看到 `tool.result ... error=true`，工具结果包含 `FILE_NOT_FOUND`，下一轮返回“读取失败”。最终 `COMPLETED` 只表示模型返回了最终响应，不保证用户任务成功；本次资料读取仍然失败。进程返回 0，因为模型已正常解释错误。

### 实验 3：只允许一轮模型请求

```sh
bash personal-agent/run.sh --max-turns 1 --messages
```

工具已被执行，结果也保留在历史，但运行时禁止第二次模型请求。最终状态应为 `TURN_LIMIT`，没有最终答案，进程返回 1。这个实验展示了程序预算和模型决策的边界。

## 文件访问与失败语义

`read_text` 默认只能读取 `examples/` 下的常规 UTF-8 文件，每次最多 16 KiB；绝对路径、越界路径和指向外部的符号链接会被拒绝。这是学习版路径约束，不是操作系统级沙箱。

| 情况 | 处理 |
| --- | --- |
| 工具不存在、文件不存在、读取失败 | 带原调用 ID 的错误结果回填给模型 |
| 重复工具调用 ID | 整批执行前拒绝，返回 `FAILED` |
| 模型异常或非法响应 | 保留已产生的历史，返回 `FAILED` |
| 达到最大模型轮次 | 返回 `TURN_LIMIT`，没有最终答案 |
| 模型给出最终响应 | 返回 `COMPLETED`，不等于真实任务必然成功 |
| CLI 参数非法 | 打印帮助，进程返回 2 |

测试使用独立临时资料，不修改本目录示例。消息与模型输入是不可变快照，避免模型适配器意外修改运行时状态。多工具请求在本课中按顺序执行。

本课的预算是模型请求次数上限，没有实现网络请求超时、流式响应、持久化、自动重试或并行调度。接真实模型时需要进一步定义这些行为。

## 后续学习顺序

1. **第二课：真实模型。** DeepSeek 适配器和工具 Schema 已实现；参照 STAGE2.md 配置 Key 并在 Trae 调试。
2. **第三课：源码研究 A。** 增加目录发现、内容检索、按行读取与源码引用，从本地 Java 仓库生成研究报告。
3. **第四课：Markdown 知识整理 B。** 增加文档检索和带来源的笔记产物，输入资料默认只读，输出独立保存。
4. **第五课：长任务。** 加入计划、上下文预算、输出外置和摘要，学习怎样维持任务方向。
5. **第六课：委派与恢复。** 加入子 Agent 的独立上下文和检查点，明确共享产物与恢复语义。

每课保留运行命令、调试入口、实验和验证结果。第一课通过只能证明基础运行机制符合测试，真实模型与 A/B 完整能力留待后续阶段分别验证。

## 本阶段实际验证（2026-11-03）

- 行为测试：25 项通过，0 项失败；另一次独立审查运行得到相同结果。
- 从项目外的 `/private/tmp` 运行正常、缺失文件、轮次限制、非法参数及帮助命令，输出与退出码均符合说明。
- 使用本机 JDK 自带 jdb 实际连接 `127.0.0.1:5005`，命中 `AgentRuntime.run`，查看 `question` 参数，继续执行至两轮模型请求后正常完成。
- 编译开启 `-Xlint:all`，无编译警告；脚本语法检查通过。

这些结果验证的是本课运行机制与本机调试入口。真实模型的工具选择与任务完成质量尚未验证。
