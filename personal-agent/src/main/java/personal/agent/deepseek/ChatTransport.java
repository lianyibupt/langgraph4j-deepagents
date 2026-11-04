package personal.agent.deepseek;

/** Authentication belongs to the transport, never to the JSON body codec. */
@FunctionalInterface
public interface ChatTransport {
    record Response(int statusCode, String body) {}

    Response post(String requestJson) throws Exception;
}
