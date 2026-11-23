package personal.agent.longtask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import personal.agent.Message;
import personal.agent.Tool;
import personal.agent.ToolCall;
import personal.agent.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Per-run plan and a budgeted projection of the original conversation. */
public final class LongTaskContext {
    public static final int MIN_CHARS = 1200;
    static final String TOOL_ERROR_PREFIX = "[工具失败] ";
    static final String TOOL_RESULT_PREFIX = "[工具原文] ";
    static final String ARTIFACT_PREFIX = "[产物引用] ";
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final int budget;
    private final ArtifactStore artifacts;
    private final Consumer<String> trace;
    private List<Step> plan = List.of();

    record Step(String id, String task, String status) {}

    public LongTaskContext(int budget, Path artifactDirectory, Path inputDirectory, Consumer<String> trace) throws IOException {
        if (budget < MIN_CHARS) throw new IllegalArgumentException("--context-chars must be at least " + MIN_CHARS);
        this.budget = budget;
        this.trace = trace;
        artifacts = new ArtifactStore(artifactDirectory, inputDirectory, trace);
    }

    /** Begin a fresh run while retaining any files saved by earlier runs. */
    public void reset() {
        plan = List.of();
        artifacts.reset();
    }

    /** Rebuild runtime state from durable results without executing historical tools. */
    public void restore(List<Message> history) throws IOException {
        reset();
        for (int i = 1; i < history.size(); i++) {
            Message assistant = history.get(i);
            for (var call : assistant.toolCalls()) {
                Message message = history.get(++i);
                if (call.name().equals("update_plan") && !message.toolError()) {
                    var result = updatePlan(call.arguments().get("steps"));
                    if (result.error() || !message.text().equals(result.text())) {
                        throw new IOException("CHECKPOINT_PLAN_INVALID");
                    }
                }
                // DEBUG S7-2: original results regenerate stable run-local artifact IDs.
                record(call, new ToolResult(message.text(), message.toolError()));
            }
        }
        trace.accept("context.restored planSteps=" + plan.size() + " artifacts=" + artifacts.count());
    }

    public List<Tool> tools() {
        return List.of(new Tool() {
            private final Definition definition = new Definition("update_plan", "Replace the current task plan",
                    Map.of("steps", "JSON array encoded as a string. 1 to 8 objects with id, task and status. "
                            + "Status is pending, in_progress or done. At most one in_progress. Keep tasks short."));

            @Override public Definition definition() { return definition; }
            @Override public ToolResult execute(Map<String, String> arguments) {
                if (!arguments.keySet().equals(definition.parameters().keySet())) {
                    return ToolResult.failure("PLAN_INVALID: expected steps");
                }
                return updatePlan(arguments.get("steps"));
            }
        }, new Tool() {
            private final Definition definition = new Definition("read_artifact", "Read up to 512 characters from an original tool result",
                    Map.of("artifact_id", "ID from this run, for example artifact-1",
                            "offset", "Zero-based UTF-16 offset as a string. Start with 0, then use next_offset"));

            @Override public Definition definition() { return definition; }
            @Override public ToolResult execute(Map<String, String> arguments) throws IOException {
                if (!arguments.keySet().equals(definition.parameters().keySet())) {
                    return ToolResult.failure("ARTIFACT_INVALID: expected artifact_id and offset");
                }
                try {
                    var slice = artifacts.read(arguments.get("artifact_id"), Integer.parseInt(arguments.get("offset")),
                            Math.min(512, budget / 4));
                    return ToolResult.success("artifact_id=" + slice.artifactId() + " offset=" + slice.offset()
                            + " next_offset=" + slice.nextOffset() + " eof=" + slice.eof() + "\n" + slice.text());
                } catch (IllegalArgumentException e) {
                    return ToolResult.failure("ARTIFACT_INVALID: check the artifact ID and offset");
                }
            }
        });
    }

    private ToolResult updatePlan(String steps) {
        try {
            if (steps.length() > 8192) throw new IllegalArgumentException();
            var node = json.readTree(steps);
            if (node == null || !node.isArray() || node.isEmpty() || node.size() > 8) throw new IllegalArgumentException();
            var next = new ArrayList<Step>();
            var ids = new HashSet<String>();
            int inProgress = 0;
            for (var item : node) {
                if (!item.isObject() || item.size() != 3 || !item.path("id").isTextual()
                        || !item.path("task").isTextual() || !item.path("status").isTextual()) throw new IllegalArgumentException();
                String id = item.path("id").textValue();
                String task = item.path("task").textValue();
                String status = item.path("status").textValue();
                if (!id.matches("[A-Za-z0-9_-]{1,24}") || !ids.add(id) || task.isBlank() || task.length() > 120
                        || !List.of("pending", "in_progress", "done").contains(status)) throw new IllegalArgumentException();
                if (status.equals("in_progress") && ++inProgress > 1) throw new IllegalArgumentException();
                next.add(new Step(id, task, status));
            }
            // DEBUG S5-1: replace the plan only after validating the complete update.
            plan = List.copyOf(next);
            long done = plan.stream().filter(step -> step.status().equals("done")).count();
            trace.accept("plan.status steps=" + plan.size() + " done=" + done);
            return ToolResult.success("PLAN_UPDATED");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ToolResult.failure("PLAN_INVALID: supply 1–8 unique steps with valid status and at most one in_progress");
        }
    }

