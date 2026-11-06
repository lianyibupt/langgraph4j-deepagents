package personal.agent.longtask;

import personal.agent.OutputPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class ArtifactStore {
    private final Path requested;
    private final Path base;
    private final Path inputRoot;
    private final Consumer<String> trace;
    private final Map<String, Artifact> byCall = new HashMap<>();
    private final Map<String, Artifact> byId = new HashMap<>();
    private Path runDirectory;

    record Artifact(String id, Path path, int chars, int bytes) {}
    record Slice(String artifactId, int offset, int nextOffset, boolean eof, String text) {}

    ArtifactStore(Path output, Path input, Consumer<String> trace) throws IOException {
        requested = output.toAbsolutePath().normalize();
        base = OutputPaths.resolveDirectory(requested);
        inputRoot = input.toRealPath();
        this.trace = trace;
        if (base.startsWith(inputRoot)) throw new IllegalArgumentException("ARTIFACT_OUTPUT_SCOPE: choose a directory outside the input");
    }

    Artifact archive(String callId, String text) throws IOException {
        Artifact previous = byCall.get(callId);
        if (previous != null) return previous;
        if (runDirectory == null) {
            if (!base.equals(OutputPaths.resolveDirectory(requested))) throw new IOException("ARTIFACT_OUTPUT_PATH_CHANGED");
            Files.createDirectories(base);
            if (!base.equals(base.toRealPath())) throw new IOException("ARTIFACT_OUTPUT_PATH_CHANGED");
            runDirectory = Files.createTempDirectory(base, "run-");
        }
        String id = "artifact-" + (byId.size() + 1);
        Path path = runDirectory.resolve(id + ".txt");
        Path temp = Files.createTempFile(runDirectory, ".artifact-", ".tmp");
        int bytes;
        try {
            // DEBUG S5-2: inspect the original text and the run-local artifact file.
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            bytes = Math.toIntExact(Files.size(temp));
            Files.move(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
        var artifact = new Artifact(id, path, text.length(), bytes);
        byCall.put(callId, artifact);
        byId.put(id, artifact);
        trace.accept("artifact.saved id=" + id + " chars=" + text.length() + " path=" + path);
        return artifact;
    }

    Artifact findByCall(String callId) { return byCall.get(callId); }

    int count() { return byId.size(); }

    void reset() {
        byCall.clear();
        byId.clear();
        runDirectory = null;
    }

    Slice read(String id, int offset, int maxChars) throws IOException {
        Artifact artifact = byId.get(id);
        if (artifact == null) throw new IllegalArgumentException("unknown artifact ID");
        if (!runDirectory.equals(runDirectory.toRealPath())
                || !Files.isRegularFile(artifact.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ARTIFACT_PATH_CHANGED");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(artifact.path(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(artifact.bytes() + 1);
        }
        if (bytes.length != artifact.bytes()) throw new IOException("ARTIFACT_SIZE_CHANGED");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (offset < 0 || offset > text.length()
                || (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))) {
            throw new IllegalArgumentException("invalid UTF-16 offset");
        }
        int end = offset + Math.min(text.length() - offset, maxChars);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new Slice(id, offset, end, end == text.length(), text.substring(offset, end));
    }
}
