package personal.agent;

import java.util.List;

/** A final answer or tool-call batch, retaining any accompanying assistant text. */
public sealed interface ModelReply {
    record FinalAnswer(String text) implements ModelReply {
        public FinalAnswer {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Final answer must not be blank");
            }
        }
    }

    record Tools(List<ToolCall> calls, String text) implements ModelReply {
        public Tools(List<ToolCall> calls) { this(calls, ""); }

        public Tools {
            calls = List.copyOf(calls);
            if (text == null) text = "";
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("Tool call batch must not be empty");
            }
        }
    }
}
