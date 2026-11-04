package personal.agent.deepseek;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DeepSeekHttpTransport implements ChatTransport {
    public static final URI OFFICIAL_ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public DeepSeekHttpTransport(String apiKey, Duration timeout) { this(OFFICIAL_ENDPOINT, apiKey, timeout); }

    /** Alternate endpoints are restricted to loopback for local protocol tests. */
    public DeepSeekHttpTransport(URI endpoint, String apiKey, Duration timeout) {
        Objects.requireNonNull(endpoint);
        boolean localTest = "http".equals(endpoint.getScheme())
                && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost());
        if ((!OFFICIAL_ENDPOINT.equals(endpoint) && !localTest) || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("Unsupported endpoint: use official DeepSeek or loopback tests");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.strip().chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Missing or invalid DeepSeek API key");
        }
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("HTTP timeout must be between 1 ms and 300 seconds");
        }
        this.endpoint = endpoint;
        this.apiKey = apiKey.strip();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Response post(String requestJson) throws Exception {
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            // JDK header validation exceptions may contain the entire header value.
            throw new IllegalArgumentException("DEEPSEEK_REQUEST: invalid HTTP request configuration");
        }
        // DEBUG S2-HTTP: inspect endpoint and requestJson; avoid expanding authorization headers.
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // Bound the complete response, including its body, rather than just headers.
            HttpResponse<String> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.body().length() > 1_000_000) throw new IOException("DEEPSEEK_RESPONSE_TOO_LARGE");
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("DEEPSEEK_TIMEOUT: response deadline exceeded");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) throw new IOException("DEEPSEEK_TIMEOUT: request deadline exceeded");
            throw new IOException("DEEPSEEK_NETWORK: request failed; check connection and proxy settings");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
    }
}
