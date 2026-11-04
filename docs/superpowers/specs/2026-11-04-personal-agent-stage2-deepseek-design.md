# 第二阶段：DeepSeek 真实模型与逐步调试

用户已要求推进已批准路线的第二阶段，并指定使用自己的 DeepSeek API；本阶段在现有循环内增加适配器和可观察数据，不重复请求阶段授权。

## 目标与边界

- 保留 scripted 演示，新增 deepseek 真实模式和 replay 离线协议回放。
- 三种模式复用 AgentRuntime、工具和消息状态。
- DeepSeek 通过 JDK HttpClient 调用官方 Chat Completions；Jackson 解析 JSON。先固定使用非流式、非思考模式。
- 默认模型 deepseek-flash，模型名可配置；默认输出上限 1024 tokens，请求总超时 60 秒，默认最多 4 轮；不自动重试付费请求。
- API Key 仅从环境读取，或由专用 Bash 启动脚本在本机无回显输入；不写入项目、日志、命令行参数或文档。
- 仍只读取自带 examples，第三阶段再开放源码检索。

## 官方依据（2026-11-04 读取）

- https://api-docs.deepseek.com/：当前模型名称 deepseek-flash / deepseek-v4-pro，OpenAI 格式基址 https://api.deepseek.com。
- https://api-docs.deepseek.com/guides/tool_calls：工具由程序执行，助手调用消息和 tool_call_id 结果必须回传。
- https://api-docs.deepseek.com/api/create-chat-completion：POST /chat/completions；工具参数为 JSON 字符串；默认思考开启，教学模式显式 thinking.type=disabled；length/content_filter 等结束原因不能当作成功答案。

## 结构

- DeepSeekCodec：Java 消息与官方 JSON 之间的双向转换，生成 string 参数的 JSON Schema，保留助手同时返回的 content 与 tool_calls。
- DeepSeekModelClient：拼请求、调用传输、检查状态码、解析响应；局部变量保留 requestJson、responseBody、decoded 等供断点查看。
- ChatTransport：返回状态码和响应体的窄接口，不含日志或协议解析。
- DeepSeekHttpTransport：固定官方 HTTPS 地址、Bearer 认证、禁止自动重定向、连接超时和完整响应等待超时。
- ReplayTransport：按官方响应结构生成确定性调用与回答，不访问网络，不需要 Key；明确标记回放，不冒充真实模型推理。
- Main：增加 --provider scripted|replay|deepseek、--model、--question；非法参数先失败，缺少 Key 不发请求。
- pom.xml：让 IDEA 按 Maven 项目直接导入 JDK 与 Jackson。命令脚本可复用本机已缓存的 Jackson 2.18.2，不需要全局安装 Maven。

## 协议与失败

- 请求包含独立 system message、完整消息序列、tools、tool_choice=auto、stream=false、thinking.disabled 和 max_tokens。
- 返回的 tool_calls 整批解析通过后才交给运行时；非法 JSON、非字符串参数、重复 JSON 键、错误角色、未知 finish_reason 或截断响应明确失败。
- stop 必须有非空最终文本；tool_calls 必须有非空工具数组。未知工具名继续由 registry 反馈错误。
- HTTP 401、402、429、5xx 提供不含响应体和认证信息的诊断；网络异常与超时不回显请求头。
- 日志默认只有状态码、消息数量和响应类型。完整请求/响应通过 --wire 显式打印 JSON body；不打印 Authorization。
- 思考模式不在本课支持范围，避免引入 reasoning_content 的跨轮保留协议。

## 调试交付

- 独立 STAGE2.md：IDEA 导入 pom、普通 Debug 与远程 Attach 两种启动方式、Step Over / Into / Out / Resume、异常断点和变量观察表。
- 依次观察 Main 组装 → runtime messages → codec requestTree/requestJson → transport request/response → codec responseTree/argumentsNode → runtime batch/result/messages → finalAnswer。
- replay 先练习确定性的完整断点路径；deepseek 使用同一适配器与 codec，替换传输为真实 HTTP。
- 验证用 jdb 实际停在 runtime、adapter、codec 与文件工具，读取关键变量并继续结束。

## 验证与当前凭证边界

当前执行环境 DEEPSEEK_API_KEY 未设置。不搜索其他应用的凭证配置。

必须完成：阶段 1 回归、JSON 请求与响应契约、工具参数校验、错误和日志脱敏、replay 真文件读取闭环、HTTP 层本地服务器测试（认证格式/重定向/错误/超时）、IDE 可导入配置及实际远程调试。

若用户尚未在本机提供 Key，真实 DeepSeek 调用明确标记未验证；代码和离线调试先完整交付，用户可无回显输入自己的 Key 启动真实模式。
