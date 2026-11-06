package personal.agent;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public final class MarkdownKnowledgeTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("markdown-knowledge-test-");
        try {
            Path notes = Files.createDirectory(temp.resolve("notes"));
            Path input = notes.resolve("Agent 学习.md");
            String original = "# Agent Loop\n\n先记录用户目标，再调用工具。参见 [[任务记录]]。\n";
            Files.writeString(input, original);
            Files.writeString(notes.resolve("Ignored.java"), "class EXCLUDED_SOURCE_MARKER {}");
            Files.writeString(notes.resolve(".hidden.md"), "# EXCLUDED_HIDDEN_MARKER");
            Path output = temp.resolve("results/整理.md");

            var run = run(notes, output, "--messages");
            check(run.status() == 0, "knowledge mode succeeds: " + run.log());
            check(run.log().contains("name=list_notes") && run.log().contains("name=search_notes")
                    && run.log().contains("name=read_note"), "all three Markdown tools are invoked");
            check(!run.log().contains("EXCLUDED_"), "non-Markdown and hidden inputs stay out");
            String saved = Files.readString(output);
            check(saved.contains("[[Agent 学习.md:1-3]]") && saved.contains("先记录用户目标"),
                    "the saved note contains material and a valid original line range");
            check(Files.readString(input).equals(original), "input content stays intact");
            System.out.println("PASS Markdown replay saves a cited note outside the read-only collection");

            var retry = run(notes, output);
            check(retry.status() != 0 && !retry.log().contains("model.request"), "existing output is rejected before requesting the model");
            check(Files.readString(output).equals(saved), "existing output is preserved");
            System.out.println("PASS output collision cannot overwrite an existing note");

            Path alias = Files.createSymbolicLink(temp.resolve("notes-alias"), notes);
            for (Path invalid : new Path[]{notes.resolve("new.md"), alias.resolve("new.md"), temp.resolve("wrong.txt")}) {
                var rejected = run(notes, invalid);
                check(rejected.status() != 0 && !rejected.log().contains("model.request") && !Files.exists(invalid),
                        "reject an invalid destination before a model request");
            }
            System.out.println("PASS output must be a Markdown file outside the input collection");

            Path limited = temp.resolve("stopped.md");
            var stopped = run(notes, limited, "--max-turns", "3");
            check(stopped.status() != 0 && stopped.log().contains("TURN_LIMIT") && !Files.exists(limited),
                    "budget stop leaves no note");
            Path empty = Files.createDirectory(temp.resolve("empty"));
            Path unverified = temp.resolve("unverified.md");
            var invalidReport = run(empty, unverified);
            check(invalidReport.status() != 0 && invalidReport.log().contains("SOURCE_CITATION_INVALID")
                    && !Files.exists(unverified), "empty or unverified report leaves no note");
            System.out.println("PASS interrupted or uncited output is not published");
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private record Run(int status, String log) {}

    private static Run run(Path notes, Path output, String... extra) {
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var args = new java.util.ArrayList<>(java.util.List.of("--notes", notes.toString(), "--output", output.toString()));
        args.addAll(java.util.List.of(extra));
        int status = Main.run(args.toArray(String[]::new), notes, stream, stream, Map.of());
        return new Run(status, bytes.toString(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
