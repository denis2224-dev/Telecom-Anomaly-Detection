package md.utm.telecom.tracelink.metrics;

import java.sql.Timestamp;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Isolated durable ingestion entry point; no Kafka subscription is active. */
@Service
public class TracePrimitiveIngestionService {
    private final JdbcTemplate jdbc;

    public TracePrimitiveIngestionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Result ingest(byte[] payload) {
        TracePrimitiveProjection p = TracePrimitiveProjection.parse(payload);
        var prior = jdbc.query("""
                SELECT payload_bytes, payload_sha256 FROM app.trace_primitive_projection
                WHERE projection_id = ?
                """, (rs, row) -> new Existing(rs.getBytes(1), rs.getString(2)), p.id())
                .stream().findFirst();
        if (prior.isPresent()) {
            if (prior.get().hash.equals(p.hash()) && Arrays.equals(prior.get().bytes, p.bytes())) {
                return Result.IDENTICAL_RETRY;
            }
            throw new IllegalArgumentException("Conflicting immutable projection identity");
        }
        var registration = jdbc.query("""
                SELECT d.manifest_sha256, d.generator_version, d.seed, d.synthetic,
                       d.history_from, d.history_to_exclusive, d.source_completed_at,
                       c.topology_version, city.active
                FROM app.trace_dataset d
                JOIN app.trace_catalogue c ON c.dataset_id = d.dataset_id
                JOIN app.trace_inventory_city city ON city.catalogue_version = c.catalogue_version
                WHERE d.dataset_id = ? AND c.catalogue_version = ? AND c.active
                  AND city.city_id = ?
                """, (rs, row) -> new Registration(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getBoolean(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) != null, rs.getString(8), rs.getBoolean(9)),
                p.datasetId(), p.catalogueVersion(), p.cityId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No active matching dataset/catalogue/city"));
        if (!registration.completed || !registration.synthetic
                || !registration.manifest.equals(p.manifestSha256())
                || !registration.generator.equals(p.generatorVersion())
                || registration.seed != p.seed() || !registration.topology.equals(p.topologyVersion())
                || !registration.cityActive || p.from().isBefore(registration.from)
                || p.to().isAfter(registration.to)) {
            throw new IllegalArgumentException("Projection conflicts with completed registered source");
        }
        int inserted = jdbc.update("""
                INSERT INTO app.trace_primitive_projection
                    (projection_id, dataset_id, domain, metric_id, scope_type, scope_id,
                     bucket_start, bucket_end, numerator, denominator, value, null_reason,
                     expected_slots, received_slots, source_quality, source_coverage,
                     quality, quality_reason, policy_version, topology_version,
                     source_natural_id, source_file, source_row_number, source_row_sha256,
                     manifest_sha256, generator_version, seed, catalogue_version,
                     import_policy_version,
                     payload_bytes, payload_sha256)
                VALUES (?, ?, 'volte', 'volte.setup.success', 'CITY', ?, ?, ?, ?, ?, ?, ?,
                        1, 1, ?, ?, ?, ?, 'trace-link-kpi-v1', ?, ?, 'city_kpi_5m.csv',
                        ?, ?, ?, ?, ?, ?, 'trace-link-native-source-v1', ?, ?)
                ON CONFLICT DO NOTHING
                """, p.id(), p.datasetId(), p.cityId(), Timestamp.from(p.from()),
                Timestamp.from(p.to()), p.numerator(), p.denominator(), p.value(), p.nullReason(),
                p.sourceQuality(), p.sourceCoverage(), p.quality(), p.qualityReason(),
                p.topologyVersion(), p.sourceNaturalId(), p.sourceRowNumber(),
                p.sourceRowSha256(), p.manifestSha256(), p.generatorVersion(), p.seed(),
                p.catalogueVersion(), p.bytes(), p.hash());
        if (inserted == 1) return Result.INSERTED;
        var existing = jdbc.query("""
                SELECT payload_bytes, payload_sha256 FROM app.trace_primitive_projection
                WHERE projection_id = ?
                """, (rs, row) -> new Existing(rs.getBytes(1), rs.getString(2)), p.id())
                .stream().findFirst();
        if (existing.isPresent() && existing.get().hash.equals(p.hash())
                && Arrays.equals(existing.get().bytes, p.bytes())) return Result.IDENTICAL_RETRY;
        throw new IllegalArgumentException("Conflicting immutable projection identity or native slot");
    }

    public enum Result { INSERTED, IDENTICAL_RETRY }
    private record Existing(byte[] bytes, String hash) {}
    private record Registration(String manifest, String generator, long seed, boolean synthetic,
            java.time.Instant from, java.time.Instant to, boolean completed, String topology,
            boolean cityActive) {}
}
