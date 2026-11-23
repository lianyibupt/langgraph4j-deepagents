package personal.agent.web;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import personal.agent.AgentResult;
import personal.agent.EnvConfig;
import personal.agent.Main;
import personal.agent.Message;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
final class AgentRuns {
    private static final int HISTORY_LIMIT = 20;
    private static final int TRACE_LIMIT = 400;
    private static final List<Preset> PRESETS = List.of(
            new Preset("loop", "工具调用入门", "读取示例资料，观察工具结果如何进入下一轮模型请求。", "agent-loop.md", 4,
                    "请先调用 read_text 阅读 agent-loop.md，再用自己的话解释 Agent Loop，并注明资料来源。"),
            new Preset("source", "源码研究", "发现、搜索和按行阅读个人 Agent 的 Java 源码，校验报告引用。", "src/main/java/personal/agent/", 8,
                    "这个 Java Agent 的主要入口在哪里？请解释一条关键调用链，并引用源码行。"),
            new Preset("notes", "知识整理", "整理项目内的 Markdown 资料，校验引用后生成笔记。", "examples/knowledge/", 8,
                    "请整理这些 Markdown 资料的主要观点和未解决的问题，并标注来源。"),
            new Preset("long", "长任务与上下文", "观察计划更新、上下文压缩、结果归档和片段读取。", "long-task.md", 12,
                    "请阅读 long-task.md，解释长任务如何维护计划并引用已归档的内容。"),
            new Preset("delegate", "委派与恢复", "将读取任务交给子 Agent，可在工具批次结束后暂停并恢复。", "agent-loop.md", 6,
                    "总结资料中工具执行和结果回填的过程。"));

    record Preset(String id, String name, String description, String input, int maxTurns, String question) {}
    record Config(String model, boolean deepSeekConfigured, int historyLimit, List<Preset> presets) {}
    record Request(String preset, String provider, String question, Integer maxTurns, boolean pause) {}
    record Summary(UUID id, String preset, String provider, String status, Instant startedAt, Instant finishedAt,
                   long durationMillis, int modelTurns, int toolCalls, int events) {}
    record Event(String at, String text) {}
    record Detail(Summary run, String question, String detail, String answer, String note,
                  List<Event> events, int omittedEvents, List<Message> messages) {}

    private final Path project;
    private final Path workspace;
    private final Map<String, String> env;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<UUID, Run> runs = new LinkedHashMap<>();

    @Autowired
    AgentRuns() throws IOException {
        this(Path.of(System.getProperty("personal.agent.project", ".")),
                EnvConfig.load(Path.of(System.getProperty("personal.agent.project", ".")).resolve(".env"), System.getenv()));
    }

    AgentRuns(Path project, Map<String, String> env) {
        this.project = project.toAbsolutePath().normalize();
        this.workspace = this.project.resolve("examples");
        this.env = Map.copyOf(env);
    }

    Config config() {
        return new Config(env.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash"),
                !env.getOrDefault("DEEPSEEK_API_KEY", "").isBlank(), HISTORY_LIMIT, PRESETS);
    }

    synchronized List<Summary> list() {
        return new ArrayList<>(runs.values()).reversed().stream().map(Run::summary).toList();
    }

    synchronized Detail detail(UUID id) {
        return find(id).detail();
    }

