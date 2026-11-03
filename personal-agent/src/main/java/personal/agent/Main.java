package personal.agent;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class Main {
    public static void main(String[] args) {
        int code = run(args, Path.of(System.getProperty("personal.agent.workspace", "examples")), System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, Path workspace, PrintStream out, PrintStream err) {
        String file = "agent-loop.md";
        int maxTurns = 4;
        boolean showMessages = false;
        try {
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--file" -> {
                        if (++index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
                            throw new IllegalArgumentException("--file requires a relative path");
                        }
                        file = args[index];
                    }
                    case "--max-turns" -> {
                        if (++index >= args.length) throw new IllegalArgumentException("--max-turns requires an integer");
                        maxTurns = Integer.parseInt(args[index]);
                        if (maxTurns < 1) throw new IllegalArgumentException("--max-turns must be positive");
                    }
                    case "--messages" -> showMessages = true;
                    case "--help" -> { help(out); return 0; }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
        } catch (IllegalArgumentException e) {
            err.println("Usage error: " + e.getMessage());
            help(err);
            return 2;
        }

        out.println("Java Personal Agent · Stage 1 · model=scripted（固定响应替身，无真实模型推理）");
        out.println("Workspace: " + workspace.toAbsolutePath().normalize());
        out.println("Requested file: " + file);
        try {
            ToolRegistry tools = new ToolRegistry(List.of(new ReadTextTool(workspace, 16 * 1024)));
            var runtime = new AgentRuntime(new ScriptedModelClient(file), tools, maxTurns, new ConsoleTrace(out));
            AgentResult result = runtime.run("请读取 " + file + "，展示关于 Agent Loop 的资料。");
            out.println("\nStatus: " + result.status() + " | modelTurns=" + result.modelTurns());
            out.println(result.detail());
            result.finalAnswer().ifPresent(answer -> out.println("\n" + answer));
            if (showMessages) printMessages(result.messages(), out);
            return result.status() == AgentResult.Status.COMPLETED ? 0 : 1;
        } catch (IOException | IllegalArgumentException e) {
            err.println("Startup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static void printMessages(List<Message> messages, PrintStream out) {
        out.println("\n--- 完整示例消息（--messages 显式开启）---");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            out.println("message[" + i + "] role=" + message.role());
            for (ToolCall call : message.toolCalls()) {
                out.println("  toolCall id=" + call.id() + " name=" + call.name() + " arguments=" + call.arguments());
            }
            if (message.toolCallId() != null) {
                out.println("  toolCallId=" + message.toolCallId() + " error=" + message.toolError());
            }
            if (!message.text().isEmpty()) out.println(message.text());
        }
    }

    private static void help(PrintStream out) {
        out.println("Usage: bash personal-agent/run.sh [--debug] [--file relative-path] [--max-turns N] [--messages] [--help]");
        out.println("The scripted model always reads one file from the bundled examples workspace.");
    }
}
