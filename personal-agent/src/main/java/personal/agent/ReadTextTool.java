package personal.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

public final class ReadTextTool implements Tool {
    private final Path root;
    private final int maxBytes;

    public ReadTextTool(Path root, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IllegalArgumentException("Workspace must be a directory");
        this.maxBytes = maxBytes;
    }

    @Override
    public Definition definition() {
        return new Definition("read_text", "Read a bounded UTF-8 file relative to the workspace",
                Map.of("path", "Required relative file path"));
    }

    @Override
    public ToolResult execute(Map<String, String> arguments) {
        if (arguments.size() != 1 || arguments.get("path") == null || arguments.get("path").isBlank()) {
            return ToolResult.failure("INVALID_ARGUMENTS: expected one nonblank 'path'");
        }
        try {
            Path relative = Path.of(arguments.get("path"));
            if (relative.isAbsolute()) return ToolResult.failure("ABSOLUTE_PATH: use a relative path");
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root)) return ToolResult.failure("OUTSIDE_WORKSPACE: path escapes root");
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) return ToolResult.failure("OUTSIDE_WORKSPACE: symlink escapes root");
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("NOT_A_FILE: expected a regular file");
            }
            byte[] content;
            try (var input = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
                content = input.readNBytes(maxBytes);
                if (input.read() != -1) return ToolResult.failure("FILE_TOO_LARGE: limit=" + maxBytes + " bytes");
            }
            // The decoder rejects malformed UTF-8 rather than silently replacing it.
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
            return ToolResult.success(text);
        } catch (NoSuchFileException e) {
            return ToolResult.failure("FILE_NOT_FOUND: " + arguments.get("path"));
        } catch (InvalidPathException e) {
            return ToolResult.failure("INVALID_ARGUMENTS: invalid path");
        } catch (CharacterCodingException e) {
            return ToolResult.failure("INVALID_UTF8: file is not valid UTF-8");
        } catch (IOException | SecurityException e) {
            return ToolResult.failure("FILE_READ_ERROR: " + e.getClass().getSimpleName());
        }
    }
}
