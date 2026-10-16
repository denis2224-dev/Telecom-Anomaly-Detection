package md.utm.telecom.tracelink;

import java.sql.Timestamp;
import java.time.Instant;
import md.utm.telecom.IncidentServiceIntegrationTestSupport;
import md.utm.telecom.tracelink.metrics.MetricScopeRepository;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class MetricCapacityHistoryIT extends IncidentServiceIntegrationTestSupport {
    private final Instant start = Instant.parse("2026-09-10T09:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired MetricScopeRepository scopes;

    @BeforeEach
    void registerAuthority() {
        jdbc.update("""
                INSERT INTO app.trace_dataset(dataset_id,generator_version,seed,synthetic,history_from,
                    history_to_exclusive,source_resolution_seconds,timezone,manifest_sha256)
                VALUES ('capacity-test','test',42,TRUE,?,?,300,'UTC',?)
                """, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)), "a".repeat(64));
        jdbc.update("""
                INSERT INTO app.trace_catalogue(catalogue_version,dataset_id,topology_version,active,valid_from)
                VALUES ('capacity-v1','capacity-test','capacity-topology',TRUE,?)
                """, Timestamp.from(start));
        for (String city : new String[] {"chi","cau"}) {
            jdbc.update("""
                    INSERT INTO app.trace_inventory_city(catalogue_version,city_id,display_name,latitude,
                        longitude,demand_share,active,valid_from,synthetic)
                    VALUES ('capacity-v1',?,?,46,28,0.5,TRUE,?,TRUE)
                    """, city, city, Timestamp.from(start));
        }
        jdbc.update("""
                INSERT INTO app.trace_inventory_link(catalogue_version,link_id,endpoint_a_city_id,
                    endpoint_b_city_id,link_type,nominal_capacity_gbps,active,valid_from,synthetic)
                VALUES ('capacity-v1','chi-cau','chi','cau','FIBRE',20,TRUE,?,TRUE)
                """, Timestamp.from(start));
    }

    private void capacity(long from, long to, int value) {
        jdbc.update("""
                INSERT INTO app.trace_link_direction(catalogue_version,link_id,direction_id,
                    effective_capacity_gbps,capacity_valid_from,capacity_valid_to,measurement_source)
                VALUES ('capacity-v1','chi-cau','a-to-b',?,?,?,'fixture')
                """, value, Timestamp.from(start.plusSeconds(from)), Timestamp.from(start.plusSeconds(to)));
    }

    private TraceQuery query() {
        return new TraceQuery("capacity-test","transport","link.utilization","LINK_DIRECTION",
                "chi-cau:a-to-b",start,start.plusSeconds(3600),"5m");
    }

    @Test
    void adjacentCapacityChangesCoverTheRequestedRange() {
        capacity(0,1800,20);
        capacity(1800,3600,10);
        assertEquals("capacity-topology", scopes.resolve(query()).topologyVersion());
    }

    @Test
    void capacityGapAndOverlappingAuthorityAreRejected() {
        capacity(0,1500,20);
        capacity(1800,3600,10);
        assertThrows(ResponseStatusException.class, () -> scopes.resolve(query()));
        capacity(1200,2100,15);
        assertThrows(ResponseStatusException.class, () -> scopes.resolve(query()));
    }
}
