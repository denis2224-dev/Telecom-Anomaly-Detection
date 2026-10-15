package md.utm.telecom.tracelink.metrics;

import java.time.Instant;
import java.util.List;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
public class TraceDatasetController {
    private final JdbcTemplate jdbc;private final AnalystAccess access;
    public TraceDatasetController(JdbcTemplate jdbc,AnalystAccess access){this.jdbc=jdbc;this.access=access;}
    public record Dataset(String datasetId,Instant from,Instant to,boolean synthetic,String manifestSha256,boolean archiveReady,String catalogueVersion) {}
    @GetMapping("/api/v2/datasets")
    @Transactional(readOnly=true)
    public List<Dataset> list(Authentication actor){access.requireEnabled(actor);return jdbc.query("""
            SELECT d.dataset_id,d.history_from,d.history_to_exclusive,d.synthetic,d.manifest_sha256,
                   EXISTS(SELECT 1 FROM app.trace_reference_import i WHERE i.dataset_id=d.dataset_id) AS ready,
                   (SELECT c.catalogue_version FROM app.trace_catalogue c WHERE c.dataset_id=d.dataset_id
                     AND c.valid_from<=d.history_from AND (c.valid_to IS NULL OR c.valid_to>=d.history_to_exclusive)
                     ORDER BY c.valid_from DESC,c.catalogue_version LIMIT 1) AS catalogue_version
            FROM app.trace_dataset d ORDER BY d.imported_at DESC,d.dataset_id LIMIT 100
            """,(r,n)->new Dataset(r.getString(1),r.getTimestamp(2).toInstant(),r.getTimestamp(3).toInstant(),r.getBoolean(4),r.getString(5).trim(),r.getBoolean(6),r.getString(7)));}
}
