package md.utm.telecom.tracelink.metrics;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MetricScopeRepository {
    private final JdbcTemplate jdbc;
    public MetricScopeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Scope resolve(TraceQuery query) {
        String sql;
        String direction = "";
        String partner = "";
        String procedure = "";
        String entity = query.scopeId();
        if (query.domain().equals("transport") && query.scopeType().equals("LINK_DIRECTION")) {
            int split = entity.lastIndexOf(':');
            if (split < 1) throw invalid();
            direction = entity.substring(split + 1);
            entity = entity.substring(0, split);
            if (!List.of("a-to-b", "b-to-a").contains(direction)) throw invalid();
            sql = """
                    SELECT c.topology_version
                    FROM app.trace_catalogue c JOIN app.trace_inventory_link i USING (catalogue_version)
                    JOIN app.trace_link_direction d USING (catalogue_version, link_id)
                    WHERE c.dataset_id = ? AND i.link_id = ? AND d.direction_id = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM app.trace_link_direction a JOIN app.trace_link_direction b
                              USING (catalogue_version, link_id, direction_id)
                          WHERE a.catalogue_version = i.catalogue_version AND a.link_id = i.link_id
                            AND a.direction_id = d.direction_id AND a.capacity_valid_from < b.capacity_valid_from
                            AND tstzrange(a.capacity_valid_from, a.capacity_valid_to, '[)')
                                && tstzrange(b.capacity_valid_from, b.capacity_valid_to, '[)')
                      )
                    GROUP BY c.topology_version
                    HAVING range_agg(tstzrange(d.capacity_valid_from, d.capacity_valid_to, '[)'))
                        @> tstzrange(?::timestamptz, ?::timestamptz, '[)')
                    """;
        } else if (query.domain().equals("roaming") && query.scopeType().equals("PARTNER_DIRECTION")) {
            int split = entity.lastIndexOf(':');
            if (split < 1) throw invalid();
            direction = entity.substring(split + 1);
            partner = entity.substring(0, split);
            entity = partner;
            procedure = query.metric().contains("voice") ? "voice" : query.metric().contains("data") ? "data" : "registration";
            if (!List.of("in", "out").contains(direction)) throw invalid();
            sql = """
                    SELECT DISTINCT c.topology_version
                    FROM app.trace_catalogue c JOIN app.trace_partner i USING (catalogue_version)
                    WHERE c.dataset_id = ? AND i.partner_id = ? AND i.direction = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                    """;
        } else if (List.of("volte", "sms", "mobile-data").contains(query.domain()) && query.scopeType().equals("CITY")) {
            sql = """
                    SELECT DISTINCT c.topology_version
                    FROM app.trace_catalogue c JOIN app.trace_inventory_city i USING (catalogue_version)
                    WHERE c.dataset_id = ? AND i.city_id = ?
                      AND c.valid_from <= ? AND (c.valid_to IS NULL OR c.valid_to >= ?)
                      AND i.valid_from <= ? AND (i.valid_to IS NULL OR i.valid_to >= ?)
                    """;
        } else throw invalid();
        var args = new java.util.ArrayList<Object>();
        args.add(query.datasetId());
        args.add(entity);
        if (!direction.isEmpty()) args.add(direction);
        int pairs = query.domain().equals("transport") ? 3 : 2;
        for (int index = 0; index < pairs; index++) {
            args.add(Timestamp.from(query.from()));
            args.add(Timestamp.from(query.to()));
        }
        var versions = jdbc.query(sql, (rs, row) -> rs.getString(1), args.toArray());
        if (versions.size() != 1) throw invalid();
        return new Scope(versions.getFirst(), direction, partner, procedure);
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scope is not valid throughout the dataset query");
    }
    public record Scope(String topologyVersion, String direction, String partnerId, String procedure) {}
}
