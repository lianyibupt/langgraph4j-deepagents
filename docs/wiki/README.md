# LangGraph4j Deep Agents — Code Wiki

> 本 Wiki 是对 `spring-ai-deepagents`（LangGraph4j Deep Agents，即 Agents 2.0 参考实现）代码仓库的结构化技术文档。
> 目标读者：需要理解、扩展或集成本项目的 Java / AI Agent 开发者。

## 项目一句话简介

本项目用 **Java + [LangGraph4j](https://github.com/langgraph4j/langgraph4j) + [Spring AI](https://spring.io/projects/spring-ai)** 实现了 **Deep Agents（深度智能体 / Agents 2.0）** 架构。它在经典「think → tool → observe → repeat」浅层循环之上，补齐了四大支柱：**显式规划（Planning）**、**层级委派（Sub-Agents）**、**持久化记忆（虚拟文件系统）** 与 **极致上下文工程（Prompt Engineering）**。

它是 Python 版 [hwchase17/deepagents](https://github.com/hwchase17/deepagents) 在 LangGraph4j 上的移植。

## 文档导航

| 章节 | 内容 | 适合场景 |
| --- | --- | --- |
| [01 · 架构总览](./01-架构总览.md) | 四大支柱、整体分层、运行时数据流、ReactAgent 图结构 | 第一次接触项目，想建立整体心智模型 |
| [02 · 核心模块职责](./02-核心模块职责.md) | `src/main` 六个核心类 + `src/test` 示例层各自的职责与协作关系 | 想知道「某个功能在哪个类里」 |
| [03 · 关键类与函数说明](./03-关键类与函数.md) | 逐类、逐方法的 API 级说明（签名、参数、返回、行为） | 需要改代码 / 查某个方法细节 |
| [04 · 依赖关系](./04-依赖关系.md) | Maven 依赖、profile、类之间的依赖图、外部服务依赖 | 搭环境、排依赖冲突、理解耦合 |
| [05 · 运行与配置](./05-运行与配置.md) | 三种运行方式（Spring Boot CLI / Javelit UI / 单测）、环境变量、模型切换 | 想把项目跑起来 |

## 仓库速览

```
langgraph4j-deepagents/
├── pom.xml                         # Maven 构建，Java 17，artifactId=spring-ai-deepagents
├── README.md                       # 官方说明（含架构图与时序图）
├── deep_agents.png                 # 架构示意图
├── deep_agents_seq.png             # 时序图
├── src/main/java/org/bsc/langgraph4j/deepagents/   # ★ 核心库（6 个类）
│   ├── DeepAgent.java              # 顶层接口：State / SubAgent / ToDo / builder()
│   ├── GraphBuilder.java           # 主 Agent 图构建器
│   ├── Tools.java                  # 内置工具：ls / read / write / edit / write_todos
│   ├── TaskToolBuilder.java        # 子 Agent 调度工具（task）构建器
│   ├── FileChannel.java            # 虚拟文件系统的状态 Channel（Reducer）
│   └── Prompts.java                # 内置系统提示词常量
└── src/test/java/...               # 示例、运行入口、Tavily 搜索工具、单测
    ├── org/bsc/langgraph4j/deepagents/
    │   ├── DeepagentsDemoApplication.java   # Spring Boot 入口
    │   ├── DeepagentsConsoleController.java # CommandLineRunner 演示
    │   ├── ChatModelConfiguration.java      # 按 profile 装配 ChatModel
    │   ├── AiModel.java / AiPrompt.java / AiTools.java / ToolsComponent.java
    │   └── TavilyApi*.java                  # Tavily Web 搜索客户端
    └── JtDeepAgentsApp.java / JtTavilyApp.java / JtSelectModel.java / JtTools.java  # Javelit(JBang) 交互式 UI
```

## 关键事实速查

- **构建工具**：Maven；**Java 版本**：17（`javelit` profile 需 JDK 21+）
- **坐标**：`org.bsc.langgraph4j:spring-ai-deepagents:1.0-SNAPSHOT`
- **核心依赖**：`langgraph4j-springai-agentexecutor`（提供 `ReactAgent`、`AgentExecutor.State`）
- **默认记忆**：虚拟文件系统（内存中的 `Map<String,String>`，由 [FileChannel](file:///Users/bytedance/Documents/git_repo/langgraph4j-deepagents/src/main/java/org/bsc/langgraph4j/deepagents/FileChannel.java) 管理）
- **默认模型**：OpenAI `gpt-4o-mini`（可切 Ollama / Gemini / GitHub Models）
- **默认搜索工具**：[Tavily](https://tavily.com) Web Search
- **⚠️ 现状**：Human-in-the-loop (HITL) 尚未实现（见 README 说明）

---

> 文档基于当前仓库源码生成。若源码变更，请以 `src/` 下实际代码为准。
