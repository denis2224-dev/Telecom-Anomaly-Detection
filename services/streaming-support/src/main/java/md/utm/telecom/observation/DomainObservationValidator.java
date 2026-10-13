package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Additive boundary for reference-domain windows; never routes a document into V2. */
public final class DomainObservationValidator {
    private final JsonSchema schema;
    private final TraceLinkCatalog catalog;
    private final Clock clock;
    private static final List<String> ID_FIELDS = List.of("datasetId", "sourceId", "scopeNamespace", "domain",
            "scopeType", "scopeId", "directionId", "direction", "partnerId", "countryId", "procedure",
            "accessTechnology", "metricId", "windowStart", "sourceResolutionSeconds", "schemaVersion",
            "catalogueVersion", "topologyVersion", "policyVersion");

    public DomainObservationValidator() throws IOException { this(Clock.systemUTC()); }
    public DomainObservationValidator(Clock clock) throws IOException {
        this.clock = Objects.requireNonNull(clock);
        catalog = new TraceLinkCatalog();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("observations/trace-link-domain-window-v1.schema.json", new ObjectMapper()),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
    }

    public void validate(JsonNode e) {
        require(schema.validate(e).isEmpty(), "Invalid domain-window schema");
        finite(e);
        var start = Instant.parse(e.path("windowStart").asText());
        var end = Instant.parse(e.path("windowEnd").asText());
        var emitted = Instant.parse(e.path("emittedAt").asText());
        int seconds = e.path("sourceResolutionSeconds").intValue();
        require(start.getNano() == 0 && Math.floorMod(start.getEpochSecond(), seconds) == 0
                && Duration.between(start, end).equals(Duration.ofSeconds(seconds)), "Unaligned native interval");
        require(!emitted.isBefore(end) && !emitted.isAfter(clock.instant()), "Invalid/future event time");
        require(identity(e).equals(e.path("eventId").asText()), "Identity mismatch");
        var metric = catalog.metric(e.path("metricId").asText());
        for (String field : List.of("domain", "population", "unit", "policyVersion"))
            require(e.path(field).equals(metric.path(field)), "Dictionary mismatch: " + field);
        require(e.path("aggregationType").equals(metric.path("aggregation")), "Aggregation mismatch");
        Set<String> expected = new HashSet<>();
        metric.path("primitiveFields").forEach(n -> expected.add(n.asText()));
        Set<String> actual = new HashSet<>();
        e.path("primitives").fieldNames().forEachRemaining(actual::add);
        require(expected.equals(actual), "Primitive fields differ from dictionary");
        String domain = e.path("domain").asText();
        Set<String> allowedScope = new HashSet<>();
        switch (domain) {
            case "TRANSPORT" -> {
                allowedScope.addAll(List.of("linkId", "directionId", "transport"));
                require(e.path("scopeType").asText().equals("LINK_DIRECTION")
                        && e.path("scopeId").equals(e.path("linkId")), "Transport scope mismatch");
                catalog.link(e.path("linkId").asText());
                require(e.has("directionId") && e.has("transport"), "Transport direction/capacity required");
                if (!e.path("transport").isNull()) {
                    var t = e.path("transport");
                    require(!Instant.parse(t.path("validFrom").asText()).isAfter(start)
                            && !Instant.parse(t.path("validTo").asText()).isBefore(end), "Capacity interval does not cover window");
                }
            }
            case "ROAMING" -> {
                allowedScope.addAll(List.of("partnerId", "countryId", "direction", "procedure"));
                require(e.path("scopeType").asText().equals("ROAMING_PROCEDURE")
                        && e.path("scopeId").equals(e.path("partnerId")), "Roaming scope mismatch");
                for (String f : allowedScope) require(e.has(f), "Missing roaming dimension: " + f);
                String country = e.path("countryId").asText();
                require(Set.of("RO","IT","DE","FR","GB","ES","UA","TR","BG","PL").contains(country)
                        && e.path("partnerId").asText().equals(country + "-DEMO-01"), "Unknown country/partner");
                String procedure = switch (e.path("metricId").asText()) {
                    case "roaming.registration.success" -> "REGISTRATION";
                    case "roaming.data.success" -> "DATA";
                    case "roaming.voice.success" -> "VOICE";
                    case "roaming.sms.finalizedSuccess" -> "SMS";
                    case "roaming.partnerRtt" -> "RTT";
                    default -> "ACTIVE_USERS";
                };
                require(e.path("procedure").asText().equals(procedure), "Roaming procedure mismatch");
                if (e.path("metricId").asText().equals("roaming.activeInbound"))
                    require(e.path("direction").asText().equals("in"), "Inbound snapshot direction");
                if (e.path("metricId").asText().equals("roaming.activeOutbound"))
                    require(e.path("direction").asText().equals("out"), "Outbound snapshot direction");
            }
            default -> {
                allowedScope.add("contractCityId");
                require(e.path("scopeType").asText().equals("CITY_SERVICE"), "City scope required");
                require(catalog.city(e.path("scopeId").asText()).path("contractCityId").equals(e.path("contractCityId")),
                        "Explicit reference/contract city binding required");
                if (domain.equals("MOBILE_DATA")) {
                    allowedScope.addAll(List.of("procedure", "accessTechnology"));
                    String procedure = switch (e.path("metricId").asText()) {
                        case "mobileData.activation.success" -> "EPS_BEARER_ACTIVATION";
                        case "mobileData.dns.success" -> "DNS";
                        default -> "USER_THROUGHPUT";
                    };
                    require(e.path("accessTechnology").asText().equals("LTE")
                            && e.path("procedure").asText().equals(procedure), "LTE procedure required");
                }
            }
        }
        for (String field : List.of("contractCityId","linkId","directionId","transport","countryId","partnerId",
                "direction","procedure","accessTechnology"))
            require(!e.has(field) || allowedScope.contains(field), "Irrelevant scope field: " + field);
        var p = e.path("primitives");
        String quality = e.path("quality").asText();
        double coverage = e.path("coverage").doubleValue();
        boolean missing = quality.equals("MISSING"), unavailable = quality.equals("INSUFFICIENT_EVIDENCE");
        if (missing) {
            require(coverage == 0, "Missing coverage must be zero");
            p.forEach(v -> require(v.isNull(), "Missing measurement contains evidence"));
            require(!e.has("transport") || e.path("transport").isNull(), "Missing transport contains evidence");
            return;
        }
        require(coverage > 0, "Observed evidence needs coverage");
        if (quality.equals("VALID")) require(coverage == 1, "Valid evidence requires full coverage");
        if (quality.equals("PARTIAL")) require(coverage < 1, "Partial evidence requires partial coverage");
        if (!unavailable) p.forEach(v -> require(!v.isNull(), "Observed primitive cannot be null"));
        if (domain.equals("TRANSPORT") && !unavailable) require(!e.path("transport").isNull(), "Absent effective capacity");
        if (p.hasNonNull("denominator")) {
            require(!p.hasNonNull("numerator") || p.path("numerator").decimalValue().compareTo(p.path("denominator").decimalValue()) <= 0,
                    "Success exceeds attempts");
            require(p.path("denominator").decimalValue().signum() != 0 || unavailable, "Zero denominator needs insufficient evidence");
        }
        for (String f : List.of("count", "validSeconds"))
            if (p.hasNonNull(f) && Set.of("WEIGHTED_MEAN", "TIME_WEIGHTED_MEAN").contains(e.path("aggregationType").asText()))
                require(p.path(f).decimalValue().signum() > 0 || unavailable, "Zero weight needs insufficient evidence");
        if (p.hasNonNull("validSeconds")) require(p.path("validSeconds").doubleValue() <= seconds, "Weight exceeds native duration");
        for (String f : List.of("snapshotAt", "at")) if (p.hasNonNull(f)) {
            var at = Instant.parse(p.path(f).asText());
            require((quality.equals("STALE") || !at.isBefore(start)) && at.isBefore(end), "Sample outside half-open window");
        }
        if (quality.equals("STALE")) require(e.path("aggregationType").asText().equals("SNAPSHOT_LAST"), "Stale requires retained snapshot");
        if (p.hasNonNull("histogram")) {
            var histogram = p.path("histogram");
            var definition = metric.path("histogramSchema");
            require(histogram.path("schemaVersion").equals(definition.path("version"))
                    && histogram.path("unit").equals(definition.path("unit"))
                    && histogram.path("representation").equals(definition.path("representation")), "Histogram schema metadata");
            var edges = definition.path("edges");
            long total = 0;
            for (int i = 0; i < edges.size(); i++) {
                require(histogram.path("edges").get(i).decimalValue().compareTo(edges.get(i).decimalValue()) == 0, "Incompatible histogram edges");
                total = Math.addExact(total, histogram.path("counts").get(i).longValue());
            }
            require(total > 0 || unavailable, "Empty histogram needs insufficient evidence");
        }
    }

