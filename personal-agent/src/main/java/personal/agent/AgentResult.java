package personal.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AgentResult(Status status, int modelTurns, List<Message> messages, String detail) {
    public enum Status { COMPLETED, TURN_LIMIT, FAILED }

    public AgentResult {
        Objects.requireNonNull(status);
        messages = List.copyOf(messages);
        Objects.requireNonNull(detail);
    }

    public Optional<String> finalAnswer() {
        if (status != Status.COMPLETED || messages.isEmpty()) return Optional.empty();
        return Optional.of(messages.getLast().text());
    }
}
