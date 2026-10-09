package md.utm.telecom.simulator.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import md.utm.telecom.analysts.model.Analyst;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.geography.GeographyCatalogue;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.simulator.model.ScenarioCommand;
import md.utm.telecom.simulator.model.ScenarioStatus;
import md.utm.telecom.simulator.model.ScenarioType;
import md.utm.telecom.simulator.repository.ScenarioCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScenarioCommandService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioCommandService.class);

    public record Run(UUID runId, ScenarioStatus status, Instant scheduledStartAt,
                      Instant scheduledEndAt, ScenarioType scenarioType, String scopeId) {}
    private record Saved(UUID runId, boolean fresh) {}
    private record Pending(ScenarioCommand command, boolean stop, boolean terminal) {}
    private record Result(Run run, WorkflowProblem problem) {
        Run get() {
            if (problem != null) throw problem;
            return run;
        }
    }

    private final ScenarioCommandRepository commands;
    private final AnalystRepository analysts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final GeneratorScenarioClient generator;
    private final Clock clock;
    private final GeographyCatalogue catalogue;

    public ScenarioCommandService(ScenarioCommandRepository commands,
            AnalystRepository analysts, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ObjectMapper json,
            Clock clock, GeographyCatalogue catalogue,
            @Value("${app.simulator.generator-base-url}") String generatorUrl) {
        this.commands = commands;
        this.analysts = analysts;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.generator = new GeneratorScenarioClient(generatorUrl, json);
        this.clock = clock;
        this.catalogue = catalogue;
    }

    public Run start(ScenarioType type, UUID requestId, long seed,
                     String scopeId, Authentication authentication) {
        validateCommand(type, requestId, seed, scopeId);
        String hash = bodyHash(type, scopeId, seed);
        Saved saved;
        try {
            saved = tx.execute(ignored -> {
                Analyst actor = actor(authentication);
                Optional<ScenarioCommand> prior = commands.findByRequestId(requestId);
                if (prior.isPresent()) return sameRequest(prior.orElseThrow(), hash, actor);
                // Serializes the empty-table check across application instances.
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT pg_advisory_xact_lock(hashtext(?))")) {
                        statement.setString(1, scopeId);
                        statement.execute();
                    }
                    return null;
                });
                prior = commands.findByRequestId(requestId);
                if (prior.isPresent()) return sameRequest(prior.orElseThrow(), hash, actor);

                // Use the minute after the scope lock, as before, so contention cannot
                // turn a valid scheduled start into a minute that has already passed.
                Instant startAt = clock.instant().truncatedTo(ChronoUnit.MINUTES)
                        .plus(1, ChronoUnit.MINUTES);
                validate(type, scopeId, startAt);
                Instant endAt = startAt.plus(8, ChronoUnit.MINUTES);
                if (commands.existsOverlapping(scopeId, startAt, endAt)) {
                    throw problem(HttpStatus.CONFLICT, "SCOPE_WINDOW_CONFLICT",
                            "A run already reserves this scope and time interval.");
                }
                ScenarioCommand command = commands.saveAndFlush(new ScenarioCommand(
                        requestId, hash, actor, type, scopeId, seed, startAt, endAt));
                return new Saved(command.getRunId(), true);
            });
        } catch (DataIntegrityViolationException duplicate) {
            // A simultaneous requestId insert lost the unique-key race.
            saved = tx.execute(ignored -> {
                ScenarioCommand prior = commands.findByRequestId(requestId)
                        .orElseThrow(() -> duplicate);
                return sameRequest(prior, hash, actor(authentication));
            });
        }
        // The insert transaction has committed before any private HTTP delivery.
        return reconcile(saved.runId(), saved.fresh()).get();
    }

    public Run status(UUID runId) {
        return reconcile(runId, false).get();
    }

    public Run stop(UUID runId) {
        Pending pending = tx.execute(ignored -> {
            ScenarioCommand command = locked(runId);
            if (command.getStatus() == ScenarioStatus.STOPPED) return new Pending(command, true, true);
            if (command.getStatus() == ScenarioStatus.COMPLETED
                    || command.getStatus() == ScenarioStatus.FAILED) {
                throw problem(HttpStatus.CONFLICT, "RUN_TERMINAL", "This run has already ended.");
            }
            command.requestStop(clock.instant());
            return new Pending(command, true, false);
        });
        if (pending.terminal()) return ok(pending.command()).get();
        return stopRemote(runId).get();
    }

    @Scheduled(fixedDelayString = "${app.simulator.reconcile-ms:5000}")
    public void reconcilePending() {
        List<UUID> ids = commands.findByStatusInOrderByScheduledStartAtAscRunIdAsc(
                List.of(ScenarioStatus.SCHEDULED, ScenarioStatus.RUNNING),
                PageRequest.of(0, 100)).stream().map(ScenarioCommand::getRunId).toList();
        for (UUID id : ids) {
            try {
                reconcile(id, false);
            } catch (RuntimeException failure) {
                // A later scan retries; one broken run must not block other runs.
                log.warn("Scenario reconciliation failed for run {}", id, failure);
            }
        }
    }

    private Result reconcile(UUID runId, boolean fresh) {
        Pending pending = tx.execute(ignored -> {
            ScenarioCommand command = locked(runId);
            if (terminal(command.getStatus())) return new Pending(command, false, true);
            if (command.getStopRequestedAt() != null) return new Pending(command, true, false);
            if (fresh) command.recordDispatchAttempt(clock.instant(), null);
            return new Pending(command, false, false);
        });
        if (pending.terminal()) return ok(pending.command());
        if (pending.stop()) return stopRemote(runId);
        try {
            GeneratorScenarioClient.Snapshot snapshot;
            if (fresh) {
                snapshot = generator.start(pending.command());
            } else {
                try {
                    snapshot = generator.status(runId);
                } catch (GeneratorScenarioClient.Failure missing) {
                    if (missing.status() != 404
                            || !"RUN_NOT_FOUND".equals(missing.code())) throw missing;
                    if (!clock.instant().isBefore(pending.command().getScheduledStartAt())) {
                        return tx.execute(ignored -> {
                            ScenarioCommand command = locked(runId);
                            if (terminal(command.getStatus())) return ok(command);
                            if (command.getStopRequestedAt() != null) return ok(command);
                            command.setStatus(ScenarioStatus.FAILED);
                            command.markDispatchError("GENERATOR_INTERRUPTED");
                            return ok(command);
                        });
                    }
                    ScenarioCommand redelivery = tx.execute(ignored -> {
                        ScenarioCommand command = locked(runId);
                        if (terminal(command.getStatus()) || command.getStopRequestedAt() != null) return null;
                        command.recordDispatchAttempt(clock.instant(), null);
                        return command;
                    });
                    if (redelivery == null) return reconcile(runId, false);
                    snapshot = generator.redeliver(redelivery); // Same runId, same saved schedule.
                }
            }
            GeneratorScenarioClient.Snapshot accepted = snapshot;
            return tx.execute(ignored -> {
                ScenarioCommand command = locked(runId);
                if (terminal(command.getStatus())) return ok(command);
                if (command.getStopRequestedAt() != null && accepted.status() != ScenarioStatus.STOPPED
                        && accepted.status() != ScenarioStatus.COMPLETED) return ok(command);
                if (command.getStatus() == ScenarioStatus.RUNNING
                        && accepted.status() == ScenarioStatus.SCHEDULED) return ok(command);
                return accept(command, accepted);
            });
        } catch (GeneratorScenarioClient.Failure failure) {
            log.warn("Generator reconciliation failed for run {}: upstream status {}, code {}",
                    runId, failure.status(), failure.code());
            return tx.execute(ignored -> {
                ScenarioCommand command = locked(runId);
                if (terminal(command.getStatus())) return ok(command);
                command.markDispatchError(failure.code());
                if (failure.status() == 409) {
                    command.setStatus(ScenarioStatus.FAILED);
                    if ("SCOPE_WINDOW_CONFLICT".equals(failure.code())) {
                        return error(HttpStatus.CONFLICT, failure.code(),
                                "The generator rejected an overlapping run.");
                    }
                }
                return error(HttpStatus.SERVICE_UNAVAILABLE, "GENERATOR_UNAVAILABLE",
                        "The generator could not confirm this run. Retry with the same requestId.");
            });
        }
    }

    private Result stopRemote(UUID runId) {
        try {
            GeneratorScenarioClient.Snapshot snapshot = generator.stop(runId);
            return tx.execute(ignored -> {
                ScenarioCommand command = locked(runId);
                if (terminal(command.getStatus())) return ok(command);
                return accept(command, snapshot);
            });
        } catch (GeneratorScenarioClient.Failure failure) {
            if (failure.status() == 404 && "RUN_NOT_FOUND".equals(failure.code())) {
                return tx.execute(ignored -> {
                    ScenarioCommand command = locked(runId);
                    if (terminal(command.getStatus())) return ok(command);
                    if (clock.instant().isBefore(command.getScheduledStartAt())) {
                        command.setStatus(ScenarioStatus.STOPPED);
                        command.markDispatchError(null);
                    } else {
                        command.setStatus(ScenarioStatus.FAILED);
                        command.markDispatchError("GENERATOR_INTERRUPTED");
                    }
                    return ok(command);
                });
            }
            if (failure.status() == 409 && "RUN_TERMINAL".equals(failure.code())) {
                try {
                    GeneratorScenarioClient.Snapshot snapshot = generator.status(runId);
                    tx.executeWithoutResult(ignored -> {
                        ScenarioCommand command = locked(runId);
                        if (!terminal(command.getStatus())) accept(command, snapshot);
                    });
                } catch (GeneratorScenarioClient.Failure refreshFailure) {
                    tx.executeWithoutResult(ignored -> locked(runId).markDispatchError(refreshFailure.code()));
                }
                return error(HttpStatus.CONFLICT, "RUN_TERMINAL",
                        "This run has already ended.");
            }
            tx.executeWithoutResult(ignored -> locked(runId).markDispatchError(failure.code()));
            return error(HttpStatus.SERVICE_UNAVAILABLE, "GENERATOR_UNAVAILABLE",
                    "The generator could not confirm the stop. Retry the same stop request.");
        }
    }

    private Result accept(ScenarioCommand command,
                          GeneratorScenarioClient.Snapshot snapshot) {
        if (!command.getRunId().equals(snapshot.runId())
                || command.getScenarioType() != snapshot.scenarioType()
                || !command.getScopeId().equals(snapshot.scopeId())
                || command.getSeed() != snapshot.seed()
                || !command.getScheduledStartAt().equals(snapshot.scheduledStartAt())
                || !command.getScheduledEndAt().equals(snapshot.scheduledEndAt())) {
            command.markDispatchError("GENERATOR_CONTRACT_MISMATCH");
            return error(HttpStatus.SERVICE_UNAVAILABLE, "GENERATOR_UNAVAILABLE",
                    "The generator returned an inconsistent run.");
        }
        command.setStatus(snapshot.status());
        command.markDispatchError(snapshot.failureCode());
        return ok(command);
    }

    private ScenarioCommand locked(UUID runId) {
        return commands.findByIdForUpdate(runId).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run not found."));
    }

    private Saved sameRequest(ScenarioCommand prior, String hash, Analyst actor) {
        if (!prior.getBodyHash().equals(hash)
                || !prior.getRequestedBy().getId().equals(actor.getId())) {
            throw problem(HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT",
                    "This requestId was used for a different command.");
        }
        return new Saved(prior.getRunId(), false);
    }

    private Analyst actor(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue.");
        }
        return analysts.findByIssuerAndSubject(
                user.getIdToken().getIssuer().toString(), user.getIdToken().getSubject())
                .filter(Analyst::isEnabled)
                .orElseThrow(() -> problem(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "This account has no enabled analyst profile."));
    }

    private static void validateCommand(ScenarioType type, UUID requestId,
                                        long seed, String scopeId) {
        if (type == null || requestId == null || seed < 0 || scopeId == null) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_COMMAND", "Invalid scenario request.");
        }
    }

    private void validate(ScenarioType type, String scopeId, Instant startAt) {
        var binding = catalogue.scope(scopeId);
        var authority = catalogue.strictScope(scopeId);
        String service = authority == null ? null : authority.path("service").asText();
        if (binding == null || !("VOLTE".equals(service) || "SMS".equals(service))
                || (!binding.path("legacy").asBoolean() && !catalogue.activeAt(startAt))
                || (type == ScenarioType.VOLTE_IMS_OVERLOAD && !"VOLTE".equals(service))
                || (type == ScenarioType.SMS_QUEUE_DELAY && !"SMS".equals(service))) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_SCOPE",
                    "Scenario type and scope do not match the service catalogue.");
        }
    }

    private static String bodyHash(ScenarioType type, String scopeId, long seed) {
        try {
            byte[] canonical = (type.name() + "\n" + scopeId + "\n" + seed)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean terminal(ScenarioStatus status) {
        return status == ScenarioStatus.COMPLETED || status == ScenarioStatus.STOPPED
                || status == ScenarioStatus.FAILED;
    }

    private static Result ok(ScenarioCommand command) {
        return new Result(new Run(command.getRunId(), command.getStatus(),
                command.getScheduledStartAt(), command.getScheduledEndAt(),
                command.getScenarioType(), command.getScopeId()), null);
    }

    private static Result error(HttpStatus status, String code, String message) {
        return new Result(null, problem(status, code, message));
    }

    private static WorkflowProblem problem(HttpStatus status, String code, String message) {
        return new WorkflowProblem(status, code, message);
    }
}
