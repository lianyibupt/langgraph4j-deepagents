# 个人知识上下文实施计划

> 在当前会话逐项执行，沿用用户已有未提交课程变更，不切换工作区，不自动提交。

**Goal:** 个人目录本地检索、显式选择后作为可观察的 Agent 上下文。

**Architecture:** 内存 KnowledgeBase → KnowledgeService/Controller → 选中资料快照 → 现有 AgentRuns notes 执行链。

**Tech Stack:** Java 21、Spring MVC、原生 JavaScript、现有 Jackson。

- [x] 新建 `knowledge/KnowledgeBase.java` 与 `KnowledgeBaseTest.java`。先运行核心测试观察缺失，再实现过滤、中文词项、来源行号、选中快照和变更拒绝；运行 `bash personal-agent/test.sh --http`。
- [x] 新建 `web/KnowledgeService.java`、`KnowledgeController.java`。修改 AgentRuns 添加受保护 knowledge 场景和检索事件；快照复制完整选中文件而非全库，证据引用维持原始路径行号。
- [x] 修改 `static/index.html`、`console.js`、`console.css`，新增 `knowledge.js`。加入本地检索、全文预览、勾选与明确 DeepSeek 启动按钮；启动后跳转现有执行详情。
- [x] 修改 `.env.example`、本机 `.env` 和 README，新增 `STAGE13.md`。检查配置不输出密钥。
- [x] 核心 Web 合成资料用例验证未授权拒绝、无命中拒绝、选中范围和回放产出。重启实际服务，以真实目录检索并在浏览器核对来源；不向真实模型发送个人笔记。

## 验证记录

- 基础与核心回归 85 个 PASS，含知识库三条主流程与严格日期边界。
- Web 合成资料三条核心检查全部通过，未向外部模型发送个人原文。
- 复核发现并修复了 11/12 月日期前缀截断问题，新增窗口内外与紧凑时间戳回归。
- 真实 8080 服务已重启加载最终代码，知识库统计仍为 65 篇、837 个片段；运行历史保留 20 条。
- 浏览器验证近期研究资料检索、预览选择与执行控制台跳转。
