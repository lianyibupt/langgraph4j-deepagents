# Java 个人 Agent 学习项目

项目已实现十四课。基础循环见下文，模型协议与断点见 [第二课](STAGE2.md)，源码研究见 [第三课](STAGE3.md)，Markdown 笔记见 [第四课](STAGE4.md)，计划与上下文见 [第五课](STAGE5.md)，委派与检查点恢复见 [第六课](STAGE6.md)，Web 研究任务与跨进程恢复见 [第七课](STAGE7.md)。第八到十二课覆盖持续关注、协作输入、通知交付、多任务监督和长期目标，第十三课接入最近一年个人知识，第十四课接入本机 LM Studio 的本地模型，见下方课程表。DeepSeek 使用项目 `.env` 中的 API Key；本机 LM Studio 不需要外网。

下文保留第一课的学习步骤。

本目录是独立的 Java 21 学习项目，目标逐步演进为源码研究助手（A）和 Markdown 知识助手（B）。第一课聚焦模型、工具与运行时怎样形成闭环。

**第一课默认使用 `ScriptedModelClient`：它按固定规则发出一次读取请求，再引用读取结果。** 第二课的 `DeepSeekModelClient` 实现同一个 `ModelClient` 接口，使用 `--provider deepseek` 选择真实调用。

## Web 工作台

