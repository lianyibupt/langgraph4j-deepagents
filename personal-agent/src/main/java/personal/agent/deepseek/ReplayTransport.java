package personal.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Deterministic provider-format replay, explicitly not a real API request. */
public final class ReplayTransport implements ChatTransport {
    private final String path;
    private final ObjectMapper json = new ObjectMapper();

    public ReplayTransport(String path) { this.path = path; }

    @Override
    public Response post(String requestJson) throws Exception {
        var requestTree = json.readTree(requestJson);
        var messages = requestTree.path("messages");
        var lastMessage = messages.get(messages.size() - 1);
        String finishReason;
        Map<String, Object> assistant;
        if ("user".equals(lastMessage.path("role").asText())) {
            var call = Map.of("id", "replay-read-1", "type", "function", "function", Map.of(
                    "name", "read_text", "arguments", json.writeValueAsString(Map.of("path", path))));
            assistant = Map.of("role", "assistant", "content", "[replay] 先读取资料。", "tool_calls", List.of(call));
            finishReason = "tool_calls";
        } else if ("tool".equals(lastMessage.path("role").asText())) {
            assistant = Map.of("role", "assistant", "content", "[replay] 工具实际返回以下内容：\n"
                    + lastMessage.path("content").asText() + "\n协议回放已结束；没有调用真实模型。");
            finishReason = "stop";
        } else throw new IllegalArgumentException("Replay expects a user message or tool result");
        // DEBUG S2-REPLAY: same provider response format, no network or credentials.
        String responseBody = json.writeValueAsString(Map.of("choices", List.of(
                Map.of("finish_reason", finishReason, "message", assistant))));
        return new Response(200, responseBody);
    }
}
