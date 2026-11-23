# 第十四课：本机 LM Studio 提供本地模型

本课给 Agent 增加第三个真实模型提供方 `lmstudio`：协议仍是 Chat Completions + 工具调用，但请求发往本机 LM Studio，而不是公网 API。任务、工具读取的文件和最终回答都只在本机流转，适合处理不方便外发的资料。

## 为什么锁定回环地址

`LMSTUDIO_BASE_URL` 只接受 `http` + `127.0.0.1` / `localhost` / `::1`。写成远程地址、`https`、带查询参数或带用户信息都会在发出请求前报错。这是隐私保证的一部分：即使误抄了一份云端地址，程序也不会把资料发出去。传输层同样不跟随重定向，避免本地服务把请求转到别处。

## 配置

`.env` 增加以下键，`LMSTUDIO_BASE_URL` 可以写到 `/v1` 也可以写到完整的 `/v1/chat/completions`，传输层会补齐路径。

```dotenv
LMSTUDIO_BASE_URL=http://127.0.0.1:1234/v1
LMSTUDIO_API_KEY=
LMSTUDIO_MODEL=qwen3.8-9b-distill
LMSTUDIO_TIMEOUT_SECONDS=180
LMSTUDIO_MAX_TOKENS=2048
```

- `LMSTUDIO_API_KEY`：LM Studio 在 Server Settings 里开启鉴权时，填 Create new token 生成的 permission token；关闭鉴权时留空，客户端不会发送 `Authorization` 头。
- `LMSTUDIO_TIMEOUT_SECONDS`：1 到 300 秒。9B 级模型在 32 tok/s 左右，长回答要留足时间。
- `LMSTUDIO_MAX_TOKENS`：思考加正文的总预算，默认 2048，最低不要低于 800。
- 温度默认 0.3，可用 `LMSTUDIO_TEMPERATURE` 覆盖。

## 与 DeepSeek 提供方的差异

请求体不带 DeepSeek 专用的 `thinking` 字段，模型名、`max_tokens`、`temperature`、工具 schema 和消息序列保持一致。响应里的 `reasoning_content` 被单独忽略，只取 `content`；`finish_reason=length` 会明确提示提高 `LMSTUDIO_MAX_TOKENS`，不再是笼统的协议错误。

本机的 Qwen3.8-9B-Distill 实测会偶尔在一轮里只输出思考、正文为空且 `finish_reason=stop`。客户端对这种 `LMSTUDIO_EMPTY` 只重试一次，并在请求末尾追加“请直接输出最终回答，不要只输出思考过程”；第二次仍为空才失败。这条重试不会改变运行时的消息历史。

## 运行

先在 LM Studio 里加载模型并启动服务器（默认 `127.0.0.1:1234`），然后：

```sh
bash personal-agent/run-lmstudio.sh --question "读取 agent-loop.md 并概括要点"
bash personal-agent/run.sh --provider lmstudio --model qwen3.8-9b-distill --messages
```

Web 控制台的「模型模式」、持续关注、长期目标都多出「LM Studio 本地」选项，知识库页面多出「用本地 LM Studio 整理」。本地模型不需要外部模型确认，`externalModelConfirmed` 记为 false；只有 DeepSeek 才要求勾选确认。

## 实测

- 模型 `qwen3.8-9b-distill`（Qwen3.8-9B Q4_K_M，GGUF），32K 上下文/槽位、4 并发，Metal 上约 30–36 tok/s。
- 第一轮能正确发出 `read_text` 工具调用，带调用 ID 的结果回填后再答，引用来源行。
- 一次完整两轮任务约 5–8 秒（不含模型加载）；首次调用需要 LM Studio 先把模型载入内存。
- 传输层、协议与命令行均有离线测试：`bash personal-agent/test.sh --http` 会启动本地假服务器，验证路径补齐、Bearer 头、匿名请求、CLI 端到端和空回答重试。

## 限制

- 只支持本机推理，不代理局域网内其他机器上的 LM Studio。
- 上下文受 LM Studio 的加载配置限制；4 个并发槽位共享 32K 配置时，长资料仍要分块。
- 非流式调用；一次回答要等模型写完才返回，Web 上表现为任务进入运行中。
- 模型能力弱于云端模型，复杂多工具任务可能出现空回答或重复调用，靠轮次上限和检查点兜底。
