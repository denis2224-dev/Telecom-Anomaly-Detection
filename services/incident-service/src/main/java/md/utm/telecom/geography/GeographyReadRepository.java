package md.utm.telecom.geography;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static md.utm.telecom.geography.GeographyResponses.*;

/** Reads only incidents_db; coverage joins on exact finalized-window identity and versions. */
@Repository
@Transactional(readOnly = true)
public class GeographyReadRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GeographyCatalogue catalogue;

    public GeographyReadRepository(JdbcTemplate jdbc, ObjectMapper json, GeographyCatalogue catalogue) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalogue = catalogue;
    }

    public CityList cities(Instant now) {
        requireActive();
        var rows = jdbc.query("""
                SELECT city_id, display_name FROM app.geo_cities
                WHERE catalogue_version = ? ORDER BY city_id
                """, (rs, n) -> new CityName(rs.getString(1).trim(), rs.getString(2)), catalogue.version());
        var summaries = rows.stream().map(city -> summary(city, now)).toList();
        return new CityList(now, catalogue.version(), catalogue.topologyVersion(), summaries);
    }

    public Optional<CityDetail> city(String cityId, Instant now) {
        requireActive();
        var rows = jdbc.query("""
                SELECT city_id, display_name FROM app.geo_cities
                WHERE catalogue_version = ? AND city_id = ?
                """, (rs, n) -> new CityName(rs.getString(1).trim(), rs.getString(2)),
                catalogue.version(), cityId);
        if (rows.isEmpty()) return Optional.empty();
        var summary = summary(rows.getFirst(), now);
        var footprints = jdbc.query("""
                SELECT DISTINCT footprint_node_id FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND city_id = ? AND NOT legacy
                ORDER BY footprint_node_id
                """, (rs, n) -> rs.getString(1), catalogue.version(), cityId);
        return Optional.of(new CityDetail(summary.cityId(), summary.displayName(), true,
                catalogue.version(), catalogue.topologyVersion(), summary.services(), now, footprints));
    }

    public KpiPage history(String cityId, String service, Instant from, Instant to, int page, int size) {
        requireActive();
        var scope = binding(cityId, service);
        if (scope.isEmpty()) {
            requireCity(cityId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Service is not mapped to city");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page too large");
        var rows = jdbc.query("""
                WITH ranked AS (
                  SELECT w.*, row_number() OVER (
                    PARTITION BY w.service, w.scope_id, w.window_start
                    ORDER BY w.received_at DESC, w.window_id DESC) AS rank
                  FROM app.service_kpi_windows w
                  WHERE w.scope_id = ? AND w.service = ?
                    AND w.window_start >= ? AND w.window_start < ?
                )
                SELECT w.window_id, w.scope_id, w.topology_version, w.window_start,
                       w.window_end, w.quality, w.payload::text, c.payload::text
                FROM ranked w LEFT JOIN app.scope_window_coverage c
                  ON c.window_id = w.window_id AND c.scope_id = w.scope_id
                  AND c.service = w.service AND c.window_start = w.window_start
                  AND c.window_end = w.window_end AND c.topology_version = w.topology_version
                  AND c.catalogue_version = ?
                WHERE w.rank = 1 AND w.topology_version = ?
                ORDER BY w.window_start, w.window_id LIMIT ? OFFSET ?
                """, (rs, n) -> new WindowRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6),
                rs.getString(7), rs.getString(8)), scope.get(), service, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to),
                catalogue.version(), catalogue.topologyVersion(), size + 1, offset);
        boolean hasNext = rows.size() > size;
        var points = rows.stream().limit(size).map(w -> new KpiPoint(w.id(), w.scope(),
                catalogue.version(), w.topology(), w.start(), w.end(), coverage(w.coverage(), false),
                metric(w.payload(), service, w.quality()))).toList();
        return new KpiPage(cityId, service, page, size, hasNext, points);
    }

    private CitySummary summary(CityName city, Instant now) {
        var services = List.of("VOLTE", "SMS").stream()
                .map(service -> serviceState(city.id(), service, now)).toList();
        return new CitySummary(city.id(), city.name(), true, catalogue.version(),
                catalogue.topologyVersion(), services);
    }

    private ServiceState serviceState(String cityId, String service, Instant now) {
        String scope = binding(cityId, service).orElseThrow(() -> new IllegalStateException("Missing city binding"));
        var rows = jdbc.query("""
                SELECT w.window_id, w.scope_id, w.topology_version, w.window_start,
                       w.window_end, w.quality, w.payload::text, c.payload::text
                FROM app.service_kpi_windows w LEFT JOIN app.scope_window_coverage c
                  ON c.window_id = w.window_id AND c.scope_id = w.scope_id
                  AND c.service = w.service AND c.window_start = w.window_start
                  AND c.window_end = w.window_end AND c.topology_version = w.topology_version
                  AND c.catalogue_version = ?
                WHERE w.scope_id = ? AND w.service = ? AND w.topology_version = ?
                  AND w.window_end <= ?
                ORDER BY w.window_start DESC, w.received_at DESC, w.window_id DESC LIMIT 1
                """, (rs, n) -> new WindowRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant(), rs.getString(6),
                rs.getString(7), rs.getString(8)), catalogue.version(), scope, service,
                catalogue.topologyVersion(), java.sql.Timestamp.from(now));
        long technical = jdbc.queryForObject("""
                SELECT count(*) FROM app.incidents WHERE scope_id = ? AND service = ?
                    AND technical_state = 'ONGOING'
                """, Long.class, scope, service);
        long analyst = jdbc.queryForObject("""
                SELECT count(*) FROM app.incidents WHERE scope_id = ? AND service = ?
                    AND status <> 'RESOLVED'
                """, Long.class, scope, service);
        if (rows.isEmpty()) return new ServiceState(service, scope, null, "NEVER_SEEN",
                technical, analyst, coverage(null, false), unavailable(service, "NEVER_SEEN"));
        var window = rows.getFirst();
        boolean stale = window.end().isBefore(now.minusSeconds(90));
        String freshness = window.quality().equals("MISSING") ? "MISSING" : stale ? "STALE" : "FRESH";
        return new ServiceState(service, scope, window.end(), freshness, technical, analyst,
                coverage(window.coverage(), stale), metric(window.payload(), service, window.quality()));
    }

    private Optional<String> binding(String cityId, String service) {
        return jdbc.query("""
                SELECT scope_id FROM app.geo_scope_bindings
                WHERE catalogue_version = ? AND city_id = ? AND service = ? AND NOT legacy
                """, (rs, n) -> rs.getString(1), catalogue.version(), cityId, service).stream().findFirst();
    }

    private void requireCity(String cityId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM app.geo_cities WHERE catalogue_version = ? AND city_id = ?
                """, Integer.class, catalogue.version(), cityId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown city");
    }

    private void requireActive() {
        if (!catalogue.active()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Geographic catalogue is not active");
    }

    private Coverage coverage(String payload, boolean stale) {
        if (payload == null) return new Coverage("UNKNOWN", null, null, null);
        JsonNode root = json.readTree(payload);
        int expected = root.path("expectedSourceIds").size();
        int received = root.path("receivedSourceIds").size();
        int usable = root.path("usableSourceIds").size();
        String state = stale ? "STALE" : usable == 0 ? "MISSING"
                : usable == expected ? "COMPLETE" : "PARTIAL";
        return new Coverage(state, expected, received, usable);
    }

    private Metric metric(String payload, String service, String quality) {
        if (quality.equals("MISSING")) return unavailable(service, "MISSING");
        JsonNode root = json.readTree(payload);
        String name = service.equals("VOLTE") ? "cssrPct" : "p95DeliveryMs";
        JsonNode item = null;
        for (var value : root.path("kpis"))
            if (value.path("name").asText().equals(name)) { item = value; break; }
        if (item == null) return unavailable(service, "UNSUPPORTED");
        Double observed = number(item.path("observed"));
        Double baseline = number(item.path("baseline"));
        Long numerator = integer(item.path("numerator"));
        Long denominator = integer(item.path("denominator"));
        String metricName = service.equals("VOLTE") ? "TECHNICAL_CSSR" : "SMS_DELIVERY_P95";
        String unit = service.equals("VOLTE") ? "PERCENT" : "MILLISECONDS";
        String reason = observed == null ? "INSUFFICIENT_DATA" : baseline == null ? "BASELINE_MISSING" : null;
        return new Metric(metricName, unit, observed, baseline,
                service.equals("VOLTE") && observed != null && baseline != null
                        ? java.math.BigDecimal.valueOf(observed)
                            .subtract(java.math.BigDecimal.valueOf(baseline)).doubleValue() : null,
                service.equals("SMS") && observed != null && baseline != null && baseline > 0
                        ? observed / baseline : null,
                numerator, denominator, service.equals("SMS") ? integer(kpi(root, "deliveredMessages")) : null,
                reason);
    }

    private JsonNode kpi(JsonNode root, String name) {
        for (var item : root.path("kpis")) if (item.path("name").asText().equals(name))
            return item.path("observed");
        return null;
    }

    private Metric unavailable(String service, String reason) {
        return new Metric(service.equals("VOLTE") ? "TECHNICAL_CSSR" : "SMS_DELIVERY_P95",
                service.equals("VOLTE") ? "PERCENT" : "MILLISECONDS",
                null, null, null, null, null, null, null, reason);
    }

    private static Double number(JsonNode value) { return value != null && value.isNumber() ? value.doubleValue() : null; }
    private static Long integer(JsonNode value) { return value != null && value.isIntegralNumber() ? value.longValue() : null; }
    private record CityName(String id, String name) {}
    private record WindowRow(String id, String scope, String topology, Instant start, Instant end,
                             String quality, String payload, String coverage) {}
}
