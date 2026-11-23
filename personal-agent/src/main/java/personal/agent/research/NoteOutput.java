package personal.agent.research;

import personal.agent.OutputPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Locale;

/** User-chosen output, published only after the run verifies its citations. */
public final class NoteOutput {
    private final Path inputRoot;
    private final Path requested;
    private final Path destination;
    private final boolean recovering;

    public NoteOutput(Path inputRoot, Path output) throws IOException {
        this(inputRoot, output, false);
    }

    public NoteOutput(Path inputRoot, Path output, boolean recovering) throws IOException {
        this.inputRoot = inputRoot.toRealPath();
        this.requested = output.toAbsolutePath().normalize();
        this.recovering = recovering;
        if (requested.getFileName() == null || !requested.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("NOTE_OUTPUT_FORMAT: --output requires a .md file");
        }
        destination = resolveDestination();
    }

    public Path save(String markdown) throws IOException {
        // DEBUG S4-2: citation validation has passed; inspect markdown and destination.
        if (!destination.equals(resolveDestination())) throw new IOException("NOTE_OUTPUT_PATH_CHANGED");
        Files.createDirectories(destination.getParent());
        Path parent = destination.getParent().toRealPath();
        if (!parent.equals(destination.getParent()) || parent.startsWith(inputRoot)) {
            throw new IOException("NOTE_OUTPUT_PATH_CHANGED");
        }
        Path temp = Files.createTempFile(parent, ".agent-note-", ".tmp");
        try {
            Files.writeString(temp, markdown.stripTrailing() + "\n", StandardCharsets.UTF_8);
            // A hard link publishes the complete file and fails if the name already exists.
            try {
                Files.createLink(destination, temp);
            } catch (FileAlreadyExistsException e) {
                if (!recovering || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                        || Files.mismatch(temp, destination) != -1) {
                    throw new IOException("NOTE_OUTPUT_CONFLICT: existing note differs from the saved answer", e);
                }
            }
            return destination;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path resolveDestination() throws IOException {
        Path resolved = OutputPaths.resolveDirectory(requested.getParent()).resolve(requested.getFileName());
        if (resolved.startsWith(inputRoot)) throw new IllegalArgumentException("NOTE_OUTPUT_SCOPE: output must be outside the input directory");
        if (!Files.notExists(resolved, LinkOption.NOFOLLOW_LINKS)
                && !(recovering && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalArgumentException("NOTE_OUTPUT_EXISTS: choose a new output file");
        }
        return resolved;
    }
}
