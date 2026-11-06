package personal.agent;

import personal.agent.delegation.CheckpointStore;
import personal.agent.delegation.DelegateTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

public final class DelegationCheckpointTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("delegation-checkpoint-test-");
        try {
            Path input = Files.createDirectory(root.resolve("input"));
            Files.writeString(input.resolve("agent-loop.md"), "# Agent Loop\n工具由运行时执行，结果回填到消息历史。\n");
            Path checkpoint = root.resolve("output/run.json");
            var paused = run(input, "--checkpoint", checkpoint.toString(), "--pause-after", "1", "--messages");
            check(paused.code() == 0 && paused.text().contains("Status: PAUSED"), "pause after completed delegation: " + paused.text());
            check(paused.text().contains("delegate.start") && paused.text().contains("delegate.finish"),
                    "child agent executes independently");
            check(paused.text().contains("reader") && paused.text().contains("parent-checkpoint"), "delegation and checkpoint visible");
            String saved = Files.readString(checkpoint);
            check(saved.contains("\"READY\"") && saved.contains("\"toolCallId\" : \"delegate-reader\""),
                    "checkpoint includes the completed parent tool result");
            System.out.println("PASS delegation pauses with a durable complete batch");

            var resumed = run(input, "--resume", checkpoint.toString(), "--messages");
            check(resumed.code() == 0 && resumed.text().contains("Status: COMPLETED | modelTurns=2"),
                    "resume to final answer");
            check(!resumed.text().contains("delegate.start") && resumed.text().contains("工具由运行时执行"),
                    "resume uses saved result without rerunning child tools");
            var repeated = run(input, "--resume", checkpoint.toString());
            check(repeated.code() == 0 && !repeated.text().contains("model.request"), "completed resume does not call the model");
            System.out.println("PASS resume and completed reopen avoid repeated tool execution");

            Path pending = root.resolve("pending.json");
            Files.writeString(pending, saved.replace("\"READY\"", "\"TOOLS_PENDING\""));
            var blocked = run(input, "--resume", pending.toString());
            check(blocked.code() != 0 && blocked.text().contains("CHECKPOINT_TOOLS_UNCERTAIN")
                    && !blocked.text().contains("model.request"), "uncertain tools require manual reconciliation");
            System.out.println("PASS pending tool state cannot automatically replay a batch");

            var mismatch = run(input, "--resume", checkpoint.toString(), "--file", "other.md");
            var overwrite = run(input, "--checkpoint", checkpoint.toString());
            check(mismatch.code() != 0 && mismatch.text().contains("CHECKPOINT_BINDING_MISMATCH"), "configuration bound to checkpoint");
            check(overwrite.code() != 0 && overwrite.text().contains("CHECKPOINT_EXISTS"), "existing checkpoint preserved");
            check(run(input, "--checkpoint", input.resolve("nested/unsafe.json").toString()).code() != 0,
                    "checkpoint path must be outside input");
            System.out.println("PASS invalid checkpoint paths, binding and overwrite are rejected");

            Path invalid = root.resolve("invalid.json");
            Files.writeString(invalid, saved.replace("\"toolCallId\" : \"delegate-reader\"", "\"toolCallId\" : \"wrong-id\""));
            var rejected = run(input, "--resume", invalid.toString());
            check(rejected.code() != 0 && rejected.text().contains("CHECKPOINT_INVALID")
                    && !rejected.text().contains("model.request"), "invalid history rejected before network");
            System.out.println("PASS restored history must have complete paired tool calls");

            var readerTools = new ToolRegistry(List.of(new ReadTextTool(input, 1024)));
            ModelClient reader = (messages, tools) -> {
                check(messages.getFirst().text().contains("指定子任务")
                        && !messages.getFirst().text().contains("PARENT_PRIVATE_MARKER"), "child receives only its delegation");
                check(tools.size() == 1 && tools.getFirst().name().equals("read_text"), "reader capability isolated");
                if (messages.size() == 1) return new ModelReply.Tools(List.of(
                        new ToolCall("reader-read", "read_text", Map.of("path", "agent-loop.md"))));
                return new ModelReply.FinalAnswer("子 Agent 摘要");
            };
            var delegates = new ToolRegistry(List.of(new DelegateTool(reader, readerTools, ignored -> {})));
            ModelClient parent = (messages, tools) -> messages.size() == 1
                    ? new ModelReply.Tools(List.of(new ToolCall("delegate", "delegate_task", Map.of("task", "指定子任务"))))
                    : new ModelReply.FinalAnswer("父 Agent 完成");
            var delegated = new AgentRuntime(parent, delegates, 2, ignored -> {}).run("PARENT_PRIVATE_MARKER");
            check(delegated.messages().size() == 4 && delegated.messages().get(2).text().contains("子 Agent 摘要")
                    && !delegated.messages().get(2).text().contains("reader-read"), "only structured result is returned to parent");
            var ungrounded = new DelegateTool((messages, tools) -> new ModelReply.FinalAnswer("没有读取资料"),
                    readerTools, ignored -> {}).execute(Map.of("task", "读取资料"));
            check(ungrounded.error() && ungrounded.text().contains("NO_EVIDENCE"), "no read cannot be reported as successful delegation");
            System.out.println("PASS child history and tools are isolated and require observed evidence");

            var binding = new CheckpointStore.Binding("replay", "test", input.toRealPath().toString(), "agent-loop.md");
            Path interruption = root.resolve("interruption.json");
            AtomicInteger effects = new AtomicInteger();
            AtomicInteger requests = new AtomicInteger();
            Tool interrupt = new Tool() {
                @Override public Definition definition() { return new Definition("interrupt", "Test interrupt", Map.of()); }
                @Override public ToolResult execute(Map<String, String> args) throws InterruptedException {
                    effects.incrementAndGet();
                    throw new InterruptedException("test");
                }
            };
            ModelClient interrupting = (messages, tools) -> {
                requests.incrementAndGet();
                return new ModelReply.Tools(List.of(new ToolCall("first", "interrupt", Map.of()),
                        new ToolCall("second", "interrupt", Map.of())));
            };
            var runtime = new AgentRuntime(interrupting, new ToolRegistry(List.of(interrupt)), 3, ignored -> {});
            try (var store = new CheckpointStore(interruption, input, binding, false)) {
                try {
                    var interrupted = runtime.runCheckpointed("验证中断", store, false, 0);
                    check(interrupted.status() == AgentResult.Status.FAILED && effects.get() == 1,
                            "remaining tools are not started after interruption");
                } finally { Thread.interrupted(); }
            }
            try (var store = new CheckpointStore(interruption, input, binding, true)) {
                expectIo("CHECKPOINT_TOOLS_UNCERTAIN", () -> runtime.runCheckpointed(null, store, true, 0));
                expectIo("CHECKPOINT_LOCKED", () -> {
                    try (var second = new CheckpointStore(interruption, input, binding, true)) {
                        throw new AssertionError("second store acquired " + second.path());
                    }
                });
                String testClasses = Path.of(DelegationCheckpointTest.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).toString();
                var probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", testClasses, LockProbe.class.getName(), interruption + ".lock").start();
                try {
                    check(probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0,
                            "another JVM still sees the lock after an in-process acquisition was rejected");
                } finally {
                    if (probe.isAlive()) probe.destroyForcibly();
                }
            }
            check(requests.get() == 1 && effects.get() == 1, "no model call or repeated effect on uncertain resume");
            System.out.println("PASS interrupted execution keeps the pending phase and exclusive lock");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    public static final class LockProbe {
        public static void main(String[] args) throws IOException {
            try (var channel = java.nio.channels.FileChannel.open(Path.of(args[0]), java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock != null) System.exit(1);
            }
        }
    }

    private record Run(int code, String text) {}
    @FunctionalInterface private interface IoAction { void run() throws IOException; }

    private static void expectIo(String marker, IoAction action) throws IOException {
        try { action.run(); }
        catch (IOException e) { check(e.getMessage().contains(marker), "expected " + marker); return; }
        throw new AssertionError("expected " + marker);
    }

    private static Run run(Path input, String... args) {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = Main.run(args, input, out, out, Map.of());
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
