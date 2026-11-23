package personal.agent.knowledge;

import personal.agent.OutputPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Read-only, local, explicitly dated Markdown retrieval. No model or network dependency. */
public final class KnowledgeBase {
    private static final int MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SELECTED_BYTES = 2 * 1024 * 1024;
    private static final Set<String> EXCLUDED = Set.of("harness", "skill sync", "outputs", "out", "target", "build", "dist",
            "node_modules", "vendor", "venv", "__pycache__", "secrets", "credentials");
    private static final Set<String> STOP_WORDS = Set.of("我的", "什么", "如何", "关于", "可以", "以及", "一个", "哪些", "请帮", "帮我", "想要", "一下", "我们", "请问", "the", "and", "with", "from");
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+");
    private final Path root;
    private final LocalDate since;
    private final LocalDate through;
    private Map<String, Document> documents = Map.of();
    private Stats stats;

    public record Stats(String root, LocalDate since, LocalDate through, int documents, int chunks, long bytes,
                        Map<String, Integer> skipped, List<String> folders, String version, Instant indexedAt) {}
    public record Match(String id, String path, String title, LocalDate date, String dateBasis, int startLine, int endLine,
                        String excerpt, List<String> matchedTerms, double score, int totalLines, int bytes) {}
    public record Search(String query, String folder, String version, List<String> terms, List<Match> matches) {}
    public record Line(int number, String text) {}
    public record Preview(String id, String path, String title, LocalDate date, String dateBasis, String version, List<Line> lines) {}
    public record Selected(String id, String path, String title, String sha256, LocalDate date, String dateBasis, int bytes) {}
    public record Context(String root, String version, LocalDate since, LocalDate through, List<Selected> documents) {}
    private record Chunk(int start, int end, String text) {}
    private record Document(String id, String path, String title, DocumentDate.Evidence dated, String hash, byte[] bytes,
                            List<String> lines, List<Chunk> chunks) {}