从仓库根目录启动 Spring Boot 服务，然后访问 [http://127.0.0.1:8080](http://127.0.0.1:8080)。

```sh
bash personal-agent/web.sh
```

页面由手写 HTML、CSS 和 JavaScript 组成。默认使用无需 Key 的协议回放，点击「运行任务」即可观察工具调用、结果和完整消息。任务场景包含工具调用入门、源码研究、知识整理、长任务、委派与恢复。知识整理生成的 Markdown 可以在结果页下载。

切换到 DeepSeek 实时调用需要现有 `.env` 中的 `DEEPSEEK_API_KEY` 和外网连接。修改 `.env` 后重启服务。Web 在本机持久保存最近 20 条运行记录，任务生成的笔记、归档和检查点保存在 `out/web/` 下。源码研究、知识整理、长任务和委派支持批次后暂停，服务重启后可从运行历史继续。展开高级参数进行恢复实验，在详情的「恢复状态」查看阶段、输入版本和计划。

使用 `bash personal-agent/web.sh --debug` 启动可附加调试的 Web 服务，JVM 在 `127.0.0.1:5005` 等待连接。完整实验与断点见 [第七课](STAGE7.md)。

启动脚本优先使用 Maven。当前机器的 Maven 依赖缓存齐全时，也可以直接用 JDK 21 编译启动。端口被占用时可运行 `bash personal-agent/web.sh --server.port=8081`。

## 主动服务课程

| 课时 | 页面入口 | 实验 |
| --- | --- | --- |
| [第八课](STAGE8.md) | 主动服务 | 资料变化、快照、观察与自动研究 |
| [第九课](STAGE9.md) | 协作输入 | 追加要求、等待回答、暂停和停止 |
| [第十课](STAGE10.md) | 通知中心 | 暂停投递、结果交付和已读回执 |
| [第十一课](STAGE11.md) | 运行控制台 | 两项并行任务、实时状态和实际模型输入 |
| [第十二课](STAGE12.md) | 长期目标 | 按范围召回偏好、持续服务建议与授权 |
| [第十三课](STAGE13.md) | 个人知识库 | 最近一年资料的本地检索、预览选择与 Agent 上下文 |
| [第十四课](STAGE14.md) | 本地模型 | 用本机 LM Studio 提供 OpenAI 兼容接口，资料不出本机 |

在 `.env` 配置 `PERSONAL_KNOWLEDGE_ROOT`，重启后访问 [个人知识库](http://127.0.0.1:8080/#knowledge)。默认排除日期不明的旧导入资料，仅在本地检索与预览。你明确选择资料、点击 DeepSeek 或本地 LM Studio 整理后，选中文件才对本次模型可读。执行详情保留检索依据、资料版本、工具结果与最终引用。日期依据与局限见 [第十三课](STAGE13.md)。

## 本地模型（LM Studio）

第十四课给运行时增加了 `lmstudio` 提供方：同一套消息与工具协议，走本机 LM Studio 的 OpenAI 兼容接口。传输层只接受 `http` 回环地址（`127.0.0.1`、`localhost`、`::1`），即使把 `LMSTUDIO_BASE_URL` 写错成远程地址也会在发请求前拒绝，因此任务与读取的资料不会离开这台电脑。

```sh
# LM Studio 里先加载模型并启动本地服务器（默认 127.0.0.1:1234）
bash personal-agent/run-lmstudio.sh --question "读取 agent-loop.md 并概括要点"
bash personal-agent/run.sh --provider lmstudio --model qwen3.8-9b-distill --messages
```

`.env` 中的配置项：

```dotenv
LMSTUDIO_BASE_URL=http://127.0.0.1:1234/v1
LMSTUDIO_API_KEY=
LMSTUDIO_MODEL=qwen3.8-9b-distill
LMSTUDIO_TIMEOUT_SECONDS=180
LMSTUDIO_MAX_TOKENS=2048
```

`LMSTUDIO_API_KEY` 可留空（LM Studio 关闭鉴权时）；打开鉴权时填 Server Settings 里创建的 permission token。`LMSTUDIO_MAX_TOKENS` 要覆盖思考加正文，低于 800 容易只得到空的正文；本地推理模型偶尔只思考不回答，客户端会自动补充一次“请直接输出最终回答”的提示重试一次，仍为空才报错。Web 控制台的「模型模式」、持续关注和长期目标都可以选择「LM Studio 本地」，知识库页面多了一个「用本地 LM Studio 整理」按钮，不再需要外部模型确认。细节与实测见 [第十四课](STAGE14.md)。

Web 任务最多两项同时执行。源码和知识整理支持自选目录，监控与研究遵守资料白名单。持续关注使用固定资料快照，事件记录接单原因并关联到运行。通知渠道为本机站内通知，目标召回采用可核对的目录与关键词规则。运行状态保存在 `out/web/runs.json`，关注、事件、目标和投递状态保存在 `out/web/management.json`。

核心自测入口如下。Web 自测使用合成资料和协议回放，覆盖五条端到端流程，并将测试记录留在管理页面。

```sh
bash personal-agent/test.sh --http
python3 personal-agent/smoke-web.py http://127.0.0.1:8080
```

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
2. **第三课：源码研究 A。** 使用 `--repo` 指定目录，发现、检索、按行读取源码并核对报告引用。
3. **第四课：Markdown 知识整理 B。** 用 `--notes` 指定资料目录，通过引用校验后将笔记保存到 `--output`。
4. **第五课：长任务。** 使用 `--context-chars` 观察计划、上下文投影、长结果归档和分段读回。
5. **第六课：委派与恢复。** 使用 `--checkpoint` 观察只读子 Agent 的委派，用 `--resume` 从完整工具批次继续。
6. **第七课：Web 研究与恢复。** 在页面暂停 A/B 和长任务，重启服务后恢复证据、计划和产物，并核对笔记发布。

每课保留运行命令、调试入口、实验和验证结果。第三课和第四课都已用合成资料验证真实 DeepSeek 的调用、引用和产物。

## 本阶段实际验证（2026-11-03）

- 行为测试：25 项通过，0 项失败；另一次独立审查运行得到相同结果。
- 从项目外的 `/private/tmp` 运行正常、缺失文件、轮次限制、非法参数及帮助命令，输出与退出码均符合说明。
- 使用本机 JDK 自带 jdb 实际连接 `127.0.0.1:5005`，命中 `AgentRuntime.run`，查看 `question` 参数，继续执行至两轮模型请求后正常完成。
- 编译开启 `-Xlint:all`，无编译警告；脚本语法检查通过。

这些结果验证的是本课运行机制与本机调试入口。真实模型的工具选择与任务完成质量尚未验证。
