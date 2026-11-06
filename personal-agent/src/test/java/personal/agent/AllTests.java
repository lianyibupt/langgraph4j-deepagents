package personal.agent;

/** The same executable tests are used by Bash, Maven and IDE run configurations. */
public final class AllTests {
    public static void main(String[] args) throws Exception {
        AgentRuntimeTest.main(args);
        DeepSeekTest.main(args);
        EnvConfigTest.main(args);
        SourceResearchTest.main(args);
        MarkdownKnowledgeTest.main(args);
        LongTaskTest.main(args);
        DelegationCheckpointTest.main(args);
        if (Boolean.getBoolean("agent.test.http")) HttpTransportTest.main(args);
        else System.out.println("HTTP loopback tests skipped; run test.sh --http to include them.");
    }
}
