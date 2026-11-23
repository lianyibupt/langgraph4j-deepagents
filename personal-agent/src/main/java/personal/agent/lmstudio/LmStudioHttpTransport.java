package personal.agent.lmstudio;

import personal.agent.deepseek.ChatTransport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends chat requests to a loopback LM Studio server.
 *
 * <p>The endpoint is deliberately restricted to {@code http} loopback hosts so a task's
 * documents cannot be sent to a remote endpoint by a misconfigured base URL. The API token
 * is optional because LM Studio can run without authentication.</p>
 */
public final class LmStudioHttpTransport implements ChatTransport {
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:1234/v1";
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final int MAX_RESPONSE_BYTES = 4_000_000;

    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public LmStudioHttpTransport(String baseUrl, String apiKey, Duration timeout) {
        this.endpoint = endpoint(baseUrl);
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("LMSTUDIO_TIMEOUT_SECONDS must be between 1 ms and 300 seconds");
        }
        if (apiKey == null || apiKey.isBlank()) {
            this.apiKey = null;
        } else {
            String key = apiKey.strip();
            if (key.chars().anyMatch(c -> c < 33 || c > 126)) throw new IllegalArgumentException("LMSTUDIO_API_KEY contains unsupported characters");
            this.apiKey = key;
        }
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public LmStudioHttpTransport(String baseUrl, String apiKey) { this(baseUrl, apiKey, Duration.ofSeconds(180)); }

    /** Accepts either a base URL ({@code /v1}) or a full {@code /v1/chat/completions} URL. */
    static URI endpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("LMSTUDIO_BASE_URL must not be blank");
        final URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LMSTUDIO_BASE_URL is not a valid URL");
        }
        if (!"http".equals(base.getScheme()) || !LOOPBACK.contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("LMSTUDIO_BASE_URL must be a loopback http URL such as http://127.0.0.1:1234/v1; local data must not leave this machine");
        }
        String path = base.getPath() == null ? "" : base.getPath();
        if (path.endsWith("/chat/completions")) return base;
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return URI.create("http://" + base.getRawAuthority() + path + "/chat/completions");
    }

    @Override
    public Response post(String requestJson) throws Exception {
        final HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json");
            if (apiKey != null) builder.header("Authorization", "Bearer " + apiKey);
            request = builder.POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            // JDK header validation exceptions may contain the entire header value.
            throw new IllegalArgumentException("LMSTUDIO_REQUEST: invalid HTTP request configuration");
        }
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.body().length() > MAX_RESPONSE_BYTES) throw new IOException("LMSTUDIO_RESPONSE_TOO_LARGE");
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("LMSTUDIO_TIMEOUT: response deadline exceeded; raise LMSTUDIO_TIMEOUT_SECONDS");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) throw new IOException("LMSTUDIO_TIMEOUT: request deadline exceeded");
            throw new IOException("LMSTUDIO_NETWORK: request failed; is LM Studio's local server running on this port?");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
    }

}
