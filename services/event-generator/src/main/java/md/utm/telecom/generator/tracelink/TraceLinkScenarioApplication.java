package md.utm.telecom.generator.tracelink;

import java.nio.file.*;

/** Explicit offline compiler; deliberately does not start Spring or discover Kafka configuration. */
public final class TraceLinkScenarioApplication {
    private TraceLinkScenarioApplication() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <command.json> <observations.jsonl>");
        Path input = Path.of(args[0]);
        if (Files.size(input) > 16384) throw new IllegalArgumentException("Command file exceeds 16 KiB");
        var command = new TraceLinkScenarioCommandCodec().read(Files.readString(input));
        var windows = new TraceLinkFixtureGenerator().generate(command);
        boolean created = TraceLinkScenarioArtifacts.write(windows, Path.of(args[1]));
        System.out.println("runId=" + command.runId() + " windows=" + windows.size() + " artifact=" + (created ? "CREATED" : "EXACT_REPLAY")
                + " Kafka=DISABLED consumer=PENDING_REVIEW");
    }
}