    public KnowledgeBase(Path root, LocalDate since, LocalDate through) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root) || since.isAfter(through)) throw new IllegalArgumentException("知识库目录或日期范围无效。");
        this.since = since;
        this.through = through;
    }

    public synchronized Stats stats() throws IOException { return stats == null ? refresh() : stats; }

    public synchronized Stats refresh() throws IOException {
        var next = new LinkedHashMap<String, Document>();
        var skipped = new LinkedHashMap<String, Integer>();
        var paths = new ArrayList<Path>();
        // Match the existing SourceWorkspace tool scope so every indexed selection remains readable.
        Files.walkFileTree(root, Set.of(), 20, new SimpleFileVisitor<>() {
            int visited;
            private FileVisitResult limit() {
                if (++visited <= 20000) return FileVisitResult.CONTINUE;
                skipped.merge("scanLimit", 1, Integer::sum);
                return FileVisitResult.TERMINATE;
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                if (limit() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                if (!path.equals(root) && !allowed(root.relativize(path))) {
                    skipped.merge("excludedDirectories", 1, Integer::sum);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (limit() == FileVisitResult.TERMINATE) return FileVisitResult.TERMINATE;
                if (attributes.isDirectory()) skipped.merge("depthLimit", 1, Integer::sum);
                else if (attributes.isSymbolicLink()) skipped.merge("symbolicLinks", 1, Integer::sum);
                else if (attributes.isRegularFile() && markdown(path) && allowed(root.relativize(path))) {
                    if (attributes.size() > MAX_FILE_BYTES) skipped.merge("largeFiles", 1, Integer::sum);
                    else paths.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException failure) {
                skipped.merge("unreadable", 1, Integer::sum);
                return limit();
            }
        });
        paths.sort(Comparator.comparing(Path::toString));
        long bytes = 0;
        int chunks = 0;
        var folders = new LinkedHashSet<String>();
        for (Path path : paths) {
            String relative = root.relativize(path).toString();
            try {
                String header;
                // Metadata only; old and undated bodies are never admitted to the index.
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    var prefix = new StringBuilder();
                    for (int i = 0; i < 16 && prefix.length() < 8192; i++) {
                        String line = reader.readLine();
                        if (line == null) break;
                        prefix.append(line, 0, Math.min(line.length(), 1024)).append('\n');
                    }
                    header = prefix.toString();
                }
                var dated = DocumentDate.resolve(relative, header);
                if (dated == null) { skipped.merge("undated", 1, Integer::sum); continue; }
                if (dated.date().isBefore(since)) { skipped.merge("older", 1, Integer::sum); continue; }
                if (dated.date().isAfter(through)) { skipped.merge("future", 1, Integer::sum); continue; }
                byte[] content = readSafe(relative);
                if (bytes + content.length > MAX_INDEX_BYTES) { skipped.merge("indexBytes", 1, Integer::sum); continue; }
                var lines = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString().lines().toList();
                String title = Path.of(relative).getFileName().toString().replaceFirst("(?i)\\.(md|markdown)$", "");
                for (String line : lines) if (line.startsWith("# ")) { title = line.substring(2).strip(); break; }
                String hash = hash(content);
                String id = hash((relative + "\0" + hash).getBytes(StandardCharsets.UTF_8));
                var pieces = chunks(lines);
                next.put(id, new Document(id, relative, title, dated, hash, content, lines, pieces));
                bytes += content.length;
                chunks += pieces.size();
                Path parent = Path.of(relative).getParent();
                if (parent != null) folders.add(parent.toString());
            } catch (IOException | IllegalArgumentException failure) { skipped.merge("unreadable", 1, Integer::sum); }
        }
        String version = hash((root + "\0" + since + "\0" + through + "\0" + String.join("\n", next.keySet())).getBytes(StandardCharsets.UTF_8));
        // DEBUG K1: inspect the recent-only documents and each exclusion count before publishing the local index.
        documents = java.util.Collections.unmodifiableMap(next);
        stats = new Stats(root.toString(), since, through, next.size(), chunks, bytes, Map.copyOf(skipped),
                folders.stream().sorted().toList(), version, Instant.now());
        return stats;
    }

    public synchronized Search search(String query, String folder) throws IOException {
        stats();
        if (query == null || query.isBlank() || query.length() > 300) throw new IllegalArgumentException("请输入 1 到 300 字的检索问题。");
        String scope = folder == null ? "" : folder.strip();
        if (scope.length() > 500 || scope.contains("..") || scope.startsWith("/")) throw new IllegalArgumentException("请选择知识库中的相对目录。");
        var terms = terms(query);
        var matches = new ArrayList<Match>();
        for (Document document : documents.values()) {
            if (!scope.isEmpty() && !document.path().startsWith(scope + "/")) continue;
            String title = document.title().toLowerCase(Locale.ROOT);
            String path = document.path().toLowerCase(Locale.ROOT);
            Chunk best = null;
            double bestScore = 0;
            List<String> bestTerms = List.of();
            for (Chunk chunk : document.chunks()) {
                String text = chunk.text().toLowerCase(Locale.ROOT);
                var hitTerms = new ArrayList<String>();
                double score = 0;
                for (String term : terms) {
                    boolean bodyHit = text.contains(term), titleHit = title.contains(term), pathHit = path.contains(term);
                    if (bodyHit || titleHit || pathHit) {
                        hitTerms.add(term);
                        score += (bodyHit ? 2 : 0) + (titleHit ? 5 : 0) + (pathHit ? 2 : 0);
                    }
                }
                if (!hitTerms.isEmpty()) score += 4.0 * hitTerms.size() / Math.max(1, terms.size());
                if (text.contains(query.toLowerCase(Locale.ROOT))) score += 8;
                if (score > bestScore) { bestScore = score; best = chunk; bestTerms = List.copyOf(hitTerms); }
            }
            if (best != null) matches.add(new Match(document.id(), document.path(), document.title(), document.dated().date(),
                    document.dated().basis(), best.start(), best.end(), best.text(), bestTerms, bestScore,
                    document.lines().size(), document.bytes().length));
        }
        matches.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::date, Comparator.reverseOrder()).thenComparing(Match::path));
        // DEBUG K2: compare ranking, matched terms and original line numbers. No model request has occurred.
        return new Search(query.strip(), scope, stats.version(), List.copyOf(terms), List.copyOf(matches.subList(0, Math.min(30, matches.size()))));
    }

    public synchronized Preview preview(String id, String version) throws IOException {
        requireVersion(version);
        Document doc = document(id);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < doc.lines().size(); i++) lines.add(new Line(i + 1, doc.lines().get(i)));
        return new Preview(id, doc.path(), doc.title(), doc.dated().date(), doc.dated().basis(), stats.version(), List.copyOf(lines));
    }

    public synchronized Context snapshot(Path requested, String version, List<String> ids) throws IOException {
        requireVersion(version);
        if (ids == null || ids.isEmpty() || ids.size() > 6 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("请明确选择 1 到 6 篇资料。");
        }
        var selected = ids.stream().map(this::document).toList();
        if (selected.stream().mapToInt(doc -> doc.bytes().length).sum() > MAX_SELECTED_BYTES) throw new IllegalArgumentException("选中资料全文合计不能超过 2 MiB。");
        for (Document doc : selected) {
            if (!hash(readSafe(doc.path())).equals(doc.hash())) throw new IllegalStateException("资料已变化，请刷新索引、重新预览后再启动。");
        }
        Path destination = OutputPaths.resolveDirectory(requested);
        if (destination.startsWith(root) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("资料快照必须在知识库之外的新目录。");
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempDirectory(destination.getParent(), ".knowledge-");
        try {
            for (Document doc : selected) {
                Path target = temporary.resolve(doc.path());
                Files.createDirectories(target.getParent());
                Files.write(target, doc.bytes());
            }
            // DEBUG K3: only the explicitly selected, previewed documents enter this immutable tool scope.
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temporary)) try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        return new Context(root.toString(), version, since, through, selected.stream().map(doc -> new Selected(doc.id(), doc.path(),
                doc.title(), doc.hash(), doc.dated().date(), doc.dated().basis(), doc.bytes().length)).toList());
    }

    private void requireVersion(String version) throws IOException {
        if (!stats().version().equals(version)) throw new IllegalStateException("索引已变化，请重新检索并选择资料。");
    }

    private Document document(String id) {
        var document = documents.get(id);
        if (document == null) throw new IllegalArgumentException("资料不在当前最近一年索引中，请重新检索。");
        return document;
    }

    private byte[] readSafe(String relative) throws IOException {
        Path path = Path.of(relative);
        if (!allowed(path) || !markdown(path)) throw new IOException("知识库路径无效。");
        Path current = root;
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("不读取符号链接。");
        }
        try (var input = Files.newInputStream(current, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("文件超过读取上限。");
            return bytes;
        }
    }

    private static boolean allowed(Path relative) {
        if (relative.isAbsolute() || relative.toString().isBlank()) return false;
        for (Path part : relative) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.startsWith(".") || EXCLUDED.contains(name) || name.chars().anyMatch(c -> c == ':' || c == '[' || c == ']' || Character.isISOControl(c))) return false;
        }
        return true;
    }
    private static boolean markdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }
    private static List<Chunk> chunks(List<String> lines) {
        var result = new ArrayList<Chunk>();
        for (int start = 0; start < lines.size();) {
            int end = start;
            var text = new StringBuilder();
            while (end < lines.size() && end - start < 18 && text.length() < 1600) {
                String line = lines.get(end++);
                text.append(line, 0, Math.min(line.length(), 1600)).append('\n');
            }
            result.add(new Chunk(start + 1, end, text.toString()));
            start = end;
        }
        return List.copyOf(result);
    }
    private static Set<String> terms(String query) {
        var result = new LinkedHashSet<String>();
        var matcher = WORDS.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                if (word.length() <= 2) { if (!STOP_WORDS.contains(word)) result.add(word); }
                else for (int i = 0; i < word.length() - 1; i++) {
                    String pair = word.substring(i, i + 2);
                    if (!STOP_WORDS.contains(pair)) result.add(pair);
                }
            } else if (word.length() > 1 && !STOP_WORDS.contains(word)) result.add(word);
            if (result.size() >= 40) break;
        }
        return result;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
