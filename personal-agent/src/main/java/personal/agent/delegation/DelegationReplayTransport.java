package personal.agent.delegation;

import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.deepseek.ChatTransport;

import java.util.List;
import java.util.Map;

/** The parent delegates once. The reader uses the existing separate file-read replay. */
public final class DelegationReplayTransport implements ChatTransport {
    private final ObjectMapper json = new ObjectMapper();
    private final String file;

    public DelegationReplayTransport(String file) { this.file = file; }

    @Override public Response post(String requestJson) throws Exception {
        var messages = json.readTree(requestJson).path("messages");
        var last = messages.get(messages.size() - 1);
        if ("user".equals(last.path("role").asText())) {
            String arguments = json.writeValueAsString(Map.of("task", "读取 " + file + " 并摘录关于工具执行的资料。"));
            var call = Map.of("id", "delegate-reader", "type", "function",
                    "function", Map.of("name", "delegate_task", "arguments", arguments));
            return response("tool_calls", Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
        }
        String content = last.path("content").asText();
        String summary = content.startsWith("{") ? json.readTree(content).path("summary").asText() : content;
        return response("stop", Map.of("role", "assistant", "content",
                "[replay] 父 Agent 已收到 reader 的结构化结果。\n\n" + summary));
    }

    private Response response(String finish, Map<String, Object> message) throws Exception {
        return new Response(200, json.writeValueAsString(Map.of(
                "choices", List.of(Map.of("finish_reason", finish, "message", message)))));
    }
}
