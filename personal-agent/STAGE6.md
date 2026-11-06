# 第六课：子 Agent 委派与检查点恢复

父 Agent 通过 `delegate_task` 向只读的 `reader` 发送一段独立任务。子 Agent 从自己的用户消息开始，使用 `read_text` 读取同一个示例目录。子 Agent 有独立的三轮上限，父 Agent 收到的是包含 `agent`、`status`、`modelTurns` 和 `summary` 的工具结果。没有成功读取资料的委派会返回 `NO_EVIDENCE`。

## 用两个进程完成一次任务

在 `personal-agent` 目录中运行。首次运行时选择尚不存在的检查点文件。

```sh
bash run.sh --checkpoint out/delegation-demo.json --pause-after 1 --messages
bash run.sh --resume out/delegation-demo.json --messages
```

第一次运行的父 Agent 发出一次委派。子 Agent 读取示例文件并返回结果后，父 Agent 把整批结果保存为 `READY`，本次命令返回 `PAUSED`。第二次运行直接用检查点内的结果发送父 Agent 的第二轮请求。再次打开已完成的检查点会直接返回保存的答案。

`--file` 可以指定示例工作区内的其他相对路径，恢复时使用相同配置。检查点记录模型服务、模型名、规范化工作目录、示例文件、父 Agent 消息和已保存轮数。恢复沿用原问题，累计已保存的轮数继续计入 `--max-turns`。第一版的检查点针对委派示例，组合 `--repo`、`--notes` 或第五课的上下文预算时会在启动阶段拒绝。

## 观察三个持久状态

| 文件状态 | 写入时机 | 恢复行为 |
| --- | --- | --- |
| `READY` | 首轮请求前，或完整工具批次结果回填后 | 从已保存的父 Agent 消息开始下一轮 |
| `TOOLS_PENDING` | 收到完整的助手工具调用后，执行任何调用前 | 返回 `CHECKPOINT_TOOLS_UNCERTAIN`，等待人工核对 |
| `COMPLETED` | 保存父 Agent 最终答案后 | 读取并返回已保存的答案 |

`PAUSED` 是运行结果，检查点文件里的状态仍是 `READY`。如果子 Agent 运行时进程退出，保存的 `TOOLS_PENDING` 可能对应尚未开始、部分完成或已经完成但结果未落盘的操作。程序在这些情况下统一停止自动恢复。确认外部操作状态后，可以用新的检查点启动明确的新任务。

检查点使用 UTF-8 JSON，包含本次任务与父 Agent 收到的结构化摘要。文件保存上限为 2 MiB，输出路径需在示例目录之外。保存先完整写入临时文件，再原子发布，使用本地支持硬链接、原子替换与文件锁的文件系统。相邻的 `.json.lock` 用于协调同一路径上的进程，锁在进程关闭时释放。保留这个文件名可让后续进程使用同一个锁。

## 使用已配置的 DeepSeek

```sh
bash run-deepseek.sh --checkpoint out/delegation-live.json --pause-after 1
bash run-deepseek.sh --resume out/delegation-live.json
```

父 Agent 的委派任务及子 Agent 读取到的示例资料会发送到 DeepSeek。恢复复用已保存的子 Agent 摘要。实际测试使用了临时合成的资料。

## 在 Trae 中观察状态

打开 `personal-agent`，先选 **Stage 6 · Delegate and pause**，输入新的检查点文件名。查看 JSON 后选 **Stage 6 · Resume checkpoint**，输入同一个文件名。终端启动时也可加 `--debug`，然后连接 **Stage 2 · Attach localhost:5005**。

| 停点 | 观察变量 |
| --- | --- |
| `DelegateTool.execute` 的 `DEBUG S6-1` | `childQuestion`、`readerTools.definitions()`，看子 Agent 收到的任务和工具 |
| `CheckpointStore.write` 的 `DEBUG S6-2` | `state.phase()`、`state.modelTurns()`、`state.messages()`，看即将落盘的快照 |
| `CheckpointStore.load` 的 `DEBUG S6-3` | `state.phase()` 和已读回的历史，判断能否进入下一轮 |

## 验证

70 项本地测试通过。实际在委派断点处暂停一个 JVM，第二个 JVM 获取同一检查点时收到锁拒绝。终止第一个测试进程后，恢复拒绝重放待核对批次。另一个正常暂停的样例由新的 JVM 从 `READY` 恢复并保存为 `COMPLETED`。独立 JVM 探测也覆盖了同 JVM 获取锁被拒后的锁保留。

真实 DeepSeek 使用合成资料完成委派与恢复。reader 运行两轮，父 Agent 第一轮后暂停，新进程恢复到父 Agent 第二轮完成。已用 `jdb` 命中委派和恢复断点。Trae 启动配置通过 JSON 检查，图形界面启动仍待实测。
