package personal.agent.delegation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.Message;
import personal.agent.OutputPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One process owns a checkpoint for the entire run. The lock file has a stable name. */
public final class CheckpointStore implements AutoCloseable {
    private static final int VERSION = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<Path> HELD_LOCK_FILES = new HashSet<>();
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path file;
    private final Binding binding;
    private final Path lockFile;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public enum Phase { READY, TOOLS_PENDING, COMPLETED }
    public record Binding(String provider, String model, String workspace, String sampleFile) {}
    public record State(int version, Binding binding, Phase phase, int modelTurns, List<Message> messages) {
        public State {
            if (messages != null) messages = List.copyOf(messages);
        }
    }

    public CheckpointStore(Path requested, Path input, Binding binding, boolean resume) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (absolute.getFileName() == null || !absolute.getFileName().toString().endsWith(".json")) {
            throw new IllegalArgumentException("CHECKPOINT_FORMAT: use a .json file");
        }
        Path parent = OutputPaths.resolveDirectory(absolute.getParent());
        if (parent.startsWith(input.toRealPath())) throw new IllegalArgumentException("CHECKPOINT_SCOPE: choose a path outside input");
        this.file = parent.resolve(absolute.getFileName());
        this.binding = binding;
        if (resume && Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CHECKPOINT_NOT_FOUND");
        if (!resume && !Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CHECKPOINT_EXISTS");
        if (!resume) Files.createDirectories(parent);
        checkParent();

        lockFile = file.resolveSibling(file.getFileName() + ".lock");
        synchronized (HELD_LOCK_FILES) {
            // On POSIX closing a second descriptor can release locks held by this process.
            for (Path held : HELD_LOCK_FILES) {
                if (held.equals(lockFile) || (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSameFile(held, lockFile))) throw new IOException("CHECKPOINT_LOCKED");
            }
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                lock = lockChannel.tryLock();
                if (lock == null) throw new IOException("CHECKPOINT_LOCKED");
            } catch (OverlappingFileLockException e) {
                lockChannel.close();
                throw new IOException("CHECKPOINT_LOCKED", e);
            } catch (IOException | RuntimeException e) {
                lockChannel.close();
                throw e;
            }
            HELD_LOCK_FILES.add(lockFile);
        }
    }

    public Path path() { return file; }

    public void start(String question) throws IOException {
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CHECKPOINT_EXISTS");
        write(new State(VERSION, binding, Phase.READY, 0, List.of(Message.user(question))), true);
    }

    public void save(Phase phase, int turns, List<Message> messages) throws IOException {
        write(new State(VERSION, binding, phase, turns, messages), false);
    }

    public State load() throws IOException {
        checkParent();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CHECKPOINT_NOT_REGULAR_FILE");
        byte[] data;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            data = input.readNBytes(MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) throw new IOException("CHECKPOINT_TOO_LARGE");
        final State state;
        try { state = json.readValue(data, State.class); }
        catch (JsonProcessingException e) { throw new IOException("CHECKPOINT_INVALID: malformed state", e); }
        if (state == null || state.version() != VERSION || state.phase() == null) throw new IOException("CHECKPOINT_INVALID: unsupported state");
        if (!binding.equals(state.binding())) throw new IOException("CHECKPOINT_BINDING_MISMATCH");
        // DEBUG S6-3: examine the durable phase before allowing any model or tool calls.
        if (state.phase() == Phase.TOOLS_PENDING) {
            throw new IOException("CHECKPOINT_TOOLS_UNCERTAIN: inspect the pending batch before continuing");
        }
        validateHistory(state);
        return state;
    }

    private static void validateHistory(State state) throws IOException {
        List<Message> history = state.messages();
        if (history == null || history.isEmpty() || history.getFirst() == null
                || history.getFirst().role() != Message.Role.USER || history.getFirst().text().isBlank()) {
            throw new IOException("CHECKPOINT_INVALID: missing user goal");
        }
        int turns = 0;
        boolean finalAnswer = false;
        var ids = new HashSet<String>();
        for (int i = 1; i < history.size(); ) {
            Message assistant = history.get(i++);
            if (assistant == null || assistant.role() != Message.Role.ASSISTANT) {
                throw new IOException("CHECKPOINT_INVALID: expected assistant message");
            }
            turns++;
            if (assistant.toolCalls().isEmpty()) {
                if (i != history.size() || assistant.text().isBlank()) throw new IOException("CHECKPOINT_INVALID: malformed final answer");
                finalAnswer = true;
            }
            for (var call : assistant.toolCalls()) {
                if (i >= history.size() || !ids.add(call.id())) throw new IOException("CHECKPOINT_INVALID: incomplete or duplicate tool batch");
                Message result = history.get(i++);
                if (result == null || result.role() != Message.Role.TOOL || !call.id().equals(result.toolCallId())) {
                    throw new IOException("CHECKPOINT_INVALID: tool result does not match its call");
                }
            }
        }
        if (turns != state.modelTurns() || finalAnswer != (state.phase() == Phase.COMPLETED)) {
            throw new IOException("CHECKPOINT_INVALID: turn count or phase does not match history");
        }
    }

    private void write(State state, boolean first) throws IOException {
        checkParent();
        byte[] data = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        if (data.length > MAX_BYTES) throw new IOException("CHECKPOINT_TOO_LARGE");
        Path temp = Files.createTempFile(file.getParent(), ".checkpoint-", ".tmp");
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // DEBUG S6-2: atomic publication of the complete checkpoint document.
            if (first) Files.createLink(file, temp);
            else Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void checkParent() throws IOException {
        if (!file.getParent().equals(file.getParent().toRealPath())) throw new IOException("CHECKPOINT_PATH_CHANGED");
    }

    @Override public void close() throws IOException {
        synchronized (HELD_LOCK_FILES) {
            if (!lockChannel.isOpen()) return;
            try { lock.close(); }
            finally {
                try { lockChannel.close(); }
                finally { HELD_LOCK_FILES.remove(lockFile); }
            }
        }
    }
}
