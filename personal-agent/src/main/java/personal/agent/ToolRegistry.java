package personal.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A per-runtime registry. Registration order is kept for reproducible examples. */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool tool : tools) {
            if (this.tools.putIfAbsent(tool.definition().name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + tool.definition().name());
            }
        }
    }

    public List<Tool.Definition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public ToolResult execute(ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.failure("UNKNOWN_TOOL: " + call.name());
        }
        try {
            ToolResult result = tool.execute(call.arguments());
            return result == null ? ToolResult.failure("TOOL_ERROR: tool returned null") : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("INTERRUPTED: tool execution interrupted");
        } catch (Exception e) {
            return ToolResult.failure("TOOL_ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
