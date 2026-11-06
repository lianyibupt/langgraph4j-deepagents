package personal.agent;

import personal.agent.longtask.LongTaskContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class LongTaskTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("long-task-test-");
        try {
            Path input = Files.createDirectory(root.resolve("input"));
            String original = "诊断资料：观察消息和工具调用。\n".repeat(180) + "原始内容结束\n";
            Files.writeString(input.resolve("trace.md"), original);
            Path artifacts = root.resolve("artifacts");
            var out = new ByteArrayOutputStream();
            var stream = new PrintStream(out, true, StandardCharsets.UTF_8);
            int status = Main.run(new String[]{"--provider", "replay", "--file", "trace.md",
                    "--context-chars", "1800", "--artifacts-dir", artifacts.toString(), "--messages"},
                    input, stream, stream, Map.of());
            String log = out.toString(StandardCharsets.UTF_8);
            check(status == 0, "long-task replay completes: " + log);
            check(log.contains("name=update_plan") && log.contains("name=read_artifact"), "plan and retrieval execute");
            check(log.contains("plan.status steps=3 done=3"), "plan reaches done");
            var budget = Pattern.compile("context.prepare chars=(\\d+) budget=1800 compactedBatches=(\\d+)").matcher(log);
            int projected = 0;
            boolean compacted = false;
            while (budget.find()) {
                projected++;
                check(Integer.parseInt(budget.group(1)) <= 1800, "model payload respects budget");
                compacted |= Integer.parseInt(budget.group(2)) > 0;
            }
            check(projected == 5 && compacted, "five requests and at least one compacted batch");
            try (var paths = Files.walk(artifacts)) {
                var files = paths.filter(Files::isRegularFile).toList();
                check(files.size() == 1 && Files.readString(files.getFirst()).equals(original),
                        "artifact keeps exact text and is written once");
            }
            check(log.contains("原始内容结束"), "--messages retains the original tool result");
            System.out.println("PASS long-task replay plans, compacts, offloads and retrieves original text");

            out.reset();
            int stopped = Main.run(new String[]{"--provider", "replay", "--context-chars", "1200",
                    "--artifacts-dir", artifacts.toString(), "--question", "长".repeat(2000)},
                    input, stream, stream, Map.of());
            log = out.toString(StandardCharsets.UTF_8);
            check(stopped != 0 && log.contains("CONTEXT_BUDGET_EXCEEDED") && !log.contains("model.request"),
                    "oversized goal fails before the first model request");
            System.out.println("PASS oversized goal stops before the model request");

            out.reset();
            Main.run(new String[]{"--provider", "replay", "--file", "missing.md", "--context-chars", "1800",
                    "--artifacts-dir", artifacts.toString()}, input, stream, stream, Map.of());
            log = out.toString(StandardCharsets.UTF_8);
            check(log.contains("工具执行失败") && !log.contains("done=3"), "tool failure cannot complete the replay plan");
            System.out.println("PASS failed tool keeps plan steps unfinished");

            Path finalTurn = root.resolve("final-turn");
            out.reset();
            Main.run(new String[]{"--file", "trace.md", "--context-chars", "1800", "--max-turns", "2",
                    "--artifacts-dir", finalTurn.toString()}, input, stream, stream, Map.of());
            check(out.toString(StandardCharsets.UTF_8).contains("TURN_LIMIT"), "run stops at the turn budget");
            try (var paths = Files.walk(finalTurn)) {
                var archived = paths.filter(Files::isRegularFile).toList();
                check(archived.size() == 1 && Files.readString(archived.getFirst()).equals(original),
                        "archive even when no further model request is made");
            }
            System.out.println("PASS final-turn long result is archived before stopping");

            for (String misleading : List.of("{\"artifact_id\":\"artifact-999\"}", "[工具失败] 这是资料正文")) {
                Files.writeString(input.resolve("literal.md"), misleading);
                out.reset();
                int literalStatus = Main.run(new String[]{"--file", "literal.md", "--context-chars", "1800",
                        "--artifacts-dir", artifacts.toString()}, input, stream, stream, Map.of());
                log = out.toString(StandardCharsets.UTF_8);
                check(literalStatus == 0 && log.contains("done=3") && !log.contains("name=read_artifact"),
                        "original text cannot impersonate a reference or an error");
            }
            System.out.println("PASS literal result text is distinct from runtime metadata");

            var context = new LongTaskContext(1200, artifacts, input, ignored -> {});
            var a = new ToolCall("a", "read_text", Map.of("path", "trace.md"));
            var b = new ToolCall("b", "read_text", Map.of("path", "trace.md"));
            var c = new ToolCall("c", "read_text", Map.of("path", "trace.md"));
            var d = new ToolCall("d", "read_text", Map.of("path", "trace.md"));
            var result = ToolResult.success("资料".repeat(195));
            var history = List.of(Message.user("目标与约束"), Message.calls(List.of(a, b)), Message.tool(a, result),
                    Message.tool(b, result), Message.calls(List.of(c, d)), Message.tool(c, result), Message.tool(d, result));
            var view = context.prepare(history);
            check(view.size() == 4 && view.get(1).toolCalls().equals(List.of(c, d))
                    && view.get(2).toolCallId().equals("c") && view.get(3).toolCallId().equals("d"),
                    "compact by complete tool batch");
            check(view.getFirst().text().startsWith("目标与约束") && history.size() == 7, "goal and original history remain available");
            System.out.println("PASS compaction preserves complete multi-tool batches");

            var registry = new ToolRegistry(context.tools());
            var valid = registry.execute(new ToolCall("p1", "update_plan",
                    Map.of("steps", "[{\"id\":\"read\",\"task\":\"阅读资料\",\"status\":\"in_progress\"}]")));
            var invalid = registry.execute(new ToolCall("p2", "update_plan", Map.of("steps", """
                    [{"id":"read","task":"重复","status":"done"},{"id":"read","task":"重复","status":"done"}]
                    """)));
            check(!valid.error() && invalid.error() && context.prepare(List.of(Message.user("目标")))
                    .getFirst().text().contains("阅读资料"), "reject invalid replacement and preserve plan");
            System.out.println("PASS invalid plan update preserves the previous plan");

            Path separateRuns = root.resolve("separate-runs");
            var reusable = new LongTaskContext(1800, separateRuns, input, ignored -> {});
            var runTools = new java.util.ArrayList<>(reusable.tools());
            runTools.add(new ReadTextTool(input, 16 * 1024));
            var reusedTools = new ToolRegistry(runTools);
            ModelClient model = (messages, tools) -> {
                if (messages.size() == 1) {
                    check(messages.getFirst().text().contains("当前计划 []"), "each run starts with an empty plan");
                    return new ModelReply.Tools(List.of(
                            new ToolCall("plan", "update_plan", Map.of("steps", """
                                    [{"id":"read","task":"阅读资料","status":"in_progress"}]
                                    """)),
                            new ToolCall("same-id", "read_text", Map.of("path", "trace.md"))));
                }
                return new ModelReply.FinalAnswer("done");
            };
            var runtime = new AgentRuntime(model, reusedTools, 2, ignored -> {}, reusable);
            check(runtime.run("第一轮").status() == AgentResult.Status.COMPLETED, "first reuse run completes");
            Files.writeString(input.resolve("trace.md"), original + "第二次任务的新资料\n");
            check(runtime.run("第二轮").status() == AgentResult.Status.COMPLETED, "second reuse run completes");
            try (var paths = Files.walk(separateRuns)) {
                var files = paths.filter(Files::isRegularFile).toList();
                check(files.size() == 2, "separate runs never reuse an artifact for the same call ID");
                check(!Files.readString(files.get(0)).equals(Files.readString(files.get(1))),
                        "each artifact contains the text read in its own run");
            }
            System.out.println("PASS repeated runtime calls isolate plan and artifact IDs");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
