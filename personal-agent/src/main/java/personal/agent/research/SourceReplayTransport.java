package personal.agent.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.deepseek.ChatTransport;

import java.util.List;
import java.util.Map;

/** Runs the same provider protocol with a deterministic sequence of source tools. */
public final class SourceReplayTransport implements ChatTransport {
    private final ObjectMapper json = new ObjectMapper();
    private final SourceResearch.Mode mode;

    public SourceReplayTransport() { this(SourceResearch.Mode.SOURCE); }

    public SourceReplayTransport(SourceResearch.Mode mode) { this.mode = mode; }

    @Override
    public Response post(String requestJson) throws Exception {
        JsonNode request = json.readTree(requestJson);
        JsonNode messages = request.path("messages");
        boolean interactive = false;
        for (var tool : request.path("tools")) {
            if (tool.path("function").path("name").asText().equals("ask_user")) interactive = true;
        }
        boolean asked = messages.toString().contains("source-ask_user");
        boolean askFirst = false;
        for (var message : messages) {
            if (message.path("role").asText().equals("user") && message.path("content").asText().contains("[教学：先询问]")) askFirst = true;
        }
        if (interactive && askFirst && !asked) {
            return call("ask_user", Map.of("question", "这次笔记应优先整理哪些方面？请在补充要求中回答。"));
        }
        int toolIndex = messages.size() - 1;
        while (toolIndex >= 0 && !messages.get(toolIndex).path("role").asText().equals("tool")) toolIndex--;
        if (toolIndex < 0) return call(mode.listTool, Map.of("filter", ""));
        JsonNode last = messages.get(toolIndex);
        String previous = messages.get(toolIndex - 1).path("tool_calls").path(0).path("function").path("name").asText();
        if (previous.equals("ask_user")) return call(mode.listTool, Map.of("filter", ""));
        String content = last.path("content").asText();
        if (!content.startsWith("{")) return finish("[replay] 工具未返回证据，无法生成带引用的报告。");
        JsonNode result = json.readTree(content);
        if (previous.equals(mode.listTool)) {
            if (result.path("paths").isEmpty()) return finish("[replay] 未发现可读取的资料。");
            return call(mode.searchTool, Map.of("query", mode == SourceResearch.Mode.MARKDOWN ? "# " : "class"));
        }
        if (previous.equals(mode.searchTool)) {
            JsonNode hit = result.path("hits").path(0);
            String path = hit.path("path").asText();
            int line = hit.path("line").asInt(1);
            if (path.isEmpty()) {
                for (var message : messages) {
                    if (!message.path("role").asText().equals("tool") || !message.path("content").asText().startsWith("{")) continue;
                    var listing = json.readTree(message.path("content").asText()).path("paths");
                    if (!listing.isMissingNode() && !listing.isEmpty()) { path = listing.path(0).asText(); break; }
                }
            }
            return call(mode.readTool, Map.of("path", path, "start_line", String.valueOf(line),
                    "end_line", String.valueOf(line + 4)));
        }
        if (!previous.equals(mode.readTool)) throw new IllegalArgumentException("Unexpected source replay step");
        String path = result.path("path").asText();
        JsonNode lines = result.path("lines");
        int start = lines.path(0).path("number").asInt();
        if (mode == SourceResearch.Mode.MARKDOWN) {
            int end = lines.path(lines.size() - 1).path("number").asInt();
            var note = new StringBuilder("# 知识整理回放\n\n## 来源摘录\n\n");
            for (var line : lines) {
                String excerpt = line.path("text").asText().replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
                note.append("> ").append(excerpt).append('\n');
            }
            return finish(note + "\n来源 [[" + path + ":" + start + (end == start ? "" : "-" + end)
                    + "]]。\n\n本次为离线回放，摘录用于观察读取、引用与保存。");
        }
        return finish("# 源码研究回放\n\n发现并检索了源码，已读取 " + path
                + " 的第 " + start + " 行及其后续片段 [[" + path + ":" + start + "]]。\n\n"
                + "回放用于展示发现、检索、读取与引用校验；问题分析由真实模型模式完成。");
    }

    private Response call(String tool, Map<String, String> arguments) throws Exception {
        var call = Map.of("id", "source-" + tool, "type", "function", "function",
                Map.of("name", tool, "arguments", json.writeValueAsString(arguments)));
        return reply("tool_calls", Map.of("role", "assistant", "content", "", "tool_calls", List.of(call)));
    }

    private Response finish(String content) throws Exception {
        return reply("stop", Map.of("role", "assistant", "content", content));
    }

    private Response reply(String finish, Map<String, Object> message) throws Exception {
        return new Response(200, json.writeValueAsString(Map.of(
                "choices", List.of(Map.of("finish_reason", finish, "message", message)))));
    }
}