    /** Revision is intentionally excluded: a revision belongs to the same natural observation. */
    public static String identity(JsonNode e) {
        var parts = new ArrayList<String>();
        for (String key : ID_FIELDS) {
            String value = e.has(key) ? e.path(key).asText() : "-";
            if (key.equals("windowStart")) value = Instant.parse(value).toString();
            require(!value.contains("|"), "Identity dimension contains delimiter");
            parts.add(value);
        }
        return sha256(String.join("|", parts));
    }

    public static String partitionKey(JsonNode e) {
        String state = switch (e.path("scopeType").asText()) {
            case "LINK_DIRECTION" -> e.path("linkId").asText() + "|" + e.path("directionId").asText();
            case "ROAMING_PROCEDURE" -> e.path("partnerId").asText() + "|" + e.path("direction").asText() + "|" + e.path("procedure").asText();
            default -> e.path("domain").asText() + "|" + e.path("scopeId").asText();
        };
        return e.path("datasetId").asText() + "|" + TraceLinkCatalog.NAMESPACE + "|" + state;
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    private static void finite(JsonNode value) {
        if (value.isNumber()) require(Double.isFinite(value.doubleValue()), "Non-finite measurement");
        else if (value.isContainerNode()) value.forEach(DomainObservationValidator::finite);
    }
}
