package personal.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Executable behavior tests: assertions always run, without -ea or a test framework. */
public final class AgentRuntimeTest {
    @FunctionalInterface interface TestCase { void run() throws Exception; }
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("personal-agent-tests-");
        try {
            Path root = Files.createDirectory(temp.resolve("workspace"));
            Files.writeString(root.resolve("note.md"), "# Agent\n工具结果需要回填给模型。\n", StandardCharsets.UTF_8);
            Files.writeString(temp.resolve("outside.md"), "outside");
            Files.createSymbolicLink(root.resolve("escape.md"), temp.resolve("outside.md"));
            ToolRegistry registry = new ToolRegistry(List.of(new ReadTextTool(root, 256)));

            test("read result reaches next model input with matching ID", () -> {
                AtomicInteger turns = new AtomicInteger();
                ModelClient model = (messages, tools) -> {
                    check(tools.getFirst().name().equals("read_text"), "model sees available tools");
                    if (turns.getAndIncrement() == 0) return calls(call("read-1", "read_text", "note.md"));
                    check(messages.size() == 3, "user, assistant call, tool result");
                    check(messages.get(1).toolCalls().getFirst().id().equals("read-1"), "assistant call recorded");
                    check(messages.get(2).toolCallId().equals("read-1"), "tool result must match call");
                    check(!messages.get(2).toolError(), "fixture read should succeed");
                    check(messages.get(2).text().contains("工具结果"), "actual file content reaches model");
                    return new ModelReply.FinalAnswer("done");
                };
                AgentResult result = runtime(model, registry, 3).run("read the note");
                check(result.status() == AgentResult.Status.COMPLETED, "run should complete: " + result.detail());
                check(result.modelTurns() == 2 && result.finalAnswer().orElseThrow().equals("done"), "two model turns");
                expect(UnsupportedOperationException.class, () -> result.messages().clear());
            });

            test("direct final answer executes no tool", () -> {
                AtomicInteger executed = new AtomicInteger();
                ToolRegistry counting = new ToolRegistry(List.of(tool("count", a -> {
                    executed.incrementAndGet(); return ToolResult.success("x");
                })));
                AgentResult result = runtime((m, t) -> new ModelReply.FinalAnswer("hello"), counting, 1).run("hello");
                check(result.status() == AgentResult.Status.COMPLETED && executed.get() == 0, "no tool execution");
            });

            test("unknown tool becomes an error observation", () -> {
                AgentResult result = runOneBatch(registry, calls(call("unknown-1", "missing", "x")));
                check(result.messages().get(2).toolError(), "error marked");
                check(result.messages().get(2).text().startsWith("UNKNOWN_TOOL"), "error reason");
            });

            test("tool exception is returned to model", () -> {
                ToolRegistry broken = new ToolRegistry(List.of(tool("broken", a -> { throw new IllegalStateException("boom"); })));
                AgentResult result = runOneBatch(broken, calls(call("broken-1", "broken", "x")));
                check(result.messages().get(2).toolError() && result.messages().get(2).text().contains("boom"), "exception visible");
            });

            test("multiple tool calls retain separate result IDs", () -> {
                AgentResult result = runOneBatch(registry, calls(call("a", "read_text", "note.md"), call("b", "read_text", "missing.md")));
                check(result.messages().get(2).toolCallId().equals("a"), "first ID");
                check(result.messages().get(3).toolCallId().equals("b"), "second ID");
                check(!result.messages().get(2).toolError() && result.messages().get(3).toolError(), "separate outcomes");
            });

            test("turn limit prevents a second model request", () -> {
                AtomicInteger requests = new AtomicInteger();
                AgentResult result = runtime((m, t) -> { requests.incrementAndGet(); return calls(call("a", "read_text", "note.md")); }, registry, 1).run("read");
                check(result.status() == AgentResult.Status.TURN_LIMIT, "distinct limit status");
                check(requests.get() == 1 && result.modelTurns() == 1 && result.messages().size() == 3, "one complete tool round");
                check(result.finalAnswer().isEmpty(), "limit is not a final answer");
            });

            test("duplicate IDs in a batch reject the entire batch before tools run", () -> {
                AtomicInteger executed = new AtomicInteger();
                ToolRegistry counting = new ToolRegistry(List.of(tool("count", a -> { executed.incrementAndGet(); return ToolResult.success("x"); })));
                AgentResult result = runtime((m, t) -> calls(call("same", "count", "x"), call("same", "count", "x")), counting, 2).run("count");
                check(result.status() == AgentResult.Status.FAILED && result.detail().contains("DUPLICATE_CALL_ID"), "duplicate rejected");
                check(executed.get() == 0, "batch validated before any execution");
            });

            test("call IDs cannot be reused in later turns", () -> {
                AgentResult result = runtime((m, t) -> calls(call("same", "read_text", "note.md")), registry, 3).run("read");
                check(result.status() == AgentResult.Status.FAILED && result.modelTurns() == 2, "reused ID rejected");
                check(result.messages().size() == 3, "only first call executed");
            });

            test("model exception preserves existing history", () -> {
                AgentResult result = runtime((m, t) -> { if (m.size() == 1) return calls(call("r", "read_text", "note.md")); throw new IllegalStateException("provider unavailable"); }, registry, 3).run("read");
                check(result.status() == AgentResult.Status.FAILED && result.messages().size() == 3, "history retained");
                check(result.modelTurns() == 2 && result.detail().contains("provider unavailable"), "failed request counted");
            });

            test("null model response is a protocol failure", () -> {
                AgentResult result = runtime((m, t) -> null, registry, 2).run("read");
                check(result.status() == AgentResult.Status.FAILED && result.detail().contains("INVALID_MODEL_REPLY"), "invalid reply rejected");
            });

            test("model receives immutable history and tool definitions", () -> {
                AgentResult result = runtime((m, t) -> {
                    expect(UnsupportedOperationException.class, m::clear);
                    expect(UnsupportedOperationException.class, t::clear);
                    return new ModelReply.FinalAnswer("immutable");
                }, registry, 1).run("read");
                check(result.status() == AgentResult.Status.COMPLETED, "immutable inputs verified");
            });

            ReadTextTool reader = new ReadTextTool(root, 256);
            test("read normal UTF-8 file", () -> check(reader.execute(Map.of("path", "note.md")).text().contains("工具结果"), "UTF-8 preserved"));
            test("missing file becomes structured error", () -> check(reader.execute(Map.of("path", "absent.md")).text().startsWith("FILE_NOT_FOUND"), "missing file"));
            test("reject parent traversal", () -> check(reader.execute(Map.of("path", "../outside.md")).text().startsWith("OUTSIDE_WORKSPACE"), "traversal rejected"));
            test("reject absolute path", () -> check(reader.execute(Map.of("path", root.resolve("note.md").toString())).text().startsWith("ABSOLUTE_PATH"), "absolute rejected"));
            test("reject escaping symlink", () -> check(reader.execute(Map.of("path", "escape.md")).text().startsWith("OUTSIDE_WORKSPACE"), "symlink rejected"));
            test("reject directory", () -> check(reader.execute(Map.of("path", ".")).text().startsWith("NOT_A_FILE"), "directory rejected"));
            test("reject missing and unknown arguments", () -> {
                check(reader.execute(Map.of()).text().startsWith("INVALID_ARGUMENTS"), "path required");
                check(reader.execute(Map.of("path", "note.md", "extra", "x")).text().startsWith("INVALID_ARGUMENTS"), "unknown field rejected");
            });
            test("enforce byte limit", () -> {
                Files.writeString(root.resolve("large.md"), "中".repeat(200));
                check(reader.execute(Map.of("path", "large.md")).text().startsWith("FILE_TOO_LARGE"), "byte limit");
            });
            test("reject malformed UTF-8", () -> {
                Files.write(root.resolve("bad.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
                check(reader.execute(Map.of("path", "bad.md")).text().startsWith("INVALID_UTF8"), "no silent replacement");
            });
            test("protocol and configuration validation", () -> {
                expect(IllegalArgumentException.class, () -> runtime((m, t) -> new ModelReply.FinalAnswer("x"), registry, 0));
                expect(IllegalArgumentException.class, () -> new ToolCall("", "read_text", Map.of()));
                expect(IllegalArgumentException.class, () -> new ModelReply.Tools(List.of()));
                expect(IllegalArgumentException.class, () -> new ModelReply.FinalAnswer(" "));
                expect(IllegalArgumentException.class, () -> new ToolRegistry(List.of(reader, reader)));
                expect(IllegalArgumentException.class, () -> new ReadTextTool(root, 0));
            });

            test("scripted demonstration quotes actual tool content and can be reused", () -> {
                ModelClient scripted = new ScriptedModelClient("note.md");
                AgentResult first = runtime(scripted, registry, 3).run("show the note");
                check(first.status() == AgentResult.Status.COMPLETED, "scripted run completes");
                check(first.finalAnswer().orElseThrow().contains("工具结果"), "answer includes actual content");
                check(first.finalAnswer().orElseThrow().contains("scripted"), "substitute clearly labeled");
                Files.writeString(root.resolve("note.md"), "# Changed\n第二次读取的不同内容。\n");
                AgentResult second = runtime(scripted, registry, 3).run("show changed note");
                check(second.finalAnswer().orElseThrow().contains("第二次读取"), "reads again; no cached fixture answer");
            });

            test("scripted demonstration explains tool error without pretending success", () -> {
                AgentResult result = runtime(new ScriptedModelClient("absent.md"), registry, 3).run("read");
                check(result.status() == AgentResult.Status.COMPLETED, "normal final response about tool error");
                check(result.messages().get(2).toolError(), "underlying error retained");
                check(result.finalAnswer().orElseThrow().contains("FILE_NOT_FOUND"), "failure explained");
            });

            test("CLI prints trace and inspectable history", () -> {
                var output = new ByteArrayOutputStream();
                int code = Main.run(new String[]{"--file", "note.md", "--messages"}, root,
                        new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
                String text = output.toString(StandardCharsets.UTF_8);
                check(code == 0 && text.contains("scripted"), "successful and visibly scripted");
                check(text.contains("model.request turn=2 messages=3"), "second model input observed");
                check(text.contains("toolCallId=read-1") && text.contains("第二次读取"), "history can be inspected");
            });

            test("CLI distinguishes limit from usage errors", () -> {
                var sink = new PrintStream(new ByteArrayOutputStream());
                check(Main.run(new String[]{"--file", "note.md", "--max-turns", "1"}, root, sink, sink) == 1, "limit status");
                check(Main.run(new String[]{"--max-turns", "0"}, root, sink, sink) == 2, "nonpositive limit rejected");
                check(Main.run(new String[]{"--file"}, root, sink, sink) == 2, "missing argument rejected");
                check(Main.run(new String[]{"--unknown"}, root, sink, sink) == 2, "unknown option rejected");
                check(Main.run(new String[]{"--help"}, root, sink, sink) == 0, "help works");
            });
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.printf("Tests: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    private static AgentRuntime runtime(ModelClient model, ToolRegistry tools, int limit) {
        return new AgentRuntime(model, tools, limit, event -> {});
    }

    private static ToolCall call(String id, String tool, String path) {
        return new ToolCall(id, tool, Map.of("path", path));
    }

    private static ModelReply.Tools calls(ToolCall... calls) { return new ModelReply.Tools(List.of(calls)); }

    private static AgentResult runOneBatch(ToolRegistry registry, ModelReply.Tools batch) {
        AgentResult result = runtime((m, t) -> m.size() == 1 ? batch : new ModelReply.FinalAnswer("observed"), registry, 2).run("run");
        check(result.status() == AgentResult.Status.COMPLETED, "run should complete");
        return result;
    }

    @FunctionalInterface interface ToolBody { ToolResult run(Map<String, String> args) throws Exception; }

    private static Tool tool(String name, ToolBody body) {
        return new Tool() {
            public Definition definition() { return new Definition(name, "test tool", Map.of()); }
            public ToolResult execute(Map<String, String> args) throws Exception { return body.run(args); }
        };
    }

    private static void test(String name, TestCase body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static void expect(Class<? extends Throwable> type, TestCase body) throws Exception {
        try { body.run(); }
        catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("expected " + type.getSimpleName() + ", got " + e, e); }
        throw new AssertionError("expected " + type.getSimpleName());
    }
}
