package md.utm.telecom.geography;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First valid fact wins; repeated identical deliveries do not change the read model. */
@Service
public class CoverageProjectionService {
    private final JdbcTemplate jdbc;
    public CoverageProjectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean ingest(String kafkaKey, String raw) {
        var fact = CoverageFact.parse(raw, kafkaKey, jdbc);
        int inserted = jdbc.update("""
                INSERT INTO app.scope_window_coverage
                  (coverage_id, window_id, scope_id, service, catalogue_version,
                   topology_version, window_start, window_end, expected_source_ids,
                   received_source_ids, usable_source_ids, source_issues, payload_sha256, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?::jsonb) ON CONFLICT DO NOTHING
                """, fact.coverageId(), fact.windowId(), fact.scopeId(), fact.service(),
                fact.catalogueVersion(), fact.topologyVersion(), java.sql.Timestamp.from(fact.windowStart()), java.sql.Timestamp.from(fact.windowEnd()),
                fact.expectedJson(), fact.receivedJson(), fact.usableJson(), fact.issuesJson(),
                fact.payloadHash(), fact.canonicalPayload());
        if (inserted == 1) return true;
        var existing = jdbc.query("""
                SELECT coverage_id, payload_sha256 FROM app.scope_window_coverage
                WHERE coverage_id = ? OR (scope_id = ? AND service = ? AND window_start = ?
                    AND topology_version = ? AND catalogue_version = ?)
                """, (rs, n) -> new Stored(rs.getString(1), rs.getString(2)),
                fact.coverageId(), fact.scopeId(), fact.service(), java.sql.Timestamp.from(fact.windowStart()),
                fact.topologyVersion(), fact.catalogueVersion());
        if (existing.size() != 1 || !existing.getFirst().id().equals(fact.coverageId())
                || !existing.getFirst().hash().equals(fact.payloadHash()))
            throw new IllegalArgumentException("Conflicting coverage identity or body");
        return false;
    }

    private record Stored(String id, String hash) {}
}
