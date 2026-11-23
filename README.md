# Java Deep Agents

这个仓库包含一个可运行的 [Java 个人 Agent](./personal-agent/README.md)，以及基于 LangGraph4j 和 Spring AI 的 Deep Agents 参考实现。个人 Agent 使用 Java 21，实现了工具调用、源码研究、Markdown 知识整理、长任务上下文管理和子 Agent 委派，并提供命令行和本地 Web 工作台。

## 快速启动

安装 **JDK 21**。首次克隆时可使用 **Maven** 下载依赖。已有所需 Maven 缓存时，Web 启动脚本也能直接用 JDK 编译启动。

```bash
git clone https://github.com/lianyibupt/langgraph4j-deepagents.git
cd langgraph4j-deepagents
bash personal-agent/web.sh
```

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，选择任务场景并点击 **运行任务**。默认使用**协议回放**，无需 API Key，工具会实际读取本地样例。页面可以查看模型轮次、工具调用、执行轨迹、完整消息和最终结果。运行历史保留在当前进程中，笔记、归档和检查点写入 `personal-agent/out/web/`。

### 使用 DeepSeek

首次配置时，把 [`.env.example`](./personal-agent/.env.example) 复制为 `personal-agent/.env`，填入 `DEEPSEEK_API_KEY`，然后启动或重启 Web 工作台并选择 **DeepSeek 实时调用**。已有 `.env` 时直接编辑现有文件。Git 会忽略 `.env`。

### 使用命令行

准备好 Jackson 依赖后，从仓库根目录运行以下命令。脚本可以复用本机 Maven 缓存中的 Jackson JAR。

```bash
bash personal-agent/run.sh --provider replay --messages
bash personal-agent/run-deepseek.sh --messages
bash personal-agent/test.sh
```

## 学习路径

| 阶段 | 可以观察到的行为 |
| --- | --- |
| [1. Agent Loop](./personal-agent/README.md) | 模型提出工具调用，运行时执行工具，并将结果回填到下一轮消息 |
| [2. 模型协议](./personal-agent/STAGE2.md) | 对比固定响应、协议回放和真实 DeepSeek 调用，也可逐步调试 |
| [3. 源码研究](./personal-agent/STAGE3.md) | 发现、检索和按行读取源码，并校验报告引用的文件与行号 |
| [4. Markdown 知识整理](./personal-agent/STAGE4.md) | 读取笔记并校验引用，通过校验后保存 Markdown 产物 |
| [5. 长任务](./personal-agent/STAGE5.md) | 更新计划、按预算压缩上下文，并归档和读回工具结果 |
| [6. 委派与恢复](./personal-agent/STAGE6.md) | 将任务交给子 Agent，从完整工具批次的检查点暂停并继续 |

## LangGraph4j 与 Spring AI 参考实现

仓库根目录的 [Maven 项目](./pom.xml) 保留上游参考实现。核心库在 [`src/main`](./src/main)，示例在 [`src/test`](./src/test)。架构、依赖和运行方式见 [代码 Wiki](./docs/wiki/README.md)。

> A reference implementation of **Deep Agents** (aka *Agents 2.0*) in Java using [LangGraph4j].  
> Move beyond one-loop “shallow” agents to explicit planning, hierarchical delegation, persistent memory, and rigorous context engineering.

![Deep Agents Architecture](./deep_agents.png)

---

### Why Deep Agents?

Classic agents often run a simple loop: **think → call a tool → observe → repeat**. That’s great for quick, transactional queries, but it breaks down on multi-hour or multi-day tasks (hallucinations, loss of goal, looping, no recovery).  
**Deep Agents** try fix this by **changing the architecture**, not just the prompt:

1. **Explicit Planning** – The agent continuously maintains a plan (e.g., a TODO in Markdown) with clear statuses instead of hiding intent in a just chain-of-thought.
2. **Hierarchical Delegation (Sub-Agents)** – An **Orchestrator** delegates to specialized agents (Researcher, Coder, Writer, …), each working in a clean context and returning synthesized results.
3. **Persistent Memory** – Intermediate artifacts (notes, code, data) are written to external storage (filesystem, vector store, …) and referenced by path/query rather than stuffing everything into the context window.
4. **Extreme Context Engineering** – Long, specific instructions define: when to plan vs. act, when to spawn sub-agents and the human-in-the-loop(HITL) rules.

This repository demonstrates those four pillars.

