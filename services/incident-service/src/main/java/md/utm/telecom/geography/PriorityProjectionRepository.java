package md.utm.telecom.geography;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.geography.PriorityResponses.Item;
import md.utm.telecom.geography.PriorityResponses.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A bounded view of saved incidents under the project priority policy. */
@Repository
@Transactional(readOnly = true)
public class PriorityProjectionRepository {
    public static final String POLICY_VERSION = "geographic-priority-v1";
    private final JdbcTemplate jdbc;
    private final GeographyCatalogue catalogue;

    public PriorityProjectionRepository(JdbcTemplate jdbc, GeographyCatalogue catalogue) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
    }

    public Page page(String cityId, String service, String technicalState,
                     int page, int size, Instant now) {
        if (cityId != null) {
            Integer known = jdbc.queryForObject("""
                    SELECT count(*) FROM app.geo_cities
                    WHERE catalogue_version = ? AND city_id = ?
                    """, Integer.class, catalogue.version(), cityId);
            if (known == null || known == 0)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown city");
        }
        StringBuilder sql = new StringBuilder("""
                WITH ranked AS (
                SELECT i.id, trim(loc.city_id) AS city_id, loc.matches,
                       window_authority.coverage_matches,
                       opening.topology_version, i.service, i.scope_id,
                       i.technical_state, i.status, i.severity, i.first_observed_at,
                       i.detected_at, i.last_observed_at,
                       CASE WHEN i.service = 'VOLTE'
                                 AND jsonb_typeof(latest.payload->'impact'->'extraFailedAttempts') = 'number'
                            THEN (latest.payload #>> '{impact,extraFailedAttempts}')::numeric
                            WHEN i.service = 'SMS'
                                 AND jsonb_typeof(latest.payload->'impact'->'affectedDeliveredMessages') = 'number'
                            THEN (latest.payload #>> '{impact,affectedDeliveredMessages}')::numeric
                       END AS raw_comparable_impact,
                       CASE WHEN i.technical_state = 'RECOVERED' THEN 2
                            WHEN i.technical_state = 'ONGOING'
                             AND i.last_observed_at BETWEEN ? AND ? THEN 0
                            ELSE 1 END AS priority_band
                FROM app.incidents i
                LEFT JOIN LATERAL (
                    SELECT d.payload->>'topologyVersion' AS topology_version,
                           d.window_start AS window_start
                    FROM app.detection_evidence d
                    WHERE d.episode_id = i.episode_id AND d.sequence = 1
                ) opening ON true
                LEFT JOIN LATERAL (
                    SELECT d.payload
                    FROM app.detection_evidence d
                    WHERE d.episode_id = i.episode_id AND d.sequence = i.latest_sequence
                ) latest ON true
                LEFT JOIN LATERAL (
                    SELECT count(DISTINCT w.catalogue_version) AS coverage_matches,
                           min(w.catalogue_version) AS catalogue_version
                    FROM app.scope_window_coverage w
                    WHERE w.scope_id = i.scope_id AND w.service = i.service
                      AND w.window_start = opening.window_start
                      AND w.topology_version = opening.topology_version
                ) window_authority ON true
                LEFT JOIN LATERAL (
                    SELECT count(*) AS matches,
                           CASE WHEN count(*) = 1 THEN min(b.city_id) END AS city_id
                    FROM app.geo_catalogue_versions v
                    JOIN app.geo_scope_bindings b
                      ON b.catalogue_version = v.catalogue_version
                     AND b.scope_id = i.scope_id AND b.service = i.service
                     AND NOT b.legacy
                    WHERE v.topology_version = opening.topology_version
                      AND (window_authority.coverage_matches = 0
                        OR (window_authority.coverage_matches = 1
                          AND v.catalogue_version = window_authority.catalogue_version))
                ) loc ON true
                WHERE 1 = 1
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(Timestamp.from(now.minusSeconds(90)));
        args.add(Timestamp.from(now));
        if (cityId != null) { sql.append(" AND loc.city_id = ?"); args.add(cityId); }
        if (service != null) { sql.append(" AND i.service = ?"); args.add(service); }
        if (technicalState != null) { sql.append(" AND i.technical_state = ?"); args.add(technicalState); }
        sql.append("""
                ), prioritized AS (
                    SELECT ranked.*,
                           CASE WHEN priority_band = 0 THEN raw_comparable_impact END
                             AS comparable_impact
                    FROM ranked
                ) SELECT * FROM prioritized
                ORDER BY priority_band,
                  CASE WHEN priority_band = 0 THEN
                    CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END
                  ELSE 3 END,
                  CASE WHEN priority_band = 0 THEN
                    CASE service WHEN 'SMS' THEN 0 ELSE 1 END
                  ELSE 2 END,
                  comparable_impact DESC NULLS LAST,
                  first_observed_at, id
                LIMIT ? OFFSET ?
                """);
        args.add(size + 1);
        args.add((long) page * size);
        List<Item> rows = jdbc.query(sql.toString(), (rs, index) -> {
            int band = rs.getInt("priority_band");
            String technical = rs.getString("technical_state");
            Instant last = rs.getTimestamp("last_observed_at").toInstant();
            String cityReason = rs.getString("city_id") != null ? null
                    : rs.getString("scope_id").equals("VOLTE-MD-CENTRAL")
                      || rs.getString("scope_id").equals("SMS-MD-ROUTE-A") ? "UNALLOCATED"
                    : rs.getString("topology_version") == null ? "CAPTURED_VERSION_MISSING"
                    : rs.getLong("coverage_matches") > 1 || rs.getLong("matches") > 1
                      ? "AMBIGUOUS_CATALOGUE_VERSION"
                    : "BINDING_NOT_FOUND";
            String freshness = technical.equals("RECOVERED") ? "RECOVERED"
                    : technical.equals("UNKNOWN") ? "UNKNOWN"
                    : band == 0 ? "FRESH" : "STALE";
            return new Item(rs.getObject("id", java.util.UUID.class), rs.getString("city_id"),
                    cityReason, rs.getString("service"), rs.getString("scope_id"), technical,
                    rs.getString("status"), rs.getString("severity"), band != 0,
                    freshness, band == 0 ? "FRESH_ONGOING" : band == 1 ? "UNCERTAIN" : "RECOVERED",
                    rs.getBigDecimal("comparable_impact"),
                    band == 0 ? rs.getString("service").equals("VOLTE")
                            ? "EXTRA_FAILED_ATTEMPTS" : "AFFECTED_DELIVERED_MESSAGES" : null,
                    rs.getTimestamp("first_observed_at").toInstant(),
                    rs.getTimestamp("detected_at").toInstant(), last);
        }, args.toArray());
        return new Page(now, POLICY_VERSION, "ACTIVE", page, size,
                rows.size() > size, rows.stream().limit(size).toList());
    }
}
