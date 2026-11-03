package personal.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class AgentRuntime {
    private final ModelClient model;
    private final ToolRegistry tools;
    private final int maxModelTurns;
    private final Consumer<String> trace;

    public AgentRuntime(ModelClient model, ToolRegistry tools, int maxModelTurns, Consumer<String> trace) {
        this.model = Objects.requireNonNull(model);
        this.tools = Objects.requireNonNull(tools);
        if (maxModelTurns < 1) throw new IllegalArgumentException("maxModelTurns must be positive");
        this.maxModelTurns = maxModelTurns;
        this.trace = Objects.requireNonNull(trace);
    }

    public AgentResult run(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
        var messages = new ArrayList<Message>();
        var seenCallIds = new HashSet<String>();
        messages.add(Message.user(question));

        for (int turn = 1; turn <= maxModelTurns; turn++) {
            if (Thread.currentThread().isInterrupted()) {
                return stop(AgentResult.Status.FAILED, turn - 1, messages, "INTERRUPTED");
            }
            trace.accept("model.request turn=" + turn + " messages=" + messages.size());
            final ModelReply reply;
            try {
                // Breakpoint 1: inspect exactly what this model request receives.
                reply = model.reply(List.copyOf(messages), tools.definitions());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stop(AgentResult.Status.FAILED, turn, messages, "INTERRUPTED");
            } catch (Exception e) {
                return stop(AgentResult.Status.FAILED, turn, messages,
                        "MODEL_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // Breakpoint 2: a model response is a decision, not tool execution.
            if (reply instanceof ModelReply.FinalAnswer answer) {
                trace.accept("model.response type=final_answer");
                messages.add(Message.answer(answer.text()));
                return stop(AgentResult.Status.COMPLETED, turn, messages, "Final response received");
            }
            if (!(reply instanceof ModelReply.Tools batch)) {
                return stop(AgentResult.Status.FAILED, turn, messages, "INVALID_MODEL_REPLY: null response");
            }

            // Validate the whole batch before executing even its first tool.
            var batchIds = new HashSet<String>();
            for (ToolCall call : batch.calls()) {
                if (seenCallIds.contains(call.id()) || !batchIds.add(call.id())) {
                    return stop(AgentResult.Status.FAILED, turn, messages,
                            "DUPLICATE_CALL_ID: " + call.id());
                }
            }
            seenCallIds.addAll(batchIds);
            trace.accept("model.response type=tool_calls count=" + batch.calls().size());
            messages.add(Message.calls(batch.calls()));
            for (ToolCall call : batch.calls()) {
                trace.accept("tool.start id=" + call.id() + " name=" + call.name());
                // Breakpoint 3: only the runtime, through the registry, executes tools.
                ToolResult result = tools.execute(call);
                messages.add(Message.tool(call, result));
                // Breakpoint 4: the result is now in the next model input.
                trace.accept("tool.result id=" + call.id() + " error=" + result.error()
                        + " chars=" + result.text().length());
            }
        }
        return stop(AgentResult.Status.TURN_LIMIT, maxModelTurns, messages,
                "Maximum model turns reached: " + maxModelTurns);
    }

    private AgentResult stop(AgentResult.Status status, int turns, List<Message> messages, String detail) {
        trace.accept("run.stop status=" + status + " modelTurns=" + turns);
        return new AgentResult(status, turns, messages, detail);
    }
}
