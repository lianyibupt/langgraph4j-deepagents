# 第五课：长任务的计划、上下文与产物

这次在 Agent Loop 中观察两个视图。运行时保留完整历史，模型每轮只收到满足字符预算的一份投影。任务目标、当前计划和最近的完整工具批次会保留在投影中。

## 先跑五轮回放

在 `personal-agent` 目录执行：

```sh
bash run.sh --context-chars 1800 --messages
```

启用预算后，未指定文件时使用 `examples/long-task.md`。回放建立计划，读取样例，把较长的结果写入 `out/agent-runs/run-*/artifact-1.txt`，然后通过 `read_artifact` 读回片段，更新计划并结束。每次任务的产物保存在新的子目录。

| 日志 | 观察内容 |
| --- | --- |
| `plan.status` | 计划步数和已完成步数 |
| `artifact.saved` | 原文编号、长度与本地路径 |
| `context.prepare` | 当前估算字符数、预算、压缩批次数、完整历史和模型消息数 |
| `tool.start ... name=read_artifact` | 模型开始按编号读回原文 |

`--messages` 显示完整运行历史，`--wire` 显示传给模型适配器的请求 JSON。第五轮可以看到历史有 9 条消息，而模型输入有 7 条，最早的计划调用及其结果作为一个批次一起移出。新计划仍作为运行时状态保留。

## 预算和摘要怎样工作

`--context-chars` 的最小值是 1200，启用后默认最多请求模型 12 轮。预算按 Java UTF-16 长度估算对话动态内容，包含消息正文、工具调用名称、ID、参数以及每条消息和调用的固定开销。模型供应商的 token 统计还包括协议格式、系统提示和工具定义等内容，使用各自的计数方式。

工具结果长度超过 `min(1200, budget / 3)` 时，工具执行后立刻保存原文。模型看到产物编号和起始偏移量。已经受限的 `read_artifact` 片段直接回填，每段上限为 `min(512, budget / 4)` 个 UTF-16 代码单元。工具返回 `next_offset` 供继续读取，当前运行结束后可直接打开磁盘原文。

历史压缩按完整的助手调用与对应工具结果批次进行。省略的批次留下一份程序生成的执行摘要，包括最近的结果状态、原始字符数和可用产物编号。引用事实时依据实际读到的原文。目标、计划和最新工具批次仍超出预算时，程序在发出下一轮请求前返回 `CONTEXT_BUDGET_EXCEEDED`。

`update_plan` 接受包含 `id`、`task`、`status` 的步骤列表。状态可用 `pending`、`in_progress`、`done`，最多一个步骤处于进行中。非法更新会返回工具错误，先前计划仍可继续使用。计划状态是执行记录，运行结果和产物仍需分别核对。

## 用真实模型观察不同的选择

```sh
bash run-deepseek.sh --context-chars 2200 \
  --question '读取教学样例 long-task.md，先制定三步计划，从产物读回原文，再整理两条调试建议。'
```

真实 DeepSeek 可以将字符预算与 `--repo` 或 `--notes` 一起使用。产物目录必须位于输入目录之外，通过 `--artifacts-dir` 指定。真实模式会发送问题、可见工具结果及按需读回的片段。第五课的离线回放用示例文件展示固定工具序列。

## 在 Trae 中看三处断点

选择 **Stage 5 · Long task · Replay**。也可以在终端运行 `bash run.sh --debug --context-chars 1800 --messages`，再用 **Stage 2 · Attach localhost:5005** 连接。

| 停点 | 观察变量 |
| --- | --- |
| `LongTaskContext.updatePlan` 中的 `DEBUG S5-1` | `next` 和旧的 `plan`，单步后看更新结果 |
| `ArtifactStore.archive` 中的 `DEBUG S5-2` | `text`、`id` 和 `path`，继续后打开原文文件 |
| `LongTaskContext.prepare` 中的 `DEBUG S5-3` | `history.size()`、`snapshot.size()`、`chars` 和 `omitted` |

将预算改为 `1200`，观察较早发生的压缩和更短的读回片段。再将轮次限制改为 `--max-turns 2`，观察工具执行后程序如何停止，以及最后一轮的长结果仍出现在产物目录。

## 验证

本地 63 项测试通过。离线回放验证了最低预算、最后一轮归档、同批多工具消息配对、失败后的计划状态和多次任务间的状态隔离。样例归档与原文逐字节一致。使用 `jdb` 实际检查计划、原文归档和上下文压缩三个位置，并继续运行至结束。Trae 启动配置通过 JSON 检查，图形界面启动仍待实测。

最终版本用真实 DeepSeek 和同一份教学样例完成五轮请求。模型两次调用 `read_artifact`，三步计划全部完成。最后一轮完整历史有 10 条消息，模型收到 5 条，估算字符数为 1731，低于 2200 的预算。归档文件与样例原文一致。
