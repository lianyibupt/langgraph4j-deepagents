package personal.agent;

import java.io.PrintStream;
import java.util.function.Consumer;

/** Metadata-only events; full fixture history is an explicit CLI option. */
public final class ConsoleTrace implements Consumer<String> {
    private final PrintStream output;

    public ConsoleTrace(PrintStream output) { this.output = output; }

    @Override
    public void accept(String event) { output.println("[trace] " + event); }
}
