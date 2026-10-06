package md.utm.telecom.geography;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Imports the pinned, strictly validated catalogue into the incident read database. */
@Component
public class CatalogueImporter implements ApplicationRunner {
    private final GeographyCatalogue catalogue;
    private final JdbcTemplate jdbc;

    public CatalogueImporter(GeographyCatalogue catalogue, JdbcTemplate jdbc) {
        this.catalogue = catalogue;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments ignored) {
        importCatalogue();
    }

    @Transactional
    public void importCatalogue() {
        JsonNode root = catalogue.root();
        String version = catalogue.version();
        String status = root.path("activation").path("status").asText();
        var effective = root.path("activation").path("effectiveFrom");
        Timestamp effectiveAt = effective.isNull() ? null : Timestamp.from(Instant.parse(effective.asText()));
        int inserted = jdbc.update("""
                INSERT INTO app.geo_catalogue_versions
                    (catalogue_version, topology_version, content_sha256, activation_status, effective_from)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, version, catalogue.topologyVersion(), catalogue.digest(), status, effectiveAt);
        if (inserted == 0) {
            var stored = jdbc.query("""
                    SELECT topology_version, content_sha256, activation_status, effective_from
                    FROM app.geo_catalogue_versions WHERE catalogue_version = ?
                    """, (rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), version);
            GeographyCatalogue.require(stored.size() == 1, "Catalogue identity conflict");
            var old = stored.getFirst();
            GeographyCatalogue.require(old.topology().equals(catalogue.topologyVersion())
                    && old.digest().equals(catalogue.digest()) && old.status().equals(status)
                    && java.util.Objects.equals(old.effectiveAt(), effectiveAt == null ? null : effectiveAt.toInstant()),
                    "Catalogue version reused with different content");
            return;
        }
        for (var city : root.path("cities")) {
            jdbc.update("""
                    INSERT INTO app.geo_cities (catalogue_version, city_id, display_name)
                    VALUES (?, ?, ?)
                    """, version, city.path("cityId").asText(), city.path("displayName").asText());
        }
        for (var node : root.path("nodes")) {
            jdbc.update("""
                    INSERT INTO app.geo_nodes
                        (catalogue_version, node_id, parent_node_id, city_id, node_type)
                    VALUES (?, ?, ?, ?, ?)
                    """, version, node.path("nodeId").asText(), textOrNull(node.path("parentId")),
                    textOrNull(node.path("cityId")), node.path("type").asText());
        }
        for (var binding : root.path("scopes")) {
            String scopeId = binding.path("scopeId").asText();
            var strict = catalogue.strictScope(scopeId);
            var footprint = binding.path("footprintNodeIds");
            jdbc.update("""
                    INSERT INTO app.geo_scope_bindings
                        (catalogue_version, scope_id, service, city_id, legacy,
                         service_source_id, footprint_node_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, version, scopeId, strict.path("service").asText(),
                    textOrNull(binding.path("cityId")), binding.path("legacy").asBoolean(),
                    strict.path("serviceSourceId").asText(),
                    footprint.isEmpty() ? null : footprint.get(0).asText());
            Map<String, String> sources = new HashMap<>();
            for (var node : strict.path("nodes"))
                sources.put(node.path("nodeId").asText(), node.path("sourceId").asText());
            for (var role : binding.path("roles")) {
                String nodeId = role.path("nodeId").asText();
                jdbc.update("""
                        INSERT INTO app.geo_scope_roles
                            (catalogue_version, scope_id, role, node_id, source_id)
                        VALUES (?, ?, ?, ?, ?)
                        """, version, scopeId, role.path("role").asText(), nodeId, sources.get(nodeId));
            }
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isNull() ? null : node.asText();
    }

    private record Stored(String topology, String digest, String status, Instant effectiveAt) {}
}
