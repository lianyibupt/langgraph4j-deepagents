package personal.agent.research;

import personal.agent.ReadTextTool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, read-only access to source files in one explicitly selected directory. */
final class SourceWorkspace {
    private static final int FILE_BYTES = 512 * 1024;
    private static final int SEARCH_BYTES = 4 * 1024 * 1024;
    private static final int MAX_VISITS = 3000;
    private static final int MAX_DEPTH = 20;
    private static final int MAX_READ_LINES = 40;
    private static final int MAX_LINE_CHARS = 300;
    private static final Set<String> EXCLUDED = Set.of(
            "target", "build", "dist", "out", "node_modules", "vendor", "__pycache__",
            "secrets", "credentials");
    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "scala", "py", "go", "rs", "js", "ts", "tsx", "jsx",
            "c", "cpp", "h", "hpp", "cs", "md", "xml", "gradle", "toml", "sh");
    private final Path root;
    private final ReadTextTool reader;
    private final boolean markdownOnly;

    record Listing(List<String> paths, boolean truncated) {}
    record SourceLine(int number, String text) {}
    record Excerpt(String path, List<SourceLine> lines) {}
    record Hit(String path, int line, String text) {}
    record Search(List<Hit> hits, boolean truncated) {}
    private record SourceFile(Path relative, long bytes) {}
    private record Scan(List<SourceFile> files, boolean truncated) {}

    SourceWorkspace(Path root, boolean markdownOnly) throws IOException {
        this.root = root.toRealPath();
        this.reader = new ReadTextTool(this.root, FILE_BYTES);
        this.markdownOnly = markdownOnly;
    }

    Listing list(String filter) throws IOException {
        if (filter.length() > 120) throw new IllegalArgumentException("filter exceeds 120 characters");
        var scan = scan();
        var needle = filter.toLowerCase(Locale.ROOT);
        var matches = scan.files().stream().map(file -> file.relative().toString())
                .filter(path -> path.toLowerCase(Locale.ROOT).contains(needle)).limit(41).toList();
        return new Listing(matches.subList(0, Math.min(40, matches.size())), scan.truncated() || matches.size() > 40);
    }

    Search search(String query) throws IOException {
        if (query.isBlank() || query.length() > 120 || query.contains("\n") || query.contains("\r")) {
            throw new IllegalArgumentException("query must contain 1–120 characters on one line");
        }
        var scan = scan();
        var needle = query.toLowerCase(Locale.ROOT);
        var hits = new ArrayList<Hit>();
        long scannedBytes = 0;
        boolean truncated = scan.truncated();
        // DEBUG S3-2: inspect the bounded list of candidate files and literal query.
        for (var file : scan.files()) {
            scannedBytes += file.bytes();
            if (scannedBytes > SEARCH_BYTES) return new Search(hits, true);
            final List<String> lines;
            try { lines = readLines(file.relative()); }
            catch (IllegalArgumentException e) { truncated = true; continue; }
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                int offset = line.toLowerCase(Locale.ROOT).indexOf(needle);
                if (offset < 0) continue;
                if (hits.size() == 30) return new Search(hits, true);
                hits.add(new Hit(file.relative().toString(), index + 1, preview(line, Math.max(0, offset - 60))));
            }
        }
        return new Search(hits, truncated);
    }

    Excerpt read(String path, int start, int end) {
        if (start < 1 || end < start || (long) end - start + 1 > MAX_READ_LINES) {
            throw new IllegalArgumentException("expected a 1-based range of at most 40 lines");
        }
        Path relative = Path.of(path);
        var lines = readLines(relative);
        if (start > lines.size()) throw new IllegalArgumentException("start_line is beyond the end of the file");
        var excerpt = new ArrayList<SourceLine>();
        // DEBUG S3-3: compare the requested range to the lines that are actually returned.
        for (int number = start; number <= Math.min(end, lines.size()); number++) {
            excerpt.add(new SourceLine(number, preview(lines.get(number - 1), 0)));
        }
        return new Excerpt(relative.toString(), List.copyOf(excerpt));
    }

    private Scan scan() throws IOException {
        var files = new ArrayList<SourceFile>();
        var visitor = new SimpleFileVisitor<Path>() {
            private int visited;
            private boolean truncated;

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (++visited > MAX_VISITS) { truncated = true; return FileVisitResult.TERMINATE; }
                if (!directory.equals(root) && !allowedPath(root.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (++visited > MAX_VISITS) { truncated = true; return FileVisitResult.TERMINATE; }
                if (attributes.isDirectory()) truncated = true;
                Path relative = root.relativize(file);
                if (attributes.isRegularFile() && allowedPath(relative) && sourceType(relative)) {
                    if (attributes.size() <= FILE_BYTES) files.add(new SourceFile(relative, attributes.size()));
                    else truncated = true;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                truncated = true;
                return ++visited > MAX_VISITS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        };
        Files.walkFileTree(root, Set.of(), MAX_DEPTH, visitor);
        files.sort(Comparator.comparing(file -> file.relative().toString()));
        return new Scan(files, visitor.truncated);
    }

    private List<String> readLines(Path relative) {
        if (!allowedPath(relative) || !sourceType(relative)) {
            throw new IllegalArgumentException("path is outside the allowed source scope");
        }
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symbolic links are excluded");
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("source file not found");
        }
        var result = reader.execute(Map.of("path", relative.toString()));
        if (result.error()) throw new IllegalArgumentException(result.text());
        return result.text().lines().toList();
    }

    private static boolean allowedPath(Path relative) {
        if (relative.isAbsolute() || relative.toString().isBlank()) return false;
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || EXCLUDED.contains(name) || name.indexOf(':') >= 0
                    || name.indexOf('[') >= 0 || name.indexOf(']') >= 0
                    || name.chars().anyMatch(Character::isISOControl)) return false;
        }
        return true;
    }

    private boolean sourceType(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (markdownOnly) return name.endsWith(".md") || name.endsWith(".markdown");
        int dot = name.lastIndexOf('.');
        return name.equals("makefile") || name.equals("dockerfile")
                || (dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1)));
    }

    private static String preview(String line, int start) {
        int from = Math.min(start, line.length());
        if (from > 0 && from < line.length() && Character.isLowSurrogate(line.charAt(from))) from--;
        int to = Math.min(line.length(), from + MAX_LINE_CHARS);
        if (to < line.length() && to > from && Character.isHighSurrogate(line.charAt(to - 1))) to--;
        return (from > 0 ? "…" : "") + line.substring(from, to) + (to < line.length() ? "…" : "");
    }
}
