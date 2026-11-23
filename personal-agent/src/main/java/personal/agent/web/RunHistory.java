package personal.agent.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.OutputPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Durable Web history. One service process owns the directory until shutdown. */
final class RunHistory implements AutoCloseable {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final Set<Path> HELD = new HashSet<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Path directory;
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private final Path lockFile;

    RunHistory(Path requested) throws IOException {
        directory = OutputPaths.resolveDirectory(requested);
        Files.createDirectories(directory);
        file = directory.resolve("runs.json");
        lockFile = directory.resolve("service.lock");
        synchronized (HELD) {
            for (Path held : HELD) {
                if (held.equals(lockFile) || (Files.exists(lockFile) && Files.isSameFile(held, lockFile))) {
                    throw new IOException("WEB_STATE_LOCKED: another service owns this project");
                }
            }
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                lock = channel.tryLock();
                if (lock == null) throw new IOException("WEB_STATE_LOCKED: another service owns this project");
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
            HELD.add(lockFile);
        }
    }

    List<AgentRuns.Saved> load() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("WEB_HISTORY_INVALID");
        byte[] data;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            data = input.readNBytes(MAX_BYTES + 1);
        }
        if (data.length > MAX_BYTES) throw new IOException("WEB_HISTORY_TOO_LARGE");
        return json.readValue(data, new TypeReference<List<AgentRuns.Saved>>() {});
    }

    void save(List<AgentRuns.Saved> runs) throws IOException {
        if (!directory.equals(directory.toRealPath())) throw new IOException("WEB_HISTORY_PATH_CHANGED");
        byte[] data = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(runs);
        if (data.length > MAX_BYTES) throw new IOException("WEB_HISTORY_TOO_LARGE");
        Path temp = Files.createTempFile(directory, ".runs-", ".tmp");
        try {
            try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // DEBUG S7-3: publish metadata only after the complete document is durable.
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override public void close() throws IOException {
        synchronized (HELD) {
            if (!channel.isOpen()) return;
            try { lock.close(); }
            finally {
                try { channel.close(); }
                finally { HELD.remove(lockFile); }
            }
        }
    }
}
