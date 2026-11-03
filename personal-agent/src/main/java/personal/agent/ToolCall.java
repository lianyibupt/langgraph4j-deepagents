package personal.agent;

import java.util.Map;

/** A model's request to execute one tool. IDs correlate calls with results. */
public record ToolCall(String id, String name, Map<String, String> arguments) {
    public ToolCall {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool call ID and name must not be blank");
        }
        arguments = Map.copyOf(arguments);
    }
}
