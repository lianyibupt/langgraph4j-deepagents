package personal.agent;

import com.sun.net.httpserver.HttpServer;
import personal.agent.deepseek.DeepSeekHttpTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
            System.out.println("HTTP integration tests: 4 passed, 0 failed");
        } finally { server.stop(0); }
    }
}
