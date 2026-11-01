package md.utm.telecom.tracelink.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import md.utm.telecom.tracelink.RoamingDemoEvidence;
import md.utm.telecom.tracelink.TraceLinkIncidentService;
import md.utm.telecom.tracelink.metrics.TraceDatasetController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoamingDemoService {
    private final boolean enabled;
    private final Clock clock;
    private final ObjectProvider<TraceLinkIncidentService> incidents;
    private Instant completed;
    public RoamingDemoService(@Value("${telecom.roaming-demo.enabled:false}") boolean enabled,
            Clock clock, ObjectProvider<TraceLinkIncidentService> incidents) {
        this.enabled = enabled; this.clock = clock; this.incidents = incidents;
    }
    public md.utm.telecom.tracelink.metrics.MetricSeriesResponse series(md.utm.telecom.tracelink.metrics.TraceQuery query) { return RoamingDemoData.series(query,clock.instant()); }
    public boolean enabled() { return enabled; }
    public TraceDatasetController.Dataset dataset() {
        Instant end = RoamingDemoData.completedAt(clock.instant());
        return new TraceDatasetController.Dataset(RoamingDemoData.DATASET,end.minusSeconds(30*86400L),end,true,
                RoamingDemoData.manifestSha256(),true,RoamingDemoData.CATALOGUE);
    }
    /** Replays are validated and deduplicated by the normal incident service, including after restarts. */
    @Scheduled(initialDelayString="${telecom.roaming-demo.initial-delay:1000}", fixedDelay=60000)
    public synchronized void publish() {
        if (!enabled) return;
        var receiver = incidents.getIfAvailable();
        if (receiver == null) throw new IllegalStateException("Synthetic roaming requires Trace Link incident workflow enabled");
        Instant end = RoamingDemoData.completedAt(clock.instant());
        if (end.equals(completed)) return;
        Instant begin = completed == null ? end.minusSeconds(30*86400L) : completed;
        long cycle = completed == null ? Math.floorDiv(begin.getEpochSecond()+10799,10800)*10800 : Math.floorDiv(begin.getEpochSecond(),10800)*10800;
        for (; cycle < end.getEpochSecond(); cycle += 10800) {
            for (var stream : List.of(new String[]{"RO","out",RoamingDemoData.METRICS.get(0)},
                    new String[]{"IT","out",RoamingDemoData.METRICS.get(1)},new String[]{"DE","in",RoamingDemoData.METRICS.get(2)})) {
                for (int step=1;step<=6;step++) {
                    Instant windowEnd = Instant.ofEpochSecond(cycle+(step+1)*300L);
                    if (windowEnd.isAfter(end) || completed != null && !windowEnd.isAfter(completed)) continue;
                    var value = RoamingDemoEvidence.envelope(RoamingDemoData.partner(stream[0]),stream[1],stream[2],Instant.ofEpochSecond(cycle),step);
                    receiver.ingest(value.path("lifecycle").path("episodeId").asText(),value.toString());
                }
            }
        }
        completed = end;
    }
}
