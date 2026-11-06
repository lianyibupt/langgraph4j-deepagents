# 第四课：Markdown 知识整理

从一个 Markdown 目录整理出一篇带出处的笔记。第三课的目录扫描、字面检索和行号证据表继续使用。这一课增加 Markdown 文件筛选、整理提示，以及引用校验后的文件保存。

## 离线走一遍

在 `personal-agent` 目录执行：

```sh
bash run.sh --notes examples/knowledge \
  --output out/agent-loop-note.md --messages
```

默认回放会调用 `list_notes → search_notes → read_note`，搜索标题标记，读取一段样例并保存摘录。终端打印 `Note saved` 和文件路径。再次运行时选一个新的输出文件名。

## 用真实模型整理多篇资料

```sh
bash run-deepseek.sh --notes examples/knowledge \
  --output out/agent-debug-checklist.md \
  --question '综合这两份资料写一篇 Agent 调试清单，区分原文事实和建议，注明出处。'
```

`--notes` 接受本地目录，相对路径以当前工作目录为基准。使用 DeepSeek 时，问题、检索结果和读取的 Markdown 片段会发给服务端。真实模型可以根据已经发现的路径直接读取笔记，也可以先搜索正文。

| 工具 | 可观察的数据 |
| --- | --- |
| `list_notes` | 按字面词筛选相对路径，仅列出 `.md` 和 `.markdown` |
| `search_notes` | 忽略大小写搜索标题与正文，返回路径、行号、预览和截断标记 |
| `read_note` | 按 1 起始的闭区间读取行，并把实际返回范围登记为证据 |

扫描、文件大小和读取行数使用第三课的限额。输出引文 `[[相对路径:起始行-结束行]]` 中的路径以 `--notes` 目录为基准。搜索命中用于定位，读到的行才进入证据表。

## 笔记保存的边界

`--output` 必须指向输入目录之外尚不存在的 `.md` 文件。开始调用模型前检查目标，确认已有文件、目录内目标和通过符号链接指向输入目录的目标会被拒绝。只有最终答复通过引文校验，才会创建输出目录并写入笔记。

写入先在目标目录完成临时文件，再用排他的硬链接发布。这样同时向同一路径保存时，只有一个进程能创建目标。输出所在文件系统需要支持硬链接。

## 在 Trae 中观察保存前后的状态

打开 `personal-agent`，选择 **Stage 4 · Markdown knowledge · Replay**。启动时输入一个尚不存在的输出路径，默认是 `out/knowledge-note.md`。也可以在终端执行以下命令，再选择现有的 **Stage 2 · Attach localhost:5005**。

```sh
bash run.sh --debug --notes examples/knowledge \
  --output out/knowledge-debug.md --messages
```

| 停点 | 观察内容 |
| --- | --- |
| `SourceWorkspace.search` 中的 `DEBUG S3-2` | `query`、候选文件和 `hits` |
| `SourceWorkspace.read` 中的 `DEBUG S3-3` | `relative`、读取区间和返回行 |
| `SourceResearch.verifyCitations` 中的 `DEBUG S3-4` | 当前引用、已读证据表 |
| `Main.run` 中的 `DEBUG S4-1` | 通过校验的 `answer` 和引用数量 `verified` |
| `NoteOutput.save` 中的 `DEBUG S4-2` | `markdown` 和 `destination`，此时目标文件尚未发布 |

用 `--max-turns 3` 再试一次。回放能走到读取笔记，但没有第四轮生成最终答案，因此目标文件不会出现。再把输出换到输入目录下，观察程序如何在第一轮请求前结束。

## 本次验证

55 项本地测试通过，其中第四阶段覆盖输出内容、路径边界、已有文件保护和未完成任务的输出约束。真实 DeepSeek 用两份临时生成的 Markdown 资料综合出调试清单，三轮请求完成，九处引用通过校验，并验证了磁盘笔记引用两份输入。用 `jdb` 实际停在搜索、引用校验和保存三个位置，确认保存断点处文件尚未发布，继续运行后文件可读。Trae 启动配置已检查，图形界面启动仍待实测。
