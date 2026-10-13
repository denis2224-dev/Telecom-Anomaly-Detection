package md.utm.telecom.tracelink.incidents;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.tracelink.metrics.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TraceDomainProjectionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final MetricDefinitionRegistry definitions;
    private final MetricScopeRepository scopes;
    public TraceDomainProjectionService(JdbcTemplate jdbc, ObjectMapper json,
            MetricDefinitionRegistry definitions, MetricScopeRepository scopes) {
        this.jdbc = jdbc; this.json = json; this.definitions = definitions; this.scopes = scopes;
    }
    @Transactional
    public void ingest(TraceDomainDetection input) {
        input.validateIdentity(json);
        var definition = definitions.require(input.domain(), input.metric());
        var scope = scopes.resolve(new TraceQuery(input.datasetId(), input.domain(), input.metric(),
                input.scopeType(), input.scopeId(), input.windowStart(), input.windowEnd(), "5m"));
        if (!definition.policyVersion().equals(input.policyVersion()) || !scope.topologyVersion().equals(input.topologyVersion())) {
            throw new IllegalArgumentException("Domain detection versions do not match inventory and policy");
        }
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) locked",
                Integer.class, input.episodeId());
        var facts = jdbc.query("""
                SELECT payload, numerator, denominator, quality, coverage
                FROM app.trace_metric_window
                WHERE dataset_id = ? AND domain = ? AND metric_id = ? AND scope_type = ? AND scope_id = ?
                  AND direction = ? AND partner_id = ? AND procedure = ? AND bucket_start = ?
                  AND policy_version = ? AND topology_version = ? AND revision = ? AND finalized
                  AND unit = 'PERCENT' AND aggregation_type = 'RATIO_OF_SUMS'
                """, (rs, row) -> json.createObjectNode().set("payload", json.readTree(rs.getString("payload")))
                .put("quality", rs.getString("quality"))
                .put("coverage", rs.getBigDecimal("coverage"))
                .put("numerator", rs.getBigDecimal("numerator"))
                .put("denominator", rs.getBigDecimal("denominator")),
                input.datasetId(), input.domain(), input.metric(), input.scopeType(), input.scopeId(),
                scope.direction(), scope.partnerId(), scope.procedure(), Timestamp.from(input.windowStart()),
                input.policyVersion(), input.topologyVersion(), input.revision());
        if (facts.size() != 1) throw new IllegalArgumentException("Detection requires one persisted primitive window");
        var fact = facts.getFirst();
        if (!input.phase().equals("UNKNOWN") && (!"VALID".equals(fact.path("quality").asText())
                || fact.path("coverage").asDouble() != 1.0 || !fact.path("numerator").isNumber()
                || !fact.path("denominator").isNumber() || fact.path("denominator").asDouble() <= 0)) {
            throw new IllegalArgumentException("Positive/recovery detection requires usable primary evidence");
        }
        var envelope = json.createObjectNode().set("detection", json.valueToTree(input));
        envelope.set("primitiveEvidence", fact);
        envelope.put("causeStatus", "UNDETERMINED");
        envelope.putNull("probableCause"); envelope.putNull("causeConfidence");
        String payload = json.writeValueAsString(envelope);
        int inserted = jdbc.update("""
                INSERT INTO app.trace_domain_detection
                    (detection_id, episode_id, sequence, dataset_id, domain, phase, window_start, window_end, detected_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
                """, input.detectionId(), input.episodeId(), input.sequence(), input.datasetId(), input.domain(), input.phase(),
                Timestamp.from(input.windowStart()), Timestamp.from(input.windowEnd()), Timestamp.from(input.detectedAt()), payload);
        if (inserted == 0) {
            var stored = jdbc.query("""
                    SELECT detection_id, payload FROM app.trace_domain_detection
                    WHERE episode_id = ? AND sequence = ?
                    """, (rs, row) -> rs.getString("detection_id").equals(input.detectionId())
                    && json.readTree(rs.getString("payload")).equals(json.readTree(payload)), input.episodeId(), input.sequence());
            if (stored.size() != 1 || !stored.getFirst()) throw new IllegalArgumentException("Conflicting domain detection replay");
        }
        var ordered = jdbc.query("SELECT payload FROM app.trace_domain_detection WHERE episode_id = ? ORDER BY sequence",
                (rs, row) -> json.treeToValue(json.readTree(rs.getString(1)).path("detection"), TraceDomainDetection.class), input.episodeId());
        TraceDomainDetection last = null;
        String severity = null;
        for (var next : ordered) {
            long expected = last == null ? 1 : last.sequence() + 1;
            if (next.sequence() != expected) break;
            if (last != null && (last.phase().equals("RECOVERY") || next.windowStart().isBefore(last.windowEnd()))) {
                throw new IllegalArgumentException("Domain episode cannot reopen or overlap windows");
            }
            if (!next.phase().equals("UNKNOWN")) severity = next.severity();
            last = next;
        }
        if (last == null) return;
        String state = switch (last.phase()) { case "UNKNOWN" -> "UNKNOWN"; case "RECOVERY" -> "RECOVERED"; default -> "ONGOING"; };
        UUID id = UUID.nameUUIDFromBytes(("trace-domain:" + input.episodeId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO app.trace_domain_incident
                    (id, episode_id, dataset_id, latest_detection_id, latest_sequence, technical_state, severity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (episode_id) DO UPDATE SET latest_detection_id = EXCLUDED.latest_detection_id,
                    latest_sequence = EXCLUDED.latest_sequence, technical_state = EXCLUDED.technical_state,
                    severity = EXCLUDED.severity, version = trace_domain_incident.version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE trace_domain_incident.latest_sequence < EXCLUDED.latest_sequence
                """, id, input.episodeId(), input.datasetId(), last.detectionId(), last.sequence(), state, severity);
    }
}
