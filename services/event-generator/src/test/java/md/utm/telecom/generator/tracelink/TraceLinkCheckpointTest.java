package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import md.utm.telecom.observation.DomainObservationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioEngine.Status.*;
import static md.utm.telecom.generator.tracelink.TraceLinkTransportScenarioTest.*;

class TraceLinkCheckpointTest {
    @TempDir Path output;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest @ValueSource(ints = {0, 3, 6})
    void stopBeforeFirstDuringFaultAndDuringRecoverySurvivesDiskRestart(int minutes) throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.TRANSPORT_LINK_OVERLOAD, Scope.transport("chi-hin", "a-to-b")); engine.start(c);
        if (minutes > 0) { clock.now = START.plusSeconds(minutes * 60L); engine.advance(); }
        var stopped = engine.stop(c.runId()); var path = output.resolve("stopped.json");
        assertTrue(TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path));
        clock.now = c.end(); var restored = TraceLinkScenarioCheckpoints.restore(path, clock, 8);
        assertEquals(stopped, restored.start(c)); assertEquals(STOPPED, restored.status(c.runId()).status());
        assertEquals(minutes, restored.status(c.runId()).history().size()); assertTrue(restored.advance().isEmpty());
        assertEquals("UNKNOWN_NOT_TRACKED", restored.status(c.runId()).publicationStatus());
        assertThrows(IllegalArgumentException.class, () -> restored.start(command(Type.NORMAL_CONTROL, c.scope())));
    }

    @ParameterizedTest @ValueSource(ints = {0, 3, 6})
    void unfinishedCheckpointCannotReplayOrInventRecovery(int minutes) throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01", "RO", "out")); engine.start(c);
        if (minutes > 0) { clock.now = START.plusSeconds(minutes * 60L); engine.advance(); }
        var before = engine.status(c.runId()).history(); var path = output.resolve("unfinished.json");
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path);
        clock.now = c.end(); var restored = TraceLinkScenarioCheckpoints.restore(path, clock, 8);
        assertEquals(INTERRUPTED, restored.start(c).status()); assertEquals(before, restored.status(c.runId()).history());
        assertTrue(restored.advance().isEmpty()); assertEquals(before, restored.status(c.runId()).history());
    }

    @Test void completedRetryAndImmutableCheckpointPreserveBytesAndIdentities() throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.MOBILE_DATA_DNS_FAILURE, Scope.data("chi")); engine.start(c); clock.now = c.end(); engine.advance();
        var path = output.resolve("complete.json"); TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path);
        var bytes = Files.readAllBytes(path); var modified = Files.getLastModifiedTime(path);
        assertFalse(TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path));
        assertArrayEquals(bytes, Files.readAllBytes(path)); assertEquals(modified, Files.getLastModifiedTime(path));
        var restored = TraceLinkScenarioCheckpoints.restore(path, clock, 8);
        assertEquals(engine.status(c.runId()), restored.start(c)); assertEquals(COMPLETED, restored.start(c).status());
        assertTrue(restored.advance().isEmpty());
        var changed = new Command(c.type(), c.version(), c.scope(), 43, c.start(), c.parameters());
        assertThrows(IllegalArgumentException.class, () -> restored.start(c.runId(), changed));
    }

    @Test void newerCheckpointRequiresNewPathAndCannotOverwriteInvestigationEvidence() throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.NORMAL_CONTROL, Scope.data("chi")); engine.start(c); var path = output.resolve("first.json");
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path); var bytes = Files.readAllBytes(path);
        clock.now = START.plusSeconds(60); engine.advance();
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertTrue(TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, output.resolve("second.json")));
        try (var files = Files.list(output)) { assertEquals(2, files.count()); }
    }

    @Test void corruptedChecksumHistoryIdentityAndUnknownStatusFailClosed() throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.NORMAL_CONTROL, Scope.data("chi")); engine.start(c); var path = output.resolve("source.json");
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path); var original = (ObjectNode) json.readTree(Files.readString(path));
        for (String control : List.of("checksum", "identity", "status", "publication", "missing-publication", "history")) {
            var copy = original.deepCopy(); var run = (ObjectNode) copy.path("runs").get(0);
            switch (control) {
                case "identity" -> run.put("runId", "a".repeat(64));
                case "status" -> run.put("status", "ACKNOWLEDGED");
                case "publication" -> copy.put("publicationStatus", "ACKNOWLEDGED");
                case "missing-publication" -> copy.remove("publicationStatus");
                case "history" -> run.putArray("history").addObject().put("start", START.toString()).putArray("observations");
                default -> run.put("status", "COMPLETED");
            }
            if (!control.equals("checksum")) copy.put("contentSha256", DomainObservationValidator.sha256(copy.path("runs").toString()));
            var corrupt = output.resolve(control + ".json"); Files.writeString(corrupt, copy.toString());
            assertThrows(Exception.class, () -> TraceLinkScenarioCheckpoints.restore(corrupt, clock, 8), control);
        }
        assertEquals(SCHEDULED, engine.status(c.runId()).status());
    }

    @Test void fileAndCapacityBoundsRejectBeforeRestore() throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        engine.start(command(Type.NORMAL_CONTROL, Scope.data("chi"))); var path = output.resolve("one.json");
        TraceLinkScenarioCheckpoints.write(engine.checkpoint(), clock, path);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioCheckpoints.restore(path, clock, 0));
        Files.write(path, new byte[TraceLinkScenarioCheckpoints.MAX_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> TraceLinkScenarioCheckpoints.restore(path, clock, 8));
    }

    @Test void concurrentExactCommandsAndArtifactWritersProduceOneObservableCopy() throws Exception {
        var clock = new TraceLinkScenarioEngineTest.TestClock(); var engine = new TraceLinkScenarioEngine(clock, 8);
        var c = command(Type.NORMAL_CONTROL, Scope.data("chi"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2); var starts = new ArrayList<Future<TraceLinkScenarioEngine.Snapshot>>();
            for (int i = 0; i < 2; i++) starts.add(pool.submit(() -> { barrier.await(); return engine.start(c); }));
            assertEquals(starts.get(0).get(10, TimeUnit.SECONDS), starts.get(1).get(10, TimeUnit.SECONDS));
            clock.now = c.end(); var windows = engine.advance(); assertEquals(8, windows.size()); assertTrue(engine.advance().isEmpty());
            var path = output.resolve("race.jsonl"); var writers = new ArrayList<Future<Boolean>>(); var writeBarrier = new CyclicBarrier(2);
            for (int i = 0; i < 2; i++) writers.add(pool.submit(() -> { writeBarrier.await(); return TraceLinkScenarioArtifacts.write(windows, path); }));
            assertNotEquals(writers.get(0).get(10, TimeUnit.SECONDS), writers.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(16, Files.readAllLines(path).size()); assertEquals(1, engine.checkpoint().size());
        }
    }

    @Test void recoveryGapRemainsMissingUntilFreshMeasuredWindow() throws Exception {
        var c = command(Type.ROAMING_REGISTRATION_FAILURE, Scope.roaming("RO-DEMO-01", "RO", "out"));
        var p = c.parameters();
        c = new Command(c.type(), c.version(), c.scope(), c.seed(), c.start(), new Parameters(p.normalUtilizationBp(),
                p.degradedUtilizationBp(), p.capacityGbps(), p.attempts(), p.healthySuccessBp(), p.degradedSuccessBp(), Set.of(5,6)));
        var windows = new TraceLinkFixtureGenerator().generate(c);
        for (int i : List.of(5,6)) {
            var e = json.readTree(windows.get(i).observations().getFirst());
            assertEquals("MISSING", e.path("quality").asText()); assertEquals(0, e.path("coverage").asInt());
            e.path("primitives").forEach(v -> assertTrue(v.isNull()));
        }
        var measured = json.readTree(windows.get(7).observations().getFirst());
        assertEquals("VALID", measured.path("quality").asText()); assertTrue(measured.path("primitives").path("denominator").asLong() > 0);
        assertEquals(START.plusSeconds(420).toString(), measured.path("windowStart").asText());
        assertNotEquals(json.readTree(windows.get(4).observations().getFirst()).path("eventId"), measured.path("eventId"));
    }
}
