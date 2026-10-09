package md.utm.telecom.simulator.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.simulator.model.ScenarioCommand;
import md.utm.telecom.simulator.model.ScenarioStatus;
import md.utm.telecom.simulator.model.ScenarioType;
import tools.jackson.databind.ObjectMapper;

final class GeneratorScenarioClient {
    record Command(ScenarioType scenarioType, String scopeId, long seed,
                   String scheduledStartAt, String scheduledEndAt) {}
    record Snapshot(UUID runId, ScenarioType scenarioType, String scopeId, long seed,
                    Instant scheduledStartAt, Instant scheduledEndAt,
                    ScenarioStatus status, int publishedWindows, String failureCode) {}
    record ErrorBody(String code, String message) {}

    static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        Failure(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
        int status() { return status; }
        String code() { return code; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final URI base;
    private final ObjectMapper json;

    GeneratorScenarioClient(String baseUrl, ObjectMapper json) {
        this.base = URI.create(baseUrl);
        this.json = json;
    }

    Snapshot start(ScenarioCommand command) {
        Command body = new Command(command.getScenarioType(), command.getScopeId(),
                command.getSeed(), command.getScheduledStartAt().toString(),
                command.getScheduledEndAt().toString());
        return call("PUT", "/internal/scenario-runs/" + command.getRunId(), body, 1);
    }

    // A saved command may have been accepted before an uncertain response or
    // lost by a generator restart. PUT is idempotent for the same runId/body.
    Snapshot redeliver(ScenarioCommand command) {
        Command body = new Command(command.getScenarioType(), command.getScopeId(),
                command.getSeed(), command.getScheduledStartAt().toString(),
                command.getScheduledEndAt().toString());
        return call("PUT", "/internal/scenario-runs/" + command.getRunId(), body, 2);
    }

    Snapshot status(UUID runId) {
        return call("GET", "/internal/scenario-runs/" + runId, null, 2);
    }

    Snapshot stop(UUID runId) {
        return call("POST", "/internal/scenario-runs/" + runId + "/stop", null, 1);
    }

    private Snapshot call(String method, String path, Object body, int attempts) {
        for (int attempt = 1; ; attempt++) {
            try {
                return callOnce(method, path, body);
            } catch (Failure failure) {
                if (attempt >= attempts || failure.status() < 500
                        || "GENERATOR_CONTRACT_MISMATCH".equals(failure.code())) throw failure;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Failure(503, "GENERATOR_UNAVAILABLE");
                }
            }
        }
    }

    private Snapshot callOnce(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                    .timeout(Duration.ofSeconds(5));
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body)));
            }
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    Snapshot snapshot = json.readValue(response.body(), Snapshot.class);
                    if (snapshot == null || snapshot.runId() == null
                            || snapshot.status() == null
                            || snapshot.publishedWindows() < 0
                            || snapshot.publishedWindows() > 8
                            || (snapshot.failureCode() != null
                            && !"PUBLISH_FAILED".equals(snapshot.failureCode()))) {
                        throw new Failure(503, "GENERATOR_CONTRACT_MISMATCH");
                    }
                    return snapshot;
                } catch (RuntimeException invalidReply) {
                    throw new Failure(503, "GENERATOR_CONTRACT_MISMATCH");
                }
            }
            String code = "GENERATOR_UNAVAILABLE";
            try {
                ErrorBody error = json.readValue(response.body(), ErrorBody.class);
                code = safeCode(error.code());
            } catch (RuntimeException ignored) {
                // The upstream body is not a safe public error contract.
            }
            throw new Failure(response.statusCode(), code);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "GENERATOR_UNAVAILABLE");
        } catch (IOException error) {
            throw new Failure(503, "GENERATOR_UNAVAILABLE");
        }
    }

    private static String safeCode(String code) {
        if (code == null) return "GENERATOR_UNAVAILABLE";
        return switch (code) {
            case "INVALID_COMMAND", "INVALID_SEED", "INVALID_SCHEDULE",
                    "UNSUPPORTED_SCENARIO", "INVALID_SCOPE", "RUN_CONFLICT",
                    "SCOPE_WINDOW_CONFLICT", "SCHEDULE_ALREADY_STARTED",
                    "RUN_TERMINAL", "RUN_NOT_FOUND", "SCHEDULING_FAILED" -> code;
            default -> "GENERATOR_UNAVAILABLE";
        };
    }
}
