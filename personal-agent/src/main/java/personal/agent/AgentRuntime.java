package personal.agent;

import personal.agent.longtask.LongTaskContext;
import personal.agent.delegation.CheckpointStore;
import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final LongTaskContext context;
    private final AgentControl control;

    public AgentRuntime(ModelClient model, ToolRegistry tools, int maxModelTurns, Consumer<String> trace) {
        this(model, tools, maxModelTurns, trace, null);
    }

    public AgentRuntime(ModelClient model, ToolRegistry tools, int maxModelTurns, Consumer<String> trace,
                        LongTaskContext context) {
        this(model, tools, maxModelTurns, trace, context, AgentControl.NONE);
    }

    public AgentRuntime(ModelClient model, ToolRegistry tools, int maxModelTurns, Consumer<String> trace,
                        LongTaskContext context, AgentControl control) {
        this.model = Objects.requireNonNull(model);
        this.tools = Objects.requireNonNull(tools);
        if (maxModelTurns < 1) throw new IllegalArgumentException("maxModelTurns must be positive");
        this.maxModelTurns = maxModelTurns;
        this.trace = Objects.requireNonNull(trace);
        this.context = context;
        this.control = Objects.requireNonNull(control);
    }

    public AgentResult run(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }
        var initial = List.of(Message.user(question));
        if (context == null) return runLoop(initial, 0, null, 0);
        synchronized (context) {
            context.reset();
            return runLoop(initial, 0, null, 0);
        }
    }

    public AgentResult runCheckpointed(String question, CheckpointStore store, boolean resume, int pauseAfter)
            throws IOException {
        if (context == null) return continueCheckpoint(question, store, resume, pauseAfter);
        synchronized (context) {
            return continueCheckpoint(question, store, resume, pauseAfter);
        }
    }

    private AgentResult continueCheckpoint(String question, CheckpointStore store, boolean resume, int pauseAfter)
            throws IOException {
        if (context != null && !resume) context.reset();
        if (!resume) {
            if (question == null || question.isBlank()) throw new IllegalArgumentException("Question must not be blank");
            store.start(question);
        }
        var state = store.load();
        if (context != null && resume) context.restore(state.messages());
        trace.accept("parent-checkpoint " + (resume ? "loaded" : "created") + " phase=" + state.phase()
                + " modelTurns=" + state.modelTurns() + " path=" + store.path());
        if (state.phase() == CheckpointStore.Phase.COMPLETED) {
            return stop(AgentResult.Status.COMPLETED, state.modelTurns(), state.messages(), "Completed checkpoint loaded");
        }
        try {
            return runLoop(state.messages(), state.modelTurns(), store, pauseAfter);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private AgentResult runLoop(List<Message> initial, int completedTurns, CheckpointStore store, int pauseAfter) {
        var messages = new ArrayList<>(initial);
        var seenCallIds = new HashSet<String>();
        for (Message message : initial) for (ToolCall call : message.toolCalls()) seenCallIds.add(call.id());

        for (int turn = completedTurns + 1; turn <= maxModelTurns; turn++) {
            if (control.stopped()) return stop(AgentResult.Status.CANCELLED, turn - 1, messages, "Stopped by user");
            applyInputs(messages, store, turn - 1);
            if (Thread.currentThread().isInterrupted()) {
                return stop(AgentResult.Status.FAILED, turn - 1, messages, "INTERRUPTED");
            }
            final List<Message> modelMessages;
            try {
                modelMessages = context == null ? List.copyOf(messages) : context.prepare(messages);
            } catch (IllegalArgumentException e) {
                return stop(AgentResult.Status.FAILED, turn - 1, messages, e.getMessage());
            } catch (IOException e) {
                return stop(AgentResult.Status.FAILED, turn - 1, messages, "CONTEXT_ARTIFACT_FAILED: " + e.getMessage());
            }
            trace.accept("model.request turn=" + turn + " messages=" + modelMessages.size());
            control.observe(new AgentControl.Snapshot("MODEL_REQUEST", turn, messages, modelMessages, null));
            final ModelReply reply;
            try {
                // Breakpoint 1: inspect exactly what this model request receives.
                reply = model.reply(modelMessages, tools.definitions());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stop(AgentResult.Status.FAILED, turn, messages, "INTERRUPTED");
            } catch (Exception e) {
                return stop(AgentResult.Status.FAILED, turn, messages,
                        "MODEL_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            // Breakpoint 2: a model response is a decision, not tool execution.
            if (control.stopped()) return stop(AgentResult.Status.CANCELLED, turn, messages, "Stopped after the active model request");
            if (reply instanceof ModelReply.FinalAnswer answer) {
                trace.accept("model.response type=final_answer");
                messages.add(Message.answer(answer.text()));
                if (!control.finishAllowed(messages)) {
                    applyInputs(messages, store, turn);
                    continue;
                }
                save(store, CheckpointStore.Phase.COMPLETED, turn, messages);
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
            messages.add(Message.calls(batch.calls(), batch.text()));
            save(store, CheckpointStore.Phase.TOOLS_PENDING, turn, messages);
            for (ToolCall call : batch.calls()) {
                trace.accept("tool.start id=" + call.id() + " name=" + call.name());
                control.observe(new AgentControl.Snapshot("TOOL_RUNNING", turn, messages, null, call));
                // Breakpoint 3: only the runtime, through the registry, executes tools.
                ToolResult result = control.stopped() ? ToolResult.failure("CANCELLED: tool skipped after stop request") : tools.execute(call);
                messages.add(Message.tool(call, result));
                // Breakpoint 4: the result is now in the next model input.
                trace.accept("tool.result id=" + call.id() + " error=" + result.error()
                        + " chars=" + result.text().length());
                if (store != null && Thread.currentThread().isInterrupted()) {
                    return stop(AgentResult.Status.FAILED, turn, messages, "CHECKPOINT_TOOLS_UNCERTAIN: interrupted during tools");
                }
                if (context != null) {
                    try {
                        context.record(call, result);
                    } catch (IOException e) {
                        return stop(AgentResult.Status.FAILED, turn, messages, "CONTEXT_ARTIFACT_FAILED: " + e.getMessage());
                    }
                }
                control.observe(new AgentControl.Snapshot("TOOL_COMPLETED", turn, messages, null, null));
            }
            save(store, CheckpointStore.Phase.READY, turn, messages);
            if (control.stopped()) return stop(AgentResult.Status.CANCELLED, turn, messages, "Stopped after the complete tool batch");
            if (control.waiting()) return stop(AgentResult.Status.WAITING_INPUT, turn, messages, "Waiting for a user answer");
            if (control.paused() || (store != null && pauseAfter > 0 && turn >= pauseAfter)) {
                return stop(AgentResult.Status.PAUSED, turn, messages, "Paused after a complete tool batch. Resume with --resume " + store.path());
            }
        }
        return stop(AgentResult.Status.TURN_LIMIT, Math.max(completedTurns, maxModelTurns), messages,
                "Maximum model turns reached: " + maxModelTurns);
    }

    private void applyInputs(ArrayList<Message> messages, CheckpointStore store, int turn) {
        var inputs = control.pending(messages);
        if (inputs.isEmpty()) return;
        for (var input : inputs) messages.add(Message.user(input.message()));
        // DEBUG S9-1: save the input in history before acknowledging its application.
        save(store, CheckpointStore.Phase.READY, turn, messages);
        control.applied(inputs);
        trace.accept("inbox.applied count=" + inputs.size());
        control.observe(new AgentControl.Snapshot("INPUT_APPLIED", turn, messages, null, null));
    }

    private void save(CheckpointStore store, CheckpointStore.Phase phase, int turn, List<Message> messages) {
        if (store == null) return;
        try {
            store.save(phase, turn, messages);
            trace.accept("parent-checkpoint saved phase=" + phase + " modelTurns=" + turn);
        } catch (IOException e) {
            throw new UncheckedIOException(new IOException("CHECKPOINT_WRITE_FAILED: " + e.getMessage(), e));
        }
    }

    private AgentResult stop(AgentResult.Status status, int turns, List<Message> messages, String detail) {
        trace.accept("run.stop status=" + status + " modelTurns=" + turns);
        control.observe(new AgentControl.Snapshot(status.name(), turns, messages, null, null));
        return new AgentResult(status, turns, messages, detail);
    }
}
