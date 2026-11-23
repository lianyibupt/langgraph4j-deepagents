package personal.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Recovery regressions exercised through the same entry point as the Web workbench. */
public final class ResearchRecoveryTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("research-recovery-");
        try {
            Path input = Files.createDirectory(temp.resolve("input"));
            Path source = input.resolve("Entry.java");
            Files.writeString(source, "class Entry {\n  void run() {}\n}\n");
            Path checkpoint = temp.resolve("source.json");
            var sourceArgs = List.of("--repo", input.toString(), "--provider", "replay");
            var paused = execute(sourceArgs, input, "--checkpoint", checkpoint.toString(), "--pause-after", "3");
            check(paused.execution().result().status() == AgentResult.Status.PAUSED, "source pauses after evidence");
            var resumed = execute(sourceArgs, input, "--resume", checkpoint.toString());
            check(resumed.execution().publicationError() == null && resumed.execution().result().status() == AgentResult.Status.COMPLETED,
                    "source evidence restored for final citation validation");
            check(resumed.events().stream().noneMatch(event -> event.startsWith("tool.start")), "completed reads not repeated");
            System.out.println("PASS source checkpoint restores citation evidence without repeating tools");

            Path changed = temp.resolve("changed.json");
            execute(sourceArgs, input, "--checkpoint", changed.toString(), "--pause-after", "3");
            Files.writeString(source, "class Changed {}\n");
            var events = new ArrayList<String>();
            try {
                Main.execute(join(sourceArgs, "--resume", changed.toString()), input, Map.of(), events::add);
                throw new AssertionError("changed input was accepted");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("CHECKPOINT_INPUT_CHANGED"), expected.getMessage());
                check(events.stream().noneMatch(event -> event.startsWith("model.request")), "reject change before calling model");
            }
            System.out.println("PASS changed input is rejected before another model request");

            Path notes = Files.createDirectory(temp.resolve("notes"));
            Files.writeString(notes.resolve("learning.md"), "# Agent\n\n工具结果进入消息历史。\n");
            Path note = temp.resolve("output/note.md");
            Path notesCheckpoint = temp.resolve("notes.json");
            var notesArgs = List.of("--notes", notes.toString(), "--output", note.toString());
            execute(notesArgs, notes, "--checkpoint", notesCheckpoint.toString(), "--pause-after", "3");
            check(!Files.exists(note), "paused research publishes no note");
            var complete = execute(notesArgs, notes, "--resume", notesCheckpoint.toString());
            check(complete.execution().note() != null, "resumed note is published");
            String published = Files.readString(note);
            var reopened = execute(notesArgs, notes, "--resume", notesCheckpoint.toString());
            check(reopened.execution().publicationError() == null && Files.readString(note).equals(published), "same published note reused");
            check(reopened.events().stream().noneMatch(event -> event.startsWith("model.request")), "completed resume does not request model");
            System.out.println("PASS Markdown resume publishes once and reopens an identical completed note");

            Files.writeString(note, "User's independently edited note\n");
            var conflict = execute(notesArgs, notes, "--resume", notesCheckpoint.toString());
            check(conflict.execution().publicationError().contains("NOTE_OUTPUT_CONFLICT"), "conflicting note rejected");
            check(Files.readString(note).startsWith("User's"), "user edits preserved");
            System.out.println("PASS completed recovery preserves a conflicting user-edited note");

            Path longInput = Files.createDirectory(temp.resolve("long"));
            Files.writeString(longInput.resolve("long-task.md"), "# Original\n" + "资料中的证据必须保留。\n".repeat(160));
            var longArgs = List.of("--context-chars", "1800", "--artifacts-dir", temp.resolve("artifacts").toString());
            Path longCheckpoint = temp.resolve("long.json");
            execute(longArgs, longInput, "--checkpoint", longCheckpoint.toString(), "--pause-after", "2");
            var longResume = execute(longArgs, longInput, "--resume", longCheckpoint.toString());
            check(longResume.execution().result().status() == AgentResult.Status.COMPLETED, "long run finishes after restore");
            check(longResume.events().stream().anyMatch(event -> event.contains("context.restored planSteps=3 artifacts=1")),
                    "plan and stable artifact id restored");
            check(longResume.events().stream().anyMatch(event -> event.contains("name=read_artifact"))
                    && longResume.events().stream().noneMatch(event -> event.contains("name=read_text")), "archive used without reexecuting original tool");
            System.out.println("PASS long-task checkpoint restores plan and artifact reads without replaying source reads");
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private record Observed(Main.Execution execution, List<String> events) {}

    private static Observed execute(List<String> base, Path input, String... extra) throws Exception {
        var events = new ArrayList<String>();
        return new Observed(Main.execute(join(base, extra), input, Map.of(), events::add), events);
    }

    private static String[] join(List<String> base, String... extra) {
        var arguments = new ArrayList<>(base);
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
