package md.utm.telecom.geography;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** One bounded batch, anchored to immutable OPEN evidence rather than the active catalogue. */
@Repository
@Transactional(readOnly = true)
public class IncidentLocationProjection {
    public record Location(String cityId, String catalogueVersion, String topologyVersion,
            String measuredScopeId, List<String> containmentPath, List<String> dependencyNodeIds,
            String nullReason) {}

    private final NamedParameterJdbcTemplate jdbc;

    public IncidentLocationProjection(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<UUID, Location> project(Collection<UUID> incidentIds) {
        if (incidentIds.isEmpty()) return Map.of();
        if (incidentIds.size() > 100) throw new IllegalArgumentException("At most 100 incident locations");
        Map<UUID, Location> result = new HashMap<>();
        jdbc.query("""
                WITH RECURSIVE captured AS (
                  SELECT i.id, i.scope_id, i.service, d.window_start AS opening_window_start,
                         d.payload->>'topologyVersion' AS topology_version
                  FROM app.incidents i LEFT JOIN app.detection_evidence d
                    ON d.episode_id = i.episode_id AND d.sequence = 1
                  WHERE i.id IN (:ids)
                ), window_authority AS (
                  SELECT c.id, count(DISTINCT w.catalogue_version) AS coverage_matches,
                         min(w.catalogue_version) AS catalogue_version
                  FROM captured c LEFT JOIN app.scope_window_coverage w
                    ON w.scope_id = c.scope_id AND w.service = c.service
                    AND w.window_start = c.opening_window_start
                    AND w.topology_version = c.topology_version
                  GROUP BY c.id
                ), candidates AS (
                  SELECT c.id, b.*, count(*) OVER (PARTITION BY c.id) AS matches
                  FROM captured c JOIN window_authority w ON w.id = c.id
                  JOIN app.geo_catalogue_versions v ON v.topology_version = c.topology_version
                    AND (w.coverage_matches = 0 OR (w.coverage_matches = 1
                      AND v.catalogue_version = w.catalogue_version))
                  JOIN app.geo_scope_bindings b ON b.catalogue_version = v.catalogue_version
                    AND b.scope_id = c.scope_id AND b.service = c.service
                ), resolved AS (
                  SELECT * FROM candidates WHERE matches = 1
                ), ancestry AS (
                  SELECT r.id, n.catalogue_version, n.node_id, n.parent_node_id, n.node_type,
                         ARRAY[n.node_id]::text[] AS visited, 1 AS depth
                  FROM resolved r JOIN app.geo_nodes n ON n.catalogue_version = r.catalogue_version
                    AND n.node_id = r.footprint_node_id AND n.city_id = r.city_id
                  UNION ALL
                  SELECT a.id, n.catalogue_version, n.node_id, n.parent_node_id, n.node_type,
                         a.visited || n.node_id, a.depth + 1
                  FROM ancestry a JOIN app.geo_nodes n ON n.catalogue_version = a.catalogue_version
                    AND n.node_id = a.parent_node_id
                  WHERE a.depth < 5 AND NOT n.node_id = ANY(a.visited)
                )
                SELECT c.id, c.scope_id, c.topology_version, r.catalogue_version, r.city_id, r.legacy,
                  (SELECT max(matches) FROM candidates m WHERE m.id = c.id) AS matches,
                  w.coverage_matches,
                  ARRAY(SELECT a.node_id FROM ancestry a WHERE a.id = c.id ORDER BY a.depth DESC) AS path,
                  EXISTS(SELECT 1 FROM ancestry a WHERE a.id = c.id AND a.depth = 5
                    AND a.node_type = 'COUNTRY' AND a.parent_node_id IS NULL) AS complete_path,
                  ARRAY(SELECT DISTINCT e.node_id FROM app.geo_scope_roles e
                    WHERE e.catalogue_version = r.catalogue_version AND e.scope_id = c.scope_id
                    ORDER BY e.node_id) AS dependencies
                FROM captured c JOIN window_authority w ON w.id = c.id
                LEFT JOIN resolved r ON r.id = c.id
                """, Map.of("ids", incidentIds), rs -> {
            String scope = rs.getString("scope_id");
            String topology = rs.getString("topology_version");
            long matches = rs.getLong("matches");
            long coverageMatches = rs.getLong("coverage_matches");
            boolean legacy = scope.equals("VOLTE-MD-CENTRAL") || scope.equals("SMS-MD-ROUTE-A")
                    || rs.getBoolean("legacy");
            String reason = legacy ? "UNALLOCATED"
                    : topology == null || topology.isBlank() ? "CAPTURED_VERSION_MISSING"
                    : coverageMatches > 1 ? "AMBIGUOUS_CATALOGUE_VERSION"
                    : matches == 0 ? "BINDING_NOT_FOUND"
                    : matches > 1 ? "AMBIGUOUS_CATALOGUE_VERSION"
                    : !rs.getBoolean("complete_path") ? "INVALID_CONTAINMENT" : null;
            result.put(rs.getObject("id", UUID.class), new Location(
                    reason == null ? rs.getString("city_id").trim() : null,
                    matches == 1 ? rs.getString("catalogue_version") : null, topology, scope,
                    reason == null ? Arrays.asList((String[]) rs.getArray("path").getArray()) : List.of(),
                    reason == null ? Arrays.asList((String[]) rs.getArray("dependencies").getArray()) : List.of(), reason));
        });
        return Map.copyOf(result);
    }
}
