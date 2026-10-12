package md.utm.telecom.tracelink;

import java.sql.Timestamp;
import java.time.Instant;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.inventory.TraceInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceInventoryIT extends IncidentServiceIntegrationTestSupport {
    private static final Instant FROM = Instant.parse("2026-09-10T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-10T09:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TraceInventoryService inventory;

    @BeforeEach
    void seedCatalogue() {
        jdbc.update("""
                INSERT INTO app.trace_dataset
                    (dataset_id, generator_version, seed, synthetic, history_from,
                     history_to_exclusive, source_resolution_seconds, timezone, manifest_sha256)
                VALUES (?, ?, ?, TRUE, ?, ?, 300, ?, ?)
                """, "trace-link-md-30d-v1", "reference-1.0", 42,
                Timestamp.from(FROM), Timestamp.from(TO), "Europe/Chisinau", "a".repeat(64));
        jdbc.update("""
                INSERT INTO app.trace_catalogue
                    (catalogue_version, dataset_id, topology_version, active, valid_from)
                VALUES (?, ?, ?, TRUE, ?)
                """, "trace-active-v1", "trace-link-md-30d-v1", "trace-link-md-topology-v1",
                Timestamp.from(FROM));
        jdbc.update("""
                INSERT INTO app.trace_catalogue
                    (catalogue_version, dataset_id, topology_version, active, valid_from, valid_to)
                VALUES (?, ?, ?, FALSE, ?, ?)
                """, "trace-old-v1", "trace-link-md-30d-v1", "trace-old-topology-v1",
                Timestamp.from(FROM.minusSeconds(86400)), Timestamp.from(FROM));

        String[][] cities = {
                {"chi", "Chișinău"}, {"bal", "Bălți"}, {"cah", "Cahul"},
                {"ung", "Ungheni"}, {"orh", "Orhei"}, {"sor", "Soroca"},
                {"com", "Comrat"}, {"edi", "Edineț"}, {"hin", "Hîncești"},
                {"cau", "Căușeni"}
        };
        for (String[] city : cities) {
            jdbc.update("""
                    INSERT INTO app.trace_inventory_city
                        (catalogue_version, city_id, display_name, latitude, longitude,
                         demand_share, active, valid_from, synthetic)
                    VALUES ('trace-active-v1', ?, ?, 47.000000, 28.000000,
                            0.100000, TRUE, ?, TRUE)
                    """, city[0], city[1], Timestamp.from(FROM));
        }
        jdbc.update("""
                INSERT INTO app.trace_inventory_city
                    (catalogue_version, city_id, display_name, latitude, longitude,
                     demand_share, active, valid_from, valid_to, synthetic)
                VALUES ('trace-old-v1', 'tir', 'Tiraspol', 46.800000, 29.600000,
                        0.100000, FALSE, ?, ?, TRUE)
                """, Timestamp.from(FROM.minusSeconds(86400)), Timestamp.from(FROM));

        String[][] links = {
                {"chi-orh", "chi", "orh"}, {"orh-sor", "orh", "sor"},
                {"sor-bal", "sor", "bal"}, {"bal-edi", "bal", "edi"},
                {"bal-ung", "bal", "ung"}, {"ung-chi", "ung", "chi"},
                {"chi-hin", "chi", "hin"}, {"hin-com", "hin", "com"},
                {"com-cah", "com", "cah"}, {"chi-cau", "chi", "cau"},
                {"cau-com", "cau", "com"}, {"orh-bal", "orh", "bal"}
        };
        for (String[] link : links) {
            jdbc.update("""
                    INSERT INTO app.trace_inventory_link
                        (catalogue_version, link_id, endpoint_a_city_id, endpoint_b_city_id,
                         link_type, nominal_capacity_gbps, active, valid_from, synthetic)
                    VALUES ('trace-active-v1', ?, ?, ?, 'FIBER', 20, TRUE, ?, TRUE)
                    """, link[0], link[1], link[2], Timestamp.from(FROM));
            for (String direction : new String[]{"a-to-b", "b-to-a"}) {
                jdbc.update("""
                        INSERT INTO app.trace_link_direction
                            (catalogue_version, link_id, direction_id, effective_capacity_gbps,
                             capacity_valid_from, measurement_source)
                        VALUES ('trace-active-v1', ?, ?, 20, ?, 'reference-fixture')
                        """, link[0], direction, Timestamp.from(FROM));
            }
        }
        jdbc.update("""
                INSERT INTO app.trace_partner
                    (catalogue_version, partner_id, country_id, direction, active,
                     valid_from, synthetic)
                VALUES ('trace-active-v1', 'ro-partner', 'RO', 'out', TRUE, ?, TRUE)
                """, Timestamp.from(FROM));
    }

    @Test
    void activeCatalogueReturnsApprovedCitiesAndBothLinkDirections() {
        var cities = inventory.cities(null);
        assertEquals(10, cities.cities().size());
        assertFalse(cities.cities().stream().anyMatch(city -> city.id().equals("tir")
                || city.id().equals("rib")));
        var links = inventory.links(null, null, null);
        assertEquals(12, links.links().size());
        assertTrue(links.links().stream().allMatch(link -> link.directions().size() == 2));
        assertEquals(2, inventory.links(null, "hin", "2026-10-10T08:40:00Z").links().size());
        assertEquals(1, inventory.partners(null, "RO", "out").partners().size());
    }

    @Test
    void archivedCatalogueRequiresAnExplicitVersion() {
        assertEquals("tir", inventory.cities("trace-old-v1").cities().getFirst().id());
        assertThrows(ResponseStatusException.class, () -> inventory.cities("unknown"));
    }
}
