package personal.agent;

import personal.agent.deepseek.DeepSeekHttpTransport;
import personal.agent.deepseek.DeepSeekModelClient;
import personal.agent.deepseek.ReplayTransport;
import personal.agent.lmstudio.LmStudioHttpTransport;
import personal.agent.lmstudio.LmStudioModelClient;
import personal.agent.delegation.CheckpointStore;
import personal.agent.delegation.DelegateTool;
import personal.agent.delegation.DelegationReplayTransport;
import personal.agent.longtask.LongTaskContext;
import personal.agent.longtask.LongTaskReplayTransport;
import personal.agent.research.NoteOutput;
import personal.agent.research.SourceReplayTransport;
import personal.agent.research.SourceResearch;
import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        if (options.provider().equals("lmstudio")) out.println("LM Studio 本地模式：推理在本机 127.0.0.1 完成，任务与资料不会离开这台电脑。");
        Path selected = options.researchDirectory();
        Path directory = selected == null ? workspace : selected;
        out.println("Workspace: " + directory.toAbsolutePath().normalize());
        try {
            Execution execution = execute(options, directory, modelClient, new ConsoleTrace(out), AgentControl.NONE);
            if (execution.publicationError() != null) {
                err.println("Status: FAILED | " + execution.publicationError());
                if (options.messages()) printMessages(execution.result().messages(), out);
                return 1;
            }
            if (execution.note() != null) out.println("Note saved: " + execution.note());
            return printResult(execution.result(), options.messages(), out);
        } catch (IOException | IllegalArgumentException e) {
            err.println("Startup failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    public record Execution(AgentResult result, Path note, String publicationError) {}

    public static Execution execute(String[] args, Path workspace, Map<String, String> environment,
                                    Consumer<String> trace) throws IOException {
        return execute(args, workspace, environment, trace, AgentControl.NONE);
    }

    public static Execution execute(String[] args, Path workspace, Map<String, String> environment,
                                    Consumer<String> trace, AgentControl control) throws IOException {
        Options options = Options.parse(args, environment);
        Path directory = options.researchDirectory() == null ? workspace : options.researchDirectory();
        return execute(options, directory, model(options, environment, trace), trace, control);
    }

    private static Execution execute(Options options, Path directory, ModelClient modelClient, Consumer<String> trace, AgentControl control)
            throws IOException {
        if (options.delegation()) {
            return new Execution(runDelegation(options, directory, modelClient, trace, control), null, null);
        }
        var research = options.researchDirectory() == null ? null : new SourceResearch(directory, options.researchMode());
        var note = options.notes() == null ? null : new NoteOutput(directory, options.output(), options.resume() != null);
        var context = options.contextChars() == 0 ? null
                : new LongTaskContext(options.contextChars(), options.artifactsDirectory(), directory, trace);
        if (options.checkpointPath() == null) {
            return executeTask(options, directory, modelClient, trace, research, note, context, null, control);
        }
        String version = inputVersion(options, directory, research);
        var settings = Map.of("mode", research == null ? (context == null ? "loop" : "long") : options.researchMode().name(),
                "contextChars", String.valueOf(options.contextChars()), "inputVersion", version,
                "output", options.output() == null ? "" : options.output().toAbsolutePath().normalize().toString(),
                "artifacts", options.artifactsDirectory() == null ? "" : options.artifactsDirectory().toAbsolutePath().normalize().toString());
        var binding = new CheckpointStore.Binding(options.provider(), options.model(), directory.toRealPath().toString(),
                options.file(), settings);
        try (var store = new CheckpointStore(options.checkpointPath(), directory, binding, options.resume() != null)) {
            trace.accept("checkpoint.input version=" + version);
            return executeTask(options, directory, modelClient, trace, research, note, context, store, control);
        }
    }

    private static Execution executeTask(Options options, Path directory, ModelClient modelClient, Consumer<String> trace,
                                         SourceResearch research, NoteOutput note, LongTaskContext context,
                                         CheckpointStore store, AgentControl control) throws IOException {
        var toolList = new ArrayList<Tool>(research == null
                ? List.of(new ReadTextTool(directory, 16 * 1024)) : research.tools());
        if (context != null) toolList.addAll(context.tools());
        if (control != AgentControl.NONE) toolList.add(new Tool() {
            public Definition definition() { return new Definition("ask_user", "Ask a question and wait for the user before continuing", Map.of("question", "A concise question for the user")); }
            public ToolResult execute(Map<String, String> arguments) {
                String question = arguments.get("question");
                if (question == null || question.isBlank() || question.length() > 2000) return ToolResult.failure("ASK_INVALID");
                control.ask(question);
                return ToolResult.success("WAITING_FOR_USER: the user's answer will arrive as a follow-up message");
            }
        });
        // DEBUG S2-1: inspect options, modelClient and tools before starting the task.
        var runtime = new AgentRuntime(modelClient, new ToolRegistry(toolList), options.maxTurns(), trace, context, control);
        String question = research == null ? options.question() : research.prompt(options.question());
        if (store != null && options.resume() != null && research != null) {
            research.restoreEvidence(store.load().messages());
            trace.accept("source.evidence restored=true");
        }
        String prompt = context == null ? question : context.prompt(question);
        AgentResult result = store == null ? runtime.run(prompt)
                : runtime.runCheckpointed(prompt, store, options.resume() != null, options.pauseAfter());
        if (store != null && !store.binding().configuration().get("inputVersion")
                .equals(inputVersion(options, directory, research))) {
            return new Execution(result, null, "CHECKPOINT_INPUT_CHANGED: input changed during the run");
        }
        if (research == null || result.finalAnswer().isEmpty()) return new Execution(result, null, null);
        try {
            String answer = result.finalAnswer().orElseThrow();
            trace.accept("source.report verifiedCitations=" + research.verifyCitations(answer));
            // DEBUG S4-1: the verified answer can now be saved to the user-selected path.
            return new Execution(result, note == null ? null : note.save(answer), null);
        } catch (IllegalArgumentException e) {
            return new Execution(result, null, e.getMessage());
        } catch (IOException e) {
            return new Execution(result, null, "NOTE_WRITE_FAILED: " + e.getMessage());
        }
    }

    private static String inputVersion(Options options, Path directory, SourceResearch research) throws IOException {
        if (research != null) return research.fingerprint();
        var result = new ReadTextTool(directory, 16 * 1024).execute(Map.of("path", options.file()));
        if (result.error()) throw new IOException("CHECKPOINT_INPUT_UNAVAILABLE: " + result.text());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(result.text().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentResult runDelegation(Options options, Path directory, ModelClient parent, Consumer<String> trace, AgentControl control)
            throws IOException {
        ModelClient reader = options.provider().equals("replay")
                ? new DeepSeekModelClient(options.model(), new ReplayTransport(options.file()), trace, options.wire())
                : parent;
        var readerTools = new ToolRegistry(List.of(new ReadTextTool(directory, 16 * 1024)));
        var parentTools = new ToolRegistry(List.of(new DelegateTool(reader, readerTools, trace)));
        var binding = new CheckpointStore.Binding(options.provider(), options.model(), directory.toRealPath().toString(), options.file());
        try (var store = new CheckpointStore(options.checkpointPath(), directory, binding, options.resume() != null)) {
            var runtime = new AgentRuntime(parent, parentTools, options.maxTurns(), trace, null, control);
            return runtime.runCheckpointed(options.question(), store, options.resume() != null, options.pauseAfter());
        }
    }

    private static int printResult(AgentResult result, boolean messages, PrintStream out) {
        out.println("\nStatus: " + result.status() + " | modelTurns=" + result.modelTurns());
        out.println(result.detail());
        result.finalAnswer().ifPresent(answer -> out.println("\n" + answer));
        if (messages) printMessages(result.messages(), out);
        return result.status() == AgentResult.Status.COMPLETED || result.status() == AgentResult.Status.PAUSED ? 0 : 1;
    }

    private static ModelClient model(Options options, Map<String, String> env, Consumer<String> trace) {
        return switch (options.provider()) {
            case "scripted" -> new ScriptedModelClient(options.file());
            case "replay" -> new DeepSeekModelClient(options.model(), options.replayTransport(), trace, options.wire());
            case "deepseek" -> {
                String key = env.get("DEEPSEEK_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("Missing DEEPSEEK_API_KEY; configure it in personal-agent/.env");
                }
                long timeout = Long.parseLong(env.getOrDefault("DEEPSEEK_TIMEOUT_SECONDS", "60"));
                yield new DeepSeekModelClient(options.model(), new DeepSeekHttpTransport(key, Duration.ofSeconds(timeout)), trace, options.wire());
            }
            case "lmstudio" -> {
                String baseUrl = env.getOrDefault("LMSTUDIO_BASE_URL", LmStudioHttpTransport.DEFAULT_BASE_URL);
                String key = env.get("LMSTUDIO_API_KEY");
                long timeout = environmentLong(env, "LMSTUDIO_TIMEOUT_SECONDS", 180);
                int maxTokens = environmentInt(env, "LMSTUDIO_MAX_TOKENS", 2048);
                double temperature = environmentDouble(env, "LMSTUDIO_TEMPERATURE", 0.3);
                yield new LmStudioModelClient(options.model(),
                        new LmStudioHttpTransport(baseUrl, key, Duration.ofSeconds(timeout)),
                        trace, options.wire(), maxTokens, temperature);
            }
            default -> throw new IllegalArgumentException("Unknown provider");
        };
    }

    private static int environmentInt(Map<String, String> env, String key, int fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static long environmentLong(Map<String, String> env, String key, long fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static double environmentDouble(Map<String, String> env, String key, double fallback) {
        String raw = env.get(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
    }

    private record Options(String provider, String file, String model, String question,
                           Path repository, Path notes, Path output, int maxTurns,
                           int contextChars, Path artifactsDirectory,
                           Path checkpoint, Path resume, int pauseAfter,
                           boolean messages, boolean wire, boolean help, boolean direct) {
        Path researchDirectory() { return notes == null ? repository : notes; }
        Path checkpointPath() { return resume == null ? checkpoint : resume; }
        boolean delegation() { return !direct && checkpointPath() != null && researchDirectory() == null && contextChars == 0; }

        SourceResearch.Mode researchMode() {
            return notes == null ? SourceResearch.Mode.SOURCE : SourceResearch.Mode.MARKDOWN;
        }

        personal.agent.deepseek.ChatTransport replayTransport() {
            if (delegation()) return new DelegationReplayTransport(file);
            if (contextChars > 0) return new LongTaskReplayTransport(file);
            if (researchDirectory() != null) return new SourceReplayTransport(researchMode());
            return new ReplayTransport(file);
        }

        static Options parse(String[] args, Map<String, String> env) {
            String provider = null, file = null, model = null;
            String question = null;
            Path repository = null;
            Path notes = null;
            Path output = null;
            int maxTurns = 0;
            int contextChars = 0;
            Path artifactsDirectory = null;
            Path checkpoint = null;
            Path resume = null;
            int pauseAfter = 0;
            boolean messages = false, wire = false, help = false;
            boolean direct = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--provider" -> provider = value(args, ++index, "--provider");
                    case "--file" -> file = value(args, ++index, "--file");
                    case "--repo" -> repository = Path.of(value(args, ++index, "--repo"));
                    case "--notes" -> notes = Path.of(value(args, ++index, "--notes"));
                    case "--output" -> output = Path.of(value(args, ++index, "--output"));
                    case "--context-chars" -> {
                        contextChars = Integer.parseInt(value(args, ++index, "--context-chars"));
                        if (contextChars < LongTaskContext.MIN_CHARS) {
                            throw new IllegalArgumentException("--context-chars must be at least " + LongTaskContext.MIN_CHARS);
                        }
                    }
                    case "--artifacts-dir" -> artifactsDirectory = Path.of(value(args, ++index, "--artifacts-dir"));
                    case "--checkpoint" -> checkpoint = Path.of(value(args, ++index, "--checkpoint"));
                    case "--resume" -> resume = Path.of(value(args, ++index, "--resume"));
                    case "--pause-after" -> {
                        pauseAfter = Integer.parseInt(value(args, ++index, "--pause-after"));
                        if (pauseAfter < 1) throw new IllegalArgumentException("--pause-after must be positive");
                    }
                    case "--model" -> model = value(args, ++index, "--model");
                    case "--question" -> question = value(args, ++index, "--question");
                    case "--max-turns" -> {
                        maxTurns = Integer.parseInt(value(args, ++index, "--max-turns"));
                        if (maxTurns < 1) throw new IllegalArgumentException("--max-turns must be positive");
                    }
                    case "--messages" -> messages = true;
                    case "--wire" -> wire = true;
                    case "--help" -> help = true;
                    case "--direct" -> direct = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            boolean research = repository != null || notes != null;
            boolean delegation = checkpoint != null || resume != null;
            if (provider == null) provider = research || contextChars > 0 || delegation ? "replay" : "scripted";
            if (!List.of("scripted", "replay", "deepseek", "lmstudio").contains(provider)) throw new IllegalArgumentException("--provider must be scripted, replay, deepseek or lmstudio");
            if (research && ("scripted".equals(provider) || file != null)) {
                throw new IllegalArgumentException("--repo and --notes use replay or deepseek and cannot be combined with --file");
            }
            if (repository != null && notes != null) throw new IllegalArgumentException("choose either --repo or --notes");
            if ((notes == null) != (output == null)) throw new IllegalArgumentException("--notes and --output must be used together");
            if (checkpoint != null && resume != null) throw new IllegalArgumentException("choose either --checkpoint or --resume");
            if (pauseAfter > 0 && !delegation) throw new IllegalArgumentException("--pause-after requires a checkpoint");
            if (resume != null && question != null) throw new IllegalArgumentException("--resume uses the saved question");
            if (delegation && "scripted".equals(provider)) {
                throw new IllegalArgumentException("checkpoint mode requires replay or deepseek");
            }
            if (artifactsDirectory != null && contextChars == 0) throw new IllegalArgumentException("--artifacts-dir requires --context-chars");
            if (contextChars > 0 && ("scripted".equals(provider) || (research && "replay".equals(provider)))) {
                throw new IllegalArgumentException("long-task replay uses the example file; --repo or --notes with a context budget requires --provider deepseek");
            }
            if (contextChars > 0 && artifactsDirectory == null) artifactsDirectory = Path.of("out/agent-runs");
            if (model == null) model = "lmstudio".equals(provider)
                    ? env.getOrDefault("LMSTUDIO_MODEL", "qwen3.8-9b-distill")
                    : env.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash");
            if (model.isBlank()) throw new IllegalArgumentException("--model must not be blank");
            if (file == null) file = contextChars == 0 ? "agent-loop.md" : "long-task.md";
            if (delegation && !direct && !research && contextChars == 0 && resume == null) {
                String task = question == null ? "总结资料中工具执行和结果回填的过程。" : question;
                question = "先调用 delegate_task，把读取 " + file + " 的任务交给 reader。收到结构化摘要后再回答。\n\n用户任务：\n" + task;
            } else if (question == null) {
                if (notes != null) question = "请整理这些 Markdown 资料的主要观点和未解决的问题，并标注来源。";
                else if (repository != null) question = "这个仓库的主要入口在哪里？请解释一条关键调用链。";
                else question = "请先调用 read_text 阅读 " + file + "，再用自己的话解释 Agent Loop 的步骤，并注明资料来源。";
            }
            if (maxTurns == 0) maxTurns = contextChars > 0 ? 12 : research ? 8 : 4;
            return new Options(provider, file, model, question, repository, notes, output, maxTurns,
                    contextChars, artifactsDirectory, checkpoint, resume, pauseAfter, messages, wire, help, direct);
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
        out.println("Usage: bash run.sh [--debug] [--provider scripted|replay|deepseek|lmstudio] [--model NAME]");
        out.println("       [--file RELATIVE_PATH | --repo DIRECTORY | --notes DIRECTORY --output NOTE.md]");
        out.println("       [--question TEXT] [--max-turns N] [--messages] [--wire] [--help]");
        out.println("       [--context-chars N [--artifacts-dir DIRECTORY]]");
        out.println("       [--checkpoint FILE.json | --resume FILE.json] [--pause-after N]");
        out.println("Default: scripted for examples, offline replay for --repo and --notes. DeepSeek must be selected explicitly.");
        out.println("Configuration: project .env; process environment overrides file values.");
        out.println("DeepSeek needs DEEPSEEK_API_KEY; optional DEEPSEEK_MODEL and DEEPSEEK_TIMEOUT_SECONDS.");
        out.println("LM Studio needs a loopback LMSTUDIO_BASE_URL (default http://127.0.0.1:1234/v1); optional");
        out.println("LMSTUDIO_API_KEY, LMSTUDIO_MODEL, LMSTUDIO_TIMEOUT_SECONDS and LMSTUDIO_MAX_TOKENS.");
    }
}
