package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.DomainObservationValidator;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.require;

/** Immutable local investigation checkpoints. These files are never a delivery acknowledgement ledger. */
public final class TraceLinkScenarioCheckpoints {
    public static final String VERSION = "trace-link-local-checkpoint-v1";
    public static final String PUBLICATION = "UNKNOWN_NOT_TRACKED";
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private TraceLinkScenarioCheckpoints() {}

    /** Use a new path for each checkpoint generation; exact retries never replace an earlier file. */
    public static boolean write(List<TraceLinkScenarioEngine.Snapshot> snapshots, Clock clock, Path output) throws IOException {
        TraceLinkScenarioEngine.restore(clock, 128, snapshots); // Validate identities, history prefixes, reservations and clock.
        var runs = JSON.createArrayNode(); var codec = new TraceLinkScenarioCommandCodec();
        for (var s : snapshots.stream().sorted(Comparator.comparing(TraceLinkScenarioEngine.Snapshot::runId)).toList()) {
            var run = runs.addObject().put("runId", s.runId()).put("status", s.status().name());
            run.set("command", codec.write(s.command()));
            var identities = run.putArray("expectedWindowIdentities"); s.expectedWindowIdentities().forEach(identities::add);
            var history = run.putArray("history");
            for (var w : s.history()) {
                var window = history.addObject().put("start", w.start().toString());
                var observations = window.putArray("observations"); w.observations().forEach(observations::add);
            }
        }
        var envelope = JSON.createObjectNode().put("version", VERSION).put("publicationStatus", PUBLICATION);
        envelope.set("runs", runs); envelope.put("contentSha256", DomainObservationValidator.sha256(runs.toString()));
        byte[] bytes = (envelope + "\n").getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_BYTES, "Checkpoint file bound exceeded");
        return TraceLinkScenarioArtifacts.writeBytes(bytes, output);
    }

    /** Missing/unknown publication status provides no permission to release windows on restart. */
    public static TraceLinkScenarioEngine restore(Path input, Clock clock, int capacity) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
        require(bytes.length <= MAX_BYTES, "Checkpoint file bound exceeded");
        var envelope = JSON.readTree(bytes);
        fields(envelope, "version", "publicationStatus", "runs", "contentSha256");
        require(VERSION.equals(text(envelope, "version")) && PUBLICATION.equals(text(envelope, "publicationStatus")),
                "Unrecognized checkpoint/publication policy");
        var runs = envelope.path("runs");
        require(runs.isArray() && runs.size() <= 128 && runs.size() <= capacity, "Checkpoint run bound exceeded");
        require(DomainObservationValidator.sha256(runs.toString()).equals(text(envelope, "contentSha256")), "Checkpoint checksum mismatch");
        var snapshots = new ArrayList<TraceLinkScenarioEngine.Snapshot>(); var codec = new TraceLinkScenarioCommandCodec();
        for (var run : runs) {
            fields(run, "runId", "status", "command", "expectedWindowIdentities", "history");
            var command = codec.read(run.path("command").toString());
            var identities = strings(run.path("expectedWindowIdentities"), 8);
            var windows = run.path("history"); require(windows.isArray() && windows.size() <= 8, "Checkpoint history bound exceeded");
            var history = new ArrayList<TraceLinkFixtureGenerator.Window>();
            for (var window : windows) {
                fields(window, "start", "observations");
                history.add(new TraceLinkFixtureGenerator.Window(Instant.parse(text(window, "start")), strings(window.path("observations"), 3)));
            }
            snapshots.add(new TraceLinkScenarioEngine.Snapshot(text(run, "runId"), command,
                    TraceLinkScenarioEngine.Status.valueOf(text(run, "status")), history, identities));
        }
        return TraceLinkScenarioEngine.restore(clock, capacity, snapshots);
    }
    private static List<String> strings(JsonNode array, int maximum) {
        require(array.isArray() && array.size() <= maximum, "Invalid checkpoint array");
        var result = new ArrayList<String>();
        for (var item : array) { require(item.isTextual() && item.textValue().length() <= 16384, "Invalid checkpoint text"); result.add(item.textValue()); }
        return List.copyOf(result);
    }
    private static String text(JsonNode n, String field) {
        require(n.path(field).isTextual() && !n.path(field).textValue().isBlank(), "Invalid checkpoint field: " + field);
        return n.path(field).textValue();
    }
    private static void fields(JsonNode n, String... expected) {
        require(n != null && n.isObject(), "Expected checkpoint object");
        var actual = new HashSet<String>(); n.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(expected)), "Unexpected/missing checkpoint fields");
    }
}
