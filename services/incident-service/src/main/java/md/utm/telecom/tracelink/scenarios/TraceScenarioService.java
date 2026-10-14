package md.utm.telecom.tracelink.scenarios;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Durable public UUID -> producer fingerprint mapping, isolated from legacy scenario enums. */
@Service
public class TraceScenarioService {
    private record Saved(UUID id, UUID actor, String request, String command, String fingerprint,
                         String status, boolean stop, String snapshot) {}
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "STOPPED", "INTERRUPTED", "FAILED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AnalystRepository analysts;
    private final TraceScenarioGateway gateway;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;

    public TraceScenarioService(JdbcTemplate jdbc, PlatformTransactionManager manager, AnalystRepository analysts,
            TraceScenarioGateway gateway, ObjectMapper json, Clock clock,
            @Value("${app.trace-link.scenarios.enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.analysts = analysts;
        this.gateway = gateway; this.json = json; this.clock = clock; this.enabled = enabled;
    }
    public JsonNode catalogue(Authentication auth) {
        actor(auth);
        if (!enabled) return json.createObjectNode().put("enabled", false).put("synthetic", true)
                .put("version", "trace-link-scenarios-v1").put("deliveryStatus", "DISABLED_PENDING_REVIEW");
        return gateway.call("GET", "/catalogue", null);
    }
    public JsonNode start(JsonNode body, UUID headerId, Authentication auth) {
        requireEnabled();
        if (body == null || !body.isObject()) throw invalid();
        var fields = new HashSet<String>(); body.properties().forEach(e -> fields.add(e.getKey()));
        var allowed = Set.of("requestId","type","version","scope","seed","parameters");
        if (!allowed.containsAll(fields) || !fields.containsAll(Set.of("requestId","type","version","scope","seed"))) throw invalid();
        if (body == null || !body.isObject() || body.toString().length() > 16384
                || !body.has("requestId") || !body.path("requestId").isTextual()
                || !body.path("seed").isIntegralNumber() || !body.path("seed").canConvertToLong()
                || body.path("seed").longValue() < 0 || body.path("seed").longValue() > 9007199254740991L)
            throw invalid();
        UUID request;
        try { request = UUID.fromString(body.path("requestId").asText()); }
        catch (IllegalArgumentException badId) { throw invalid(); }
        if (headerId != null && !headerId.equals(request)) throw invalid();
        UUID actor = actor(auth);
        String canonical = canonical(body);
        UUID id = tx.execute(ignored -> {
            // Serializes request-ID and empty-scope races across API instances; no network dispatch before commit.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(981401009)", Object.class);
            var previous = jdbc.query("SELECT run_id FROM app.trace_scenario_command WHERE request_id=?", (rs,n) -> rs.getObject(1, UUID.class), request);
            if (!previous.isEmpty()) {
                var saved = load(previous.getFirst(), actor);
                if (!saved.request().equals(canonical)) throw conflict();
                return saved.id();
            }
            var proposed = ((ObjectNode) body).deepCopy(); proposed.remove("requestId");
            proposed.put("start", clock.instant().truncatedTo(ChronoUnit.MINUTES).plusSeconds(60).toString());
            var validated = gateway.call("POST", "/validate", proposed);
            var command = validated.path("command");
            String fingerprint = validated.path("fingerprint").asText();
            if (!fingerprint.matches("[0-9a-f]{64}") || !command.isObject()) throw TraceScenarioGateway.unavailable();
            for (var entry : proposed.properties())
                if (!canonicalProducerField(entry.getKey(), entry.getValue()).equals(canonicalProducerField(entry.getKey(), command.path(entry.getKey())))) throw TraceScenarioGateway.unavailable();
            Instant start = Instant.parse(command.path("start").asText());
            String scope = canonical(command.path("scope"));
            Long overlaps = jdbc.queryForObject("SELECT count(*) FROM app.trace_scenario_command WHERE scope_key=? AND scheduled_start_at < ? AND scheduled_end_at > ?", Long.class,
                    scope, java.sql.Timestamp.from(start.plusSeconds(480)), java.sql.Timestamp.from(start));
            if (overlaps != null && overlaps > 0) throw conflict();
            UUID fresh = UUID.randomUUID();
            jdbc.update("INSERT INTO app.trace_scenario_command(run_id,request_id,actor_id,request_text,command_text,fingerprint,scope_key,scheduled_start_at,scheduled_end_at,status) VALUES (?,?,?,?,?,?,?,?,?,'SCHEDULED')",
                    fresh, request, actor, canonical, command.toString(), fingerprint, scope,
                    java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plusSeconds(480)));
            return fresh;
        });
        return reconcile(id, actor, false);
    }
    public List<JsonNode> history(Authentication auth) {
        requireEnabled(); UUID actor = actor(auth);
        return tx.execute(ignored -> jdbc.query("SELECT run_id FROM app.trace_scenario_command WHERE actor_id=? ORDER BY created_at DESC,run_id LIMIT 128", (rs,n) -> rs.getObject(1,UUID.class), actor)
                .stream().map(id -> response(load(id, actor))).map(n -> (JsonNode)n).toList());
    }
    public JsonNode status(UUID id, Authentication auth) { requireEnabled(); return reconcile(id, actor(auth), false); }
    public JsonNode stop(UUID id, Authentication auth) { requireEnabled(); return reconcile(id, actor(auth), true); }
    public JsonNode reconcile(UUID id, Authentication auth) { requireEnabled(); return reconcile(id, actor(auth), false); }

