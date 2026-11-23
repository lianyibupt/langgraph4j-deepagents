# 第七课：在 Web 中恢复研究任务

本课把 A/B、长任务和 Web 运行记录接入检查点。重点观察服务进程退出后，哪些状态能继续使用，以及工具执行、报告发布和页面展示分别处于哪个阶段。

## 在页面完成一次跨进程实验

在仓库根目录启动服务。

```sh
bash personal-agent/web.sh
```

打开 http://127.0.0.1:8080，选择「知识整理」和「协议回放」，展开「高级参数」，勾选「读取证据后暂停」，然后运行。

任务在第三个工具批次之后暂停，此时 list_notes、search_notes 和 read_note 的结果均已保存。打开「消息记录」检查读取结果，再打开「恢复状态」，可以看到 READY 和输入版本。此时最终笔记尚未发布。

在启动服务的终端按 Ctrl+C，再用同一命令启动服务。刷新页面，运行历史中仍有原任务。点击「继续运行」，观察 source.evidence restored=true 和第四轮模型请求。恢复不会再次执行前三个工具。模型给出最终回答后核对引用，再发布 Markdown，可以在「最终结果」下载。

源码研究采用相同流程。长任务选择「归档资料后暂停」，在第二批工具之后停止，恢复状态中可以看到三步计划。继续运行时，context.restored 说明计划和原文编号已重建，模型接着执行 read_artifact。

## 在 Trae 设置断点

停止普通 Web 服务，再启动调试版本。

```sh
bash personal-agent/web.sh --debug
```

JVM 在 127.0.0.1:5005 等待调试器。使用现有的「Stage 2 · Attach localhost:5005」配置连接，再继续执行，浏览器随后可以访问页面。

| 停点 | 观察内容 |
| --- | --- |
| SourceResearch.restoreEvidence 的 DEBUG S7-1 | history 中保存的成功读取结果和重建的 evidence |
| LongTaskContext.restore 的 DEBUG S7-2 | 历史 ToolCall、原始结果与恢复后的计划、产物编号 |
| RunHistory.save 的 DEBUG S7-3 | 即将发布的完整运行记录和临时文件 |
| AgentRuntime.continueCheckpoint | READY、历史轮次，以及下一轮从哪里开始 |

恢复发生在 Web worker 线程，HTTP 接口先返回已接收状态。停在断点时，页面保持运行中，可以切换轨迹、消息和恢复状态。

## 终端也能运行

以下命令在仓库根目录执行。首次运行需选一个尚不存在的检查点路径。

```sh
bash personal-agent/run.sh --notes personal-agent/examples/knowledge \
  --output personal-agent/out/stage7-note.md \
  --checkpoint personal-agent/out/stage7-note.json --pause-after 3 --messages

bash personal-agent/run.sh --notes personal-agent/examples/knowledge \
  --output personal-agent/out/stage7-note.md \
  --resume personal-agent/out/stage7-note.json --messages
```

恢复时重用原 provider、model、输入目录、输出、字符预算和产物目录。问题来自保存的历史。真实模式显式传 --provider deepseek，依然从 .env 读取 Key。

## 恢复状态的依据

检查点保存完整的原始消息。研究任务从成功读取结果重建引用范围。长任务从成功的 update_plan 结果重建计划，并把原始长结果重新归档，按历史顺序恢复相同的 artifact ID。重建可能生成新的归档目录，工具读取和模型请求不因重建重复执行。

A/B 的输入版本由白名单内文件路径和内容计算，内容按行规范化换行符。要求白名单扫描完整，总读取内容不超过 4 MiB，单文件继续遵守既有 512 KiB 限制。长任务版本覆盖选定示例文件。恢复和结果发布前核对版本，输入变化时返回 CHECKPOINT_INPUT_CHANGED，需要启动新研究。运行中的本地输入仍需保持稳定，跨文件扫描不提供文件系统事务快照。

COMPLETED 表示模型最终回答已保存，笔记发布随后执行。若进程在发布后退出，恢复会核对既有文件与保存的回答，完全相同时复用，内容冲突时返回 NOTE_OUTPUT_CONFLICT 并保留已有文件。

TOOLS_PENDING 表示工具结果未完整保存。服务重启后对应任务显示「待核对」，页面说明不确定的工具批次，自动恢复停止。READY 或 COMPLETED 的中断记录显示为可手动继续的暂停任务。

Web 将最近二十条记录保存到 out/web/runs.json。暂停和待核对记录不会被正常历史淘汰。该目录由一个 Web 进程持有锁，第二个使用相同目录的进程无法启动。密钥继续只从 .env 和进程环境读取。

## 验证

```sh
bash personal-agent/test.sh --http
```

新增回归覆盖源码证据恢复、输入变化后拒绝调用模型、笔记恢复与相同输出复用、冲突笔记保护，以及计划和归档读回。原六课和 HTTP 回环测试一并执行。本轮共 79 项核心及 HTTP 测试通过。Chrome 实测知识整理在第三批暂停，新 JVM 从第四轮完成，工具调用仍为三次，下载笔记成功。长任务在新 JVM 恢复计划和归档后完成，jdb 命中 Web worker 中的 LongTaskContext.restore。另一次在 ToolRegistry.execute 断点处终止测试 JVM，重启后显示待核对，恢复接口返回 HTTP 409。真实模型的本轮恢复路径尚待单独实测。
