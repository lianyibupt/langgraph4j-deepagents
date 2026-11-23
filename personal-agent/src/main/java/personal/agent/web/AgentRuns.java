package personal.agent.web;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import personal.agent.AgentResult;
import personal.agent.AgentControl;
import personal.agent.ToolCall;
import personal.agent.EnvConfig;
import personal.agent.Main;
import personal.agent.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            new Preset("knowledge", "个人知识研究", "仅研究你从最近一年知识库中明确选中的资料，保留原始路径与行号。", "个人知识库中选中的资料快照", 12,
                    "请根据选中的近期资料整理我的 AI 协作产物、主题、未解决问题与下一步建议，并标注来源。"),
            new Preset("long", "长任务与上下文", "观察计划更新、上下文压缩、结果归档和片段读取。", "long-task.md", 12,
                    "请阅读 long-task.md，解释长任务如何维护计划并引用已归档的内容。"),
            new Preset("delegate", "委派与恢复", "将读取任务交给子 Agent，可在工具批次结束后暂停并恢复。", "agent-loop.md", 6,
                    "总结资料中工具执行和结果回填的过程。"));

    record Preset(String id, String name, String description, String input, int maxTurns, String question) {}
    record Config(String model, boolean deepSeekConfigured, int historyLimit, List<Preset> presets, String projectPath, int concurrency) {}
    record Request(String preset, String provider, String question, Integer maxTurns, boolean pause, String inputPath) {
        Request(String preset, String provider, String question, Integer maxTurns, boolean pause) {
            this(preset, provider, question, maxTurns, pause, null);
        }
    }
    record Summary(UUID id, String preset, String provider, String status, Instant startedAt, Instant finishedAt,
                   long durationMillis, int modelTurns, int toolCalls, int events, String phase, String originId) {}
    record Event(String at, String text) {}
    record Detail(Summary run, String question, String detail, String answer, String note,
                  List<Event> events, int omittedEvents, List<Message> messages, List<Message> modelInput,
                  String phase, ToolCall activeTool, List<InboxEntry> inbox, String waitingQuestion,
                  String inputPath, String background) {}
    record Saved(Detail detail, int maxTurns, boolean stopRequested, boolean pauseRequested) {}
    record InboxEntry(String id, String text, Instant receivedAt, Instant appliedAt) {}
    record Recovery(String phase, String inputVersion, String detail, List<Map<String, String>> plan) {}

    private final Path project;
    private final Path workspace;
    private final Map<String, String> env;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    private final RunHistory history;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    AgentRuns() throws IOException {
        this(Path.of(System.getProperty("personal.agent.project", ".")),
                EnvConfig.load(Path.of(System.getProperty("personal.agent.project", ".")).resolve(".env"), System.getenv()));
    }

    AgentRuns(Path project, Map<String, String> env) throws IOException {
        this.project = project.toAbsolutePath().normalize();
        this.workspace = this.project.resolve("examples");
        this.env = Map.copyOf(env);
        history = new RunHistory(this.project.resolve("out/web"));
        try {
            for (Saved saved : history.load()) {
                var run = new Run(saved);
                if (runs.put(run.id, run) != null || runs.size() > HISTORY_LIMIT) throw new IOException("WEB_HISTORY_INVALID");
                if (run.status.equals("RUNNING") || run.status.equals("QUEUED")) {
                    Recovery recovery = recovery(run.id);
                    run.status = List.of("READY", "COMPLETED").contains(recovery.phase()) ? "PAUSED" : "RECOVERY_REQUIRED";
                    run.detail = "服务在任务结束前退出。" + recovery.detail();
                }
            }
        } catch (IOException | RuntimeException e) {
            history.close();
            throw e;
        }
    }

    Config config() {
        return new Config(env.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash"),
                !env.getOrDefault("DEEPSEEK_API_KEY", "").isBlank(), HISTORY_LIMIT, PRESETS, project.toString(), 2);
    }

    synchronized List<Summary> list() {
        return new ArrayList<>(runs.values()).reversed().stream().map(Run::summary).toList();
    }

    synchronized Detail detail(UUID id) {
        return find(id).detail();
    }

    synchronized Recovery recovery(UUID id) {
        Run run = find(id);
        Path checkpoint = output(run).resolve("checkpoint.json");
        if (!Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
            return new Recovery("NONE", null, "尚无可恢复的检查点。", List.of());
        }
        try (var input = Files.newInputStream(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("checkpoint too large");
            var state = json.readTree(bytes);
            String phase = state.path("phase").asText();
            var plan = new ArrayList<Map<String, String>>();
            var successful = new java.util.HashSet<String>();
            for (var message : state.path("messages")) {
                if (message.path("role").asText().equals("TOOL") && !message.path("toolError").asBoolean()) {
                    successful.add(message.path("toolCallId").asText());
                }
            }
            for (var message : state.path("messages")) {
                if (!message.path("role").asText().equals("ASSISTANT")) continue;
                for (var call : message.path("toolCalls")) {
                    if (!call.path("name").asText().equals("update_plan") || !successful.contains(call.path("id").asText())) continue;
                    plan.clear();
                    for (var step : json.readTree(call.path("arguments").path("steps").asText("[]"))) {
                        plan.add(Map.of("id", step.path("id").asText(), "task", step.path("task").asText(),
                                "status", step.path("status").asText()));
                    }
                }
            }
            String description = switch (phase) {
                case "READY" -> "完整工具批次已保存，可从下一轮继续。";
                case "COMPLETED" -> "最终回答已保存，可核对并完成结果发布。";
                case "TOOLS_PENDING" -> "工具结果尚未完整落盘，需要人工核对，自动恢复已停止。";
                default -> "检查点格式无法识别。";
            };
            return new Recovery(phase, state.path("binding").path("configuration").path("inputVersion").asText(null),
                    description, plan);
        } catch (IOException | RuntimeException e) {
            return new Recovery("INVALID", null, "检查点读取失败，需要核对本地文件。", List.of());
        }
    }

    synchronized Detail start(Request request) {
        return startManaged(UUID.randomUUID(), request, "", null);
    }

    Path project() { return project; }
    String setting(String name) { return env.getOrDefault(name, ""); }

    synchronized boolean hasCapacity() {
        return !worker.isShutdown() && runs.values().stream().filter(run -> run.status.equals("RUNNING")).count() < 2;
    }

    synchronized Detail existing(UUID id) { return runs.containsKey(id) ? find(id).detail() : null; }

    synchronized Detail startManaged(UUID id, Request request, String background, String originId) {
        if (runs.containsKey(id)) return find(id).detail();
        if (Files.exists(project.resolve("out/web").resolve(id.toString()).resolve("checkpoint.json"))) {
            throw new IllegalStateException("该事件已有检查点，运行记录已淘汰，需要人工核对。");
        }
        Preset preset = PRESETS.stream().filter(item -> item.id().equals(request.preset())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请选择有效的任务场景。"));
        if (preset.id().equals("knowledge") && (originId == null || !originId.startsWith("knowledge:"))) {
            throw new IllegalArgumentException("请先到个人知识库检索、预览并选择本次资料。");
        }
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
        if (request.pause() && preset.id().equals("loop")) throw new IllegalArgumentException("请选择研究、长任务或委派场景进行恢复实验。");
        ensureIdle();
        var previousRuns = new LinkedHashMap<>(runs);
        String inputPath = request.inputPath();
        if (inputPath != null && !inputPath.isBlank()) {
            if (!List.of("source", "notes", "knowledge").contains(preset.id())) throw new IllegalArgumentException("当前场景使用固定示例目录。");
            try { inputPath = Path.of(inputPath).toRealPath().toString(); }
            catch (IOException e) { throw new IllegalArgumentException("输入目录不可访问。"); }
        } else {
            Path base = List.of("source", "notes").contains(preset.id()) ? project : workspace;
            inputPath = base.resolve(preset.input()).normalize().toString();
        }
        var run = new Run(id, preset, provider, question, maxTurns, inputPath, background, originId);
        if (runs.size() == HISTORY_LIMIT) {
            UUID oldest = runs.values().stream().filter(item -> !List.of("RUNNING", "QUEUED", "WAITING_INPUT", "PAUSED", "RECOVERY_REQUIRED").contains(item.status))
                    .map(item -> item.id).findFirst().orElseThrow(() -> new IllegalStateException("请先完成已有暂停任务，再创建新任务。"));
            runs.remove(oldest);
        }
        runs.put(run.id, run);
        if (preset.id().equals("knowledge")) {
            trace(run, "knowledge.search " + run.background.lines().filter(line -> line.startsWith("检索问题 ")).findFirst().orElse("已检索最近一年资料"));
            trace(run, "knowledge.selected " + run.background);
            trace(run, "knowledge.snapshot path=" + inputPath + " index=" + originId.substring("knowledge:".length()));
        }
        try { submit(run, false, request.pause()); }
        catch (IllegalStateException e) {
            runs.clear();
            runs.putAll(previousRuns);
            throw e;
        }
        return run.detail();
    }

    synchronized Detail resume(UUID id) {
        Run run = find(id);
        boolean retryable = run.status.equals("FAILED") && List.of("READY", "COMPLETED").contains(recovery(id).phase());
        if (!List.of("PAUSED", "QUEUED").contains(run.status) && !retryable) throw new IllegalStateException("当前任务没有可继续的暂停检查点。");
        ensureIdle();
        submit(run, true, false);
        return run.detail();
    }

    synchronized Detail input(UUID id, String text) {
        Run run = find(id);
        if (!run.acceptingInputs || run.stopRequested) throw new IllegalStateException("本轮已进入结果发布或停止阶段，请在结束后发起新的研究。");
        if (!List.of("RUNNING", "PAUSED", "WAITING_INPUT", "QUEUED").contains(run.status)) throw new IllegalStateException("该任务当前不接收补充要求。");
        if (text == null || text.isBlank() || text.length() > 4000 || run.inbox.size() >= 50) throw new IllegalArgumentException("补充内容须为 1 到 4000 字，每个任务最多 50 条。");
        run.inbox.add(new InboxEntry(UUID.randomUUID().toString(), text.strip(), Instant.now(), null));
        trace(run, "inbox.received input=" + run.inbox.getLast().id());
        run.waitingQuestion = null;
        if (run.status.equals("WAITING_INPUT")) {
            run.status = "QUEUED";
            run.phase = "QUEUED";
        }
        saveControl();
        return run.detail();
    }

    synchronized Detail action(UUID id, String action) {
        Run run = find(id);
        if (!List.of("RUNNING", "PAUSED", "WAITING_INPUT", "QUEUED").contains(run.status)) throw new IllegalStateException("任务已停止。");
        if (action.equals("stop")) {
            run.stopRequested = true;
            run.phase = "STOP_REQUESTED";
            if (!run.status.equals("RUNNING")) { run.status = "CANCELLED"; run.phase = "CANCELLED"; run.finishedAt = Instant.now(); }
        } else if (action.equals("pause") && run.status.equals("RUNNING")) {
            run.pauseRequested = true;
            run.phase = "PAUSE_REQUESTED";
        } else throw new IllegalArgumentException("当前状态不支持该操作。");
        trace(run, "control." + action + " requested=true");
        saveControl();
        return run.detail();
    }

    synchronized void dispatchReady() {
        for (Run run : runs.values()) {
            if (!hasCapacity()) return;
            if (run.status.equals("QUEUED")) submit(run, true, false);
        }
    }

    private void saveControl() {
        try { persist(); }
        catch (IOException e) { throw new IllegalStateException("运行状态保存失败，请检查磁盘。", e); }
    }

    private AgentControl control(Run run) {
        return new AgentControl() {
            @Override public List<Input> pending(List<Message> history) {
                synchronized (AgentRuns.this) {
                    var pending = new ArrayList<Input>();
                    for (int i = 0; i < run.inbox.size(); i++) {
                        var entry = run.inbox.get(i);
                        if (entry.appliedAt() != null) continue;
                        var input = new Input(entry.id(), entry.text());
                        boolean present = history.stream().anyMatch(message -> message.role() == Message.Role.USER && message.text().equals(input.message()));
                        if (present) run.inbox.set(i, new InboxEntry(entry.id(), entry.text(), entry.receivedAt(), Instant.now()));
                        else pending.add(input);
                    }
                    return List.copyOf(pending);
                }
            }
            @Override public void applied(List<Input> inputs) {
                synchronized (AgentRuns.this) {
                    for (int i = 0; i < run.inbox.size(); i++) {
                        var entry = run.inbox.get(i);
                        if (inputs.stream().anyMatch(input -> input.id().equals(entry.id()))) {
                            run.inbox.set(i, new InboxEntry(entry.id(), entry.text(), entry.receivedAt(), Instant.now()));
                        }
                    }
                    saveControl();
                }
            }
            @Override public boolean finishAllowed(List<Message> messages) {
                synchronized (AgentRuns.this) {
                    if (!pending(messages).isEmpty()) return false;
                    run.acceptingInputs = false;
                    return true;
                }
            }
            @Override public boolean stopped() { synchronized (AgentRuns.this) { return run.stopRequested; } }
            @Override public boolean paused() { synchronized (AgentRuns.this) { return run.pauseRequested; } }
            @Override public boolean waiting() { synchronized (AgentRuns.this) { return run.waitingQuestion != null; } }
            @Override public void ask(String question) {
                synchronized (AgentRuns.this) {
                    run.waitingQuestion = question;
                    trace(run, "inbox.question awaiting_user=true");
                    saveControl();
                }
            }
            @Override public void observe(Snapshot snapshot) {
                synchronized (AgentRuns.this) {
                    run.phase = snapshot.phase();
                    run.liveMessages = snapshot.messages();
                    run.activeTool = snapshot.activeTool();
                    if (snapshot.modelInput() != null) run.modelInput = snapshot.modelInput();
                    saveControl();
                }
            }
        };
    }

    private void submit(Run run, boolean resume, boolean pause) {
        String previous = run.status;
        Instant previousFinished = run.finishedAt;
        run.status = "RUNNING";
        run.phase = "STARTING";
        run.acceptingInputs = true;
        run.pauseRequested = false;
        run.finishedAt = null;
        run.segmentStarted = System.nanoTime();
        try { persist(); }
        catch (IOException e) {
            run.status = previous;
            run.finishedAt = previousFinished;
            throw new IllegalStateException("运行记录无法保存，请检查 out/web 的磁盘和权限。");
        }
        worker.execute(() -> execute(run, resume, pause));
    }

    private Path output(Run run) { return project.resolve("out/web").resolve(run.id.toString()); }

    private void persist() throws IOException {
        history.save(runs.values().stream().map(run -> new Saved(run.detail(), run.maxTurns, run.stopRequested, run.pauseRequested)).toList());
    }

    private void execute(Run run, boolean resume, boolean pause) {
        Path output = output(run);
        var args = new ArrayList<>(List.of("--provider", run.provider, "--max-turns", String.valueOf(run.maxTurns)));
        if (!resume) args.addAll(List.of("--question", run.question + (run.background.isBlank() ? "" : "\n\n[本次任务补充上下文]\n" + run.background)));
        switch (run.preset.id()) {
            case "source" -> args.addAll(List.of("--repo", run.inputPath == null ? project.resolve(run.preset.input()).toString() : run.inputPath));
            case "notes", "knowledge" -> args.addAll(List.of("--notes", run.inputPath == null ? workspace.resolve("knowledge").toString() : run.inputPath,
                    "--output", output.resolve("note.md").toString()));
            case "long" -> args.addAll(List.of("--context-chars", "2000", "--artifacts-dir", output.resolve("artifacts").toString()));
            default -> { /* Default fixture. */ }
        }
        if (run.preset.id().equals("loop")) args.add("--direct");
        if (!run.provider.equals("scripted")) {
            args.addAll(List.of(resume ? "--resume" : "--checkpoint", output.resolve("checkpoint.json").toString()));
            if (pause) args.addAll(List.of("--pause-after", switch (run.preset.id()) {
                case "source", "notes", "knowledge" -> "3";
                case "long" -> "2";
                default -> "1";
            }));
        }
        try {
            var result = Main.execute(args.toArray(String[]::new), workspace, env, event -> trace(run, event), control(run));
            synchronized (this) {
                run.result = result.result();
                run.liveMessages = run.result.messages();
                run.modelTurns = run.result.modelTurns();
                run.status = result.publicationError() == null ? run.result.status().name() : "FAILED";
                if (result.publicationError() != null) run.phase = "PUBLICATION_FAILED";
                run.detail = result.publicationError() == null ? run.result.detail() : result.publicationError();
                if (result.note() != null) run.note = project.relativize(output.resolve("note.md")).toString();
                run.finish();
                try { persist(); }
                catch (IOException e) { run.detail += " 运行记录保存失败，请核对磁盘。"; }
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                run.status = "FAILED";
                run.phase = "FAILED";
                String reason = e.getMessage();
                run.detail = e instanceof IllegalArgumentException || (reason != null && reason.startsWith("CHECKPOINT_"))
                        ? reason : "Agent 无法启动或读写本地运行文件。";
                run.finish();
                try { persist(); }
                catch (IOException failure) { run.detail += " 运行记录保存失败，请核对磁盘。"; }
            }
        }
    }

    private synchronized void trace(Run run, String event) {
        if (event.startsWith("model.request ")) run.modelTurns++;
        if (event.startsWith("tool.start ")) run.toolCalls++;
        if (run.events.size() == TRACE_LIMIT) { run.events.removeFirst(); run.omittedEvents++; }
        run.events.add(new Event(Instant.now().toString(), event));
    }

    private void ensureIdle() {
        if (worker.isShutdown()) throw new IllegalStateException("服务正在停止。");
        if (!hasCapacity()) {
            throw new IllegalStateException("两个执行槽位均在运行，请稍候。");
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
        try {
            if (worker.awaitTermination(10, TimeUnit.SECONDS)) history.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IllegalStateException("Web 状态锁关闭失败。", e);
        }
    }

    static final class UnknownRun extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Run {
        private final UUID id;
        private final Preset preset;
        private final String provider;
        private final String question;
        private final int maxTurns;
        private final Instant startedAt;
        private final String inputPath;
        private final String background;
        private final String originId;
        private String phase = "STARTING";
        private ToolCall activeTool;
        private List<Message> liveMessages = List.of();
        private List<Message> modelInput = List.of();
        private final List<InboxEntry> inbox = new ArrayList<>();
        private String waitingQuestion;
        private boolean stopRequested;
        private boolean pauseRequested;
        private boolean acceptingInputs = true;
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

        private Run(UUID id, Preset preset, String provider, String question, int maxTurns,
                    String inputPath, String background, String originId) {
            this.id = id;
            this.inputPath = inputPath;
            this.background = background == null ? "" : background;
            this.originId = originId;
            this.startedAt = Instant.now();
            this.preset = preset;
            this.provider = provider;
            this.question = question;
            this.maxTurns = maxTurns;
        }

        private Run(Saved saved) {
            Detail detail = saved.detail();
            Summary summary = detail.run();
            id = summary.id();
            inputPath = detail.inputPath() == null ? null : detail.inputPath();
            background = detail.background() == null ? "" : detail.background();
            originId = summary.originId();
            phase = detail.phase() == null ? summary.status() : detail.phase();
            activeTool = detail.activeTool();
            liveMessages = detail.messages();
            modelInput = detail.modelInput() == null ? List.of() : detail.modelInput();
            if (detail.inbox() != null) inbox.addAll(detail.inbox());
            waitingQuestion = detail.waitingQuestion();
            stopRequested = saved.stopRequested();
            pauseRequested = saved.pauseRequested();
            preset = PRESETS.stream().filter(item -> item.id().equals(summary.preset())).findFirst().orElseThrow();
            provider = summary.provider();
            question = detail.question();
            maxTurns = saved.maxTurns();
            startedAt = summary.startedAt();
            finishedAt = summary.finishedAt();
            durationNanos = TimeUnit.MILLISECONDS.toNanos(summary.durationMillis());
            status = summary.status();
            this.detail = detail.detail();
            note = detail.note();
            modelTurns = summary.modelTurns();
            toolCalls = summary.toolCalls();
            events.addAll(detail.events());
            omittedEvents = detail.omittedEvents();
            result = detail.messages().isEmpty() ? null : new AgentResult(
                    status.equals("COMPLETED") ? AgentResult.Status.COMPLETED : AgentResult.Status.PAUSED,
                    modelTurns, detail.messages(), this.detail == null ? "Restored Web history" : this.detail);
        }

        private Summary summary() {
            return new Summary(id, preset.id(), provider, status, startedAt, finishedAt,
                    TimeUnit.NANOSECONDS.toMillis(durationNanos), modelTurns, toolCalls, events.size() + omittedEvents, phase, originId);
        }

        private void finish() {
            durationNanos += System.nanoTime() - segmentStarted;
            finishedAt = Instant.now();
        }

        private Detail detail() {
            String answer = status.equals("COMPLETED") && result != null ? result.finalAnswer().orElse(null) : null;
            List<Message> messages = liveMessages.isEmpty() && result != null ? result.messages() : liveMessages;
            return new Detail(summary(), question, detail, answer, note, List.copyOf(events), omittedEvents, messages, modelInput, phase, activeTool,
                    List.copyOf(inbox), waitingQuestion, inputPath, background);
        }
    }
}