    synchronized Detail start(Request request) {
        Preset preset = PRESETS.stream().filter(item -> item.id().equals(request.preset())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的任务场景。"));
        String provider = request.provider();
        if (!"replay".equals(provider) && !"deepseek".equals(provider)
                && !("scripted".equals(provider) && preset.id().equals("loop"))) {
            throw new IllegalArgumentException("当前场景不支持该模型模式。");
        }
        if ("deepseek".equals(provider) && !config().deepSeekConfigured()) {
            throw new IllegalArgumentException("请在 personal-agent/.env 填写 DEEPSEEK_API_KEY 后重启控制台。");
        }
        String question = request.question() == null ? "" : request.question().strip();
        if (question.isEmpty() || question.length() > 4000 || question.startsWith("--")) {
            throw new IllegalArgumentException("请填写 1 到 4000 字的任务内容，开头使用普通文本。");
        }
        int maxTurns = request.maxTurns() == null ? preset.maxTurns() : request.maxTurns();
        if (maxTurns < 1 || maxTurns > 20) throw new IllegalArgumentException("模型轮次上限须在 1 到 20 之间。");
        if (request.pause() && !preset.id().equals("delegate")) throw new IllegalArgumentException("委派场景支持暂停。");
        ensureIdle();
        var run = new Run(preset, provider, question, maxTurns);
        if (runs.size() == HISTORY_LIMIT) runs.remove(runs.keySet().iterator().next());
        runs.put(run.id, run);
        submit(run, false, request.pause());
        return run.detail();
    }

    synchronized Detail resume(UUID id) {
        Run run = find(id);
        if (!run.status.equals("PAUSED")) throw new IllegalStateException("当前任务没有可继续的暂停检查点。");
        ensureIdle();
        submit(run, true, false);
        return run.detail();
    }

    private void submit(Run run, boolean resume, boolean pause) {
        run.status = "RUNNING";
        run.finishedAt = null;
        run.segmentStarted = System.nanoTime();
        worker.execute(() -> execute(run, resume, pause));
    }

    private void execute(Run run, boolean resume, boolean pause) {
        Path output = project.resolve("out/web").resolve(run.id.toString());
        var args = new ArrayList<>(List.of("--provider", run.provider, "--max-turns", String.valueOf(run.maxTurns)));
        if (!resume) args.addAll(List.of("--question", run.question));
        switch (run.preset.id()) {
            case "source" -> args.addAll(List.of("--repo", project.resolve(run.preset.input()).toString()));
            case "notes" -> args.addAll(List.of("--notes", workspace.resolve("knowledge").toString(),
                    "--output", output.resolve("note.md").toString()));
            case "long" -> args.addAll(List.of("--context-chars", "2000", "--artifacts-dir", output.resolve("artifacts").toString()));
            case "delegate" -> {
                args.addAll(List.of(resume ? "--resume" : "--checkpoint", output.resolve("checkpoint.json").toString()));
                if (pause) args.addAll(List.of("--pause-after", "1"));
            }
            default -> { /* Default fixture. */ }
        }
        try {
            var result = Main.execute(args.toArray(String[]::new), workspace, env, event -> trace(run, event));
            synchronized (this) {
                run.result = result.result();
                run.modelTurns = run.result.modelTurns();
                run.status = result.publicationError() == null ? run.result.status().name() : "FAILED";
                run.detail = result.publicationError() == null ? run.result.detail() : result.publicationError();
                if (result.note() != null) run.note = project.relativize(output.resolve("note.md")).toString();
                run.finish();
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                run.status = "FAILED";
                run.detail = e instanceof IllegalArgumentException ? e.getMessage() : "Agent 无法启动或读写本地运行文件。";
                run.finish();
            }
        }
    }

    private synchronized void trace(Run run, String event) {
        if (event.startsWith("model.request ")) run.modelTurns++;
        if (event.startsWith("tool.start ")) run.toolCalls++;
        if (run.events.size() == TRACE_LIMIT) run.omittedEvents++;
        else run.events.add(new Event(Instant.now().toString(), event));
    }

    private void ensureIdle() {
        if (runs.values().stream().anyMatch(run -> run.status.equals("RUNNING"))) {
            throw new IllegalStateException("已有任务运行中，请等待当前任务结束。");
        }
    }

    private Run find(UUID id) {
        Run run = runs.get(id);
        if (run == null) throw new UnknownRun();
        return run;
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }

    static final class UnknownRun extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Run {
        private final UUID id = UUID.randomUUID();
        private final Preset preset;
        private final String provider;
        private final String question;
        private final int maxTurns;
        private final Instant startedAt = Instant.now();
        private final List<Event> events = new ArrayList<>();
        private Instant finishedAt;
        private long segmentStarted;
        private long durationNanos;
        private String status = "RUNNING";
        private String detail;
        private String note;
        private int modelTurns;
        private int toolCalls;
        private int omittedEvents;
        private AgentResult result;

        private Run(Preset preset, String provider, String question, int maxTurns) {
            this.preset = preset;
            this.provider = provider;
            this.question = question;
            this.maxTurns = maxTurns;
        }

        private Summary summary() {
            return new Summary(id, preset.id(), provider, status, startedAt, finishedAt,
                    TimeUnit.NANOSECONDS.toMillis(durationNanos), modelTurns, toolCalls, events.size() + omittedEvents);
        }

        private void finish() {
            durationNanos += System.nanoTime() - segmentStarted;
            finishedAt = Instant.now();
        }

        private Detail detail() {
            String answer = status.equals("COMPLETED") ? result.finalAnswer().orElse(null) : null;
            List<Message> messages = result == null ? List.of() : result.messages();
            return new Detail(summary(), question, detail, answer, note, List.copyOf(events), omittedEvents, messages);
        }
    }
}
