package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import md.utm.telecom.observation.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

/** Pure compilation of genuine minute primitives. No archive reader, Spring bean or transport. */
public final class TraceLinkFixtureGenerator {
    public static final String DATASET = TraceLinkSyntheticSource.DATASET;
    public static final String VERSION = TraceLinkSyntheticSource.GENERATOR;
    private final ObjectMapper json = new ObjectMapper();
    private final TraceLinkCatalog inventory;
    private final TraceLinkScenarioCatalogue catalogue;
    public TraceLinkFixtureGenerator() throws java.io.IOException { inventory = new TraceLinkCatalog(); catalogue = new TraceLinkScenarioCatalogue(); }
    public record Window(Instant start, List<String> observations) {
        public Window { observations = List.copyOf(observations); }
        public String identity() { return DomainObservationValidator.sha256(String.join("\n", observations)); }
    }
    public List<Window> generate(Command c) throws java.io.IOException {
        catalogue.validate(c);
        var windows = new ArrayList<Window>();
        for (int minute = 0; minute < 8; minute++) {
            Instant start = c.start().plusSeconds(minute * 60L);
            boolean fault = phase(minute) == Phase.DEGRADED && c.type() != Type.NORMAL_CONTROL;
            boolean missing = c.parameters().missingMinutes().contains(minute) || fault && c.type() == Type.TELEMETRY_GAP;
            var observations = new ArrayList<String>();
            for (String metric : metrics(c.scope().domain())) {
                var e = envelope(c, start, metric);
                var p = (ObjectNode) e.path("primitives");
                if (!missing) measurements(c, e, p, fault);
                else { e.put("quality", "MISSING"); e.put("coverage", 0); }
                e.put("eventId", DomainObservationValidator.identity(e));
                // Compilation time is event time. Runtime release separately validates against its injected clock.
                new TraceLinkSyntheticSource(Clock.fixed(start.plusSeconds(60), ZoneOffset.UTC)).resolve(e);
                observations.add(e.toString());
            }
            windows.add(new Window(start, observations));
        }
        return List.copyOf(windows);
    }
    private static List<String> metrics(String domain) {
        return switch (domain) {
            case "TRANSPORT" -> List.of("transport.traffic", "transport.effectiveCapacity", "transport.link.utilization");
            case "ROAMING" -> List.of("roaming.registration.success");
            case "MOBILE_DATA" -> List.of("mobileData.dns.success", "mobileData.activation.success");
            default -> throw new IllegalArgumentException("Domain generator not yet implemented");
        };
    }
    private ObjectNode envelope(Command c, Instant start, String metric) {
        var m = inventory.metric(metric); var s = c.scope();
        var e = json.createObjectNode().put("schemaVersion", "trace-link-domain-window-v1").put("datasetId", DATASET)
                .put("synthetic", true).put("sourceId", VERSION + "-seed-" + c.seed()).put("scopeNamespace", TraceLinkCatalog.NAMESPACE)
                .put("catalogueVersion", "trace-link-geography-v2").put("topologyVersion", s.topologyVersion())
                .put("policyVersion", m.path("policyVersion").asText()).put("domain", s.domain()).put("scopeId", s.id())
                .put("windowStart", start.toString()).put("windowEnd", start.plusSeconds(60).toString()).put("sourceResolutionSeconds", 60)
                .put("emittedAt", start.plusSeconds(60).toString()).put("revision", 0).put("finalized", true)
                .put("metricId", metric).put("population", m.path("population").asText()).put("unit", m.path("unit").asText())
                .put("aggregationType", m.path("aggregation").asText()).put("quality", "VALID").put("coverage", 1);
        switch (s.domain()) {
            case "TRANSPORT" -> { e.put("scopeType", "LINK_DIRECTION").put("linkId", s.id()).put("directionId", s.direction()); e.putNull("transport"); }
            case "ROAMING" -> e.put("scopeType", "ROAMING_PROCEDURE").put("partnerId", s.id()).put("countryId", s.country())
                    .put("direction", s.direction()).put("procedure", s.procedure());
            case "MOBILE_DATA" -> e.put("scopeType", "CITY_SERVICE").put("contractCityId", inventory.city(s.id()).path("contractCityId").asText())
                    .put("accessTechnology", "LTE").put("procedure", metric.equals("mobileData.dns.success") ? "DNS" : "EPS_BEARER_ACTIVATION");
            default -> throw new IllegalArgumentException("Unsupported domain");
        }
        var p = e.putObject("primitives"); m.path("primitiveFields").forEach(f -> p.putNull(f.asText()));
        return e;
    }
    private void measurements(Command c, ObjectNode e, ObjectNode p, boolean fault) {
        var settings = c.parameters();
        // SHA-derived, metric-independent per-scope/window load variation; never dependent on execution order.
        String hash = DomainObservationValidator.sha256(VERSION + "|" + c.version() + "|" + c.seed() + "|" + c.scope().key() + "|" + e.path("windowStart").asText());
        int variation = Integer.parseInt(hash.substring(0, 4), 16) % 51;
        String metric = e.path("metricId").asText();
        if (!c.scope().domain().equals("TRANSPORT")) {
            // DNS and bearer activation have distinct generated populations, not inferred counters.
            int countVariation = Integer.parseInt(DomainObservationValidator.sha256(hash + "|" + metric).substring(0, 4), 16) % 51;
            int attempts = settings.attempts() == 0 ? 0 : settings.attempts() + countVariation;
            int successBp = fault && (c.type() == Type.ROAMING_REGISTRATION_FAILURE
                    || c.type() == Type.MOBILE_DATA_DNS_FAILURE && metric.equals("mobileData.dns.success"))
                    ? settings.degradedSuccessBp() : settings.healthySuccessBp();
            p.put("denominator", attempts).put("numerator", (long) attempts * successBp / 10000);
            if (attempts == 0) e.put("quality", "INSUFFICIENT_EVIDENCE");
            return;
        }
        int bp = fault && c.type() == Type.TRANSPORT_LINK_OVERLOAD ? settings.degradedUtilizationBp() : settings.normalUtilizationBp();
        bp = Math.max(0, bp - variation);
        BigDecimal capacity = BigDecimal.valueOf(settings.capacityGbps());
        BigDecimal traffic = capacity.multiply(BigDecimal.valueOf(bp, 4));
        e.putObject("transport").put("trafficGbps", traffic).put("effectiveCapacityGbps", capacity)
                .put("validFrom", e.path("windowStart").asText()).put("validTo", e.path("windowEnd").asText());
        switch (metric) {
            case "transport.traffic" -> { p.put("weightedSum", traffic.multiply(BigDecimal.valueOf(60))); p.put("validSeconds", 60); }
            case "transport.link.utilization" -> { p.put("weightedSum", BigDecimal.valueOf(bp, 2).multiply(BigDecimal.valueOf(60))); p.put("validSeconds", 60); }
            case "transport.effectiveCapacity" -> { p.put("value", capacity); p.put("snapshotAt", e.path("windowStart").asText()); }
            default -> throw new IllegalArgumentException("Unsupported metric");
        }
    }
}
