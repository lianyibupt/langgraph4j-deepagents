package personal.agent.deepseek;

import personal.agent.Message;
import personal.agent.ModelClient;
import personal.agent.ModelReply;
import personal.agent.Tool;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class DeepSeekModelClient implements ModelClient {
    private final String model;
    private final ChatTransport transport;
    private final Consumer<String> trace;
    private final boolean wire;
    private final DeepSeekCodec codec = new DeepSeekCodec();

    public DeepSeekModelClient(String model, ChatTransport transport, Consumer<String> trace, boolean wire) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("DeepSeek model must not be blank");
        this.model = model;
        this.transport = Objects.requireNonNull(transport);
        this.trace = Objects.requireNonNull(trace);
        this.wire = wire;
    }

    @Override
    public ModelReply reply(List<Message> messages, List<Tool.Definition> tools) throws Exception {
        String requestJson = codec.encode(model, messages, tools);
        trace.accept("deepseek.request model=" + model + " conversationMessages=" + messages.size());
        if (wire) trace.accept("wire.request " + requestJson);
        // DEBUG S2-4: requestJson is the exact JSON body; authentication is separate.
        ChatTransport.Response response = transport.post(requestJson);
        int statusCode = response.statusCode();
        trace.accept("deepseek.response status=" + statusCode);
        if (statusCode < 200 || statusCode >= 300) {
            String hint = switch (statusCode) {
                case 401 -> "Check DEEPSEEK_API_KEY";
                case 402 -> "Check API account balance";
                case 429 -> "Rate limited; retry manually later";
                default -> "Request failed; inspect API status and request configuration";
            };
            // Never log an error body that could reflect credentials or user data.
            throw new IllegalArgumentException("DEEPSEEK_HTTP: HTTP " + statusCode + "; " + hint);
        }
        String responseBody = response.body();
        if (wire) trace.accept("wire.response " + responseBody);
        // DEBUG S2-5: inspect statusCode and responseBody before parsing.
        ModelReply decoded = codec.decode(responseBody);
        return decoded;
    }
}
