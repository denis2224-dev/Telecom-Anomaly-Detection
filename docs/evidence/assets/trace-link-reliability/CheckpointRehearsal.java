import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import md.utm.telecom.generator.tracelink.*;

/** Offline packaged-library rehearsal with an explicit logical clock; no runtime transport. */
public class CheckpointRehearsal {
    static class LogicalClock extends Clock {
        Instant now;
        LogicalClock(Instant now) { this.now = now; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <command.json> <new-output-directory>");
        var command = new TraceLinkScenarioCommandCodec().read(Files.readString(Path.of(args[0])));
        var output = Files.createDirectory(Path.of(args[1]));
        var clock = new LogicalClock(command.start().minusSeconds(1));
        var engine = new TraceLinkScenarioEngine(clock, 8); engine.start(command);
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, output.resolve("01-scheduled.json"));
        clock.now = command.start().plusSeconds(180); var windows = engine.advance();
        if (windows.size() != 3) throw new IllegalStateException("Expected three generated windows");
        var checkpoint = output.resolve("02-running.json");
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, checkpoint);
        var checkpointBytes = Files.readAllBytes(checkpoint); var modified = Files.getLastModifiedTime(checkpoint);
        if (TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, checkpoint)
                || !Arrays.equals(checkpointBytes, Files.readAllBytes(checkpoint))
                || !modified.equals(Files.getLastModifiedTime(checkpoint))) throw new IllegalStateException("Checkpoint replay changed evidence");
        var artifact = output.resolve("measurements.jsonl");
        TraceLinkScenarioArtifacts.write(windows, artifact); var artifactBytes = Files.readAllBytes(artifact);
        if (TraceLinkScenarioArtifacts.write(windows, artifact)
                || !Arrays.equals(artifactBytes, Files.readAllBytes(artifact))) throw new IllegalStateException("Artifact replay changed evidence");
        engine.stop(command.runId()); TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, output.resolve("03-stopped.json"));
        clock.now = command.end();
        var restored = TraceLinkScenarioCheckpoints.restore(checkpoint, clock, 8);
        var stopped = TraceLinkScenarioCheckpoints.restore(output.resolve("03-stopped.json"), clock, 8);
        if (restored.start(command).status() != TraceLinkScenarioEngine.Status.INTERRUPTED
                || stopped.start(command).status() != TraceLinkScenarioEngine.Status.STOPPED
                || !restored.advance().isEmpty() || !stopped.advance().isEmpty()) throw new IllegalStateException("Restart released uncertain history");
        var report = "{\n  \"runId\":\"" + command.runId() + "\",\n  \"logicalClock\":true,\n"
                + "  \"generatedWindows\":3,\n  \"observations\":" + Files.readAllLines(artifact).size() + ",\n"
                + "  \"restoredStatus\":\"INTERRUPTED\",\n  \"stoppedStatus\":\"STOPPED\",\n  \"restartReleasedWindows\":0,\n"
                + "  \"publicationStatus\":\"UNKNOWN_NOT_TRACKED\",\n  \"Kafka\":\"DISABLED\",\n"
                + "  \"checkpointReplayByteAndTimestampStable\":true,\n  \"artifactReplayByteStable\":true,\n"
                + "  \"checkpointSha256\":\"" + hash(checkpointBytes) + "\",\n  \"artifactSha256\":\"" + hash(artifactBytes) + "\"\n}\n";
        Files.writeString(output.resolve("result.json"), report); System.out.print(report);
    }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
