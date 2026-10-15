package md.utm.telecom.generator.tracecontrol;

import md.utm.telecom.generator.tracelink.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import md.utm.telecom.observation.TraceLinkCatalog;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

/** Single-writer, durable LOCAL generation. There is deliberately no Kafka dependency. */
public final class TraceLinkRuntime implements AutoCloseable {
    private final Clock clock;
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final TraceLinkScenarioCommandCodec codec = new TraceLinkScenarioCommandCodec();
    private final ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private TraceLinkScenarioEngine engine;
    private long sequence;
    private boolean failed;

    public TraceLinkRuntime(Clock clock, Path directory) throws IOException {
        this.clock = clock;
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        channel = FileChannel.open(this.directory.resolve("runtime.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("Trace Link runtime already owns this directory");
            Path last;
            try (var paths = Files.list(this.directory)) {
                last = paths.filter(p -> p.getFileName().toString().matches("checkpoint-[0-9]{12}\\.json"))
                        .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            }
            if (last == null) engine = new TraceLinkScenarioEngine(clock, 128);
            else {
                sequence = Long.parseLong(last.getFileName().toString().substring(11, 23));
                engine = TraceLinkScenarioCheckpoints.restore(last, clock, 128);
                persist(); // Persist restart interruption before accepting HTTP work.
            }
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    public Command validate(String body) throws IOException {
        // The producer owns defaults. The public interface may omit the whole parameters object.
        JsonNode parsed = json.readTree(body);
        if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Expected command object");
        if (parsed instanceof ObjectNode object && !object.has("parameters"))
            object.set("parameters", json.valueToTree(Parameters.defaults()));
        return codec.read(parsed.toString());
    }

    public ObjectNode catalogue() throws IOException {
        var inventory = new TraceLinkCatalog();
        var catalogue = new TraceLinkScenarioCatalogue();
        var result = json.createObjectNode().put("enabled", true).put("synthetic", true)
                .put("version", VERSION).put("topologyVersion", TOPOLOGY)
                .put("sourceNamespace", TraceLinkCatalog.NAMESPACE).put("deliveryStatus", "DISABLED_PENDING_REVIEW");
        result.set("definitions", json.valueToTree(catalogue.definitions()));
        for (var definition : result.path("definitions"))
            ((ObjectNode)definition.path("parameterRanges").path("seed")).put("maximum", 9007199254740991L);
        result.set("defaults", json.valueToTree(Parameters.defaults()));
        var scopes = result.putArray("scopes");
        for (var link : inventory.topology().path("links")) for (var direction : link.path("directions")) {
            var entry = scopes.addObject();
            entry.set("scope", json.valueToTree(Scope.transport(link.path("linkId").asText(), direction.path("directionId").asText())));
            entry.put("label", direction.path("sourceReferenceCityId").asText() + " → " + direction.path("destinationReferenceCityId").asText());
            entry.put("sourceCity", direction.path("sourceReferenceCityId").asText());
            entry.put("destinationCity", direction.path("destinationReferenceCityId").asText());
            entry.set("nominalCapacityGbps", link.path("nominalCapacityGbps"));
        }
        for (String country : List.of("RO","IT","DE","FR","GB","ES","UA","TR","BG","PL"))
            for (String direction : List.of("in", "out")) {
                var entry = scopes.addObject().put("label", country + " / " + country + "-DEMO-01 / " + direction + " / REGISTRATION");
                entry.set("scope", json.valueToTree(Scope.roaming(country + "-DEMO-01", country, direction)));
            }
        for (var city : inventory.geography().path("cities")) {
            var entry = scopes.addObject().put("label", city.path("displayName").asText(city.path("referenceCityId").asText()) + " / LTE / DNS");
            entry.set("scope", json.valueToTree(Scope.data(city.path("referenceCityId").asText())));
        }
        return result;
    }

    public synchronized ObjectNode start(String id, String body) throws IOException {
        healthy();
        var snapshot = engine.start(id, validate(body));
        persist();
        return response(snapshot);
    }
    public synchronized ObjectNode status(String id) throws IOException { healthy(); return response(engine.status(id)); }
    public synchronized ObjectNode stop(String id) throws IOException {
        healthy(); var snapshot = engine.stop(id); persist(); return response(snapshot);
    }
    public synchronized void tick() throws IOException {
        healthy();
        var before = engine.checkpoint();
        engine.advance();
        if (!before.equals(engine.checkpoint())) persist();
    }
    private ObjectNode response(TraceLinkScenarioEngine.Snapshot snapshot) {
        var result = json.createObjectNode().put("fingerprint", snapshot.runId()).put("status", snapshot.status().name())
                .put("completedWindows", snapshot.history().size())
                .put("generatedObservations", snapshot.history().stream().mapToInt(w -> w.observations().size()).sum())
                .put("deliveryStatus", "DISABLED_PENDING_REVIEW").put("offered", 0).put("acknowledged", 0)
                .putNull("persisted").putNull("accepted");
        result.set("command", codec.write(snapshot.command()));
        // Completed local history is inspectable, never labelled accepted telemetry.
        var history = result.putArray("history");
        for (var window : snapshot.history()) {
            var item = history.addObject().put("start", window.start().toString());
            var observations = item.putArray("observations");
            window.observations().forEach(observations::add);
        }
        return result;
    }
    private void persist() throws IOException {
        try {
            if (sequence >= 999999999999L) throw new IOException("Checkpoint sequence exhausted");
            TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock,
                    directory.resolve(String.format(Locale.ROOT, "checkpoint-%012d.json", ++sequence)));
        } catch (IOException | RuntimeException error) {
            failed = true; // Never expose uncommitted in-memory state or continue generating after storage failure.
            throw error;
        }
    }
    private void healthy() throws IOException { if (failed) throw new IOException("Runtime checkpoint unavailable; restart and reconcile history"); }
    @Override public synchronized void close() throws IOException { lock.release(); channel.close(); }
}
