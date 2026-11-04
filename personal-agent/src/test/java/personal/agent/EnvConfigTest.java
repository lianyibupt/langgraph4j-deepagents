package personal.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class EnvConfigTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("agent-dotenv-");
        Path file = folder.resolve(".env");
        try {
            check(EnvConfig.load(file, Map.of("EXISTING", "yes")).get("EXISTING").equals("yes"), "missing file allowed");
            Files.writeString(file, "# config\nDEEPSEEK_API_KEY=test-sentinel\nDEEPSEEK_MODEL=deepseek-flash\n");
            Map<String, String> values = EnvConfig.load(file, Map.of("DEEPSEEK_MODEL", "override"));
            check("test-sentinel".equals(values.get("DEEPSEEK_API_KEY")), "file value loaded");
            check("override".equals(values.get("DEEPSEEK_MODEL")), "process environment overrides file");
            System.out.println("PASS dotenv loading, absent file and environment precedence");

            Files.writeString(file, "\ufeffexport DEEPSEEK_API_KEY = 'sentinel=#value' # note\r\nDEEPSEEK_MODEL=\"deepseek-flash\"\nEMPTY=\nPLAIN=abc # comment\n");
            values = EnvConfig.load(file, Map.of());
            check("sentinel=#value".equals(values.get("DEEPSEEK_API_KEY")), "quoted equal and hash retained");
            check("deepseek-flash".equals(values.get("DEEPSEEK_MODEL")), "double quotes");
            check(values.get("EMPTY").isEmpty() && "abc".equals(values.get("PLAIN")), "empty value and inline comment");
            System.out.println("PASS dotenv quotes, comments, BOM, CRLF and export syntax");

            Files.writeString(file, "VALUE=$(echo should-not-run)\nOTHER=${HOME}\n");
            values = EnvConfig.load(file, Map.of());
            check(values.get("VALUE").equals("$(echo should-not-run)") && values.get("OTHER").equals("${HOME}"), "no code or variable evaluation");
            System.out.println("PASS dotenv treats shell expressions as literal data");

            for (String bad : new String[]{"BROKEN secret-sentinel", "A='secret-sentinel", "A=secret-sentinel\nA=again", "A='secret-sentinel' unexpected"}) {
                Files.writeString(file, bad);
                try { EnvConfig.load(file, Map.of()); throw new AssertionError("expected malformed config failure"); }
                catch (IllegalArgumentException e) { check(!e.getMessage().contains("secret-sentinel") && e.getMessage().contains("line"), "line number only, no values"); }
            }
            System.out.println("PASS dotenv rejects malformed or duplicate entries without secret echo");
            System.out.println("Dotenv tests: 4 passed, 0 failed");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(folder); }
    }

    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
}
