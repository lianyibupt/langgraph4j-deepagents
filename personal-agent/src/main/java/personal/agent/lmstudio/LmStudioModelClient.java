package personal.agent.lmstudio;

import personal.agent.Message;
import personal.agent.ModelClient;
import personal.agent.Tool;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** ModelClient backed by a locally hosted LM Studio model. */
public final class LmStudioModelClient implements ModelClient {
    /**
     * Local distilled reasoning models occasionally end a turn with an empty answer after
     * thinking. One retry with an explicit instruction recovers that case without weakening
     * the final-answer contract; anything else is reported as a protocol error.
     */
    private static final String ANSWER_NUDGE = "请直接输出最终回答，不要只输出思考过程。";
    private final String model;
    private final personal.agent.deepseek.ChatTransport transport;
    private final Consumer<String> trace;
    private final boolean wire;
    private final LmStudioCodec codec;

    public LmStudioModelClient(String model, personal.agent.deepseek.ChatTransport transport,
                               Consumer<String> trace, boolean wire, int maxTokens, double temperature) {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("LM Studio model must not be blank");
        this.model = model;
        this.transport = Objects.requireNonNull(transport);
        this.trace = Objects.requireNonNull(trace);
        this.wire = wire;
        this.codec = new LmStudioCodec(maxTokens, temperature);
    }

    public LmStudioModelClient(String model, personal.agent.deepseek.ChatTransport transport,
                               Consumer<String> trace, boolean wire) {
        this(model, transport, trace, wire, LmStudioCodec.DEFAULT_MAX_TOKENS, LmStudioCodec.DEFAULT_TEMPERATURE);
    }

    @Override
    public personal.agent.ModelReply reply(List<Message> messages, List<Tool.Definition> tools) throws Exception {
        for (int attempt = 1; ; attempt++) {
            List<Message> outgoing = attempt == 1 ? messages : nudged(messages);
            String requestJson = codec.encode(model, outgoing, tools);
            trace.accept("lmstudio.request model=" + model + " conversationMessages=" + outgoing.size() + " attempt=" + attempt);
            if (wire) trace.accept("wire.request " + requestJson);
            personal.agent.deepseek.ChatTransport.Response response = transport.post(requestJson);
            int statusCode = response.statusCode();
            trace.accept("lmstudio.response status=" + statusCode + " attempt=" + attempt);
            if (statusCode < 200 || statusCode >= 300) {
                String hint = switch (statusCode) {
                    case 401, 403 -> "LM Studio requires an API token; check LMSTUDIO_API_KEY or Manage Tokens";
                    case 404 -> "Model not found; check LMSTUDIO_MODEL and LMSTUDIO_BASE_URL";
                    case 400 -> "Request rejected; check the model name, max tokens and tool protocol";
                    case 503 -> "LM Studio could not load the model; open the app and load it once";
                    default -> "Local request failed; inspect LM Studio's server logs";
                };
                // Never log an error body that could reflect credentials or user data.
                throw new IllegalArgumentException("LMSTUDIO_HTTP: HTTP " + statusCode + "; " + hint);
            }
            String responseBody = response.body();
            if (wire) trace.accept("wire.response " + responseBody);
            try {
                return codec.decode(responseBody);
            } catch (IllegalArgumentException e) {
                if (attempt == 1 && e.getMessage() != null && e.getMessage().contains("LMSTUDIO_EMPTY")) {
                    trace.accept("lmstudio.retry reason=empty_answer");
                    continue;
                }
                throw e;
            }
        }
    }

    private static List<Message> nudged(List<Message> messages) {
        var copy = new java.util.ArrayList<Message>(messages);
        copy.add(Message.user(ANSWER_NUDGE));
        return List.copyOf(copy);
    }
}
