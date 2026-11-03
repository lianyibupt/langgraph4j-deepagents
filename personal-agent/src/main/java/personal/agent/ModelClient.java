package personal.agent;

import java.util.List;

/** Replace the scripted implementation with a real provider in stage 2. */
@FunctionalInterface
public interface ModelClient {
    ModelReply reply(List<Message> messages, List<Tool.Definition> tools) throws Exception;
}
