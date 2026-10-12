package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.TraceLinkSyntheticSource;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

/** Bounded pure scheduler. Release means local output, never Kafka publication/acknowledgement. */
public final class TraceLinkScenarioEngine {
    public enum Status { SCHEDULED, RUNNING, COMPLETED, STOPPED, INTERRUPTED }
    public record Snapshot(String runId, Command command, Status status, List<TraceLinkFixtureGenerator.Window> history,
                           List<String> expectedWindowIdentities) {
        public Snapshot { history = List.copyOf(history); expectedWindowIdentities = List.copyOf(expectedWindowIdentities); }
        /** Generation history cannot establish whether an external system received a window. */
        public String publicationStatus() { return "UNKNOWN_NOT_TRACKED"; }
    }
    private static final class Run {
        final String id; final Command command; final List<TraceLinkFixtureGenerator.Window> expected;
        final List<TraceLinkFixtureGenerator.Window> history = new ArrayList<>(); Status status;
        Run(String id, Command command, List<TraceLinkFixtureGenerator.Window> expected, Status status) {
            this.id = id; this.command = command; this.expected = expected; this.status = status;
        }
        Snapshot snapshot() { return new Snapshot(id, command, status, history, expected.stream().map(TraceLinkFixtureGenerator.Window::identity).toList()); }
    }
    private final Clock clock;
    private final int capacity;
    private final Map<String, Run> runs = new TreeMap<>();
    private final TraceLinkFixtureGenerator generator;
    private final TraceLinkScenarioCatalogue catalogue;
    private final ObjectMapper json = new ObjectMapper();
    public TraceLinkScenarioEngine(Clock clock, int capacity) throws IOException {
        this.clock = Objects.requireNonNull(clock); require(capacity > 0 && capacity <= 128, "Capacity must be 1..128 retained runs");
        this.capacity = capacity; generator = new TraceLinkFixtureGenerator(); catalogue = new TraceLinkScenarioCatalogue();
    }
    public synchronized Snapshot start(Command command) throws IOException { return start(command.runId(), command); }
    public synchronized Snapshot start(String runId, Command command) throws IOException {
        require(runId != null && runId.matches("[0-9a-f]{64}"), "Invalid run identity"); catalogue.validate(command);
        require(runId.equals(command.runId()), "RUN_IDENTITY_CONFLICT");
        var existing = runs.get(runId);
        if (existing != null) { require(existing.command.equals(command), "RUN_CONFLICT"); return existing.snapshot(); }
        require(runs.size() < capacity, "Retained run capacity reached");
        reserve(command);
        // Unknown past executions may already have emitted data. Never catch them up from minute zero.
        var run = new Run(runId, command, generator.generate(command), clock.instant().isBefore(command.start()) ? Status.SCHEDULED : Status.INTERRUPTED);
        runs.put(runId, run); return run.snapshot();
    }
    private void reserve(Command command) {
        for (var r : runs.values()) require(!r.command.scope().key().equals(command.scope().key())
                || !command.start().isBefore(r.command.end()) || !r.command.start().isBefore(command.end()), "SCOPE_WINDOW_CONFLICT");
    }
    /** Idempotent ticks expose only windows completed on the injected clock. No background thread. */
    public synchronized List<TraceLinkFixtureGenerator.Window> advance() throws IOException {
        var released = new ArrayList<TraceLinkFixtureGenerator.Window>();
        var source = new TraceLinkSyntheticSource(clock);
        for (var r : runs.values()) {
            if (r.status != Status.SCHEDULED && r.status != Status.RUNNING) continue;
            if (!clock.instant().isBefore(r.command.start())) r.status = Status.RUNNING;
            while (r.history.size() < 8 && !clock.instant().isBefore(r.expected.get(r.history.size()).start().plusSeconds(60))) {
                var w = r.expected.get(r.history.size());
                for (String payload : w.observations()) source.resolve(json.readTree(payload));
                r.history.add(w); released.add(w);
            }
            if (r.history.size() == 8) r.status = Status.COMPLETED;
        }
        return List.copyOf(released);
    }
    public synchronized Snapshot status(String runId) { return required(runId).snapshot(); }
    public synchronized Snapshot stop(String runId) {
        var r = required(runId);
        if (r.status == Status.SCHEDULED || r.status == Status.RUNNING) r.status = Status.STOPPED;
        return r.snapshot();
    }
    public synchronized List<Snapshot> checkpoint() { return runs.values().stream().map(Run::snapshot).toList(); }
    /** Caller owns checkpoint storage. Nonterminal restored runs are interrupted and cannot resume automatically. */
    public static TraceLinkScenarioEngine restore(Clock clock, int capacity, List<Snapshot> checkpoints) throws IOException {
        var engine = new TraceLinkScenarioEngine(clock, capacity);
        require(checkpoints.size() <= capacity, "Checkpoint capacity exceeded");
        for (var s : checkpoints) {
            require(!engine.runs.containsKey(s.runId()), "Duplicate checkpoint run");
            var created = engine.start(s.runId(), s.command()); var r = engine.required(s.runId());
            require(created.expectedWindowIdentities().equals(s.expectedWindowIdentities()) && s.history().size() <= 8
                    && r.expected.subList(0, s.history().size()).equals(s.history()), "Checkpoint payload conflict");
            require(s.status() != null && (s.status() == Status.COMPLETED) == (s.history().size() == 8), "Checkpoint status conflict");
            for (var w : s.history()) require(!clock.instant().isBefore(w.start().plusSeconds(60)), "Checkpoint history is in future");
            require(s.status() != Status.SCHEDULED || s.history().isEmpty(), "Scheduled checkpoint has history");
            r.history.addAll(s.history()); r.status = switch (s.status()) {
                case SCHEDULED, RUNNING -> Status.INTERRUPTED;
                default -> s.status();
            };
        }
        return engine;
    }
    private Run required(String id) { var r = runs.get(id); require(r != null, "UNKNOWN_RUN_INTERRUPTED: reconcile external history"); return r; }
}
