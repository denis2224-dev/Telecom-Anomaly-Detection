package md.utm.telecom.generator.tracelink;

import java.time.Instant;
import java.util.*;
import md.utm.telecom.observation.DomainObservationValidator;
import md.utm.telecom.observation.TraceLinkCatalog;

/** Versioned command boundary only. Definitions are never detector observations. */
public final class TraceLinkScenarioCatalogue {
    public static final String VERSION = "trace-link-scenarios-v1";
    public static final String TOPOLOGY = "trace-link-links-v1";
    public enum Type {
        VOLTE_SETUP_FAILURE, VOLTE_SETUP_DELAY, VOLTE_MEDIA_QUALITY, VOLTE_HANDOVER_FAILURE,
        TRANSPORT_LINK_OVERLOAD, TRANSPORT_PACKET_LOSS, TRANSPORT_RTT_INCREASE, TRANSPORT_CAPACITY_REDUCTION,
        ROAMING_REGISTRATION_FAILURE, ROAMING_DATA_FAILURE, ROAMING_PARTNER_DEGRADATION,
        MOBILE_DATA_ACTIVATION_FAILURE, MOBILE_DATA_DNS_FAILURE,
        SMS_DELIVERY_DELAY, SMS_QUEUE_CONGESTION, SMS_ROUTE_FAILURE, NORMAL_CONTROL, TELEMETRY_GAP
    }
    public enum Phase { NORMAL, DEGRADED, RECOVERY }
    public record Range(double minimum, double maximum) {}
    public record Definition(Type type, String version, Set<String> domains, Set<String> scopeTypes,
                             boolean implemented, Map<String, Range> parameterRanges,
                             String expectedPrimitiveChanges, String positiveControl, String negativeControl) {
        public Definition { domains = Set.copyOf(domains); scopeTypes = Set.copyOf(scopeTypes); parameterRanges = Map.copyOf(parameterRanges); }
        public int durationSeconds() { return 480; }
        public List<Phase> timeProfile() { return List.of(Phase.NORMAL, Phase.NORMAL, Phase.DEGRADED,
                Phase.DEGRADED, Phase.DEGRADED, Phase.RECOVERY, Phase.RECOVERY, Phase.RECOVERY); }
    }
    /** Reference inventory identity; does not activate legacy geographic authority. */
    public record Scope(String domain, String id, String direction, String country, String procedure,
                        String topologyVersion) {
        public Scope { require(domain != null && id != null && topologyVersion != null, "Required scope identity"); }
        public static Scope transport(String link, String direction) {
            return new Scope("TRANSPORT", link, direction, null, null, TOPOLOGY);
        }
        public static Scope roaming(String partner, String country, String direction) {
            return new Scope("ROAMING", partner, direction, country, "REGISTRATION", TOPOLOGY);
        }
        public static Scope data(String city) {
            return new Scope("MOBILE_DATA", city, null, null, "DNS", TOPOLOGY);
        }
        public String type() { return domain.equals("TRANSPORT") ? "LINK_DIRECTION" : domain.equals("ROAMING") ? "ROAMING_PROCEDURE" : "CITY_SERVICE"; }
        public String key() { return String.join("|", domain, id, Objects.toString(direction, "-"),
                Objects.toString(country, "-"), Objects.toString(procedure, "-"), topologyVersion); }
    }
    /** Percentages are integer basis points. Missing minute ordinals are zero-based, 0..7. */
    public record Parameters(int normalUtilizationBp, int degradedUtilizationBp, int capacityGbps,
                             int attempts, int healthySuccessBp, int degradedSuccessBp, Set<Integer> missingMinutes) {
        public Parameters { missingMinutes = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(missingMinutes))); }
        public static Parameters defaults() { return new Parameters(5000, 9800, 20, 1000, 9900, 7000, Set.of()); }
        public String canonical() { return normalUtilizationBp + "|" + degradedUtilizationBp + "|" + capacityGbps
                + "|" + attempts + "|" + healthySuccessBp + "|" + degradedSuccessBp + "|" + missingMinutes; }
    }
    public record Command(Type type, String version, Scope scope, long seed, Instant start, Parameters parameters) {
        public Command { Objects.requireNonNull(type); Objects.requireNonNull(version); Objects.requireNonNull(scope);
            Objects.requireNonNull(start); Objects.requireNonNull(parameters); }
        public Instant end() { return start.plusSeconds(480); }
        public String fingerprint() { return DomainObservationValidator.sha256(type + "|" + version + "|" + scope.key()
                + "|" + seed + "|" + start + "|" + parameters.canonical()); }
        public String runId() { return fingerprint(); }
    }
    private final TraceLinkCatalog inventory;
    public TraceLinkScenarioCatalogue() throws java.io.IOException { inventory = new TraceLinkCatalog(); }

    public Definition definition(Type type) {
        String domain = type.name().startsWith("VOLTE") ? "VOLTE" : type.name().startsWith("SMS") ? "SMS"
                : type.name().startsWith("TRANSPORT") ? "TRANSPORT" : type.name().startsWith("ROAMING") ? "ROAMING" : "MOBILE_DATA";
        boolean control = type == Type.NORMAL_CONTROL || type == Type.TELEMETRY_GAP;
        boolean implemented = control || Set.of(Type.TRANSPORT_LINK_OVERLOAD, Type.ROAMING_REGISTRATION_FAILURE,
                Type.MOBILE_DATA_DNS_FAILURE).contains(type);
        String changes = switch (type) {
            case TRANSPORT_LINK_OVERLOAD -> "Carried rate and valid-time utilization rise; capacity stays constant; no packet or outage inference.";
            case ROAMING_REGISTRATION_FAILURE -> "Registration successes fall within their independent attempt population.";
            case MOBILE_DATA_DNS_FAILURE -> "DNS successes fall; separate EPS bearer activation counters remain healthy.";
            case NORMAL_CONTROL -> "Healthy measured populations, including optionally high carried utilization.";
            case TELEMETRY_GAP -> "Missing primitives and zero coverage in minutes 3-5; missing is never recovery.";
            case VOLTE_SETUP_FAILURE -> "UNSUPPORTED: requires independent setup attempts/successes.";
            case VOLTE_SETUP_DELAY -> "UNSUPPORTED: requires successful-setup elapsed samples/histograms.";
            case VOLTE_MEDIA_QUALITY -> "UNSUPPORTED: requires measured media loss/jitter, not setup failure.";
            case VOLTE_HANDOVER_FAILURE -> "UNSUPPORTED: requires independent handover attempts/successes.";
            case TRANSPORT_PACKET_LOSS -> "UNSUPPORTED: requires genuinely measured expected/lost packet counts.";
            case TRANSPORT_RTT_INCREASE -> "UNSUPPORTED: requires identified RTT sample population.";
            case TRANSPORT_CAPACITY_REDUCTION -> "UNSUPPORTED: requires a changed positive capacity interval; cannot imply LAG failure.";
            case ROAMING_DATA_FAILURE -> "UNSUPPORTED: requires separate roaming data attempts/successes.";
            case ROAMING_PARTNER_DEGRADATION -> "UNSUPPORTED: requires explicitly selected independent procedures/directions.";
            case MOBILE_DATA_ACTIVATION_FAILURE -> "UNSUPPORTED: requires failed activation attempts without assuming DNS failure.";
            case SMS_DELIVERY_DELAY -> "UNSUPPORTED: requires delivered-message elapsed samples.";
            case SMS_QUEUE_CONGESTION -> "UNSUPPORTED: requires queue snapshots independent of finalized delivery success.";
            case SMS_ROUTE_FAILURE -> "UNSUPPORTED: requires explicit route-scoped finalized outcomes.";
        };
        return new Definition(type, VERSION, control ? Set.of("TRANSPORT", "ROAMING", "MOBILE_DATA") : Set.of(domain),
                control ? Set.of("LINK_DIRECTION", "ROAMING_PROCEDURE", "CITY_SERVICE") : Set.of(domain.equals("TRANSPORT") ? "LINK_DIRECTION"
                        : domain.equals("ROAMING") ? "ROAMING_PROCEDURE" : "CITY_SERVICE"),
                implemented, Map.of("normalUtilizationBp", new Range(0, 10000), "degradedUtilizationBp", new Range(0, 10000),
                        "capacityGbps", new Range(1, 1000), "attempts", new Range(0, 1000000),
                        "healthySuccessBp", new Range(0, 10000), "degradedSuccessBp", new Range(0, 10000),
                        "seed", new Range(0, Long.MAX_VALUE), "missingMinute", new Range(0, 7)),
                changes, "Measured symptom only; no incident command or probable-cause assertion.",
                "Other scopes/procedures stay independent; stale/missing cannot prove technical recovery.");
    }
    public List<Definition> definitions() { return Arrays.stream(Type.values()).map(this::definition).toList(); }
    public Definition validate(Command c) {
        Objects.requireNonNull(c);
        var d = definition(c.type());
        require(d.implemented(), "UNSUPPORTED_SCENARIO");
        require(c.version().equals(VERSION) && c.scope().topologyVersion().equals(TOPOLOGY), "Unsupported version");
        require(c.seed() >= 0 && c.start().getNano() == 0 && Math.floorMod(c.start().getEpochSecond(), 60) == 0,
                "Seed/start must be nonnegative/aligned UTC minute");
        require(d.domains().contains(c.scope().domain()) && d.scopeTypes().contains(c.scope().type()), "Scenario/domain/scope mismatch");
        var s = c.scope();
        switch (s.domain()) {
            case "TRANSPORT" -> { inventory.link(s.id()); require(s.direction() != null && Set.of("a-to-b", "b-to-a").contains(s.direction())
                    && s.country() == null && s.procedure() == null, "Invalid link direction"); }
            case "ROAMING" -> require(s.country() != null && Set.of("RO","IT","DE","FR","GB","ES","UA","TR","BG","PL").contains(s.country())
                    && s.id().equals(s.country() + "-DEMO-01") && s.direction() != null && Set.of("in", "out").contains(s.direction())
                    && "REGISTRATION".equals(s.procedure()), "Invalid roaming population");
            case "MOBILE_DATA" -> { inventory.city(s.id()); require(s.direction() == null && s.country() == null
                    && "DNS".equals(s.procedure()), "Explicit LTE DNS scope required"); }
            default -> throw new IllegalArgumentException("Unsupported domain");
        }
        var p = c.parameters();
        require(p.normalUtilizationBp() >= 0 && p.normalUtilizationBp() <= 10000 && p.degradedUtilizationBp() >= p.normalUtilizationBp()
                && p.degradedUtilizationBp() <= 10000 && p.capacityGbps() >= 1 && p.capacityGbps() <= 1000
                && p.attempts() >= 0 && p.attempts() <= 1000000 && p.healthySuccessBp() >= 0 && p.healthySuccessBp() <= 10000
                && p.degradedSuccessBp() >= 0 && p.degradedSuccessBp() <= p.healthySuccessBp()
                && p.missingMinutes().stream().allMatch(m -> m != null && m >= 0 && m < 8), "Invalid parameter range");
        return d;
    }
    public static Phase phase(int minute) { require(minute >= 0 && minute < 8, "Invalid minute");
        return minute < 2 ? Phase.NORMAL : minute < 5 ? Phase.DEGRADED : Phase.RECOVERY; }
    static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
