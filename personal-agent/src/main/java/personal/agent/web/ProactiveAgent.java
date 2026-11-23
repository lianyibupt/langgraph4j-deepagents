package personal.agent.web;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import personal.agent.research.SourceResearch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Durable event intake, bounded scheduling, user policy and local delivery. */
@Service
final class ProactiveAgent {
    record WatchRequest(String title, String inputPath, String preset, String provider, String goal,
                        String keywords, String mode, int intervalSeconds, int dailyLimit) {}
    record GoalRequest(String title, String content, String keywords, String inputPath, String preset,
                       String provider, String mode) {}

    public static final class State {
        public int version = 1;
        public boolean deliveryEnabled = true;
        public List<Watch> watches = new ArrayList<>();
        public List<Trigger> triggers = new ArrayList<>();
        public List<Notice> notices = new ArrayList<>();
        public List<Goal> goals = new ArrayList<>();
        public List<Suggestion> suggestions = new ArrayList<>();
        public String health;
    }
    public static final class Watch {
        public String id, title, inputPath, preset, provider, goal, keywords, mode, status, version, lastError, goalId, quotaDay;
        public int intervalSeconds, dailyLimit, dispatchedToday;
        public long sequence, nextCheckAt, lastCheckedAt;
        public Map<String, String> files = Map.of();
    }
    public static final class Trigger {
        public String id, watchId, title, at, dispatchedAt, version, snapshot, status, decision, runId;
        public List<String> changed = List.of();
        public boolean approved;
    }
    public static final class Notice {
        public String id, runId, watchId, kind, title, body, status = "PENDING", createdAt, sentAt, readAt, error;
        public int attempts;
        public long nextAttemptAt;
    }
    public static final class Goal {
        public String id, title, content, keywords, inputPath, preset, provider, mode;
        public boolean enabled = true;
    }
    public static final class Suggestion {
        public String id, goalId, runId, reason, status = "PROPOSED", watchId;
    }

    private final AgentRuns runs;
    private final ManagementStore store;
    private final Path project;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private State state;

    ProactiveAgent(AgentRuns runs) throws IOException {
        this.runs = runs;
        project = runs.project();
        store = new ManagementStore(project);
        state = store.load();
    }

