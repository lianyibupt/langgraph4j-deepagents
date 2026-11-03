package personal.agent;

import java.util.Objects;

/** Errors are observations returned to the model, rather than fake successes. */
public record ToolResult(String text, boolean error) {
    public ToolResult {
        Objects.requireNonNull(text);
    }

    public static ToolResult success(String text) { return new ToolResult(text, false); }
    public static ToolResult failure(String text) { return new ToolResult(text, true); }
}
