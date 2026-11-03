package personal.agent;

import java.util.List;

/** Stage 1 supports either a final answer or a batch of tool calls. */
public sealed interface ModelReply {
    record FinalAnswer(String text) implements ModelReply {
        public FinalAnswer {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Final answer must not be blank");
            }
        }
    }

    record Tools(List<ToolCall> calls) implements ModelReply {
        public Tools {
            calls = List.copyOf(calls);
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("Tool call batch must not be empty");
            }
        }
    }
}
