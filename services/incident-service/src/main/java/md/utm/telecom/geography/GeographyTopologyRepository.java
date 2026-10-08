package md.utm.telecom.geography;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static md.utm.telecom.geography.GeographyTopologyResponses.*;

/** Versioned inventory only: dependencies never become containment children or copied KPI values. */
@Repository
@Transactional(readOnly = true)
public class GeographyTopologyRepository {
    private final JdbcTemplate jdbc;
    private final GeographyCatalogue catalogue;

    public GeographyTopologyRepository(JdbcTemplate jdbc, GeographyCatalogue catalogue) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
    }

    public TopologyPage topology(String cityId, String parentId, String requestedVersion,
                                 int page, int size, Instant now) {
        if (requestedVersion == null && !catalogue.active())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geographic catalogue is not active");
        String version = requestedVersion == null ? catalogue.version() : requestedVersion;
        var versions = jdbc.query("""
                SELECT topology_version FROM app.geo_catalogue_versions WHERE catalogue_version = ?
                """, (rs, n) -> rs.getString(1), version);
        if (versions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown catalogue version");
        var roots = jdbc.query("""
                SELECT node_id FROM app.geo_nodes
                WHERE catalogue_version = ? AND city_id = ? AND node_type = 'CITY'
                """, (rs, n) -> rs.getString(1), version, cityId);
        if (roots.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown city");
        String parent = parentId == null ? roots.getFirst() : parentId;
        int parents = jdbc.queryForObject("""
                SELECT count(*) FROM app.geo_nodes WHERE catalogue_version = ? AND city_id = ?
                AND node_id = ? AND node_type IN ('CITY', 'AGGREGATION', 'SITE', 'CELL')
                """, Integer.class, version, cityId, parent);
        if (parents != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid containment parent");
        var nodes = jdbc.query("""
                SELECT n.node_id, n.parent_node_id, n.node_type, EXISTS (
                    SELECT 1 FROM app.geo_scope_bindings b
                    WHERE b.catalogue_version = n.catalogue_version AND b.city_id = n.city_id
                    AND b.footprint_node_id = n.node_id AND NOT b.legacy)
                FROM app.geo_nodes n
                WHERE n.catalogue_version = ? AND n.city_id = ? AND n.parent_node_id = ?
                  AND n.node_type IN ('AGGREGATION', 'SITE', 'CELL')
                ORDER BY n.node_type, n.node_id LIMIT ? OFFSET ?
                """, (rs, n) -> new Node(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                version, cityId, parent, size + 1, (long) page * size);
        var footprints = jdbc.query("""
                SELECT DISTINCT footprint_node_id FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND city_id = ? AND NOT legacy ORDER BY footprint_node_id
                """, (rs, n) -> rs.getString(1), version, cityId);
        var dependencies = jdbc.query("""
                SELECT b.scope_id, b.service, r.role, r.node_id, r.source_id
                FROM app.geo_scope_bindings b JOIN app.geo_scope_roles r
                  ON r.catalogue_version = b.catalogue_version AND r.scope_id = b.scope_id
                WHERE b.catalogue_version = ? AND b.city_id = ? AND NOT b.legacy
                ORDER BY b.scope_id, r.role, r.node_id
                """, (rs, n) -> new Dependency(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), version, cityId);
        return new TopologyPage(cityId, version, versions.getFirst(), now, parent, footprints,
                page, size, nodes.size() > size, nodes.stream().limit(size).toList(), dependencies);
    }
}
