package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent Day 1 primitive arithmetic. Not a detector and never consumes evaluator truth. */
public final class TraceLinkArithmetic {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final double[] VOICE_EDGES = {.5, 1, 2, 3, 5, 8, 15, 30, 60};
    private static final double[] SMS_EDGES = {1, 2, 5, 10, 20, 40, 80, 160, 320};
    private static final List<String> CITY_SUM = List.of("attempts", "success", "drops", "holding", "setupSum",
            "smsSubmitted", "smsDelivered", "smsExpired", "dataAttempts", "dataSuccess", "dnsAttempts",
            "dnsSuccess", "hoAttempts", "hoSuccess");
    private static final List<String> CITY_VALUES = List.of("voiceSR", "dropRate", "erlang", "setupMeanSeconds",
            "setupP95Seconds", "smsP95Ms", "smsSR", "dataSR", "dnsSR", "hoSR", "throughputMbps");
    private static final List<String> ROAM_COUNTERS = List.of("regAtt", "regOk", "dataAtt", "dataOk",
            "voiceAtt", "voiceOk", "smsAtt", "smsOk");

    private TraceLinkArithmetic() { }

    /** The boundary intentionally accepts only primitives and a UTC window, never fixture expectations. */
    public static JsonNode calculate(String operation, JsonNode input, JsonNode window) {
        try {
            rejectTruth(input);
            rejectTruth(window);
            keys(window, Set.of("from", "to"));
            Instant from = time(window.get("from")), to = time(window.get("to"));
            require(from.isBefore(to) && operation != null);
            Map<String, Object> result = switch (operation) {
                case "ratio" -> ratioOperation(input);
                case "histogram" -> histogram(input);
                case "utilization" -> utilization(input);
                case "queue" -> queue(input);
                case "city_summary" -> city(input, from, to);
                case "link_snapshot" -> link(input, from, to);
                case "roaming_summary" -> roaming(input, from, to);
                default -> throw new InvalidInput();
            };
            JsonNode tree = JSON.valueToTree(result);
            rejectTruth(tree); // Also rejects a derived non-finite arithmetic result.
            return tree;
        } catch (InvalidInput invalid) {
            return JSON.createObjectNode().put("error", "INVALID_INPUT");
        }
    }

    private static Map<String, Object> ratioOperation(JsonNode input) {
        keys(input, Set.of("partitions"));
        long numerator = 0, denominator = 0;
        for (JsonNode row : array(input.get("partitions"), false)) {
            keys(row, Set.of("numerator", "denominator"));
            long n = count(row.get("numerator")), d = count(row.get("denominator"));
            require(n <= d);
            numerator = add(numerator, n);
            denominator = add(denominator, d);
        }
        return result(object("numerator", numerator, "denominator", denominator),
                object("value", rate(numerator, denominator)), denominator == 0 ? "INSUFFICIENT_DATA" : "VALID",
                denominator == 0 ? "ZERO_DENOMINATOR" : null, 1, null);
    }

