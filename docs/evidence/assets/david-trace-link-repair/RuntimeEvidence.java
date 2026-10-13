import java.nio.file.*;
import java.time.*;
import md.utm.telecom.generator.tracecontrol.TraceLinkRuntime;

class RuntimeEvidence {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T08:59:59Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    public static void main(String[] args) throws Exception {
        var clock = new MutableClock();
        Path directory = Path.of(args[1]);
        if (Files.exists(directory)) throw new IllegalStateException("Evidence directory already exists; no overwrite");
        String body = Files.readString(Path.of(args[0]));
        String id;
        String retained;
        try (var runtime = new TraceLinkRuntime(clock, directory)) {
            id = runtime.validate(body).runId();
            runtime.start(id, body);
            clock.now = Instant.parse("2026-10-13T09:03:00Z");
            runtime.tick();
            var state = runtime.status(id);
            if (state.path("completedWindows").asInt() != 3) throw new AssertionError("Expected three completed local windows");
            retained = state.path("history").toString();
        }
        try (var restored = new TraceLinkRuntime(clock, directory)) {
            var state = restored.status(id);
            if (!state.path("status").asText().equals("INTERRUPTED") || !state.path("history").toString().equals(retained))
                throw new AssertionError("Restart changed retained local history");
            restored.tick();
            if (!restored.status(id).equals(state)) throw new AssertionError("Interrupted run replayed");
            Files.writeString(directory.resolve("restart-result.json"), state.toPrettyString() + "\n", StandardOpenOption.CREATE_NEW);
            System.out.println("PASS actual runtime restart: INTERRUPTED, 3 completed local windows, identical history, no replay, delivery disabled; fingerprint=" + id);
        }
    }
}
