package md.utm.telecom.tracelink.metrics;

import java.time.Instant;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class TraceSnapshotController {
    private final AnalystAccess access;
    private final TraceSnapshotService snapshots;
    public TraceSnapshotController(AnalystAccess access, TraceSnapshotService snapshots) {
        this.access = access;
        this.snapshots = snapshots;
    }
    @GetMapping("/api/v2/transport/snapshot")
    public TraceSnapshotService.SnapshotList transport(@RequestParam String datasetId,
            @RequestParam String at, @RequestParam(required = false) String cityId, Authentication authentication) {
        access.requireEnabled(authentication);
        return snapshots.read(datasetId, "transport", utc(at), cityId, null, null);
    }
    @GetMapping("/api/v2/roaming/snapshot")
    public TraceSnapshotService.SnapshotList roaming(@RequestParam String datasetId,
            @RequestParam String at, @RequestParam String direction,
            @RequestParam(required = false) String country, Authentication authentication) {
        access.requireEnabled(authentication);
        return snapshots.read(datasetId, "roaming", utc(at), null, direction, country);
    }
    private static Instant utc(String text) {
        if (!text.endsWith("Z")) throw new IllegalArgumentException("UTC timestamp required");
        return Instant.parse(text);
    }
}
