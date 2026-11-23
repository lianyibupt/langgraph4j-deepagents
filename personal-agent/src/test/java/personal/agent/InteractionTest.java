package personal.agent;

import personal.agent.delegation.CheckpointStore;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Critical boundary cases for late user input and cooperative stopping. */
public final class InteractionTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("interaction-");
        var input = Files.createDirectory(directory.resolve("input"));
        var turns = new AtomicInteger();
        var correction = new AgentControl.Input("followup-1", "请采用补充要求。");
        var control = new AgentControl() {
            public List<Input> pending(List<Message> history) {
                return turns.get() == 1 && history.stream().noneMatch(message -> message.text().equals(correction.message()))
                        ? List.of(correction) : List.of();
            }
        };
        ModelClient model = (history, tools) -> {
            if (turns.incrementAndGet() == 1) return new ModelReply.FinalAnswer("较早生成的回答");
            if (!history.getLast().text().equals(correction.message())) throw new AssertionError("late input missing");
            return new ModelReply.FinalAnswer("已按补充要求继续。");
        };
        var binding = new CheckpointStore.Binding("test", "test", input.toString(), "test");
        try (var store = new CheckpointStore(directory.resolve("run.json"), input, binding, false)) {
            var runtime = new AgentRuntime(model, new ToolRegistry(List.of()), 3, event -> {}, null, control);
            var result = runtime.runCheckpointed("原任务", store, false, 0);
            if (result.modelTurns() != 2 || store.load().phase() != CheckpointStore.Phase.COMPLETED) throw new AssertionError("late input not continued durably");
        }
        System.out.println("PASS user input arriving with a final answer is checkpointed and processed in a new turn");

        var calls = new AtomicInteger();
        var stop = new AgentControl() { public boolean stopped() { return calls.get() > 0; } };
        Tool tool = new Tool() {
            public Definition definition() { return new Definition("count", "count", java.util.Map.of()); }
            public ToolResult execute(java.util.Map<String, String> args) { calls.incrementAndGet(); return ToolResult.success("done"); }
        };
        ModelClient batch = (history, tools) -> new ModelReply.Tools(List.of(
                new ToolCall("one", "count", java.util.Map.of()), new ToolCall("two", "count", java.util.Map.of())));
        var runtime = new AgentRuntime(batch, new ToolRegistry(List.of(tool)), 3, event -> {}, null, stop);
        var result = runtime.run("两项工具任务");
        if (result.status() != AgentResult.Status.CANCELLED || calls.get() != 1
                || result.messages().stream().filter(message -> message.role() == Message.Role.TOOL).count() != 2) {
            throw new AssertionError("stop must keep paired tool results and skip remaining work");
        }
        System.out.println("PASS stop skips remaining tool work while preserving a complete paired batch");
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
