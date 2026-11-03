package personal.agent;

import java.util.List;
import java.util.Map;

/** A deterministic teaching substitute, not an LLM and not an inference engine. */
public final class ScriptedModelClient implements ModelClient {
    private final String path;

    public ScriptedModelClient(String path) { this.path = path; }

    @Override
    public ModelReply reply(List<Message> messages, List<Tool.Definition> tools) {
        if (messages.size() == 1 && messages.getFirst().role() == Message.Role.USER) {
            return new ModelReply.Tools(List.of(new ToolCall("read-1", "read_text", Map.of("path", path))));
        }
        if (messages.size() != 3 || messages.getLast().role() != Message.Role.TOOL
                || !"read-1".equals(messages.getLast().toolCallId())) {
            throw new IllegalArgumentException("Scripted model expects its matching read-1 result");
        }
        Message observation = messages.getLast();
        if (observation.toolError()) {
            return new ModelReply.FinalAnswer("[scripted] 读取失败，无法引用资料：\n" + observation.text());
        }
        return new ModelReply.FinalAnswer("[scripted] 已收到工具读取的资料，以下为原文：\n\n"
                + observation.text() + "\n这个回答由模型替身按固定规则拼接，用于观察消息回填，没有进行模型推理。");
    }
}
