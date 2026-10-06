package md.utm.telecom.geography;

import java.time.Instant;
import java.util.List;

/** Response shapes follow the canonical geography OpenAPI contract. */
public final class GeographyResponses {
    private GeographyResponses() {}

    public record Coverage(String state, Integer expectedSources, Integer receivedSources,
                           Integer usableSources) {}
    public record Metric(String name, String unit, Double observed, Double baseline,
                         Double deltaPp, Double delayRatio, Long numerator, Long denominator,
                         Long sampleCount, String nullReason) {}
    public record ServiceState(String service, String scopeId, Instant latestWindowEnd,
                               String freshness, long technicalActiveCount, long analystOpenCount,
                               Coverage coverage, Metric metric) {}
    public record CitySummary(String cityId, String displayName, boolean synthetic,
                              String catalogueVersion, String topologyVersion,
                              List<ServiceState> services) {}
    public record CityList(Instant generatedAt, String catalogueVersion, String topologyVersion,
                           List<CitySummary> cities) {}
    public record CityDetail(String cityId, String displayName, boolean synthetic,
                             String catalogueVersion, String topologyVersion,
                             List<ServiceState> services, Instant generatedAt,
                             List<String> footprintNodeIds) {}
    public record KpiPoint(String windowId, String scopeId, String catalogueVersion,
                           String topologyVersion, Instant windowStart, Instant windowEnd,
                           Coverage coverage, Metric metric) {}
    public record KpiPage(String cityId, String service, int page, int size, boolean hasNext,
                          List<KpiPoint> points) {}
}