    private JsonNode reconcile(UUID id, UUID actor, boolean stop) {
        // Stop intent commits before remote delivery and survives timeouts or API restarts.
        if (stop) tx.executeWithoutResult(ignored -> {
            var saved = load(id, actor);
            if (!TERMINAL.contains(saved.status())) jdbc.update("UPDATE app.trace_scenario_command SET stop_requested=TRUE WHERE run_id=?", id);
        });
        return tx.execute(ignored -> {
            var saved = load(id, actor);
            if (TERMINAL.contains(saved.status())) return response(saved);
            JsonNode snapshot;
            try {
                snapshot = gateway.call("GET", "/runs/" + saved.fingerprint(), null);
            } catch (WorkflowProblem failure) {
                if (failure.status() != HttpStatus.NOT_FOUND) throw failure;
                // The engine records late unknown delivery as INTERRUPTED; it never replays minute zero.
                snapshot = gateway.call("PUT", "/runs/" + saved.fingerprint(), json.readTree(saved.command()));
            }
            verify(snapshot, saved);
            if (saved.stop() && !TERMINAL.contains(snapshot.path("status").asText())) {
                snapshot = gateway.call("POST", "/runs/" + saved.fingerprint() + "/stop", null); verify(snapshot, saved);
            }
            jdbc.update("UPDATE app.trace_scenario_command SET status=?,snapshot=CAST(? AS jsonb) WHERE run_id=?",
                    snapshot.path("status").asText(), snapshot.toString(), id);
            return response(load(id, actor));
        });
    }
    @Scheduled(fixedDelayString = "${app.trace-link.scenarios.reconcile-ms:5000}")
    public void reconcilePending() {
        if (!enabled) return;
        var pending = jdbc.query("SELECT run_id,actor_id FROM app.trace_scenario_command WHERE status IN ('SCHEDULED','RUNNING') ORDER BY created_at LIMIT 128",
                (rs,n) -> new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)});
        for (var ids : pending) try { reconcile(ids[0],ids[1],false); }
        catch (RuntimeException failure) { /* Retained durable command and stop intent are retried on the next scan. */ }
    }
    private Saved load(UUID id, UUID actor) {
        var rows = jdbc.query("SELECT * FROM app.trace_scenario_command WHERE run_id=? AND actor_id=? FOR UPDATE", (rs,n) -> new Saved(
                rs.getObject("run_id",UUID.class), rs.getObject("actor_id",UUID.class),rs.getString("request_text"),
                rs.getString("command_text"),rs.getString("fingerprint").trim(),rs.getString("status"),rs.getBoolean("stop_requested"),rs.getString("snapshot")),id,actor);
        if (rows.isEmpty()) throw new WorkflowProblem(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","No run exists for this account.");
        return rows.getFirst();
    }
    private ObjectNode response(Saved saved) {
        var node = saved.snapshot() == null ? json.createObjectNode().put("completedWindows",0).put("generatedObservations",0)
                .put("deliveryStatus","DISABLED_PENDING_REVIEW").put("offered",0).put("acknowledged",0).putNull("persisted").putNull("accepted")
                : (ObjectNode)json.readTree(saved.snapshot());
        node.put("runId",saved.id().toString()).put("fingerprint",saved.fingerprint()).put("status",saved.status());
        node.set("command",json.readTree(saved.command()));
        return node;
    }
    private void verify(JsonNode node, Saved saved) {
        if (!saved.fingerprint().equals(node.path("fingerprint").asText()) || !canonical(json.readTree(saved.command())).equals(canonical(node.path("command")))
                || !Set.of("SCHEDULED","RUNNING","COMPLETED","STOPPED","INTERRUPTED").contains(node.path("status").asText())
                || !node.path("completedWindows").isIntegralNumber() || node.path("completedWindows").intValue()<0 || node.path("completedWindows").intValue()>8
                || !"DISABLED_PENDING_REVIEW".equals(node.path("deliveryStatus").asText())
                || !node.path("offered").isIntegralNumber() || node.path("offered").intValue()!=0
                || !node.path("acknowledged").isIntegralNumber() || node.path("acknowledged").intValue()!=0
                || !node.path("persisted").isNull() || !node.path("accepted").isNull()) throw TraceScenarioGateway.unavailable();
        int windows = node.path("completedWindows").intValue();
        String status = node.path("status").asText();
        var history = node.path("history");
        if (!history.isArray() || history.size() != windows || ("COMPLETED".equals(status) != (windows == 8))
                || ("SCHEDULED".equals(status) && windows != 0)
                || !node.path("generatedObservations").isIntegralNumber() || !node.path("generatedObservations").canConvertToInt())
            throw TraceScenarioGateway.unavailable();
        int observations = 0;
        Instant start = Instant.parse(node.path("command").path("start").asText());
        for (int index = 0; index < windows; index++) {
            var window = history.get(index);
            if (!start.plusSeconds(index * 60L).toString().equals(window.path("start").asText())
                    || !window.path("observations").isArray() || window.path("observations").size() > 3)
                throw TraceScenarioGateway.unavailable();
            for (var observation : window.path("observations"))
                if (!observation.isTextual() || observation.asText().length() > 16384) throw TraceScenarioGateway.unavailable();
            observations += window.path("observations").size();
        }
        if (observations != node.path("generatedObservations").intValue()) throw TraceScenarioGateway.unavailable();
    }
    private UUID actor(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof OidcUser user))
            throw new WorkflowProblem(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Sign in to continue.");
        return analysts.findByIssuerAndSubject(user.getIdToken().getIssuer().toString(),user.getIdToken().getSubject())
                .filter(a -> a.isEnabled()).orElseThrow(() -> new WorkflowProblem(HttpStatus.FORBIDDEN,"FORBIDDEN","An enabled analyst profile is required.")).getId();
    }
    private String canonicalProducerField(String field, JsonNode node) {
        // Only producer validation normalizes this set. Request identity and saved commands retain their array order.
        if (!"parameters".equals(field) || !node.isObject() || !node.has("missingMinutes")) return canonical(node);
        var missing = node.path("missingMinutes");
        if (!missing.isArray()) throw TraceScenarioGateway.unavailable();
        var sorted = new TreeSet<Integer>();
        for (var minute : missing) {
            if (!minute.isIntegralNumber() || !minute.canConvertToInt() || minute.intValue() < 0 || minute.intValue() > 7
                    || !sorted.add(minute.intValue())) throw TraceScenarioGateway.unavailable();
        }
        var normalized = ((ObjectNode) node).deepCopy();
        normalized.set("missingMinutes", json.valueToTree(sorted));
        return canonical(normalized);
    }
    private String canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String,String>(); node.properties().forEach(e -> sorted.put(e.getKey(),canonical(e.getValue())));
            return "{" + String.join(",", sorted.entrySet().stream().map(e -> json.writeValueAsString(e.getKey())+":"+e.getValue()).toList()) + "}";
        }
        if (node.isArray()) { var items = new ArrayList<String>(); node.forEach(item -> items.add(canonical(item))); return "["+String.join(",",items)+"]"; }
        return node.toString();
    }
    private void requireEnabled() { if (!enabled) throw new WorkflowProblem(HttpStatus.SERVICE_UNAVAILABLE,"FEATURE_DISABLED","Trace Link scenario runtime is disabled."); }
    private static WorkflowProblem invalid() { return new WorkflowProblem(HttpStatus.BAD_REQUEST,"INVALID_COMMAND","Send a request UUID and a safely representable nonnegative integer seed."); }
    private static WorkflowProblem conflict() { return new WorkflowProblem(HttpStatus.CONFLICT,"COMMAND_CONFLICT","Request identity or scope interval is already reserved."); }
}
