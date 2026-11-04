package personal.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads dotenv as data, never as executable shell code. */
public final class EnvConfig {
    private EnvConfig() {}

    public static Map<String, String> load(Path file, Map<String, String> environment) throws IOException {
        var values = new LinkedHashMap<String, String>();
        if (Files.exists(file)) {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.startsWith("\ufeff")) line = line.substring(1);
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int separator = line.indexOf('=');
                if (separator < 1) throw invalid(i + 1);
                String key = line.substring(0, separator).strip();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || values.containsKey(key)) throw invalid(i + 1);
                String value = line.substring(separator + 1).strip();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    char quote = value.charAt(0);
                    int end = value.indexOf(quote, 1);
                    if (end < 0) throw invalid(i + 1);
                    String tail = value.substring(end + 1).strip();
                    if (!tail.isEmpty() && !tail.startsWith("#")) throw invalid(i + 1);
                    value = value.substring(1, end);
                } else {
                    var comment = java.util.regex.Pattern.compile("(^|\\s)#").matcher(value);
                    if (comment.find()) value = value.substring(0, comment.start()).stripTrailing();
                }
                values.put(key, value);
            }
        }
        values.putAll(environment);
        return Map.copyOf(values);
    }

    private static IllegalArgumentException invalid(int line) {
        return new IllegalArgumentException("Invalid .env entry at line " + line + "; expected a unique KEY=value");
    }
}
