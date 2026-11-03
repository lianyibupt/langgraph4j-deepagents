package personal.agent;

import java.util.List;
import java.util.Objects;

/** The runtime's immutable conversation protocol, independent of any provider. */
public record Message(Role role, String text, List<ToolCall> toolCalls,
                      String toolCallId, boolean toolError) {
    public enum Role { USER, ASSISTANT, TOOL }

    public Message {
        Objects.requireNonNull(role);
        Objects.requireNonNull(text);
        toolCalls = List.copyOf(toolCalls);
        if (role != Role.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Only assistant messages can request tools");
        }
        if (role == Role.TOOL) {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("Tool results require a call ID");
            }
        } else if (toolCallId != null || toolError) {
            throw new IllegalArgumentException("Only tool results carry a call ID or error flag");
        }
    }

    public static Message user(String text) {
        return new Message(Role.USER, text, List.of(), null, false);
    }

    public static Message answer(String text) {
        return new Message(Role.ASSISTANT, text, List.of(), null, false);
    }

    public static Message calls(List<ToolCall> calls) {
        return new Message(Role.ASSISTANT, "", calls, null, false);
    }

    public static Message tool(ToolCall call, ToolResult result) {
        return new Message(Role.TOOL, result.text(), List.of(), call.id(), result.error());
    }
}
