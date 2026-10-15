import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.generator.tracelink.*;
import md.utm.telecom.observation.ObservationValidator;

/** Packaged producer-only rehearsal. All clocks are logical; no Spring context or transport. */
public final class TraceLinkProducerRehearsal {
    static final ObjectMapper JSON = new ObjectMapper();
    static final class LogicalClock extends Clock {
        Instant now;
        LogicalClock(Instant start) { now = start; }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
    static void write(Path file, JsonNode value) throws Exception {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE_NEW);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <service-seeds.json> <new-output-directory>");
        var config = JSON.readTree(Path.of(args[0]).toFile());
        var output = Files.createDirectory(Path.of(args[1]));
        var codec = new TraceLinkScenarioCommandCodec();
        var commands = new LinkedHashMap<String, TraceLinkScenarioCatalogue.Command>();
        for (var item : config.required("scenarios")) {
            String name = item.required("name").asText();
            require(name.matches("[a-z0-9-]+"), "Unsafe artifact name");
            require(commands.put(name, codec.read(item.required("command").toString())) == null, "Duplicate case");
        }
        var first = commands.values().stream().map(c -> c.start()).min(Instant::compareTo).orElseThrow();
        var last = commands.values().stream().map(c -> c.end()).max(Instant::compareTo).orElseThrow();
        var clock = new LogicalClock(first.minusSeconds(1));
        var engine = new TraceLinkScenarioEngine(clock, 128);
        for (var command : commands.values()) {
            var scheduled = engine.start(command);
            require(scheduled.status() == TraceLinkScenarioEngine.Status.SCHEDULED, "Not scheduled");
            require(scheduled.equals(engine.start(command)), "Command replay changed status");
        }
        int generated = 0;
        for (Instant tick = first; !tick.isAfter(last); tick = tick.plusSeconds(60)) {
            clock.now = tick.minusNanos(1);
            require(engine.advance().isEmpty(), "Minute released before window end");
            clock.now = tick;
            generated += engine.advance().size();
            require(engine.advance().isEmpty(), "Repeated tick emitted a duplicate");
        }
        require(generated == commands.size() * 8, "Incomplete schedule");
        var report = JSON.createObjectNode().put("logicalClock", true).put("Kafka", "DISABLED")
                .put("publicationStatus", "UNKNOWN_NOT_TRACKED").put("generatedWindows", generated);
        var cases = report.putArray("cases");
        var generator = new TraceLinkFixtureGenerator();
        for (var entry : commands.entrySet()) {
            var c = entry.getValue();
            var snapshot = engine.status(c.runId());
            require(snapshot.status() == TraceLinkScenarioEngine.Status.COMPLETED, "Incomplete run");
            require(snapshot.history().equals(generator.generate(c)), "Scheduled and compiled primitives differ");
            var file = output.resolve(entry.getKey() + ".jsonl");
            require(TraceLinkScenarioArtifacts.write(snapshot.history(), file), "Artifact already exists");
            var time = Files.getLastModifiedTime(file);
            require(!TraceLinkScenarioArtifacts.write(snapshot.history(), file)
                    && time.equals(Files.getLastModifiedTime(file)), "Exact artifact replay changed file");
            var row = cases.addObject().put("name", entry.getKey()).put("runId", c.runId())
                    .put("status", snapshot.status().name()).put("windows", snapshot.history().size());
            row.set("windowIdentities", JSON.valueToTree(snapshot.expectedWindowIdentities()));
        }
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, output.resolve("completed-checkpoint.json"));
        var restored = TraceLinkScenarioCheckpoints.restore(output.resolve("completed-checkpoint.json"), clock, 128);
        require(restored.advance().isEmpty(), "Completed restore emitted output");
        // Independently interrupt the first run during degradation, preserving its generated prefix.
        var c = commands.values().iterator().next();
        var interruptedClock = new LogicalClock(c.start().minusSeconds(1));
        var interrupted = new TraceLinkScenarioEngine(interruptedClock, 8);
        interrupted.start(c); interruptedClock.now = c.start().plusSeconds(180); interrupted.advance();
        var checkpoint = output.resolve("interrupted-checkpoint.json");
        TraceLinkScenarioCheckpoints.write(interrupted.checkpoint(), interruptedClock, checkpoint);
        var checkpointTime = Files.getLastModifiedTime(checkpoint);
        require(!TraceLinkScenarioCheckpoints.write(interrupted.checkpoint(), interruptedClock, checkpoint)
                && checkpointTime.equals(Files.getLastModifiedTime(checkpoint)), "Checkpoint replay changed file");
        interruptedClock.now = c.end();
        var afterRestart = TraceLinkScenarioCheckpoints.restore(checkpoint, interruptedClock, 8);
        require(afterRestart.start(c).status() == TraceLinkScenarioEngine.Status.INTERRUPTED
                && afterRestart.status(c.runId()).history().size() == 3 && afterRestart.advance().isEmpty(), "Unsafe restart");
        try {
            afterRestart.start(new TraceLinkScenarioCatalogue.Command(TraceLinkScenarioCatalogue.Type.NORMAL_CONTROL,
                    c.version(), c.scope(), c.seed(), c.start(), c.parameters()));
            throw new IllegalStateException("Conflicting reservation accepted");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("SCOPE_WINDOW_CONFLICT"), "Unexpected conflict rejection");
        }
        report.put("interruptedHistoryWindows", 3).put("restartReleasedWindows", 0)
                .put("conflictingReservationRejected", true).put("earlyReleaseRejected", true)
                .put("artifactReplayByteAndTimestampStable", true).put("checkpointReplayByteAndTimestampStable", true);
        // Existing approved legacy APIs, with their frozen V2 validator and no runtime publisher.
        var validator = new ObservationValidator();
        var voice = new VoiceScenario(JSON, validator);
        var sms = new SmsQueueScenario(JSON, validator);
        var legacy = config.required("legacy");
        var start = Instant.parse(legacy.required("start").asText());
        long seed = legacy.required("seed").longValue();
        for (var profile : VoiceScenario.Profile.values())
            write(output.resolve("legacy-volte-" + profile.name().toLowerCase(Locale.ROOT) + ".json"),
                    JSON.valueToTree(voice.generateWindows(start, seed, profile)));
        write(output.resolve("legacy-sms-fault.json"), JSON.valueToTree(sms.generateWindows(start, seed)));
        write(output.resolve("legacy-sms-normal.json"), JSON.valueToTree(sms.generateHealthyWindows(start, seed)));
        write(output.resolve("legacy-sms-gap.json"), JSON.valueToTree(sms.generateTelemetryGapWindows(start, seed)));
        write(output.resolve("result.json"), report);
        System.out.println("PASS: " + commands.size() + " scoped schedules; six legacy profiles; restart emits zero; Kafka DISABLED");
    }
}
