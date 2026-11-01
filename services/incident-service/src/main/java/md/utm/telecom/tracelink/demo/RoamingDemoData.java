package md.utm.telecom.tracelink.demo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import md.utm.telecom.tracelink.inventory.InventoryResponses;
import md.utm.telecom.tracelink.metrics.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A separate reproducible demonstration source. Never reads or impersonates operator/archive data. */
public final class RoamingDemoData {
    public static final String DATASET = "roaming-demo-v1";
    public static final String CATALOGUE = "roaming-demo-partners-v1";
    public static final String TOPOLOGY = "roaming-demo-topology-v1";
    public static final String BASELINE = "roaming-demo-static-v1";
    public static final BigDecimal CENTER = new BigDecimal("99.5");
    public static final List<String> COUNTRIES = List.of("RO", "IT", "DE", "FR", "GB", "ES", "UA", "TR", "PL", "BG");
    public static final List<String> METRICS = List.of("roaming.registration.success", "roaming.data.success", "roaming.voice.success");
    public static String manifestSha256() {
        String identity = DATASET+":seed=42:resolution=300:baseline="+CENTER+":"+BASELINE+":"+COUNTRIES+":"+METRICS;
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static Instant completedAt(Instant now) { return Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 300) * 300); }
    public static String partner(String country) { return country + "-DEMO-01"; }
    public static List<InventoryResponses.Partner> partners() {
        return COUNTRIES.stream().flatMap(country -> List.of("in", "out").stream().map(direction ->
                new InventoryResponses.Partner(partner(country), country, direction, true, Instant.EPOCH, null, true))).toList();
    }
    public record Counts(long successes, long attempts) {
        public BigDecimal rate() { return BigDecimal.valueOf(successes).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(attempts), MathContext.DECIMAL128); }
    }
    public static boolean faultStream(String partner, String direction, String metric) {
        return partner.equals(partner("RO")) && direction.equals("out") && metric.equals(METRICS.get(0))
                || partner.equals(partner("IT")) && direction.equals("out") && metric.equals(METRICS.get(1))
                || partner.equals(partner("DE")) && direction.equals("in") && metric.equals(METRICS.get(2));
    }
    public static Counts counts(String partner, String direction, String metric, Instant start) {
        long slot = start.getEpochSecond() / 300;
        var random = new Random(slot ^ ((long) (partner + direction + metric).hashCode() << 16) ^ 42);
        double demand = 1 + .35 * Math.sin((slot % 288) * Math.PI / 144);
        long attempts = Math.round((1600 + random.nextInt(1200)) * demand * (direction.equals("in") ? .65 : 1));
        double rate = 99.25 + random.nextDouble() * .5;
        if (faultStream(partner, direction, metric) && Math.floorMod(slot, 36) < 4) rate = 92.5 + random.nextDouble() * 2;
        return new Counts(Math.round(attempts * rate / 100), attempts);
    }
    public static long users(String partner, String direction, Instant at) {
        long slot = at.getEpochSecond() / 300;
        var random = new Random(slot ^ (long)(partner + direction).hashCode());
        return Math.round((18000 + COUNTRIES.indexOf(partner.substring(0,2)) * 2300)
                * (1 + .18 * Math.sin(slot * Math.PI / 144)) * (direction.equals("in") ? .4 : 1)) + random.nextInt(400);
    }
    public static MetricSeriesResponse series(TraceQuery query, Instant now) {
        Instant end = completedAt(now);
        String[] population = query.scopeId().split(":", -1);
        boolean snapshot = query.metric().equals("roaming.activeInbound") || query.metric().equals("roaming.activeOutbound");
        if (!query.datasetId().equals(DATASET) || !query.domain().equals("roaming") || !query.scopeType().equals("PARTNER_DIRECTION")
                || population.length != 2 || !COUNTRIES.stream().anyMatch(country -> partner(country).equals(population[0]))
                || !List.of("in", "out").contains(population[1]) || !snapshot && !METRICS.contains(query.metric())
                || snapshot && !query.metric().equals(population[1].equals("in") ? "roaming.activeInbound" : "roaming.activeOutbound")
                || query.from().isBefore(end.minusSeconds(30 * 86400L)) || query.to().isAfter(end) || query.pointCount() > 8641)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query does not match available synthetic roaming history");
        var points = new ArrayList<MetricSeriesResponse.Point>();
        for (Instant start = query.effectiveFrom(); start.isBefore(query.effectiveTo()); start = start.plusSeconds(query.bucketSeconds())) {
            Instant finish = start.plusSeconds(query.bucketSeconds());
            long numerator = 0, denominator = 0, received = 0;
            Instant snapshotAt = null;
            for (Instant nativeStart = start; nativeStart.isBefore(finish); nativeStart = nativeStart.plusSeconds(300)) {
                if (nativeStart.isBefore(query.from()) || nativeStart.plusSeconds(300).isAfter(query.to())) continue;
                received++;
                if (snapshot) snapshotAt = nativeStart.plusSeconds(299);
                else { var counts = counts(population[0], population[1], query.metric(), nativeStart);
                    numerator += counts.successes(); denominator += counts.attempts(); }
            }
            int expected = Math.toIntExact(query.bucketSeconds() / 300);
            BigDecimal coverage = BigDecimal.valueOf(received).divide(BigDecimal.valueOf(expected), MathContext.DECIMAL128);
            BigDecimal value = snapshot ? snapshotAt == null ? null : BigDecimal.valueOf(users(population[0], population[1], snapshotAt))
                    : denominator == 0 ? null : new Counts(numerator, denominator).rate();
            points.add(new MetricSeriesResponse.Point(start, finish, value, coverage,
                    received == 0 ? "MISSING" : received < expected ? "PARTIAL" : "VALID",
                    snapshot || received == 0 ? null : BigDecimal.valueOf(numerator), snapshot || received == 0 ? null : BigDecimal.valueOf(denominator),
                    null, null, received == 0 ? "NO_SOURCE_SLOTS" : null, received < expected ? "PARTIAL_REQUEST_EDGE" : null,
                    expected, Math.toIntExact(received), "SYNTHETIC", coverage, List.of(), List.of(), snapshotAt,
                    snapshotAt == null ? null : java.time.Duration.between(snapshotAt, query.to()).getSeconds(), List.of(), List.of(), List.of(),
                    snapshot || received == 0 ? null : CENTER, snapshot || received == 0 ? null : BASELINE));
        }
        return new MetricSeriesResponse(DATASET, "roaming", query.metric(), query.scopeType(), query.scopeId(), query.resolution(),
                query.from(), query.to(), query.effectiveFrom(), query.effectiveTo(), !query.from().equals(query.effectiveFrom()),
                !query.to().equals(query.effectiveTo()), 300, snapshot ? "COUNT" : "PERCENT", snapshot ? "SNAPSHOT_LAST" : "RATIO_OF_SUMS",
                "trace-link-kpi-v1", TOPOLOGY, true, null, points);
    }
}