> The design is inspired by the Deep Agents pattern popularized by [LangChain](https://blog.langchain.com/deep-agents/) and based on the [python version](https://github.com/hwchase17/deepagents) adapted to [LangGraph4j] using [Spring AI].
>
> ‼️ Currently HITL is not implemented yet.

---

### Example

This repo includes sample code under `src/test` that create a **Deep Researcher Agent** that use [Tavily] as web search engine and [OpenAI gpt-4o-mini model] 

#### Configuration

* **Memory**: Virtual Filesystem by default. You can swap in your DB of choice.
* **Prompts**: Tune the system prompts in the [`Prompts.java`](src/main/java/org/bsc/langgraph4j/deepagents/Prompts.java) to match your domain.
* **Models**: Bring your own LLM via [Spring AI] bindings (OpenAI, Anthropic, local models, etc.).
  
  > set your model/provider keys in environment variables or a `.env` consumed by your Spring AI integration.
  > ```
  > OPENAI_API_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx 
  > TAVILY_API_KEY=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
  > ```

#### Creating a Deep Agent with LangGraph4j

Let's look at a sequence diagram for a Deep Agent handling a complex request: 

![sequence](deep_agents_seq.png)

Below a representative code snippet that implements the Deep Agents in the diagram 

```java
// Create a Researche Sub Agent

var researchSubagent = DeepAgent.SubAgent.builder()
    .name("research-agent")
    .description("""
        Used to research more in depth questions. Only give this researcher one topic at a time.
        Do not pass multiple sub questions to this researcher.
        Instead, you should break down a large topic into the necessary components, and then call multiple research agents in parallel, one for each sub question.
        """)
    .prompt("""
        You are a dedicated researcher. Your job is to conduct research based on the users questions.
        Conduct thorough research and then reply to the user with a detailed answer to their question

        ........(to be continued).............. 
        """)
    .tools( List.of( "internet_search" ))
    .build();

var critiqueSubAgent =  DeepAgent.SubAgent.builder()
        .name("critique-agent")
        .description("Used to critique the final report. Give this agent some information about how you want it to critique the report.")
        .prompt("""
            You are a dedicated editor. You are being tasked to critique a report.
            
            You can find the report at `final_report.md`.
            
            You can find the question/topic for this report at `question.txt`.
            
            ........(to be continued).............. 
            """)
        .build();

var deepAgent = DeepAgent.builder()
        .instructions("""
        You are an expert researcher. Your job is to conduct thorough research, and then write a polished report.
        
        The first thing you should do is to write the original user question to `question.txt` so you have a record of it.
        
        Use the research-agent to conduct deep research. It will respond to your questions/topics with a detailed answer.
        
        When you think you enough information to write a final report, write it to `final_report.md`
        
        You can call the critique-agent to get a critique of the final report. After that (if needed) you can do more research and edit the `final_report.md`
        
        ........(to be continued).............. 
        
        """)
        .subAgents( List.of( researchSubagent, critiqueSubAgent ))
        .chatModel(chatModel)
        .tools( List.of( tools.internetSearch()) )
        .build()
        .compile( CompileConfig.builder()
                .recursionLimit(100)
                .build());

        var userMessage = """
                        What is langgraph4j project?
                        """;

        var runnableConfig = RunnableConfig.builder().build();

        var result = agent.stream(
                        Map.of("messages", new UserMessage(userMessage), 
                        runnableConfig );

        var output = result.stream()
                .peek(s -> System.out.println(s.node()) )
                .reduce((a, b) -> b)
                .orElseThrow();

        System.out.println( """
        ================================
        TODO
        ================================
        """);
        output.state().todos().forEach((value) -> System.out.printf("""
                -----------
                %s
                -----------
                """, value));
        System.out.println( """
        ================================
        FILES
        ================================
        """);
        output.state().files().forEach((key, value) -> System.out.printf("""
                file: '%s'
                -----------
                %s
                -----------
                """, key, value));

        System.out.println( """
        ================================
        FINAL RESULT
        ================================
        """);
        System.out.printf( "result: %s\n",
                output.state().lastMessage()
                        .map(AssistantMessage.class::cast)
                        .map(AssistantMessage::getText)
                        .orElseThrow());

```

### References

* [Deep Agents](https://blog.langchain.com/deep-agents/)
* [hwchase17/deepagents (Github)](https://github.com/hwchase17/deepagents)
* [Agents 2.0: From Shallow Loops to Deep Agents](https://www.philschmid.de/agents-2.0-deep-agents)


[LangGraph4j]: https://github.com/langgraph4j/langgraph4j
[Spring AI]: https://spring.io/projects/spring-ai
[Tavily]: https://tavily.com/
[OpenAI gpt-4o-mini model]: https://platform.openai.com/docs/models/gpt-4o-mini
