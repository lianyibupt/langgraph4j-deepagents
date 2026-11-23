package personal.agent;

import personal.agent.knowledge.KnowledgeBase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Essential knowledge retrieval and selected-context boundaries, with synthetic notes only. */
public final class KnowledgeBaseTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("personal-knowledge-test-");
        try {
            Path root = Files.createDirectory(temp.resolve("vault"));
            Files.createDirectories(root.resolve("Projects"));
            Files.writeString(root.resolve("Projects/Agent 上下文.md"), "---\ncreated: 2026-10-20\n---\n# Agent 的上下文设计\n\n资料检索需要保留出处。\n每次只读取选中的笔记。\n");
            Files.writeString(root.resolve("无关-2026-10-20.md"), "# 午饭\n今天准备吃面条。\n");
            Files.writeString(root.resolve("旧笔记-2024-10-20.md"), "# Agent 的旧上下文\n");
            Files.writeString(root.resolve("没有日期.md"), "# Agent 的不明日期上下文\n");
            Files.createDirectories(root.resolve("Harness/Memory"));
            Files.writeString(root.resolve("Harness/Memory/private.md"), "# 不应检索的上下文\n");
            Files.writeString(root.resolve(".hidden.md"), "# 不应检索的上下文\n");
            Files.writeString(root.resolve("script.py"), "print('上下文')\n");
            Files.createSymbolicLink(root.resolve("link.md"), root.resolve("无关-2026-10-20.md"));
            KnowledgeBase knowledge = new KnowledgeBase(root, java.time.LocalDate.of(2025, 11, 23), java.time.LocalDate.of(2026, 11, 23));
            var stats = knowledge.refresh();
            var search = knowledge.search("Agent 上下文", "");
            check(stats.documents() == 2, "only allowed Markdown documents are indexed");
            check(stats.skipped().getOrDefault("older", 0) == 1 && stats.skipped().getOrDefault("undated", 0) == 1,
                    "imported old and undated documents do not become recent merely because of modification time");
            check(search.matches().size() == 1, "Chinese and English terms retrieve the relevant note");
            var match = search.matches().getFirst();
            check(match.path().equals("Projects/Agent 上下文.md") && match.startLine() == 1
                    && match.excerpt().contains("保留出处") && !match.matchedTerms().isEmpty(), "original source lines and matching reasons are visible");
            check(knowledge.search("不存在的主题", "").matches().isEmpty(), "unrelated query does not fabricate context");
            check(knowledge.search("上下文", "Notion").matches().isEmpty(), "folder boundary is applied before ranking");
            System.out.println("PASS knowledge retrieval preserves Chinese sources and excludes hidden, memory and linked files");

            Path snapshot = temp.resolve("context/selected");
            var context = knowledge.snapshot(snapshot, search.version(), List.of(match.id()));
            check(context.documents().size() == 1 && Files.exists(snapshot.resolve(match.path()))
                    && !Files.exists(snapshot.resolve("无关-2026-10-20.md")), "only selected files are available to the agent");
            check(Files.readString(snapshot.resolve(match.path())).equals(Files.readString(root.resolve(match.path()))), "snapshot preserves original line numbers");
            Path note = temp.resolve("answer.md");
            var trace = new java.util.ArrayList<String>();
            var execution = Main.execute(new String[]{"--notes", snapshot.toString(), "--output", note.toString()}, root, java.util.Map.of(), trace::add);
            check(execution.publicationError() == null && Files.readString(note).contains("[[Projects/Agent 上下文.md:"), "existing note runtime cites selected original paths");
            System.out.println("PASS selected knowledge snapshot runs through real tools and citation validation");

            Files.writeString(root.resolve(match.path()), "# 文件已变化\n请重新检索后再选择。\n");
            boolean refused = false;
            try { knowledge.snapshot(temp.resolve("stale"), search.version(), List.of(match.id())); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused && !Files.exists(temp.resolve("stale")), "changed original cannot be silently sent using old preview");
            check(Files.readString(snapshot.resolve(match.path())).contains("每次只读取"), "existing run keeps its immutable original input");
            knowledge.refresh();
            boolean versionRefused = false;
            try { knowledge.snapshot(temp.resolve("old-version"), search.version(), List.of(match.id())); }
            catch (IllegalStateException expected) { versionRefused = true; }
            check(versionRefused, "old selection is rejected after index refresh");
            System.out.println("PASS changed knowledge and stale index selections require a new preview");

            Path boundary = Files.createDirectory(temp.resolve("dates"));
            for (String date : List.of("2025-11-22", "2025-11-23", "2025-12-01", "2026-11-23", "2026-11-24", "2026-12-01")) {
                Files.writeString(boundary.resolve("Agent-" + date + ".md"), "# Agent 日期边界\n");
            }
            Files.writeString(boundary.resolve("Agent-20261123103029.md"), "# Agent 会议\n");
            Files.writeString(boundary.resolve("Agent-元数据.md"), "---\ncreated: 2025-12-02\n---\n# Agent 元数据\n");
            var recent = new KnowledgeBase(boundary, java.time.LocalDate.of(2025, 11, 23), java.time.LocalDate.of(2026, 11, 23));
            var dated = recent.search("Agent", "").matches();
            check(dated.size() == 5 && dated.stream().noneMatch(item -> item.path().contains("2026-12-01") || item.path().contains("2025-11-22") || item.path().contains("2026-11-24")), "November and December dates respect the complete year window");
            check(dated.stream().anyMatch(item -> item.path().contains("2025-11-23") && item.date().equals(java.time.LocalDate.of(2025, 11, 23))), "lower bound is inclusive and its month/day are preserved");
            check(dated.stream().anyMatch(item -> item.path().contains("20261123103029") && item.date().equals(java.time.LocalDate.of(2026, 11, 23))), "compact meeting timestamp preserves the calendar date");
            System.out.println("PASS knowledge date boundaries preserve full November, December and compact timestamp dates");

            Path deepRoot = Files.createDirectory(temp.resolve("deep-vault"));
            Path deep = deepRoot;
            for (int i = 0; i < 19; i++) deep = Files.createDirectory(deep.resolve("d" + i));
            Files.writeString(deep.resolve("Agent-2026-10-20.md"), "# Agent 深目录\n");
            Files.createDirectory(deep.resolve("too-deep"));
            Files.writeString(deep.resolve("too-deep/Agent-2026-10-20.md"), "# Agent 超深目录\n");
            var deepKnowledge = new KnowledgeBase(deepRoot, java.time.LocalDate.of(2025, 11, 23), java.time.LocalDate.of(2026, 11, 23));
            var deepSearch = deepKnowledge.search("Agent", "");
            check(deepSearch.matches().size() == 1 && deepKnowledge.stats().skipped().getOrDefault("depthLimit", 0) == 1,
                    "retrieval and existing research tools use the same maximum directory depth");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
