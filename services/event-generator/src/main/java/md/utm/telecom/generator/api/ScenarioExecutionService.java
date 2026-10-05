package md.utm.telecom.generator.api;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.observation.TopologyCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** Process-local execution only. The incident service owns the durable command ledger. */
@Service
public class ScenarioExecutionService {
    public record Command(String scenarioType, String scopeId, Long seed,
                          Instant scheduledStartAt, Instant scheduledEndAt) {}
    public record Snapshot(UUID runId, String scenarioType, String scopeId, long seed,
                           Instant scheduledStartAt, Instant scheduledEndAt,
                           String status, int publishedWindows, String failureCode) {}

    public static final class ApiFailure extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        ApiFailure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }

    private static final String TOPIC = "telecom.observations.v2";
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final KafkaTemplate<String, String> kafka;
    private final VoiceScenario voice;
    private final SmsQueueScenario sms;
    private final TopologyCatalog topology;

    public ScenarioExecutionService(@Qualifier("clock") Clock clock, @Qualifier("scenarioTaskScheduler") TaskScheduler scheduler,
            KafkaTemplate<String, String> kafka, VoiceScenario voice, SmsQueueScenario sms,
            TopologyCatalog topology) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.kafka = kafka;
        this.voice = voice;
        this.sms = sms;
        this.topology = topology;
    }

    /** The entire check/create/schedule sequence is atomic across concurrent HTTP deliveries. */
    public synchronized Snapshot start(UUID runId, Command command) {
        Objects.requireNonNull(runId, "runId");
        Run existing = runs.get(runId);
        if (existing != null) {
            if (!existing.command.equals(command)) {
                throw new ApiFailure(HttpStatus.CONFLICT, "RUN_CONFLICT",
                        "runId already has different execution parameters");
            }
            return existing.snapshot();
        }
        validate(command);
        // Every accepted run reserves its authoritative scope and half-open
        // schedule, including after STOP/FAILED/COMPLETED. This check shares
        // start()'s lock with insertion, so concurrent different IDs cannot win.
        for (Run reserved : runs.values()) {
            Command other = reserved.command;
            if (other.scopeId().equals(command.scopeId())
                    && command.scheduledStartAt().isBefore(other.scheduledEndAt())
                    && other.scheduledStartAt().isBefore(command.scheduledEndAt())) {
                throw new ApiFailure(HttpStatus.CONFLICT, "SCOPE_WINDOW_CONFLICT",
                        "Another run reserves an overlapping interval for this scope");
            }
        }
        // A new delivery after the scheduled start could be a restart of a run
        // that already published. Never replay it from minute zero.
        if (!clock.instant().isBefore(command.scheduledStartAt())) {
            throw new ApiFailure(HttpStatus.CONFLICT, "SCHEDULE_ALREADY_STARTED",
                    "Unknown run cannot start at or after its scheduled start; reconcile as interrupted");
        }
        List<List<String>> windows = generate(command);
        if (windows.size() != 8) throw new IllegalStateException("Expected eight scenario windows");
        Run run = new Run(runId, command, windows);
        synchronized (run) {
            try {
                run.futures.add(requireSchedule(scheduler.schedule(() -> begin(run), command.scheduledStartAt())));
                for (int minute = 0; minute < 8; minute++) {
                    int index = minute;
                    Instant end = command.scheduledStartAt().plus(minute + 1L, ChronoUnit.MINUTES);
                    run.futures.add(requireSchedule(scheduler.schedule(() -> publish(run, index), end)));
                }
                if (!clock.instant().isBefore(command.scheduledStartAt())) {
                    run.status = "FAILED";
                    run.cancel();
                    throw new ApiFailure(HttpStatus.CONFLICT, "SCHEDULE_ALREADY_STARTED",
                            "Schedule passed before execution could be installed");
                }
                runs.put(runId, run);
                return run.snapshot();
            } catch (ApiFailure failure) {
                run.status = "FAILED";
                run.cancel();
                throw failure;
            } catch (RuntimeException failure) {
                run.status = "FAILED";
                run.cancel();
                throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "SCHEDULING_FAILED",
                        "Execution could not be scheduled");
            }
        }
    }

    public synchronized Snapshot status(UUID runId) {
        return requireRun(runId).snapshot();
    }

    /** Terminal runs retain ownership: stopping a simulation is not measured recovery. */
    public synchronized boolean reserves(String scopeId, Instant windowStart) {
        return runs.values().stream().anyMatch(run -> run.command.scopeId().equals(scopeId)
                && !windowStart.isBefore(run.command.scheduledStartAt())
                && windowStart.isBefore(run.command.scheduledEndAt()));
    }

    public synchronized Snapshot stop(UUID runId) {
        Run run = requireRun(runId);
        synchronized (run) {
            if (run.status.equals("COMPLETED") || run.status.equals("FAILED")) {
                throw new ApiFailure(HttpStatus.CONFLICT, "RUN_TERMINAL",
                        "Completed or failed run cannot be stopped");
            }
            if (!run.status.equals("STOPPED")) {
                run.status = "STOPPED";
                run.cancel();
            }
            return run.snapshot();
        }
    }

    private Run requireRun(UUID runId) {
        Run run = runs.get(runId);
        if (run == null) {
            throw new ApiFailure(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND",
                    "Run is unknown in this generator process; a previously dispatched run is interrupted");
        }
        return run;
    }

    private void validate(Command command) {
        if (command == null || command.scenarioType() == null || command.scopeId() == null
                || command.seed() == null || command.scheduledStartAt() == null
                || command.scheduledEndAt() == null) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_COMMAND", "All execution fields are required");
        }
        if (command.seed() < 0) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SEED", "Seed must be nonnegative");
        }
        if (!command.scheduledStartAt().equals(command.scheduledStartAt().truncatedTo(ChronoUnit.MINUTES))
                || !command.scheduledEndAt().equals(command.scheduledStartAt().plus(8, ChronoUnit.MINUTES))) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE",
                    "Schedule must start on a UTC minute and last exactly eight minutes");
        }
        if (!List.of("VOLTE_IMS_OVERLOAD", "SMS_QUEUE_DELAY", "NORMAL_CONTROL", "TELEMETRY_GAP")
                .contains(command.scenarioType())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SCENARIO", "Unsupported scenario type");
        }
        TopologyCatalog.Scope scope;
        // Geographic scenario targeting is Day 3. Never publish legacy payloads under a city Kafka key.
        if (!java.util.List.of("VOLTE-MD-CENTRAL", "SMS-MD-ROUTE-A").contains(command.scopeId()))
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "Scenario targeting requires a legacy scope");
        try { scope = topology.requireScope(command.scopeId()); }
        catch (IllegalArgumentException invalid) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "Unknown scenario scope");
        }
        if (command.scenarioType().equals("VOLTE_IMS_OVERLOAD") && !scope.service().equals("VOLTE")
                || command.scenarioType().equals("SMS_QUEUE_DELAY") && !scope.service().equals("SMS")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "Scenario and scope service differ");
        }
    }

    private List<List<String>> generate(Command command) {
        var start = command.scheduledStartAt();
        var seed = command.seed();
        return switch (command.scenarioType()) {
            case "VOLTE_IMS_OVERLOAD" -> voice.generateWindows(start, seed, VoiceScenario.Profile.VOLTE_IMS_OVERLOAD);
            case "SMS_QUEUE_DELAY" -> sms.generateWindows(start, seed);
            case "NORMAL_CONTROL" -> topology.requireScope(command.scopeId()).service().equals("VOLTE")
                    ? voice.generateWindows(start, seed, VoiceScenario.Profile.NORMAL_CONTROL)
                    : sms.generateHealthyWindows(start, seed);
            case "TELEMETRY_GAP" -> topology.requireScope(command.scopeId()).service().equals("VOLTE")
                    ? voice.generateWindows(start, seed, VoiceScenario.Profile.TELEMETRY_GAP)
                    : sms.generateTelemetryGapWindows(start, seed);
            default -> throw new IllegalStateException("Validated scenario missing implementation");
        };
    }

    private static ScheduledFuture<?> requireSchedule(ScheduledFuture<?> future) {
        if (future == null) throw new IllegalStateException("Scheduler returned no future");
        return future;
    }

    private void begin(Run run) {
        synchronized (run) {
            if (run.status.equals("SCHEDULED")) run.status = "RUNNING";
        }
    }

    private void publish(Run run, int minute) {
        synchronized (run) {
            if (run.status.equals("STOPPED") || run.status.equals("FAILED")) return;
            if (run.status.equals("SCHEDULED")) run.status = "RUNNING";
            try {
                for (String observation : run.windows.get(minute)) {
                    kafka.send(TOPIC, run.command.scopeId(), observation).get(15, TimeUnit.SECONDS);
                }
                run.publishedWindows++;
                if (run.publishedWindows == 8) run.status = "COMPLETED";
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                run.status = "FAILED";
                // Never expose broker exceptions, credentials or stack traces through status.
                run.failureCode = "PUBLISH_FAILED";
                run.cancel();
            }
        }
    }

    private static final class Run {
        final UUID id;
        final Command command;
        final List<List<String>> windows;
        final List<ScheduledFuture<?>> futures = new ArrayList<>();
        String status = "SCHEDULED";
        int publishedWindows;
        String failureCode;
        Run(UUID id, Command command, List<List<String>> windows) {
            this.id = id;
            this.command = command;
            this.windows = windows;
        }
        synchronized Snapshot snapshot() {
            return new Snapshot(id, command.scenarioType(), command.scopeId(), command.seed(),
                    command.scheduledStartAt(), command.scheduledEndAt(), status, publishedWindows, failureCode);
        }
        void cancel() { futures.forEach(future -> future.cancel(false)); }
    }
}
