package md.utm.telecom.tracelink.capacity;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("load")
@RestController
public class CapacityController {
    private final AnalystAccess access;
    private final MeterRegistry meters;
    public CapacityController(AnalystAccess access, MeterRegistry meters) { this.access = access; this.meters = meters; }
    @GetMapping("/api/ops/capacity")
    public Map<String, Object> read(Authentication authentication) {
        access.requireEnabled(authentication);
        if (authentication.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_ADMIN"))) {
            throw new WorkflowProblem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Admin role is required.");
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("observedAt", java.time.Instant.now().toString());
        for (String name : java.util.List.of("hikaricp.connections.active", "hikaricp.connections.idle",
                "hikaricp.connections.pending", "incident.sse.connections", "incident.sse.queued",
                "jvm.memory.used", "process.cpu.usage")) {
            var gauges = meters.find(name).gauges();
            result.put(name, gauges.isEmpty() ? null : gauges.stream().mapToDouble(gauge -> gauge.value()).sum());
        }
        for (String name : java.util.List.of("incident.sse.queue.drops", "incident.sse.executor.rejects")) {
            var counters = meters.find(name).counters();
            result.put(name, counters.isEmpty() ? null : counters.stream().mapToDouble(counter -> counter.count()).sum());
        }
        var pool = meters.find("hikaricp.connections.acquire").timer();
        result.put("poolAcquireCount", pool == null ? null : pool.count());
        result.put("poolAcquireMeanMs", pool == null ? null : pool.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        var lag = meters.find("evidence.receipt.to.commit").timer();
        result.put("projectionSamples", lag == null ? null : lag.count());
        result.put("projectionMeanMs", lag == null ? null : lag.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        return result;
    }
}
