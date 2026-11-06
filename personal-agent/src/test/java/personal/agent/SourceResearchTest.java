package personal.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.research.SourceResearch;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Map;

public final class SourceResearchTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("source-research-test-");
        try {
            Files.writeString(root.resolve("Demo.java"), "package demo;\nclass Demo {}\n");
            Files.writeString(root.resolve(".env"), "PRIVATE_CONFIG_MARKER=hidden\n");
            Files.createDirectories(root.resolve("target"));
            Files.writeString(root.resolve("target/Generated.java"), "class PRIVATE_BUILD_MARKER {}\n");
            Files.createSymbolicLink(root.resolve("Linked.java"), root.resolve(".env"));
            Files.createSymbolicLink(root.resolve("alias"), root.resolve("target"));
            var bytes = new ByteArrayOutputStream();
            var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            int status = Main.run(new String[]{"--provider", "replay", "--repo", root.toString(), "--messages"},
                    root, out, out, Map.of());
            String result = bytes.toString(StandardCharsets.UTF_8);
            check(status == 0, "source research must complete");
            check(result.contains("name=list_sources") && result.contains("name=search_source")
                    && result.contains("name=read_source"), "discover, search and read tools must run");
            check(result.contains("[[Demo.java:2]]") && result.contains("modelTurns=4"), "report references a real read");
            check(!result.contains("PRIVATE_CONFIG_MARKER") && !result.contains("PRIVATE_BUILD_MARKER"), "excluded content stays out");
            System.out.println("PASS source research replay through CLI");

            var research = new SourceResearch(root);
            var tools = new ToolRegistry(research.tools());
            var json = new ObjectMapper();
            var listed = tools.execute(new ToolCall("list", "list_sources", Map.of("filter", "")));
            check(!listed.error() && json.readTree(listed.text()).path("paths").size() == 1, "only allowed source is listed");
            var found = tools.execute(new ToolCall("search", "search_source", Map.of("query", "CLASS")));
            check(!found.error() && json.readTree(found.text()).path("hits").get(0).path("line").asInt() == 2,
                    "literal search returns the real 1-based line number");
            rejectCitation(research, "[[Demo.java:2]]");
            System.out.println("PASS literal search cannot create citation evidence");

            for (String path : new String[]{".env", "Linked.java", "alias/Generated.java",
                    "target/Generated.java", "../Demo.java", root.resolve("Demo.java").toString()}) {
                check(read(tools, path, "1", "2").error(), "reject out-of-scope file " + path);
            }
            check(read(tools, "Demo.java", "0", "2").error() && read(tools, "Demo.java", "1", "41").error(),
                    "reject invalid ranges");
            System.out.println("PASS source read rejects hidden, linked, generated and out-of-scope files");

            var excerpt = read(tools, "Demo.java", "2", "6");
            check(!excerpt.error() && json.readTree(excerpt.text()).path("lines").size() == 1,
                    "read clips the requested range at EOF");
            check(research.verifyCitations("已读源码 [[Demo.java:2]]") == 1, "real citation passes");
            for (String answer : new String[]{"无引文", "[[Demo.java:1]]", "[[Demo.java:2-3]]",
                    "[[Fake.java:2]]", "[[Demo.java:2]] [[Demo.java:wrong]]", "[[Demo.java:2]] [["}) {
                rejectCitation(research, answer);
            }
            System.out.println("PASS report citations require actual returned ranges");
        } finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static ToolResult read(ToolRegistry tools, String path, String start, String end) {
        return tools.execute(new ToolCall("read", "read_source",
                Map.of("path", path, "start_line", start, "end_line", end)));
    }

    private static void rejectCitation(SourceResearch research, String answer) {
        try {
            research.verifyCitations(answer);
            throw new AssertionError("citation should be rejected: " + answer);
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("SOURCE_CITATION_INVALID"), "expected citation diagnostic");
        }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
