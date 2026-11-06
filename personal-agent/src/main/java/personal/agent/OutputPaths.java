package personal.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolve aliases in the existing portion of a user-selected output directory. */
public final class OutputPaths {
    private OutputPaths() {}

    public static Path resolveDirectory(Path directory) throws IOException {
        Path requested = directory.toAbsolutePath().normalize();
        Path ancestor = requested;
        while (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        Path real = ancestor.toRealPath();
        if (!Files.isDirectory(real)) throw new IOException("OUTPUT_PARENT_NOT_DIRECTORY");
        return real.resolve(ancestor.relativize(requested));
    }
}
