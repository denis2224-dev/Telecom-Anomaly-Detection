package md.utm.telecom.geography;

import org.springframework.jdbc.core.JdbcTemplate;

/** An independently named historical hierarchy, inserted within each rolled-back test transaction. */
final class CatalogueTestCopies {
    static void copy(JdbcTemplate jdbc, String source, String version, String topology) {
        jdbc.update("INSERT INTO app.geo_catalogue_versions (catalogue_version, topology_version, content_sha256, activation_status) VALUES (?, ?, ?, 'CONTRACT_ONLY')",
                version, topology, "a".repeat(64));
        jdbc.update("INSERT INTO app.geo_cities SELECT ?, city_id, display_name FROM app.geo_cities WHERE catalogue_version = ?", version, source);
        jdbc.update("""
                INSERT INTO app.geo_nodes SELECT ?, replace(node_id, '-01', '-99'), replace(parent_node_id, '-01', '-99'), city_id, node_type
                FROM app.geo_nodes WHERE catalogue_version = ?
                """, version, source);
        jdbc.update("""
                INSERT INTO app.geo_scope_bindings SELECT ?, scope_id, service, city_id, legacy, service_source_id, replace(footprint_node_id, '-01', '-99')
                FROM app.geo_scope_bindings WHERE catalogue_version = ?
                """, version, source);
        jdbc.update("""
                INSERT INTO app.geo_scope_roles SELECT ?, scope_id, role, replace(node_id, '-01', '-99'), source_id
                FROM app.geo_scope_roles WHERE catalogue_version = ?
                """, version, source);
    }
}
