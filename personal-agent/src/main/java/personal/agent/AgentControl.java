package personal.agent;

import java.util.List;

/** Host-owned interaction at safe boundaries of the Agent loop. */
public interface AgentControl {
    AgentControl NONE = new AgentControl() {};

    record Input(String id, String text) {
        public String message() { return "[追加要求 id=" + id + "]\n" + text; }
    }
    record Snapshot(String phase, int turn, List<Message> messages, List<Message> modelInput, ToolCall activeTool) {
        public Snapshot {
            messages = List.copyOf(messages);
            if (modelInput != null) modelInput = List.copyOf(modelInput);
        }
    }

    default List<Input> pending(List<Message> history) { return List.of(); }
    default void applied(List<Input> inputs) {}
    default boolean finishAllowed(List<Message> history) { return pending(history).isEmpty(); }
    default boolean stopped() { return false; }
    default boolean paused() { return false; }
    default boolean waiting() { return false; }
    default void ask(String question) { throw new IllegalStateException("Interactive host is unavailable"); }
    default void observe(Snapshot snapshot) {}
}
