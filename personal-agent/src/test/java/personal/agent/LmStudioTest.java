package personal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import personal.agent.deepseek.ChatTransport;
import personal.agent.lmstudio.LmStudioCodec;
import personal.agent.lmstudio.LmStudioHttpTransport;
import personal.agent.lmstudio.LmStudioModelClient;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline tests for the local LM Studio provider: wire format, decoding and configuration guards. */
public final class LmStudioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int passed;
    private static int failed;
    @FunctionalInterface interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        var definitions = List.of(new Tool.Definition("read_text", "Read UTF-8", Map.of("path", "Relative file path")));

        test("request is OpenAI-compatible without DeepSeek thinking mode", () -> {
            var body = JSON.readTree(new LmStudioCodec(4096, 0.2).encode("qwen3.8-9b-distill", List.of(Message.user("中文问题")), definitions));
            DeepSeekTest.check(body.path("model").asText().equals("qwen3.8-9b-distill"), "model");
            DeepSeekTest.check(!body.has("thinking"), "no provider-specific thinking field");
            DeepSeekTest.check(body.has("stream") && !body.path("stream").asBoolean(), "non-streaming");
            DeepSeekTest.check(body.path("max_tokens").asInt() == 4096, "configurable output bound");
            DeepSeekTest.check(Math.abs(body.path("temperature").asDouble() - 0.2) < 0.0001, "temperature");
            DeepSeekTest.check(body.path("messages").get(0).path("role").asText().equals("system"), "system first");
            DeepSeekTest.check(body.path("messages").get(1).path("content").asText().equals("中文问题"), "Unicode preserved");
            DeepSeekTest.check(body.path("tool_choice").asText().equals("auto"), "tool choice");
            var schema = body.path("tools").get(0).path("function").path("parameters");
            DeepSeekTest.check(schema.path("type").asText().equals("object") && !schema.path("additionalProperties").asBoolean(true), "JSON schema");
        });

        test("tool call history survives provider serialization", () -> {
            ToolCall call = new ToolCall("call-1", "read_text", Map.of("path", "a\"中.md"));
            var history = List.of(Message.user("read"), Message.calls(List.of(call)), Message.tool(call, ToolResult.failure("FILE_NOT_FOUND")));
            var body = JSON.readTree(new LmStudioCodec().encode("local", history, definitions));
            DeepSeekTest.check(body.path("messages").get(2).path("tool_calls").get(0).path("id").asText().equals("call-1"), "call ID");
            String arguments = body.path("messages").get(2).path("tool_calls").get(0).path("function").path("arguments").asText();
            DeepSeekTest.check(JSON.readTree(arguments).path("path").asText().equals("a\"中.md"), "arguments as JSON string");
            DeepSeekTest.check(body.path("messages").get(3).path("tool_call_id").asText().equals("call-1"), "result ID");
        });

        test("final answer ignores reasoning_content", () -> {
            var reply = (ModelReply.FinalAnswer) new LmStudioCodec().decode(finalResponse("本地回答", "先想一想"));
            DeepSeekTest.check(reply.text().equals("本地回答"), "answer text");
        });

        test("tool calls decode with null content and reasoning text", () -> {
            var message = JSON.createObjectNode().put("role", "assistant").put("reasoning_content", "思考");
            message.putNull("content");
            message.putArray("tool_calls").addObject().put("id", "call-9").put("type", "function")
                    .putObject("function").put("name", "read_text").put("arguments", "{\"path\":\"note.md\"}");
            var body = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", message))));
            var reply = (ModelReply.Tools) new LmStudioCodec().decode(body);
            DeepSeekTest.check(reply.calls().size() == 1 && reply.calls().get(0).id().equals("call-9"), "decoded call");
            DeepSeekTest.check(reply.text().isEmpty(), "reasoning is not answer text");
        });

        test("length finish asks for a larger local budget", () -> {
            var body = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "length",
                    "message", Map.of("role", "assistant", "content", "")))));
            expect("LMSTUDIO_TRUNCATED", () -> new LmStudioCodec(256, 0.3).decode(body));
        });

        test("blank answer is reported instead of silently accepted", () -> {
            var body = JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", "  ")))));
            expect("LMSTUDIO_EMPTY", () -> new LmStudioCodec().decode(body));
        });

        test("empty local answer is retried once with an explicit instruction", () -> {
            var requests = new ArrayList<String>();
            var count = new AtomicInteger();
            ChatTransport fake = json -> {
                requests.add(json);
                return new ChatTransport.Response(200, count.getAndIncrement() == 0 ? emptyStopResponse() : finalResponse("恢复后的回答", "思考"));
            };
            var reply = (ModelReply.FinalAnswer) new LmStudioModelClient("local", fake, e -> {}, false, 512, 0.3)
                    .reply(List.of(Message.user("问题")), List.of());
            DeepSeekTest.check(reply.text().equals("恢复后的回答"), "retry answer");
            DeepSeekTest.check(requests.size() == 2 && requests.get(1).contains("请直接输出最终回答"), "nudge only on retry");
        });

        test("a second empty answer fails instead of looping", () -> {
            var count = new AtomicInteger();
            ChatTransport fake = json -> { count.incrementAndGet(); return new ChatTransport.Response(200, emptyStopResponse()); };
            var client = new LmStudioModelClient("local", fake, e -> {}, false, 512, 0.3);
            expect("LMSTUDIO_EMPTY", () -> client.reply(List.of(Message.user("问题")), List.of()));
            DeepSeekTest.check(count.get() == 2, "exactly one retry");
        });

        test("only loopback http endpoints are accepted", () -> {
            expect("loopback", () -> new LmStudioHttpTransport("http://192.168.1.5:1234/v1", "key", Duration.ofSeconds(3)));
            expect("loopback", () -> new LmStudioHttpTransport("https://127.0.0.1:1234/v1", "key", Duration.ofSeconds(3)));
            expect("loopback", () -> new LmStudioHttpTransport("http://127.0.0.1:1234/v1?x=1", "key", Duration.ofSeconds(3)));
            expect("must not be blank", () -> new LmStudioHttpTransport(" ", "key", Duration.ofSeconds(3)));
            expect("unsupported characters", () -> new LmStudioHttpTransport("http://127.0.0.1:1234/v1", "key-密钥", Duration.ofSeconds(3)));
            expect("TIMEOUT", () -> new LmStudioHttpTransport("http://127.0.0.1:1234/v1", "key", Duration.ofSeconds(301)));
            // A local server may disable authentication, so a blank key is allowed.
            new LmStudioHttpTransport("http://127.0.0.1:1234/v1", "", Duration.ofSeconds(3));
            new LmStudioHttpTransport("http://localhost:1234/v1", null, Duration.ofSeconds(3));
        });

        test("CLI rejects remote or malformed local settings before sending data", () -> {
            var sinkOut = new ByteArrayOutputStream();
            var sink = new PrintStream(sinkOut, true, StandardCharsets.UTF_8);
            var unused = Path.of("nonexistent-unused-workspace");
            DeepSeekTest.check(Main.run(new String[]{"--provider", "lmstudio", "--question", "ping"}, unused, sink, sink,
                    Map.of("LMSTUDIO_BASE_URL", "http://example.com/v1")) == 2, "remote endpoint rejected");
            DeepSeekTest.check(sinkOut.toString(StandardCharsets.UTF_8).contains("loopback"), "actionable endpoint error");
            sinkOut.reset();
            DeepSeekTest.check(Main.run(new String[]{"--provider", "lmstudio", "--question", "ping"}, unused, sink, sink,
                    Map.of("LMSTUDIO_MAX_TOKENS", "many")) == 2, "non-numeric budget rejected");
            DeepSeekTest.check(sinkOut.toString(StandardCharsets.UTF_8).contains("LMSTUDIO_MAX_TOKENS"), "names the variable");
            sinkOut.reset();
            DeepSeekTest.check(Main.run(new String[]{"--provider", "lmstudio", "--help"}, unused, sink, sink, Map.of()) == 0, "help needs no server");
            DeepSeekTest.check(sinkOut.toString(StandardCharsets.UTF_8).contains("lmstudio"), "help lists the provider");
        });

        System.out.printf("LM Studio tests: %d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static String emptyStopResponse() throws Exception {
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", "", "reasoning_content", "只有思考")))));
    }

    static String finalResponse(String text, String reasoning) throws Exception {
        var message = JSON.createObjectNode().put("role", "assistant").put("content", text).put("reasoning_content", reasoning);
        return JSON.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", message))));
    }

    private static void test(String name, Case body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }

    private static void expect(String marker, Case body) throws Exception {
        try { body.run(); }
        catch (IllegalArgumentException e) { DeepSeekTest.check(e.getMessage().contains(marker), "expected " + marker + ", got " + e.getMessage()); return; }
        throw new AssertionError("expected rejection containing " + marker);
    }
}
