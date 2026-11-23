package personal.agent;

import com.sun.net.httpserver.HttpServer;
import personal.agent.deepseek.DeepSeekHttpTransport;
import personal.agent.lmstudio.LmStudioHttpTransport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Local loopback integration tests; never connects to DeepSeek or uses real credentials. */
public final class HttpTransportTest {
    public static void main(String[] args) throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var mode = new AtomicInteger(200);
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            int status = mode.get();
            if (status == 999) {
                try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                status = 200;
            }
            if (status == 302) exchange.getResponseHeaders().set("Location", "/chat/completions");
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        var lmAuthorization = new AtomicReference<String>();
        var lmBody = new AtomicReference<String>();
        var lmRequests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            lmRequests.incrementAndGet();
            lmBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lmAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"本地模型回答\"}}]}").getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            var client = new DeepSeekHttpTransport(uri, "test-key-sentinel", Duration.ofSeconds(3));
            var response = client.post("{\"messages\":[]}");
            DeepSeekTest.check(response.statusCode() == 200 && response.body().contains("ok"), "response body");
            DeepSeekTest.check("Bearer test-key-sentinel".equals(authorization.get()), "Bearer header");
            DeepSeekTest.check("POST".equals(method.get()) && body.get().equals("{\"messages\":[]}"), "POST JSON body");
            System.out.println("PASS HTTP request headers, method, body and response");

            mode.set(302);
            int before = requests.get();
            DeepSeekTest.check(client.post("{}").statusCode() == 302, "redirect returned for caller rejection");
            DeepSeekTest.check(requests.get() == before + 1, "no redirect followed");
            System.out.println("PASS HTTP redirect is not followed");

            mode.set(429);
            before = requests.get();
            DeepSeekTest.check(client.post("{}").statusCode() == 429 && requests.get() == before + 1, "no status retry");
            System.out.println("PASS HTTP rate limit is returned without retry");

            mode.set(999);
            var impatient = new DeepSeekHttpTransport(uri, "test-key-sentinel", Duration.ofMillis(120));
            long start = System.nanoTime();
            try { impatient.post("{}"); throw new AssertionError("timeout expected"); }
            catch (java.io.IOException e) { DeepSeekTest.check(e.getMessage().contains("TIMEOUT"), "timeout diagnosis"); }
            DeepSeekTest.check(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500, "bounded wait");
            System.out.println("PASS HTTP full request timeout");

            String lmBase = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var lm = new LmStudioHttpTransport(lmBase, "sk-lm-test-sentinel", Duration.ofSeconds(3));
            var lmResponse = lm.post("{\"messages\":[]}");
            DeepSeekTest.check(lmResponse.statusCode() == 200 && lmResponse.body().contains("本地模型回答"), "LM Studio response");
            DeepSeekTest.check("Bearer sk-lm-test-sentinel".equals(lmAuthorization.get()), "LM Studio Bearer header");
            DeepSeekTest.check(lmBody.get().equals("{\"messages\":[]}"), "LM Studio request body");
            DeepSeekTest.check(lmRequests.get() >= 1, "LM Studio base URL reached /v1/chat/completions");
            System.out.println("PASS LM Studio loopback endpoint, chat path and token header");

            new LmStudioHttpTransport(lmBase, "", Duration.ofSeconds(3)).post("{}");
            DeepSeekTest.check(lmAuthorization.get() == null, "no Authorization header without a token");
            System.out.println("PASS LM Studio allows an unauthenticated local server");

            var root = Files.createTempDirectory("lmstudio-cli-test-");
            try {
                Files.writeString(root.resolve("agent-loop.md"), "本地资料");
                var bytes = new ByteArrayOutputStream();
                var sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                int code = Main.run(new String[]{"--provider", "lmstudio", "--question", "ping"}, root, sink, sink,
                        Map.of("LMSTUDIO_BASE_URL", lmBase, "LMSTUDIO_MODEL", "lmstudio-test-model",
                               "LMSTUDIO_API_KEY", "sk-lm-test-sentinel", "LMSTUDIO_MAX_TOKENS", "512"));
                String output = bytes.toString(StandardCharsets.UTF_8);
                DeepSeekTest.check(code == 0, "CLI exit code");
                DeepSeekTest.check(output.contains("provider=lmstudio") && output.contains("lmstudio-test-model"), "provider banner");
                DeepSeekTest.check(output.contains("本地模型回答"), "local answer printed");
                DeepSeekTest.check(lmBody.get().contains("\"model\":\"lmstudio-test-model\"") && !lmBody.get().contains("thinking"),
                        "local model without DeepSeek thinking field");
            } finally {
                Files.deleteIfExists(root.resolve("agent-loop.md")); Files.deleteIfExists(root);
            }
            System.out.println("PASS CLI runs against a local LM Studio endpoint");
            System.out.println("HTTP integration tests: 7 passed, 0 failed");
        } finally { server.stop(0); }
    }
}