    public String prompt(String question) {
        return question + """

                长任务规则
                先调用 update_plan 建立简短计划，完成关键步骤时更新状态，结束前标记实际完成的步骤。
                工具结果可能变为带 artifact_id 的引用，按需调用 read_artifact 从 offset=0 开始读取原文。
                如需后续内容，继续传入 next_offset。执行摘要只描述工具状态，事实依据来自实际读到的材料。
                """;
    }

    public List<Message> prepare(List<Message> history) throws IOException {
        var projected = new ArrayList<Message>(history);
        var batchStarts = new ArrayList<Integer>();
        for (int i = 1; i < history.size(); i++) {
            Message message = history.get(i);
            if (!message.toolCalls().isEmpty()) batchStarts.add(i);
            if (message.role() != Message.Role.TOOL) continue;
            var artifact = artifacts.findByCall(message.toolCallId());
            String visible;
            if (artifact == null) {
                visible = (message.toolError() ? TOOL_ERROR_PREFIX : TOOL_RESULT_PREFIX) + message.text();
            } else {
                visible = (message.toolError() ? TOOL_ERROR_PREFIX : ARTIFACT_PREFIX)
                        + json.writeValueAsString(Map.of("artifact_id", artifact.id(),
                                "original_chars", artifact.chars(), "next_offset", 0,
                                "note", "Original tool result stored locally. Use read_artifact."));
            }
            projected.set(i, new Message(Message.Role.TOOL, visible, List.of(), message.toolCallId(), message.toolError()));
        }
        String planJson = json.writeValueAsString(plan);
        int maxOmitted = Math.max(0, batchStarts.size() - 1);
        for (int omitted = 0; omitted <= maxOmitted; omitted++) {
            int from = omitted == 0 ? 1 : batchStarts.get(omitted);
            var snapshot = new ArrayList<Message>();
            String state = "\n\n[运行时状态]\n当前计划 " + planJson + summary(history, from, omitted);
            for (int i = 1; i < from; i++) {
                if (history.get(i).role() == Message.Role.USER) state += "\n\n持续生效的补充要求\n" + history.get(i).text();
            }
            snapshot.add(Message.user(history.getFirst().text() + state));
            snapshot.addAll(projected.subList(from, projected.size()));
            long chars = snapshot.stream().mapToLong(LongTaskContext::measure).sum();
            // DEBUG S5-3: compare original history and projected snapshot, including whole batches.
            if (chars <= budget) {
                trace.accept("context.prepare chars=" + chars + " budget=" + budget + " compactedBatches=" + omitted
                        + " historyMessages=" + history.size() + " modelMessages=" + snapshot.size());
                return List.copyOf(snapshot);
            }
        }
        throw new IllegalArgumentException("CONTEXT_BUDGET_EXCEEDED: goal, plan and most recent tool batch exceed " + budget + " characters");
    }

    /** Keep long results even if the runtime stops before making another model request. */
    public void record(ToolCall call, ToolResult result) throws IOException {
        if (result.text().length() > Math.min(1200, budget / 3) && !call.name().equals("read_artifact")) {
            artifacts.archive(call.id(), result.text());
        }
    }

    private String summary(List<Message> history, int from, int omitted) {
        var state = new StringBuilder();
        if (artifacts.count() > 0) state.append("\n已保存原文编号 artifact-1 至 artifact-").append(artifacts.count()).append("。");
        if (omitted == 0) return state.toString();
        state.append("\n已压缩 ").append(omitted).append(" 个历史工具批次。最近省略的结果状态如下。");
        int added = 0;
        for (int index = from - 1; index > 0 && added < 3; index--) {
            var item = history.get(index);
            if (item.role() != Message.Role.TOOL) continue;
            var artifact = artifacts.findByCall(item.toolCallId());
            state.append("\n- ").append(item.toolError() ? "失败" : "成功")
                    .append("，原始字符数 ").append(item.text().length());
            if (artifact != null) state.append("，原文 ").append(artifact.id());
            added++;
        }
        return state.toString();
    }

    private static long measure(Message message) {
        long chars = 24L + message.text().length();
        if (message.toolCallId() != null) chars += message.toolCallId().length();
        for (var call : message.toolCalls()) {
            chars += 24L + call.id().length() + call.name().length();
            for (var entry : call.arguments().entrySet()) chars += entry.getKey().length() + entry.getValue().length();
        }
        return chars;
    }
}
