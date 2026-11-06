package personal.agent.longtask;

import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.deepseek.ChatTransport;

import java.util.List;
import java.util.Map;

/** Deterministic learning run through the same provider protocol as the real model. */
public final class LongTaskReplayTransport implements ChatTransport {
    private final ObjectMapper json = new ObjectMapper();
    private final String file;

    public LongTaskReplayTransport(String file) { this.file = file; }

    @Override
    public Response post(String requestJson) throws Exception {
        var messages = json.readTree(requestJson).path("messages");
        var last = messages.get(messages.size() - 1);
        String text = last.path("content").asText();
        if ("user".equals(last.path("role").asText())) {
            return call("s5-plan-start", "update_plan", Map.of("steps", plan(false)));
        }
        if (text.startsWith(LongTaskContext.TOOL_ERROR_PREFIX)) {
            return finish("工具执行失败，计划保留当前状态。");
        }
        return switch (last.path("tool_call_id").asText()) {
            case "s5-plan-start" -> text.equals(LongTaskContext.TOOL_RESULT_PREFIX + "PLAN_UPDATED")
                    ? call("s5-read", "read_text", Map.of("path", file)) : finish("计划更新失败，请检查工具结果。");
            case "s5-read" -> {
                String artifact = text.startsWith(LongTaskContext.ARTIFACT_PREFIX)
                        ? json.readTree(text.substring(LongTaskContext.ARTIFACT_PREFIX.length())).path("artifact_id").asText() : "";
                yield artifact.isEmpty()
                        ? call("s5-plan-end", "update_plan", Map.of("steps", plan(true)))
                        : call("s5-artifact", "read_artifact", Map.of("artifact_id", artifact, "offset", "0"));
            }
            case "s5-artifact" -> call("s5-plan-end", "update_plan", Map.of("steps", plan(true)));
            case "s5-plan-end" -> finish("离线回放已结束。可以对照计划状态、原始历史和模型收到的消息，检查资料的读取与回填。");
            default -> throw new IllegalArgumentException("Unexpected long-task replay step");
        };
    }

    private String plan(boolean done) throws Exception {
        return json.writeValueAsString(List.of(
                new LongTaskContext.Step("read", "读取调试资料", done ? "done" : "in_progress"),
                new LongTaskContext.Step("evidence", "检查可见的原文片段", done ? "done" : "pending"),
                new LongTaskContext.Step("report", "汇报执行状态", done ? "done" : "pending")));
    }

    private Response call(String id, String tool, Map<String, String> arguments) throws Exception {
        var toolCall = Map.of("id", id, "type", "function", "function",
                Map.of("name", tool, "arguments", json.writeValueAsString(arguments)));
        return reply("tool_calls", Map.of("role", "assistant", "content", "", "tool_calls", List.of(toolCall)));
    }

    private Response finish(String content) throws Exception {
        return reply("stop", Map.of("role", "assistant", "content", "[replay] " + content));
    }

    private Response reply(String finishReason, Map<String, Object> message) throws Exception {
        return new Response(200, json.writeValueAsString(Map.of(
                "choices", List.of(Map.of("finish_reason", finishReason, "message", message)))));
    }
}