    private static Map<String, Object> histogram(JsonNode input) {
        keys(input, Set.of("histograms", "outputUnit"));
        String outputUnit = string(input.get("outputUnit"));
        require(Set.of("SECONDS", "MILLISECONDS").contains(outputUnit));
        double[] edges = null;
        long[] pooled = null;
        String unit = null, schema = null;
        for (JsonNode row : array(input.get("histograms"), true)) {
            keys(row, Set.of("counts", "edges", "unit", "schemaVersion"));
            JsonNode rawCounts = array(row.get("counts"), true), rawEdges = array(row.get("edges"), true);
            require(rawCounts.size() == rawEdges.size());
            String thisUnit = string(row.get("unit")), thisSchema = string(row.get("schemaVersion"));
            require(Set.of("SECONDS", "MILLISECONDS").contains(thisUnit));
            long[] counts = new long[rawCounts.size()];
            double[] thisEdges = new double[rawEdges.size()];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = count(rawCounts.get(i));
                thisEdges[i] = number(rawEdges.get(i), true);
                require(i == 0 || thisEdges[i] > thisEdges[i - 1]);
            }
            if (edges == null) {
                edges = thisEdges;
                pooled = new long[counts.length];
                unit = thisUnit;
                schema = thisSchema;
            }
            require(Arrays.equals(edges, thisEdges) && unit.equals(thisUnit) && schema.equals(thisSchema));
            for (int i = 0; i < counts.length; i++) pooled[i] = add(pooled[i], counts[i]);
        }
        long total = total(pooled);
        Double p95 = quantile(pooled, edges);
        if (p95 != null && !unit.equals(outputUnit)) p95 *= outputUnit.equals("MILLISECONDS") ? 1000 : .001;
        return result(object("counts", pooled, "edges", edges, "sampleCount", total), object("p95", p95),
                total == 0 ? "INSUFFICIENT_DATA" : "VALID", total == 0 ? "EMPTY_HISTOGRAM" : null, 1, null);
    }

    private static Map<String, Object> utilization(JsonNode input) {
        keys(input, Set.of("segments", "expectedSeconds"));
        double expected = number(input.get("expectedSeconds"), true);
        double valid = 0, trafficTime = 0, utilizationTime = 0;
        Double peak = null;
        boolean stale = false;
        for (JsonNode row : array(input.get("segments"), false)) {
            keys(row, Set.of("trafficGbps", "effectiveCapacityGbps", "validSeconds", "quality"));
            double traffic = number(row.get("trafficGbps"), false), seconds = number(row.get("validSeconds"), true);
            Double capacity = row.get("effectiveCapacityGbps").isNull() ? null : number(row.get("effectiveCapacityGbps"), true);
            require(capacity == null || traffic <= capacity);
            String quality = string(row.get("quality"));
            require(Set.of("VALID", "MISSING", "STALE").contains(quality));
            stale |= quality.equals("STALE");
            if (quality.equals("VALID") && capacity != null) {
                double pct = 100 * traffic / capacity;
                valid += seconds;
                trafficTime += traffic * seconds;
                utilizationTime += pct * seconds;
                peak = peak == null ? pct : Math.max(peak, pct);
            }
        }
        finite(valid); finite(trafficTime); finite(utilizationTime);
        Map<String, Object> primitives = object("validSeconds", valid, "trafficTime", trafficTime, "utilizationTime", utilizationTime);
        Map<String, Object> values = object("meanPct", valid == 0 ? null : utilizationTime / valid,
                "peakPct", peak, "trafficMeanGbps", valid == 0 ? null : trafficTime / valid,
                "transferredGbEstimate", valid == 0 ? null : trafficTime);
        if (valid == 0) return result(primitives, values, stale ? "STALE" : "INSUFFICIENT_DATA",
                stale ? "STALE_SOURCE" : "MISSING_CAPACITY", 0, null);
        double coverage = valid / expected;
        return result(primitives, values, coverage < 1 ? "PARTIAL" : "VALID",
                coverage < 1 ? "PARTIAL_COVERAGE" : null, coverage, null);
    }

    private static Map<String, Object> queue(JsonNode input) {
        List<String> counters = List.of("openingQueue", "submitted", "delivered", "terminalNegative", "closingQueue");
        Set<String> fields = new HashSet<>(counters);
        fields.add("durationSeconds");
        keys(input, fields);
        Map<String, Object> primitives = new LinkedHashMap<>();
        for (String field : counters) primitives.put(field, count(input.get(field)));
        double duration = number(input.get("durationSeconds"), true);
        primitives.put("durationSeconds", duration);
        long opening = count(input.get("openingQueue")), submitted = count(input.get("submitted")),
                delivered = count(input.get("delivered")), negative = count(input.get("terminalNegative")),
                closing = count(input.get("closingQueue"));
        require(closing == opening + submitted - delivered - negative);
        long finalized = add(delivered, negative), growth = closing - opening;
        return result(primitives, object("growth", growth, "growthPerSecond", growth / duration,
                "finalizedSuccessPct", rate(delivered, finalized)), finalized == 0 ? "INSUFFICIENT_DATA" : "VALID",
                finalized == 0 ? "ZERO_DENOMINATOR" : null, 1, null);
    }

    private static Map<String, Object> city(JsonNode input, Instant from, Instant to) {
        keys(input, Set.of("rows", "expectedRows"));
        long expected = count(input.get("expectedRows"));
        require(expected > 0);
        JsonNode rows = array(input.get("rows"), false);
        require(rows.size() <= expected);
        Set<String> fields = new HashSet<>(CITY_SUM);
        fields.addAll(List.of("cityId", "ts", "smsQueue", "smsOldest", "throughput", "voiceCounts", "smsCounts"));
        Map<String, Object> primitives = new LinkedHashMap<>();
        for (String name : CITY_SUM) primitives.put(name, 0L);
        long[] voice = new long[9], sms = new long[9];
        Set<Instant> slots = new HashSet<>();
        Set<List<Object>> unique = new HashSet<>();
        Map<String, JsonNode> latest = new LinkedHashMap<>();
        double throughput = 0;
        for (JsonNode row : rows) {
            keys(row, fields);
            String cityId = string(row.get("cityId"));
            Instant ts = aligned(row.get("ts"));
            require(!ts.isBefore(from) && ts.isBefore(to) && unique.add(List.of(cityId, ts)));
            slots.add(ts);
            for (String name : CITY_SUM) {
                if (name.equals("holding") || name.equals("setupSum")) {
                    double value = number(row.get(name), false) + ((Number) primitives.get(name)).doubleValue();
                    finite(value);
                    primitives.put(name, value);
                } else primitives.put(name, add(((Number) primitives.get(name)).longValue(), count(row.get(name))));
            }
            for (String[] pair : List.of(new String[]{"success", "attempts"}, new String[]{"drops", "success"},
                    new String[]{"dataSuccess", "dataAttempts"}, new String[]{"dnsSuccess", "dnsAttempts"},
                    new String[]{"hoSuccess", "hoAttempts"})) require(count(row.get(pair[0])) <= count(row.get(pair[1])));
            long[] vc = bins(row.get("voiceCounts"), 9), sc = bins(row.get("smsCounts"), 9);
            require(total(vc) == count(row.get("success")) && total(sc) == count(row.get("smsDelivered")));
            for (int i = 0; i < 9; i++) { voice[i] = add(voice[i], vc[i]); sms[i] = add(sms[i], sc[i]); }
            throughput += number(row.get("throughput"), false);
            count(row.get("smsQueue")); number(row.get("smsOldest"), false);
            if (!latest.containsKey(cityId) || ts.isAfter(time(latest.get(cityId).get("ts")))) latest.put(cityId, row);
        }
        finite(throughput);
        long queue = 0;
        double oldest = 0;
        JsonNode snapshot = null;
        for (JsonNode row : latest.values()) {
            queue = add(queue, count(row.get("smsQueue")));
            oldest = Math.max(oldest, number(row.get("smsOldest"), false));
            if (snapshot == null || time(row.get("ts")).isAfter(time(snapshot.get("ts")))) snapshot = row;
        }
        primitives.putAll(object("voiceCounts", voice, "smsCounts", sms, "throughputSum", throughput,
                "rowCount", rows.size(), "slotCount", slots.size(), "queue", queue, "oldest", oldest));
        if (rows.isEmpty()) return result(primitives, nullValues(CITY_VALUES), "MISSING", "NO_OBSERVATIONS", 0, null);
        double attempts = val(primitives, "attempts"), success = val(primitives, "success"), delivered = val(primitives, "smsDelivered");
        Double smsP95 = quantile(sms, SMS_EDGES);
        Map<String, Object> values = object("voiceSR", rate(success, attempts), "dropRate", rate(val(primitives, "drops"), success),
                "erlang", val(primitives, "holding") / (slots.size() * 300.0),
                "setupMeanSeconds", success == 0 ? null : val(primitives, "setupSum") / success,
                "setupP95Seconds", quantile(voice, VOICE_EDGES), "smsP95Ms", smsP95 == null ? null : smsP95 * 1000,
                "smsSR", rate(delivered, delivered + val(primitives, "smsExpired")),
                "dataSR", rate(val(primitives, "dataSuccess"), val(primitives, "dataAttempts")),
                "dnsSR", rate(val(primitives, "dnsSuccess"), val(primitives, "dnsAttempts")),
                "hoSR", rate(val(primitives, "hoSuccess"), val(primitives, "hoAttempts")), "throughputMbps", throughput / rows.size());
        double coverage = (double) rows.size() / expected;
        if (latest.values().stream().map(row -> time(row.get("ts"))).distinct().count() > 1) {
            primitives.put("queue", null);
            primitives.put("oldest", null);
            return result(primitives, values, "PARTIAL", "SNAPSHOT_MISALIGNED", coverage, null);
        }
        return result(primitives, values, coverage < 1 ? "PARTIAL" : "VALID",
                coverage < 1 ? "PARTIAL_COVERAGE" : null, coverage, snapshot.get("ts").asText());
    }

    private static Map<String, Object> link(JsonNode input, Instant from, Instant to) {
        keys(input, Set.of("linkId", "cityId", "at", "dataFrom", "dataTo", "maxAgeSeconds", "rows"));
        String linkId = string(input.get("linkId")), cityId = string(input.get("cityId"));
        Instant dataFrom = time(input.get("dataFrom")), dataTo = time(input.get("dataTo"));
        require(dataFrom.isBefore(dataTo));
        double ageLimit = number(input.get("maxAgeSeconds"), false);
        Instant at = input.get("at").isNull() ? null : time(input.get("at"));
        JsonNode selected = null;
        Set<List<Object>> unique = new HashSet<>();
        for (JsonNode row : array(input.get("rows"), false)) {
            keys(row, Set.of("linkId", "a", "b", "ts", "trafficGbps", "effectiveCapacityGbps"));
            String rowLink = string(row.get("linkId")), a = string(row.get("a")), b = string(row.get("b"));
            Instant ts = aligned(row.get("ts"));
            require(!a.equals(b) && unique.add(List.of(rowLink, ts)));
            number(row.get("trafficGbps"), false);
            if (!row.get("effectiveCapacityGbps").isNull())
                require(number(row.get("trafficGbps"), false) <= number(row.get("effectiveCapacityGbps"), true));
            boolean inTime = at == null ? !ts.isBefore(from) && ts.isBefore(to) : !ts.isAfter(at);
            if (rowLink.equals(linkId) && (cityId.equals("all") || cityId.equals(a) || cityId.equals(b)) && inTime
                    && !ts.isBefore(dataFrom) && ts.isBefore(dataTo)
                    && (selected == null || ts.isAfter(time(selected.get("ts"))))) selected = row;
        }
        boolean outside = at == null ? from.isBefore(dataFrom) || to.isAfter(dataTo) : at.isBefore(dataFrom) || !at.isBefore(dataTo);
        Map<String, Object> absent = object("trafficGbps", null, "effectiveCapacityGbps", null);
        Map<String, Object> unavailable = object("utilizationPct", null);
        if (outside) return result(absent, unavailable, "MISSING", "OUTSIDE_DATASET", 0, null);
        if (selected == null) return result(absent, unavailable, "MISSING", "NO_OBSERVATIONS", 0, null);
        Double capacity = selected.get("effectiveCapacityGbps").isNull() ? null : number(selected.get("effectiveCapacityGbps"), true);
        Map<String, Object> primitives = object("trafficGbps", number(selected.get("trafficGbps"), false), "effectiveCapacityGbps", capacity);
        String snapshot = selected.get("ts").asText();
        if (seconds(time(selected.get("ts")), at == null ? to : at) > ageLimit)
            return result(primitives, unavailable, "STALE", "STALE_SOURCE", 0, snapshot);
        if (capacity == null) return result(primitives, unavailable, "INSUFFICIENT_DATA", "MISSING_CAPACITY", 0, snapshot);
        return result(primitives, object("utilizationPct", 100 * number(selected.get("trafficGbps"), false) / capacity),
                "VALID", null, 1, snapshot);
    }

    private static Map<String, Object> roaming(JsonNode input, Instant from, Instant to) {
        keys(input, Set.of("country", "direction", "cityShare", "maxAgeSeconds", "rows"));
        String country = string(input.get("country")), direction = string(input.get("direction"));
        require(country.equals("all") || country.matches("[A-Z]{2}"));
        require(Set.of("out", "in", "both").contains(direction));
        double share = number(input.get("cityShare"), true), ageLimit = number(input.get("maxAgeSeconds"), false);
        require(share <= 1);
        Map<String, Object> primitives = new LinkedHashMap<>();
        for (String field : ROAM_COUNTERS) primitives.put(field, 0L);
        Map<List<String>, JsonNode> latest = new LinkedHashMap<>();
        Set<List<Object>> unique = new HashSet<>();
        Set<String> fields = new HashSet<>(ROAM_COUNTERS);
        fields.addAll(List.of("countryId", "partnerId", "direction", "ts", "users"));
        for (JsonNode row : array(input.get("rows"), false)) {
            keys(row, fields);
            String rowCountry = string(row.get("countryId")), partner = string(row.get("partnerId")), rowDirection = string(row.get("direction"));
            require(rowCountry.matches("[A-Z]{2}") && Set.of("out", "in").contains(rowDirection));
            Instant ts = aligned(row.get("ts"));
            List<String> identity = List.of(rowCountry, partner, rowDirection);
            require(unique.add(List.of(identity, ts)));
            count(row.get("users"));
            for (String field : ROAM_COUNTERS) count(row.get(field));
            for (String procedure : List.of("reg", "data", "voice", "sms"))
                require(count(row.get(procedure + "Ok")) <= count(row.get(procedure + "Att")));
            if ((country.equals("all") || country.equals(rowCountry)) && (direction.equals("both") || direction.equals(rowDirection))
                    && !ts.isBefore(from) && ts.isBefore(to)) {
                for (String field : ROAM_COUNTERS) primitives.put(field, add(((Number) primitives.get(field)).longValue(), count(row.get(field))));
                if (!latest.containsKey(identity) || ts.isAfter(time(latest.get(identity).get("ts")))) latest.put(identity, row);
            }
        }
        long users = 0, allocated = 0;
        boolean stale = false;
        JsonNode snapshot = null;
        for (JsonNode row : latest.values()) {
            long rowUsers = count(row.get("users"));
            users = add(users, rowUsers);
            double product = rowUsers * share;
            long lower = (long) Math.floor(product);
            allocated = add(allocated, lower + (product - lower >= .5 ? 1 : 0));
            stale |= seconds(time(row.get("ts")), to) > ageLimit;
            if (snapshot == null || time(row.get("ts")).isAfter(time(snapshot.get("ts")))) snapshot = row;
        }
        primitives.put("users", users);
        if (latest.isEmpty()) return result(primitives, nullValues(List.of("users", "regSR", "dataSR", "voiceSR", "smsSR")),
                "MISSING", "NO_OBSERVATIONS", 0, null);
        Map<String, Object> values = object("users", stale ? null : allocated);
        for (String procedure : List.of("reg", "data", "voice", "sms"))
            values.put(procedure + "SR", rate(val(primitives, procedure + "Ok"), val(primitives, procedure + "Att")));
        if (!stale && latest.values().stream().map(row -> time(row.get("ts"))).distinct().count() > 1) {
            primitives.put("users", null);
            values.put("users", null);
            return result(primitives, values, "PARTIAL", "SNAPSHOT_MISALIGNED", 0, null);
        }
        return result(primitives, values, stale ? "STALE" : "VALID", stale ? "STALE_SOURCE" : null,
                stale ? 0 : 1, snapshot.get("ts").asText());
    }

    private static Map<String, Object> result(Map<String, Object> primitives, Map<String, Object> values,
                                               String quality, String reason, double coverage, String snapshot) {
        require(Double.isFinite(coverage) && coverage >= 0 && coverage <= 1);
        return object("primitives", primitives, "values", values, "quality", quality,
                "nullReason", reason, "coverage", coverage, "snapshotAt", snapshot);
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private static Map<String, Object> nullValues(List<String> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) values.put(field, null);
        return values;
    }

    private static void keys(JsonNode input, Set<String> wanted) {
        require(input != null && input.isObject() && input.size() == wanted.size());
        input.fieldNames().forEachRemaining(field -> require(wanted.contains(field)));
    }

    private static JsonNode array(JsonNode input, boolean nonempty) {
        require(input != null && input.isArray() && (!nonempty || !input.isEmpty()));
        return input;
    }

    private static String string(JsonNode input) {
        require(input != null && input.isTextual() && !input.asText().isBlank());
        return input.asText();
    }

    private static double number(JsonNode input, boolean positive) {
        require(input != null && input.isNumber());
        double value = input.doubleValue();
        require(Double.isFinite(value) && (positive ? value > 0 : value >= 0));
        return value;
    }

    private static long count(JsonNode input) {
        double value = number(input, false);
        require(value <= MAX_SAFE && value == Math.rint(value));
        return (long) value;
    }

    private static long add(long a, long b) {
        require(a >= 0 && b >= 0 && a <= MAX_SAFE - b);
        return a + b;
    }

    private static void finite(double value) { require(Double.isFinite(value)); }
    private static double val(Map<String, Object> map, String field) { return ((Number) map.get(field)).doubleValue(); }
    private static Double rate(double numerator, double denominator) { return denominator == 0 ? null : 100 * numerator / denominator; }

    private static long[] bins(JsonNode raw, int length) {
        array(raw, false);
        require(raw.size() == length);
        long[] values = new long[length];
        for (int i = 0; i < length; i++) values[i] = count(raw.get(i));
        return values;
    }

    private static long total(long[] counts) {
        long total = 0;
        for (long count : counts) total = add(total, count);
        return total;
    }

    private static Double quantile(long[] counts, double[] edges) {
        long total = total(counts);
        if (total == 0) return null;
        double target = .95 * total, low = 0;
        long accumulated = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0 && accumulated + counts[i] >= target)
                return low + (target - accumulated) / counts[i] * (edges[i] - low);
            accumulated += counts[i];
            low = edges[i];
        }
        throw new InvalidInput();
    }

    private static Instant time(JsonNode input) {
        String value = string(input);
        require(value.matches("\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d{1,3})?Z"));
        require(!value.startsWith("0000-"));
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) { throw new InvalidInput(); }
    }

    private static Instant aligned(JsonNode input) {
        Instant ts = time(input);
        require(ts.getNano() == 0 && Math.floorMod(ts.getEpochSecond(), 300) == 0);
        return ts;
    }

    private static double seconds(Instant from, Instant to) {
        Duration duration = Duration.between(from, to);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static void rejectTruth(JsonNode input) {
        require(input != null && !input.isMissingNode());
        if (input.isObject()) input.fields().forEachRemaining(entry -> {
            String key = entry.getKey(), normalized = key.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
            require(key.equals("expectedSeconds") || key.equals("expectedRows")
                    || List.of("truth", "expected", "future", "scenario", "outcome").stream().noneMatch(normalized::contains));
            rejectTruth(entry.getValue());
        });
        else if (input.isArray()) input.forEach(TraceLinkArithmetic::rejectTruth);
        else if (input.isNumber()) finite(input.doubleValue());
    }

    private static void require(boolean valid) { if (!valid) throw new InvalidInput(); }
    private static final class InvalidInput extends RuntimeException { }
}