    @PostConstruct void start() { clock.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS); }
    @PreDestroy void close() {
        clock.shutdownNow();
        try { clock.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    synchronized State view() { return store.copy(state); }

    private <T> T change(Supplier<T> action) {
        State previous = store.copy(state);
        try {
            T result = action.get();
            store.save(state);
            return result;
        } catch (IOException | RuntimeException e) {
            state = previous;
            if (e instanceof IllegalArgumentException argument) throw argument;
            if (e instanceof IllegalStateException conflict) throw conflict;
            throw new IllegalStateException("管理状态无法保存，请检查目录权限和磁盘。", e);
        }
    }

    synchronized Watch createWatch(WatchRequest request) {
        return change(() -> addWatch(request, null));
    }

    private Watch addWatch(WatchRequest request, String goalId) {
        if (state.watches.size() >= 20) throw new IllegalArgumentException("最多保存 20 个关注项。");
        require(request.title(), 100); require(request.goal(), 3000);
        validateMode(request.mode()); validateProvider(request.provider());
        if (request.intervalSeconds() < 3 || request.intervalSeconds() > 86400 || request.dailyLimit() < 1 || request.dailyLimit() > 20) {
            throw new IllegalArgumentException("检测间隔须为 3 到 86400 秒，每日研究上限为 1 到 20 次。");
        }
        try {
            var research = research(request.inputPath(), request.preset());
            var inventory = research.inventory();
            var watch = new Watch();
            watch.id = UUID.randomUUID().toString(); watch.title = request.title().strip();
            watch.inputPath = Path.of(request.inputPath()).toRealPath().toString();
            watch.preset = request.preset(); watch.provider = request.provider(); watch.goal = request.goal();
            watch.keywords = keywords(request.keywords()); watch.mode = request.mode(); watch.status = "ACTIVE";
            watch.intervalSeconds = request.intervalSeconds(); watch.dailyLimit = request.dailyLimit();
            watch.version = inventory.version(); watch.files = inventory.files(); watch.goalId = goalId;
            watch.lastCheckedAt = System.currentTimeMillis(); watch.nextCheckAt = watch.lastCheckedAt + watch.intervalSeconds * 1000L;
            state.watches.add(watch);
            return watch;
        } catch (IOException e) { throw new IllegalArgumentException("无法读取关注目录或资料超出扫描预算。" + e.getMessage()); }
    }

    synchronized void watchAction(String id, String action) {
        change(() -> {
            Watch watch = watch(id);
            switch (action) {
                case "scan" -> { if (!watch.status.equals("ACTIVE")) throw new IllegalStateException("请先启用该关注项。"); watch.nextCheckAt = 0; }
                case "pause" -> watch.status = "PAUSED";
                case "enable" -> { watch.status = "ACTIVE"; watch.nextCheckAt = 0; }
                case "stop" -> {
                    watch.status = "STOPPED";
                    for (Trigger trigger : state.triggers) if (trigger.watchId.equals(id) && !terminal(trigger.status)) {
                        if (trigger.runId != null) {
                            var run = runs.existing(UUID.fromString(trigger.runId));
                            if (run != null && List.of("RUNNING", "WAITING_INPUT", "PAUSED", "QUEUED").contains(run.run().status())) runs.action(run.run().id(), "stop");
                        }
                        trigger.status = "CANCELLED";
                        trigger.decision = "用户停止了持续委托。";
                    }
                    for (Notice notice : state.notices) if (id.equals(notice.watchId) && !notice.status.equals("SENT")) notice.status = "WITHDRAWN";
                }
                default -> throw new IllegalArgumentException("未知关注操作。");
            }
            return null;
        });
    }

    synchronized void approve(String id) {
        change(() -> {
            Trigger trigger = trigger(id);
            if (!trigger.status.equals("OBSERVED")) throw new IllegalStateException("该事件已经处理。");
            if (!watch(trigger.watchId).status.equals("ACTIVE")) throw new IllegalStateException("关注项已暂停或停止。");
            trigger.approved = true; trigger.status = "QUEUED"; trigger.decision = "用户批准了本次只读研究。";
            return null;
        });
    }

    synchronized void delivery(boolean enabled) { change(() -> { state.deliveryEnabled = enabled; return null; }); }
    synchronized void noticeAction(String id, String action) {
        change(() -> {
            Notice notice = state.notices.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow();
            if (action.equals("read")) notice.readAt = Instant.now().toString();
            else if (action.equals("retry") && !List.of("SENT", "WITHDRAWN").contains(notice.status)) {
                notice.status = "PENDING"; notice.nextAttemptAt = 0; notice.attempts = 0;
            } else throw new IllegalArgumentException("该通知无法执行此操作。");
            return null;
        });
    }

    synchronized Goal createGoal(GoalRequest request) {
        return change(() -> {
            if (state.goals.size() >= 20) throw new IllegalArgumentException("最多保存 20 项长期目标。");
            require(request.title(), 100); require(request.content(), 2000);
            validateMode(request.mode()); validateProvider(request.provider());
            try { research(request.inputPath(), request.preset()).inventory(); }
            catch (IOException e) { throw new IllegalArgumentException("目标目录不可读取。"); }
            var goal = new Goal(); goal.id = UUID.randomUUID().toString(); goal.title = request.title();
            goal.content = request.content(); goal.keywords = keywords(request.keywords());
            try { goal.inputPath = Path.of(request.inputPath()).toRealPath().toString(); }
            catch (IOException e) { throw new IllegalArgumentException("目标目录不可读取。"); }
            goal.preset = request.preset(); goal.provider = request.provider(); goal.mode = request.mode();
            state.goals.add(goal);
            return goal;
        });
    }

    synchronized void goalEnabled(String id, boolean enabled) {
        change(() -> {
            Goal goal = goal(id); goal.enabled = enabled;
            if (!enabled) for (Watch watch : state.watches) if (id.equals(watch.goalId) && watch.status.equals("ACTIVE")) watch.status = "PAUSED";
            return null;
        });
    }

    synchronized AgentRuns.Detail startManual(AgentRuns.Request request) {
        String scope = scope(request);
        var matched = state.goals.stream().filter(goal -> goal.enabled && goal.preset.equals(request.preset())
                && goal.inputPath.equals(scope) && matches(request.question(), goal.keywords)).toList();
        StringBuilder background = new StringBuilder();
        for (Goal goal : matched) {
            if (background.length() + goal.content.length() > 4000) break;
            background.append("目标 ").append(goal.id).append(" · ").append(goal.title).append('\n').append(goal.content).append('\n');
        }
        AgentRuns.Detail detail = runs.startManaged(UUID.randomUUID(), request, background.toString(), null);
        try {
        if (!matched.isEmpty()) change(() -> {
            for (Goal goal : matched) {
                boolean followed = state.watches.stream().anyMatch(watch -> goal.id.equals(watch.goalId) && !watch.status.equals("STOPPED"));
                boolean proposed = state.suggestions.stream().anyMatch(item -> item.goalId.equals(goal.id) && item.status.equals("PROPOSED"));
                if (followed || proposed || state.suggestions.size() >= 100) continue;
                var suggestion = new Suggestion(); suggestion.id = UUID.randomUUID().toString(); suggestion.goalId = goal.id;
                suggestion.runId = detail.run().id().toString();
                suggestion.reason = "本次研究的目录及关键词命中长期目标“" + goal.title + "”。可持续关注该目录的后续变化。";
                state.suggestions.add(suggestion);
                if (goal.mode.equals("auto")) acceptSuggestion(suggestion);
            }
            return null;
        });
        } catch (RuntimeException e) { state.health = "任务已启动，长期目标建议保存失败：" + e.getMessage(); }
        return detail;
    }

    synchronized void suggestionAction(String id, boolean accept) {
        change(() -> {
            var suggestion = state.suggestions.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow();
            if (!suggestion.status.equals("PROPOSED")) throw new IllegalStateException("建议已处理。");
            if (accept) acceptSuggestion(suggestion); else suggestion.status = "DISMISSED";
            return null;
        });
    }

    private void acceptSuggestion(Suggestion suggestion) {
        Goal goal = goal(suggestion.goalId);
        if (!goal.enabled) throw new IllegalStateException("长期目标已停用。");
        Watch watch = addWatch(new WatchRequest(goal.title, goal.inputPath, goal.preset, goal.provider, goal.content,
                goal.keywords, "auto", 30, 5), goal.id);
        suggestion.watchId = watch.id; suggestion.status = "ACCEPTED";
    }

    synchronized void tick() {
        State previous = store.copy(state);
        try {
            runs.dispatchReady();
            long now = System.currentTimeMillis();
            boolean dirty = false;
            for (Watch watch : state.watches) if (watch.status.equals("ACTIVE") && now >= watch.nextCheckAt) {
                scan(watch, now); dirty = true;
            }
            // Intake and execution intent are durable before a worker can start.
            if (dirty) store.save(state);
            for (Trigger trigger : state.triggers) {
                Watch watch = watch(trigger.watchId);
                if (List.of("QUEUED", "DISPATCHING", "WAITING_BUDGET").contains(trigger.status) && watch.status.equals("ACTIVE")) {
                    if (!runs.hasCapacity() && trigger.runId == null) continue;
                    if (trigger.runId == null && dailyRuns(watch) >= watch.dailyLimit) {
                        if (!trigger.status.equals("WAITING_BUDGET")) { trigger.status = "WAITING_BUDGET"; trigger.decision = "已达到今日研究上限，等待次日额度。"; dirty = true; }
                        continue;
                    }
                    boolean newAssignment = trigger.runId == null;
                    if (newAssignment) watch.dispatchedToday++;
                    trigger.runId = trigger.id; trigger.status = "DISPATCHING";
                    if (trigger.dispatchedAt == null) trigger.dispatchedAt = Instant.now().toString();
                    store.save(state);
                    try {
                        runs.startManaged(UUID.fromString(trigger.runId), new AgentRuns.Request(watch.preset, watch.provider,
                                watch.goal + "\n\n本次变化文件：" + String.join("、", trigger.changed), 12, false, trigger.snapshot), "", watch.id);
                        trigger.status = "RUNNING"; trigger.decision = "规则准入通过，已派发只读研究，使用固定资料快照。";
                    } catch (IllegalStateException e) {
                        if (!runs.hasCapacity()) {
                            if (newAssignment) {
                                trigger.status = "QUEUED"; trigger.runId = null; trigger.dispatchedAt = null;
                                watch.dispatchedToday--;
                            }
                        }
                        else { trigger.status = "FAILED"; trigger.decision = e.getMessage(); }
                    } catch (IllegalArgumentException e) { trigger.status = "FAILED"; trigger.decision = e.getMessage(); }
                    dirty = true;
                }
                if (trigger.runId != null && !trigger.status.equals("CANCELLED")) {
                    AgentRuns.Detail run = runs.existing(UUID.fromString(trigger.runId));
                    if (run != null && !trigger.status.equals(run.run().status())) { trigger.status = run.run().status(); dirty = true; }
                }
            }
            for (var summary : runs.list()) {
                if (summary.status().equals("WAITING_INPUT")) {
                    var run = runs.detail(summary.id());
                    if (run.waitingQuestion() != null) dirty |= enqueue(summary.id().toString(), summary.originId(), "question:" + summary.modelTurns() + ":" + run.waitingQuestion(),
                            "Agent 需要你的回答", run.waitingQuestion());
                } else if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(summary.status())) {
                    var run = runs.detail(summary.id());
                    dirty |= enqueue(summary.id().toString(), summary.originId(), summary.status(),
                            summary.status().equals("COMPLETED") ? "研究结果已生成" : "任务需要关注", run.answer() == null ? run.detail() : run.answer());
                }
            }
            if (dirty) store.save(state);
            for (Notice notice : state.notices) {
                if (!notice.kind.startsWith("question:") || !notice.status.equals("PENDING")) continue;
                var current = runs.existing(UUID.fromString(notice.runId));
                if (current != null && !current.run().status().equals("WAITING_INPUT")) { notice.status = "WITHDRAWN"; dirty = true; }
            }
            if (state.deliveryEnabled) for (Notice notice : state.notices) {
                if (notice.status.equals("PENDING") && notice.nextAttemptAt <= now) { deliver(notice); dirty = true; }
            }
            if (dirty) store.save(state);
            state.health = null;
        } catch (IOException | RuntimeException e) {
            // Re-read committed state so a failed disk write cannot advance a watermark.
            try { state = store.load(); }
            catch (IOException ignored) { state = previous; }
            state.health = "主动服务需要检查：" + e.getMessage();
        }
    }

    private void scan(Watch watch, long now) {
        watch.lastCheckedAt = now; watch.nextCheckAt = now + watch.intervalSeconds * 1000L;
        try {
            var research = research(watch.inputPath, watch.preset);
            var inventory = research.inventory();
            if (inventory.version().equals(watch.version)) { watch.lastError = null; return; }
            var paths = new HashSet<>(watch.files.keySet()); paths.addAll(inventory.files().keySet());
            var changed = paths.stream().filter(path -> !java.util.Objects.equals(watch.files.get(path), inventory.files().get(path))).sorted().toList();
            if (state.triggers.size() >= 200) {
                var old = state.triggers.stream().filter(item -> terminal(item.status) || List.of("OBSERVED", "IGNORED").contains(item.status)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("待处理事件已达上限。"));
                state.triggers.remove(old);
            }
            var trigger = new Trigger(); trigger.watchId = watch.id; trigger.title = watch.title;
            trigger.at = Instant.now().toString(); trigger.version = inventory.version(); trigger.changed = changed;
            trigger.id = stable(watch.id + ":" + (watch.sequence + 1) + ":" + trigger.version);
            boolean relevant = research.relevant(changed, watch.keywords) || changed.stream().anyMatch(path -> !inventory.files().containsKey(path));
            trigger.snapshot = project.resolve("out/web/snapshots").resolve(trigger.id).toString();
            research.snapshot(Path.of(trigger.snapshot), trigger.version);
            trigger.status = !relevant ? "IGNORED" : watch.mode.equals("observe") ? "OBSERVED" : "QUEUED";
            trigger.decision = !relevant ? "变化未命中关注关键词。" : watch.mode.equals("observe") ? "命中规则，观察模式等待单次批准。" : "命中已启用规则，等待执行槽位。";
            // DEBUG S8-2: event and cursor move together in the next state commit.
            state.triggers.add(trigger); watch.sequence++; watch.version = inventory.version(); watch.files = inventory.files(); watch.lastError = null;
        } catch (IOException | RuntimeException e) { watch.lastError = e.getMessage(); }
    }

    private boolean enqueue(String runId, String watchId, String kind, String title, String body) {
        String id = stable(runId + ":" + kind);
        if (state.notices.stream().anyMatch(item -> item.id.equals(id))) return false;
        if (state.notices.size() >= 200) {
            var old = state.notices.stream().filter(item -> item.status.equals("SENT") && item.readAt != null).findFirst();
            if (old.isEmpty()) return false;
            state.notices.remove(old.get());
        }
        var notice = new Notice(); notice.id = id; notice.runId = runId; notice.watchId = watchId;
        notice.kind = kind; notice.title = title; notice.body = body == null ? "查看运行详情。" : body.substring(0, Math.min(body.length(), 16000));
        notice.createdAt = Instant.now().toString(); state.notices.add(notice);
        return true;
    }

    private void deliver(Notice notice) {
        Path directory = project.resolve("out/web/notifications");
        try {
            Files.createDirectories(directory);
            if (!directory.equals(directory.toRealPath())) throw new IOException("NOTIFICATION_PATH_CHANGED");
            Path target = directory.resolve(notice.id + ".md");
            Path temporary = Files.createTempFile(directory, ".notice-", ".tmp");
            try {
                Files.writeString(temporary, "# " + notice.title + "\n\n" + notice.body + "\n", StandardCharsets.UTF_8);
                // DEBUG S10-1: stable message ID makes retry delivery verifiable.
                try { Files.createLink(target, temporary); }
                catch (FileAlreadyExistsException e) {
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(temporary, target) != -1) throw new IOException("NOTIFICATION_CONFLICT", e);
                }
            } finally { Files.deleteIfExists(temporary); }
            notice.status = "SENT"; notice.sentAt = Instant.now().toString(); notice.error = null;
        } catch (IOException e) {
            notice.attempts++; notice.error = e.getMessage();
            notice.nextAttemptAt = System.currentTimeMillis() + Math.min(60000L, 1000L << Math.min(notice.attempts, 6));
            if (notice.attempts >= 5) notice.status = "FAILED";
        }
    }

    private int dailyRuns(Watch watch) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (watch.quotaDay == null) {
            watch.dispatchedToday = (int) state.triggers.stream().filter(item -> item.watchId.equals(watch.id)
                    && item.runId != null && item.dispatchedAt != null
                    && Instant.parse(item.dispatchedAt).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(today)).count();
        } else if (!watch.quotaDay.equals(today.toString())) watch.dispatchedToday = 0;
        watch.quotaDay = today.toString();
        return watch.dispatchedToday;
    }
    private SourceResearch research(String input, String preset) throws IOException {
        if (!List.of("notes", "source").contains(preset)) throw new IllegalArgumentException("请选择源码或 Markdown 目录。");
        Path path = Path.of(input).toRealPath();
        if (path.startsWith(project.resolve("out"))) throw new IllegalArgumentException("关注目录应位于运行产物目录之外。");
        return new SourceResearch(path, preset.equals("notes") ? SourceResearch.Mode.MARKDOWN : SourceResearch.Mode.SOURCE);
    }
    private String scope(AgentRuns.Request request) {
        try {
            var preset = runs.config().presets().stream().filter(item -> item.id().equals(request.preset())).findFirst().orElseThrow();
            return (request.inputPath() == null || request.inputPath().isBlank() ? project.resolve(preset.input()) : Path.of(request.inputPath())).toRealPath().toString();
        } catch (IOException | java.util.NoSuchElementException e) { return ""; }
    }
    private Watch watch(String id) { return state.watches.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("关注项不存在。")); }
    private Trigger trigger(String id) { return state.triggers.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("事件不存在。")); }
    private Goal goal(String id) { return state.goals.stream().filter(item -> item.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("长期目标不存在。")); }
    private static String stable(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static boolean terminal(String status) { return List.of("COMPLETED", "FAILED", "CANCELLED", "TURN_LIMIT", "RECOVERY_REQUIRED").contains(status); }
    private static void require(String text, int maximum) { if (text == null || text.isBlank() || text.length() > maximum) throw new IllegalArgumentException("内容为空或超出长度限制。"); }
    private static String keywords(String value) {
        if (value == null) return "";
        if (value.length() > 500 || java.util.Arrays.stream(value.split("[,，]")).anyMatch(item -> item.strip().length() > 120)) throw new IllegalArgumentException("每个关键词最多 120 字，总长度最多 500 字。");
        return value.strip();
    }
    private static boolean matches(String text, String keywords) {
        if (text == null || keywords == null || keywords.isBlank()) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return java.util.Arrays.stream(keywords.split("[,，]")).map(String::strip).filter(item -> !item.isEmpty()).anyMatch(item -> lower.contains(item.toLowerCase(java.util.Locale.ROOT)));
    }
    private static void validateMode(String mode) { if (!List.of("observe", "auto").contains(mode)) throw new IllegalArgumentException("请选择观察或自动模式。"); }
    private void validateProvider(String provider) { if (!List.of("replay", "deepseek").contains(provider) || (provider.equals("deepseek") && !runs.config().deepSeekConfigured())) throw new IllegalArgumentException("模型模式无效或 DeepSeek 未配置。"); }
}
