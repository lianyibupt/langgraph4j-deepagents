package personal.agent;

import java.util.Map;

/** Parameter descriptions are deliberately simple here, not a JSON Schema. */
public interface Tool {
    record Definition(String name, String description, Map<String, String> parameters) {
        public Definition {
            if (name == null || name.isBlank() || description == null || description.isBlank()) {
                throw new IllegalArgumentException("Tool name and description must not be blank");
            }
            parameters = Map.copyOf(parameters);
        }
    }

    Definition definition();

    ToolResult execute(Map<String, String> arguments) throws Exception;
}
