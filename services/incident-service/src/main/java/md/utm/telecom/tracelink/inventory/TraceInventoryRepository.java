package md.utm.telecom.tracelink.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import static md.utm.telecom.tracelink.inventory.InventoryResponses.*;

@Repository
@Transactional(readOnly = true)
public class TraceInventoryRepository {
    private final JdbcTemplate jdbc;

    public TraceInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Catalogue> activeCatalogue() {
        return jdbc.query("""
                SELECT catalogue_version, valid_from, active
                FROM app.trace_catalogue WHERE active
                """, (rs, row) -> new Catalogue(rs.getString(1), instant(rs, "valid_from"),
                rs.getBoolean(3))).stream().findFirst();
    }

    public Optional<Catalogue> catalogue(String version) {
        return jdbc.query("""
                SELECT catalogue_version, valid_from, active
                FROM app.trace_catalogue WHERE catalogue_version = ?
                """, (rs, row) -> new Catalogue(rs.getString(1), instant(rs, "valid_from"),
                rs.getBoolean(3)), version).stream().findFirst();
    }

    public List<City> cities(String version, boolean onlyActive) {
        return jdbc.query("""
                SELECT city_id, display_name, latitude, longitude, demand_share,
                       active, valid_from, valid_to, synthetic
                FROM app.trace_inventory_city
                WHERE catalogue_version = ? AND (? = FALSE OR active)
                ORDER BY city_id LIMIT 501
                """, (rs, row) -> new City(rs.getString("city_id"), rs.getString("display_name"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                rs.getBigDecimal("demand_share"), rs.getBoolean("active"),
                instant(rs, "valid_from"), instant(rs, "valid_to"),
                rs.getBoolean("synthetic")), version, onlyActive);
    }

    public List<Link> links(String version, boolean onlyActive, String cityId, Instant at) {
        StringBuilder sql = new StringBuilder("""
                SELECT link_id, endpoint_a_city_id, endpoint_b_city_id, link_type,
                       nominal_capacity_gbps, active, valid_from, valid_to, synthetic
                FROM app.trace_inventory_link
                WHERE catalogue_version = ? AND (? = FALSE OR active)
                """);
        List<Object> args = new ArrayList<>(List.of(version, onlyActive));
        if (cityId != null) {
            sql.append(" AND (endpoint_a_city_id = ? OR endpoint_b_city_id = ?)");
            args.add(cityId);
            args.add(cityId);
        }
        if (at != null) {
            sql.append(" AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)");
            args.add(Timestamp.from(at));
            args.add(Timestamp.from(at));
        }
        sql.append(" ORDER BY link_id LIMIT 501");
        Map<String, List<LinkDirection>> directions = directions(version, at);
        return jdbc.query(sql.toString(), (rs, row) -> new Link(rs.getString("link_id"),
                rs.getString("endpoint_a_city_id"), rs.getString("endpoint_b_city_id"),
                rs.getString("link_type"), rs.getBigDecimal("nominal_capacity_gbps"),
                rs.getBoolean("active"), instant(rs, "valid_from"), instant(rs, "valid_to"),
                directions.getOrDefault(rs.getString("link_id"), List.of()),
                rs.getBoolean("synthetic")), args.toArray());
    }

    public List<Partner> partners(String version, boolean onlyActive,
                                  String country, String direction) {
        StringBuilder sql = new StringBuilder("""
                SELECT partner_id, country_id, direction, active, valid_from, valid_to, synthetic
                FROM app.trace_partner
                WHERE catalogue_version = ? AND (? = FALSE OR active)
                """);
        List<Object> args = new ArrayList<>(List.of(version, onlyActive));
        if (country != null) {
            sql.append(" AND country_id = ?");
            args.add(country);
        }
        if (direction != null) {
            sql.append(" AND direction = ?");
            args.add(direction);
        }
        sql.append(" ORDER BY partner_id, direction LIMIT 501");
        return jdbc.query(sql.toString(), (rs, row) -> new Partner(rs.getString("partner_id"),
                rs.getString("country_id"), rs.getString("direction"), rs.getBoolean("active"),
                instant(rs, "valid_from"), instant(rs, "valid_to"),
                rs.getBoolean("synthetic")), args.toArray());
    }

    private Map<String, List<LinkDirection>> directions(String version, Instant at) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (link_id, direction_id) link_id, direction_id,
                       source_interface, destination_interface, effective_capacity_gbps,
                       capacity_valid_from, capacity_valid_to, measurement_source
                FROM app.trace_link_direction WHERE catalogue_version = ?
                """);
        List<Object> args = new ArrayList<>(List.of(version));
        if (at != null) {
            sql.append(" AND capacity_valid_from <= ? AND (capacity_valid_to IS NULL OR capacity_valid_to > ?)");
            args.add(Timestamp.from(at));
            args.add(Timestamp.from(at));
        }
        sql.append(" ORDER BY link_id, direction_id, capacity_valid_from DESC");
        Map<String, List<LinkDirection>> byLink = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            byLink.computeIfAbsent(rs.getString("link_id"), key -> new ArrayList<>())
                    .add(new LinkDirection(rs.getString("direction_id"),
                            rs.getString("source_interface"), rs.getString("destination_interface"),
                            rs.getBigDecimal("effective_capacity_gbps"),
                            instant(rs, "capacity_valid_from"), instant(rs, "capacity_valid_to"),
                            rs.getString("measurement_source")));
        }, args.toArray());
        return byLink;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Catalogue(String version, Instant validFrom, boolean active) {}
}
