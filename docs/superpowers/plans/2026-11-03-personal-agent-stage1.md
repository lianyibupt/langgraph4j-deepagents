# Personal Java Agent Stage 1 Implementation Plan

> **For agentic workers:** Execute inline in the current chat, following the approved design and test-driven-development. No delegation is needed for this small, shared-state learning implementation.

**Goal:** Deliver a pure JDK 21 Agent Loop experiment that runs offline, exposes its message history, and can be debugged with an IDE or JDWP.

**Architecture:** Place an independent learning project under `personal-agent/`. The runtime owns the loop and message history, depends on a model interface, and routes named tool calls through a registry. A visibly scripted model reads one fixture through the same tool contract later available to real models.

**Tech Stack:** Java 21 standard library, shell entry scripts, executable Java behavior tests; no external dependencies.

## Task 1: Define protocol and failing behavior tests

Files: `personal-agent/src/main/java/personal/agent/{Message,ToolCall,ModelReply,ModelClient,Tool,ToolResult,ToolRegistry,AgentResult,AgentRuntime,ReadTextTool}.java`; `personal-agent/src/test/java/personal/agent/AgentRuntimeTest.java`.

- [x] Define immutable records for messages, calls, replies and results. Reject empty identifiers, empty tool batches and nonpositive model limits. Keep model input snapshots immutable.
- [x] Add temporary unimplemented runtime/read-tool bodies that return explicit failures, so the behavior suite compiles and fails on missing behavior rather than a missing class.
- [x] Test a read-call followed by a final answer, and inspect the next model input for the matching tool result.

```java
check(messages.get(2).toolCallId().equals("read-1"), "tool result must match call");
check(!messages.get(2).toolError(), "fixture read should succeed");
check(messages.get(2).text().contains("工具结果"), "actual file content reaches model");
```

- [x] Test direct answer, unknown tool, tool exception, multiple calls, turn budget, duplicate IDs, model exception, invalid response and immutable input.
- [x] Test normal UTF-8 reading, traversal, absolute paths, external symlinks, missing files, directories, malformed UTF-8 and byte limit.
- [x] Compile all main and test sources into a temporary output directory with `/usr/bin/javac -encoding UTF-8 --release 21 -g`, then run `personal.agent.AgentRuntimeTest`. Verify an assertion fails for unimplemented behavior.

## Task 2: Implement tool execution and runtime

- [x] Replace stub `ReadTextTool` with relative path validation, normalized and real-root containment checks, bounded UTF-8 reading and structured errors.
- [x] Replace stub `AgentRuntime` with: append user message; request reply; validate a full tool-call batch; append assistant calls; execute calls serially; append results; repeat until final answer or turn limit.

```java
for (int turn = 1; turn <= maxModelTurns; turn++) {
    ModelReply reply = model.reply(List.copyOf(messages), registry.definitions());
    // FinalAnswer ends the run. Tools is validated as a batch before execution.
}
```

- [x] Run the behavior suite. Fix only implementation failures until it passes. Preserve each call ID and count failed model requests as attempted model turns.

## Task 3: Deliver the visible learning experiment

Files: `personal-agent/src/main/java/personal/agent/{ScriptedModelClient,ConsoleTrace,Main}.java`, `personal-agent/examples/agent-loop.md`, `personal-agent/{run,test}.sh`, `personal-agent/README.md`, `personal-agent/.gitignore`.

- [x] Implement a model substitute that asks for `read_text` on its first reply, checks the matching tool result on its second reply, and produces a visibly scripted conclusion containing the actual result. Do not suggest the substitute understands arbitrary questions.
- [x] Add CLI options `--file`, `--max-turns`, `--messages`, and `--help`; reject unknown or incomplete options. Run from any working directory using the script's own directory.
- [x] Scripts use absolute `/usr/bin/java` and `/usr/bin/javac`, compile with debug information, and clean temporary output on exit. `--debug` starts JDWP bound to `127.0.0.1:5005` and waits for a debugger.
- [x] Document this command and its expected event ordering:

```sh
bash personal-agent/run.sh --messages
```

- [x] Document experiments: edit the fixture; request a missing file; set `--max-turns 1`. Explain the difference between a tool error, a normal completion and a runtime stop.
- [x] Run the complete tests, default CLI, missing-file CLI, turn-limit CLI and invalid-argument CLI. Check expected exit statuses: completion 0, turn limit/runtime failure 1, usage error 2. A handled tool error may end with a normal assistant explanation and status 0.
- [x] Verify JDWP reports its listener, then terminate the intentionally waiting demo. Do not leave a background debugger listener running.

## Task 4: Review and learning handoff

- [x] Inspect changes and check for placeholder bodies, secrets, generated build files and accidental modifications outside the new project and task documents.
- [x] Mark this plan with actual verification results, and update the design status to record approval and stage 1 delivery only.
- [x] Give the user the exact run command, the learning sequence, four useful breakpoint locations, observed test results, and the explicit remaining boundary: no live model until stage 2.

## Verification results (2026-11-03)

- Initial compiled behavior suite: 20 failed, 1 passed, confirming the runtime and reader were still unimplemented.
- After loop/tool implementation: 21 passed, 0 failed.
- Before demo/CLI implementation: 4 new behavior checks failed while the earlier 21 passed.
- Final full suite: 25 passed, 0 failed; independent read-only reviewer also ran this suite successfully.
- CLI from `/private/tmp`: normal output and full history (exit 0), missing-file explanation (exit 0), turn limit (exit 1), unknown option (exit 2), help (exit 0).
- Java compilation used `--release 21 -g -Xlint:all`; no compiler warnings were emitted. Shell syntax checks passed.
- JDWP was initially denied local socket binding inside the execution sandbox. The same project launcher was then tested with local execution permission, bound to `127.0.0.1:5005`. JDK jdb attached, hit `AgentRuntime.run` at line 24, inspected `question`, and resumed to `COMPLETED` with two model turns and exit 0. Verification processes exited.
- Independent review found no P0/P1/P2 issues within the approved stage 1 scope.
- No live-model request, personal repository analysis or personal note indexing was performed.
