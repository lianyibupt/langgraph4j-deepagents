package personal.agent;

import personal.agent.deepseek.DeepSeekHttpTransport;
import personal.agent.deepseek.DeepSeekModelClient;
import personal.agent.deepseek.ReplayTransport;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class Main {
    public static void main(String[] args) {
        final Map<String, String> configuration;
        try {
            // The launcher supplies an absolute path; IDE launches use workspace cwd.
            Path envFile = Path.of(System.getProperty("personal.agent.env", ".env"));
            configuration = EnvConfig.load(envFile, System.getenv());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e instanceof IOException ? "Configuration error: cannot read .env" : e.getMessage());
            System.exit(2);
            return;
        }
        int code = run(args, Path.of(System.getProperty("personal.agent.workspace", "examples")), System.out, System.err, configuration);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, Path workspace, PrintStream out, PrintStream err) {
        return run(args, workspace, out, err, System.getenv());
    }

    static int run(String[] args, Path workspace, PrintStream out, PrintStream err, Map<String, String> environment) {
        final Options options;
        final ModelClient modelClient;
        try {
            options = Options.parse(args, environment);
            if (options.help()) { help(out); return 0; }
            modelClient = model(options, environment, new ConsoleTrace(out));
        } catch (IllegalArgumentException e) {
            err.println("Configuration error: " + e.getMessage());
            help(err);
            return 2;
        }
        out.println("Java Personal Agent · provider=" + options.provider() + " model="
                + (options.provider().equals("scripted") ? "scripted（固定响应替身）" : options.model()));
        if (options.provider().equals("replay")) out.println("协议回放模式：不访问网络，没有真实模型推理。");
        if (options.provider().equals("deepseek")) out.println("DeepSeek 真实模式：thinking=disabled，工作目录资料可能随工具结果发给模型。");
        out.println("Workspace: " + workspace.toAbsolutePath().normalize());
        try {
            ToolRegistry tools = new ToolRegistry(List.of(new ReadTextTool(workspace, 16 * 1024)));
            // DEBUG S2-1: inspect options, modelClient and tools before starting the task.
            var runtime = new AgentRuntime(modelClient, tools, options.maxTurns(), new ConsoleTrace(out));
            AgentResult result = runtime.run(options.question());
            out.println("\nStatus: " + result.status() + " | modelTurns=" + result.modelTurns());
            out.println(result.detail());
            result.finalAnswer().ifPresent(answer -> out.println("\n" + answer));
            if (options.messages()) printMessages(result.messages(), out);
            return result.status() == AgentResult.Status.COMPLETED ? 0 : 1;
        } catch (IOException | IllegalArgumentException e) {
            err.println("Startup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static ModelClient model(Options options, Map<String, String> env, ConsoleTrace trace) {
        return switch (options.provider()) {
            case "scripted" -> new ScriptedModelClient(options.file());
            case "replay" -> new DeepSeekModelClient(options.model(), new ReplayTransport(options.file()), trace, options.wire());
            case "deepseek" -> {
                String key = env.get("DEEPSEEK_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Missing DEEPSEEK_API_KEY; configure it in personal-agent/.env");
                }
                long timeout = Long.parseLong(env.getOrDefault("DEEPSEEK_TIMEOUT_SECONDS", "60"));
                yield new DeepSeekModelClient(options.model(), new DeepSeekHttpTransport(key, Duration.ofSeconds(timeout)), trace, options.wire());
            }
            default -> throw new IllegalArgumentException("Unknown provider");
        };
    }

    private record Options(String provider, String file, String model, String question, int maxTurns,
                           boolean messages, boolean wire, boolean help) {
        static Options parse(String[] args, Map<String, String> env) {
            String provider = "scripted", file = "agent-loop.md", model = env.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash");
            String question = null;
            int maxTurns = 4;
            boolean messages = false, wire = false, help = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--provider" -> provider = value(args, ++index, "--provider");
                    case "--file" -> file = value(args, ++index, "--file");
                    case "--model" -> model = value(args, ++index, "--model");
                    case "--question" -> question = value(args, ++index, "--question");
                    case "--max-turns" -> maxTurns = Integer.parseInt(value(args, ++index, "--max-turns"));
                    case "--messages" -> messages = true;
                    case "--wire" -> wire = true;
                    case "--help" -> help = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            if (!List.of("scripted", "replay", "deepseek").contains(provider)) throw new IllegalArgumentException("--provider must be scripted, replay or deepseek");
            if (maxTurns < 1) throw new IllegalArgumentException("--max-turns must be positive");
            if (model.isBlank()) throw new IllegalArgumentException("--model must not be blank");
            if (question == null) question = "请先调用 read_text 阅读 " + file + "，再用自己的话解释 Agent Loop 的步骤，并注明资料来源。";
            return new Options(provider, file, model, question, maxTurns, messages, wire, help);
        }

        private static String value(String[] args, int index, String name) {
            if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }
    }

    private static void printMessages(List<Message> messages, PrintStream out) {
        out.println("\n--- 完整消息（--messages 显式开启）---");
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            out.println("message[" + i + "] role=" + message.role());
            for (ToolCall call : message.toolCalls()) out.println("  toolCall id=" + call.id() + " name=" + call.name() + " arguments=" + call.arguments());
            if (message.toolCallId() != null) out.println("  toolCallId=" + message.toolCallId() + " error=" + message.toolError());
            if (!message.text().isEmpty()) out.println(message.text());
        }
    }

    private static void help(PrintStream out) {
        out.println("Usage: bash run.sh [--debug] [--provider scripted|replay|deepseek] [--model NAME]");
        out.println("       [--question TEXT] [--file RELATIVE_PATH] [--max-turns N] [--messages] [--wire] [--help]");
        out.println("Default: scripted. Configuration: project .env; process environment overrides file values.");
        out.println("DeepSeek needs DEEPSEEK_API_KEY; optional DEEPSEEK_MODEL and DEEPSEEK_TIMEOUT_SECONDS.");
    }
}
