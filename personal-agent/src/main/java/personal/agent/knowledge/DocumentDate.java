package personal.agent.knowledge;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit document dates only. A newly imported old file must not become recent. */
final class DocumentDate {
    record Evidence(LocalDate date, String basis) {}
    private static final Pattern SEPARATED_DATE = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?:[-_./]|年)(0?[1-9]|1[0-2])(?:[-_./]|月)(0?[1-9]|[12]\\d|3[01])日?(?!\\d)");
    private static final Pattern COMPACT_DATE = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?:\\d{6})?(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern FIELD = Pattern.compile("(?i)^\\s*(?:[-*]\\s*)?(created(?:[_ -]?(?:at|time|date))?|updated(?:[_ -]?(?:at|time|date))?|modified(?:[_ -]?(?:at|time|date))?|date|创建(?:时间|日期)?|更新(?:时间|日期)?|日期)\\s*[:：]\\s*(.*)$");

    static Evidence resolve(String relative, String header) {
        Evidence created = null;
        for (String line : header.lines().limit(16).toList()) {
            var field = FIELD.matcher(line);
            if (!field.matches()) continue;
            LocalDate date = exact(field.group(2));
            if (date == null) continue;
            String key = field.group(1).toLowerCase(Locale.ROOT);
            Evidence value = new Evidence(date, "笔记字段 " + field.group(1));
            if (key.startsWith("updat") || key.startsWith("modif") || key.startsWith("更新")) return value;
            if (created == null) created = value;
        }
        if (created != null) return created;
        // Prefer the file's own date over its parent folder's year.
        String[] parts = relative.replace('\\', '/').split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            LocalDate date = exact(parts[i]);
            if (date != null) return new Evidence(date, "路径日期");
        }
        Integer year = null;
        var years = YEAR.matcher(relative);
        while (years.find()) year = Math.max(year == null ? 0 : year, Integer.parseInt(years.group(1)));
        return year == null ? null : new Evidence(LocalDate.of(year, 1, 1), "路径年份（按年初保守筛选）");
    }

    private static LocalDate exact(String text) {
        for (Pattern pattern : java.util.List.of(SEPARATED_DATE, COMPACT_DATE)) {
            var matcher = pattern.matcher(text);
            while (matcher.find()) {
                try {
                    return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3)));
                } catch (DateTimeException | NumberFormatException ignored) { /* Try the next explicit date. */ }
            }
        }
        return null;
    }
}
