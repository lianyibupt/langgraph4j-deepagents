package personal.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import personal.agent.OutputPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** The existing Web process lock protects this additional management state. */
final class ManagementStore {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final Path file;

    ManagementStore(Path project) throws IOException {
        file = OutputPaths.resolveDirectory(project.resolve("out/web")).resolve("management.json");
    }

    ProactiveAgent.State load() throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new ProactiveAgent.State();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MANAGEMENT_STATE_INVALID");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("MANAGEMENT_STATE_TOO_LARGE");
            var state = json.readValue(bytes, ProactiveAgent.State.class);
            if (state.version != 1) throw new IOException("MANAGEMENT_STATE_VERSION");
            return state;
        }
    }

    ProactiveAgent.State copy(ProactiveAgent.State state) {
        return json.convertValue(state, ProactiveAgent.State.class);
    }

    void save(ProactiveAgent.State state) throws IOException {
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        if (bytes.length > MAX_BYTES) throw new IOException("MANAGEMENT_STATE_TOO_LARGE");
        if (!file.getParent().equals(file.getParent().toRealPath())) throw new IOException("MANAGEMENT_PATH_CHANGED");
        Path temporary = Files.createTempFile(file.getParent(), ".management-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
