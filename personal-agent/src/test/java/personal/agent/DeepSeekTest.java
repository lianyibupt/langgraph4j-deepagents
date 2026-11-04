package personal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.deepseek.ChatTransport;
import personal.agent.deepseek.DeepSeekCodec;
import personal.agent.deepseek.DeepSeekModelClient;
import personal.agent.deepseek.DeepSeekHttpTransport;
import personal.agent.deepseek.ReplayTransport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeepSeekTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int passed;
    private static int failed;
    @FunctionalInterface interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        var codec = new DeepSeekCodec();
        var definitions = List.of(new Tool.Definition("read_text", "Read UTF-8", Map.of("path", "Relative file path")));
        test("request selects non-thinking non-streaming mode and bounds output", () -> {
            JsonNode body = JSON.readTree(codec.encode("deepseek-flash", List.of(Message.user("中文问题")), definitions));
            check(body.path("model").asText().equals("deepseek-flash"), "model");
            check(body.path("thinking").path("type").asText().equals("disabled"), "explicitly disable default thinking");
            check(body.has("stream") && !body.path("stream").asBoolean(), "non-streaming");
            check(body.path("max_tokens").asInt() == 1024, "output bound");
            check(body.path("messages").get(0).path("role").asText().equals("system"), "system first");
            check(body.path("messages").get(1).path("content").asText().equals("中文问题"), "Unicode preserved");
        });
        test("tool definition becomes a real JSON Schema", () -> {
            JsonNode body = JSON.readTree(codec.encode("deepseek-flash", List.of(Message.user("read")), definitions));
            JsonNode function = body.path("tools").get(0).path("function");
            check(function.path("name").asText().equals("read_text"), "name");
            JsonNode schema = function.path("parameters");
            check(schema.path("type").asText().equals("object"), "object parameters");
            check(schema.path("properties").path("path").path("type").asText().equals("string"), "string path");
            check(schema.path("required").get(0).asText().equals("path") && !schema.path("additionalProperties").asBoolean(true), "required field and no extras");
        });
        test("assistant call and tool result survive provider serialization", () -> {
            ToolCall call = new ToolCall("call-1", "read_text", Map.of("path", "a\"中.md"));
            var history = List.of(Message.user("read"), Message.calls(List.of(call)), Message.tool(call, ToolResult.failure("FILE_NOT_FOUND")));
            JsonNode body = JSON.readTree(codec.encode("deepseek-flash", history, definitions));
            JsonNode assistant = body.path("messages").get(2);
            check(assistant.path("tool_calls").get(0).path("id").asText().equals("call-1"), "call ID");
            String arguments = assistant.path("tool_calls").get(0).path("function").path("arguments").asText();
            check(JSON.readTree(arguments).path("path").asText().equals("a\"中.md"), "arguments encoded as JSON string");
            JsonNode tool = body.path("messages").get(3);
            check(tool.path("role").asText().equals("tool") && tool.path("tool_call_id").asText().equals("call-1"), "result ID");
            check(tool.path("content").asText().contains("FILE_NOT_FOUND"), "tool error remains content");
        });
        test("decode tool call with null content", () -> {
            var reply = (ModelReply.Tools) codec.decode(toolResponse("{\"path\":\"note.md\"}"));
            check(reply.calls().getFirst().id().equals("call-1"), "ID");
            check(reply.calls().getFirst().arguments().get("path").equals("note.md"), "argument");
        });
        test("decode final text", () -> {
            var reply = (ModelReply.FinalAnswer) codec.decode(finalResponse("来自真实格式的回答"));
            check(reply.text().equals("来自真实格式的回答"), "text decoded");
        });
        test("runtime preserves assistant text accompanying tools", () -> {
            ModelClient client = (m, t) -> m.size() == 1
                    ? new ModelReply.Tools(List.of(new ToolCall("x", "unknown", Map.of())), "先读取资料")
                    : new ModelReply.FinalAnswer("done");
            AgentResult result = new AgentRuntime(client, new ToolRegistry(List.of()), 2, e -> {}).run("read");
            check(result.messages().get(1).text().equals("先读取资料"), "assistant text retained in history");
            JsonNode wire = JSON.readTree(codec.encode("deepseek-flash", result.messages(), definitions));
            check(wire.path("messages").get(2).path("content").asText().equals("先读取资料"), "retained on next API request");
        });
        test("reject truncated or filtered completion", () -> {
            for (String reason : List.of("length", "content_filter", "aborted", "insufficient_system_resource")) {
                expect("finish_reason", () -> codec.decode(finalResponse("partial").replace("\"stop\"", "\"" + reason + "\"")));
            }
        });
        test("reject malformed JSON, duplicate keys and trailing JSON", () -> {
            expect("JSON", () -> codec.decode("not json"));
            expect("JSON", () -> codec.decode("{\"choices\":[],\"choices\":[]}"));
            expect("JSON", () -> codec.decode(finalResponse("x") + "{}"));
        });
        test("reject malformed or non-string tool arguments", () -> {
            for (String value : List.of("{broken", "[]", "{\"path\":3}", "{\"path\":null}", "{\"path\":\"a\",\"path\":\"b\"}")) {
                expect("arguments", () -> codec.decode(toolResponse(value)));
            }
        });
        test("reject missing choice, wrong role and empty final output", () -> {
            expect("choices", () -> codec.decode("{\"choices\":[]}"));
            expect("role", () -> codec.decode(finalResponse("x").replace("assistant", "user")));
            expect("content", () -> codec.decode(finalResponse(" ")));
            expect("tool_calls", () -> codec.decode("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[]}}]}"));
        });
        test("adapter sends request and returns decoded response", () -> {
            AtomicInteger requests = new AtomicInteger();
            ChatTransport transport = request -> {
                check(JSON.readTree(request).path("model").asText().equals("deepseek-flash"), "request reached transport");
                requests.incrementAndGet();
                return new ChatTransport.Response(200, finalResponse("done"));
            };
            var client = new DeepSeekModelClient("deepseek-flash", transport, event -> {}, false);
            check(((ModelReply.FinalAnswer) client.reply(List.of(Message.user("hello")), definitions)).text().equals("done"), "decoded reply");
            check(requests.get() == 1, "one request; no hidden retry");
        });
        test("HTTP errors omit reflected secret and response text", () -> {
            for (int status : List.of(401, 402, 429, 500)) {
                var events = new ArrayList<String>();
                var client = new DeepSeekModelClient("deepseek-flash", req -> new ChatTransport.Response(status, "secret-sentinel echoed by server"), events::add, true);
                try { client.reply(List.of(Message.user("hello")), definitions); throw new AssertionError("expected HTTP failure"); }
                catch (IllegalArgumentException e) {
                    check(e.getMessage().contains("HTTP " + status), "status retained");
                    check(!e.getMessage().contains("secret-sentinel") && !events.toString().contains("secret-sentinel"), "body omitted on errors");
                }
            }
        });
        test("wire JSON is opt-in and default logs omit content", () -> {
            var events = new ArrayList<String>();
            ChatTransport transport = req -> new ChatTransport.Response(200, finalResponse("private-response-marker"));
            new DeepSeekModelClient("deepseek-flash", transport, events::add, false).reply(List.of(Message.user("private-request-marker")), definitions);
            check(!events.toString().contains("private-"), "default metadata only");
            events.clear();
            new DeepSeekModelClient("deepseek-flash", transport, events::add, true).reply(List.of(Message.user("private-request-marker")), definitions);
            check(events.toString().contains("private-request-marker") && events.toString().contains("private-response-marker"), "wire opt-in");
        });
        test("decode preserves content and multiple tools", () -> {
            var root = JSON.readTree(toolResponse("{\"path\":\"note.md\"}"));
            var message = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("choices").get(0).path("message");
            message.put("content", "先读取资料");
            var calls = (com.fasterxml.jackson.databind.node.ArrayNode) message.path("tool_calls");
            var second = calls.get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) second).put("id", "call-2");
            calls.add(second);
            var reply = (ModelReply.Tools) codec.decode(JSON.writeValueAsString(root));
            check(reply.text().equals("先读取资料") && reply.calls().size() == 2, "content and both calls retained");
        });
        test("transport rejects unsafe endpoint and invalid configuration", () -> {
            expect("endpoint", () -> new DeepSeekHttpTransport(URI.create("https://other.example/chat/completions"), "sentinel", Duration.ofSeconds(2)));
            expect("API", () -> new DeepSeekHttpTransport(" ", Duration.ofSeconds(2)));
            expect("API", () -> new DeepSeekHttpTransport("secret\ninjected", Duration.ofSeconds(2)));
            expect("API", () -> new DeepSeekHttpTransport("test-key-sentinel-密钥", Duration.ofSeconds(2)));
            expect("API", () -> new DeepSeekHttpTransport("test key sentinel", Duration.ofSeconds(2)));
            expect("timeout", () -> new DeepSeekHttpTransport("sentinel", Duration.ZERO));
        });
        test("protocol replay completes through codec and a real file read", () -> {
            var root = Files.createTempDirectory("deepseek-replay-test-");
            try {
                Files.writeString(root.resolve("sample.md"), "协议回放中的实际资料");
                var client = new DeepSeekModelClient("deepseek-flash", new ReplayTransport("sample.md"), e -> {}, false);
                var result = new AgentRuntime(client, new ToolRegistry(List.of(new ReadTextTool(root, 1024))), 4, e -> {}).run("read");
                check(result.status() == AgentResult.Status.COMPLETED && result.modelTurns() == 2, "two-round replay");
                check(result.finalAnswer().orElseThrow().contains("协议回放中的实际资料"), "real file content returned");
                check(result.finalAnswer().orElseThrow().contains("replay"), "replay clearly identified");
            } finally {
                Files.deleteIfExists(root.resolve("sample.md")); Files.deleteIfExists(root);
            }
        });
        test("CLI replay exposes wire JSON without a key", () -> {
            var root = Files.createTempDirectory("deepseek-cli-test-");
            var bytes = new ByteArrayOutputStream();
            try {
                Files.writeString(root.resolve("agent-loop.md"), "回放资料");
                var out = new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
                int code = Main.run(new String[]{"--provider", "replay", "--wire", "--messages"}, root, out, out);
                String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                check(code == 0 && output.contains("provider=replay"), "replay CLI");
                check(output.contains("wire.request") && output.contains("wire.response"), "wire snapshots");
                check(output.contains("toolCallId=replay-read-1"), "result association");
            } finally {
                Files.deleteIfExists(root.resolve("agent-loop.md")); Files.deleteIfExists(root);
            }
        });
        test("CLI validates provider, question and missing key before any request", () -> {
            var bytes = new ByteArrayOutputStream();
            var sink = new PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
            var unusedRoot = java.nio.file.Path.of("nonexistent-unused-workspace");
            check(Main.run(new String[]{"--provider", "deepseek"}, unusedRoot, sink, sink, Map.of()) == 2, "missing key fails locally");
            check(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Missing DEEPSEEK_API_KEY"), "actionable missing key");
            check(Main.run(new String[]{"--provider", "other"}, unusedRoot, sink, sink, Map.of()) == 2, "provider validation");
            check(Main.run(new String[]{"--question", " "}, unusedRoot, sink, sink, Map.of()) == 2, "empty question");
            check(Main.run(new String[]{"--provider", "deepseek", "--help"}, unusedRoot, sink, sink, Map.of()) == 0, "help needs no credentials");
            bytes.reset();
            check(Main.run(new String[]{"--provider", "deepseek"}, unusedRoot, sink, sink,
                    Map.of("DEEPSEEK_API_KEY", "test-key-sentinel-密钥")) == 2, "invalid key rejected locally");
            check(!bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("test-key-sentinel"), "invalid key never echoed");
        });
        System.out.printf("DeepSeek tests: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static String finalResponse(String text) throws Exception {
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", text)))));
    }

    static String toolResponse(String arguments) throws Exception {
        var message = JSON.createObjectNode().put("role", "assistant");
        message.putNull("content");
        message.putArray("tool_calls").addObject().put("id", "call-1").put("type", "function")
                .putObject("function").put("name", "read_text").put("arguments", arguments);
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))));
    }

    private static void test(String name, Case body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }

    static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }

    private static void expect(String marker, Case body) throws Exception {
        try { body.run(); }
        catch (IllegalArgumentException e) { check(e.getMessage().contains(marker), "expected " + marker + ", got " + e.getMessage()); return; }
        throw new AssertionError("expected rejection containing " + marker);
    }
}
