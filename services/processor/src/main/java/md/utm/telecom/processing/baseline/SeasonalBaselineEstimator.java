package md.utm.telecom.processing.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.processing.detection.TraceLinkInputs;

/** Pure reference-only fitting. Historical structural eligibility is not runtime freshness. */
public final class SeasonalBaselineEstimator {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, JsonNode> DEFINITIONS = loadDefinitions();
    private static Map<String, JsonNode> loadDefinitions() {
        try {
            Map<String, JsonNode> definitions = new HashMap<>();
            for (JsonNode item : ObservationValidator.resource("kpi/trace-link-kpi-definitions-v1.json", JSON).path("metrics"))
                definitions.put(item.path("metricId").asText(), item);
            return Map.copyOf(definitions);
        } catch (java.io.IOException error) { throw new IllegalStateException("Missing KPI dictionary", error); }
    }
    private final JsonNode policy;
    public SeasonalBaselineEstimator(JsonNode policy) {
        validatePolicy(policy);
        this.policy = policy.deepCopy();
    }
    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    static void fields(JsonNode node, String... names) {
        require(node != null && node.isObject(), "Object required");
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(Set.of(names)), "Unexpected or missing fields");
    }
    static Instant time(JsonNode node) {
        require(node != null && node.isTextual() && node.asText().endsWith("Z"), "UTC Z timestamp required");
        try {
            Instant instant = Instant.parse(node.asText());
            require(instant.getNano() % 1_000_000 == 0, "Millisecond precision required");
            return instant;
        } catch (java.time.DateTimeException error) { throw new IllegalArgumentException("Invalid timestamp", error); }
    }
    static double finite(JsonNode node) {
        require(node != null && node.isNumber() && Double.isFinite(node.doubleValue()), "Finite number required");
        return node.doubleValue();
    }
    static void schema(String name, JsonNode value) {
        try {
            JsonNode raw = ObservationValidator.resource("baselines/" + name, JSON);
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(raw);
            require(schema.validate(value).isEmpty(), "Invalid " + name);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Missing baseline schema", error); }
    }
    static void key(JsonNode key) {
        fields(key, "datasetId", "domain", "metricId", "scope", "unit", "metricPolicyVersion", "topologyVersion");
        fields(key.get("scope"), "namespace", "type", "id", "cityIds", "directionId", "partnerId", "procedure");
        require(key.path("datasetId").asText().equals("trace-link-md-30d-v1")
                && Set.of("TRANSPORT", "ROAMING", "MOBILE_DATA").contains(key.path("domain").asText()), "Invalid dataset/domain");
        for (String name : List.of("metricId", "unit", "metricPolicyVersion", "topologyVersion"))
            require(key.path(name).isTextual() && !key.path(name).asText().isBlank(), "Invalid key string");
        JsonNode scope = key.path("scope");
        require(scope.path("namespace").asText().equals("trace-link-reference-v1") && scope.path("id").isTextual()
                && !scope.path("id").asText().isBlank() && scope.path("cityIds").isArray(), "Invalid scope");
        Set<String> cities = new HashSet<>();
        for (JsonNode city : scope.path("cityIds")) require(city.isTextual() && !city.asText().isBlank() && cities.add(city.asText()), "Invalid cities");
        for (String name : List.of("directionId", "partnerId", "procedure"))
            require(scope.path(name).isNull() || scope.path(name).isTextual() && !scope.path(name).asText().isBlank(), "Invalid scope dimension");
        String domain = key.path("domain").asText();
        if (domain.equals("TRANSPORT")) require(scope.path("type").asText().equals("LINK_DIRECTION")
                && Set.of("a-to-b", "b-to-a").contains(scope.path("directionId").asText())
                && scope.path("partnerId").isNull() && scope.path("procedure").isNull(), "Invalid transport scope");
        if (domain.equals("ROAMING")) require(scope.path("type").asText().equals("PARTNER_DIRECTION")
                && Set.of("out", "in").contains(scope.path("directionId").asText()) && scope.path("partnerId").isTextual()
                && Set.of("REGISTRATION", "DATA", "VOICE", "SMS").contains(scope.path("procedure").asText()), "Invalid roaming scope");
        if (domain.equals("MOBILE_DATA")) require(scope.path("type").asText().equals("CITY")
                && scope.path("directionId").isNull() && scope.path("partnerId").isNull(), "Invalid data scope");
        TraceLinkInputs.validateScope(scope, domain);
        JsonNode definition = DEFINITIONS.get(key.path("metricId").asText());
        require(definition != null && definition.path("unit").equals(key.path("unit"))
                && definition.path("domain").equals(key.path("domain"))
                && definition.path("policyVersion").equals(key.path("metricPolicyVersion")), "Unknown or incompatible metric");
    }
    static void validatePolicy(JsonNode policy) {
        schema("trace-link-baseline-policy-v1.schema.json", policy);
        finite(policy.path("minimumCoverage")); policy.path("staticCenters").forEach(SeasonalBaselineEstimator::finite);
        Set<JsonNode> seen = new HashSet<>();
        for (JsonNode peer : policy.path("peerFallbacks")) {
            JsonNode left = peer.path("key"), right = peer.path("peerKey"); key(left); key(right);
            ObjectNode l = left.deepCopy(), r = right.deepCopy(); l.remove("scope"); r.remove("scope");
            ObjectNode ls = left.path("scope").deepCopy(), rs = right.path("scope").deepCopy();
            for (String name : List.of("id", "cityIds")) { ls.remove(name); rs.remove(name); }
            require(l.equals(r) && ls.equals(rs) && !left.path("scope").path("id").equals(right.path("scope").path("id"))
                    && seen.add(left), "Incompatible or duplicate peer");
        }
    }
    static JsonNode ordered(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode(); TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, ordered(node.get(name))); return result;
        }
        if (node.isArray()) { var result = JSON.createArrayNode(); for (JsonNode child : node) result.add(ordered(child)); return result; }
        return node;
    }
    static String canonical(JsonNode node) { return ordered(node).toString(); }
    static double median(List<Double> raw) {
        List<Double> values = new ArrayList<>(raw); Collections.sort(values);
        int n = values.size(); require(n > 0, "Empty median");
        double result = n % 2 == 0 ? values.get(n/2-1)/2 + values.get(n/2)/2 : values.get(n/2);
        require(Double.isFinite(result), "Non-finite median"); return result;
    }
    record Slot(int slot, String weekday, String date) {}
    static Slot slot(Instant instant, JsonNode policy) {
        var local = instant.atZone(ZoneId.of(policy.path("timezone").asText()));
        return new Slot((local.getHour()*60+local.getMinute())/5, local.getDayOfWeek().getValue() >= 6 ? "WEEKEND" : "WEEKDAY", local.toLocalDate().toString());
    }
    private record Interval(Instant from, Instant to) {}
    private record Point(double value, double coverage) {}
    private static final class Group {
        JsonNode key; int slot; String weekday; int count;
        Map<String, List<Point>> days = new TreeMap<>();
    }
    public JsonNode fit(JsonNode request) {
        fields(request, "trainingFrom", "trainingTo", "evaluationFrom", "sourceManifestSha256", "topologySnapshotSha256", "exclusions", "samples");
        Instant start = time(request.get("trainingFrom")), end = time(request.get("trainingTo")), evaluation = time(request.get("evaluationFrom"));
        require(start.isBefore(end) && !end.isAfter(evaluation), "Training/evaluation overlap");
        require(start.getNano()==0 && end.getNano()==0 && start.getEpochSecond()%300==0 && end.getEpochSecond()%300==0,"Unaligned training bounds");
        for (String name : List.of("sourceManifestSha256", "topologySnapshotSha256")) require(request.path(name).asText().matches("[a-f0-9]{64}"), "Invalid checksum");
        require(request.path("samples").isArray() && request.path("exclusions").isArray(), "Arrays required");
        List<Interval> exclusions = new ArrayList<>();
        for (JsonNode item : request.path("exclusions")) {
            fields(item, "from", "to", "reason"); Instant a = time(item.get("from")), b = time(item.get("to"));
            require(a.isBefore(b) && Set.of("DECLARED_INJECTION", "MAINTENANCE").contains(item.path("reason").asText()), "Invalid exclusion");
            exclusions.add(new Interval(a,b));
        }
        Map<String, Set<String>> calendar = new HashMap<>(); long excludedSlots=0;
        for (Instant at = start; at.isBefore(end); at = at.plusSeconds(300)) {
            Instant to = at.plusSeconds(300);
            if (to.isAfter(end) || overlaps(at,to,exclusions)) { excludedSlots++; continue; }
            Slot slot = slot(at, policy); calendar.computeIfAbsent(slot.slot+":"+slot.weekday, ignored -> new HashSet<>()).add(slot.date);
        }
        Map<String, JsonNode> identities = new HashMap<>(); Set<String> keys = new HashSet<>(), suppliedWindows = new HashSet<>();
        Map<String, Group> groups = new TreeMap<>(); long excluded=0, accepted=0, missing=0, invalid=0;
        for (JsonNode row : request.path("samples")) {
            fields(row, "measurementId", "key", "from", "to", "value", "coverage", "eligible"); key(row.get("key"));
            require(row.path("key").path("topologyVersion").equals(policy.path("topologyVersion")), "Historical topology mismatch");
            String id = row.path("measurementId").asText(); require(row.path("measurementId").isTextual() && !id.isBlank(), "Invalid identity");
            JsonNode previous = identities.putIfAbsent(id,row);
            if (previous != null) { require(previous.equals(row), "Conflicting measurement identity"); continue; }
            keys.add(canonical(row.get("key")));
            Instant a=time(row.get("from")), b=time(row.get("to"));
            require(!a.isBefore(start) && !b.isAfter(end) && Duration.between(a,b).equals(Duration.ofSeconds(300))
                    && a.getEpochSecond()%300 == 0 && a.getNano()==0, "Invalid or future training window");
            require(suppliedWindows.add(canonical(row.get("key"))+":"+a), "Duplicate training key/window");
            double coverage=finite(row.get("coverage")); require(coverage>=0 && coverage<=1 && row.path("eligible").isBoolean(), "Invalid eligibility/coverage");
            if (!row.path("value").isNull()) {
                double value=finite(row.get("value"));
                require(value>=0 && (!row.path("key").path("unit").asText().equals("PERCENT") || value<=100),"Invalid measurement range");
            }
            if (overlaps(a,b,exclusions)) { excluded++; continue; }
            if (row.path("value").isNull()) { missing++; continue; }
            if (!row.path("eligible").booleanValue() || coverage<policy.path("minimumCoverage").doubleValue()) { invalid++; continue; }
            accepted++; Slot slot=slot(a,policy); String identity=canonical(row.get("key"))+":"+slot.slot+":"+slot.weekday;
            Group group=groups.computeIfAbsent(identity, ignored -> new Group()); group.key=row.get("key"); group.slot=slot.slot; group.weekday=slot.weekday; group.count++;
            group.days.computeIfAbsent(slot.date, ignored -> new ArrayList<>()).add(new Point(finite(row.get("value")),coverage));
        }
        ObjectNode result=JSON.createObjectNode(); result.put("schemaVersion","trace-link-seasonal-baseline-v1");
        result.set("estimatorConfiguration",policy.deepCopy());
        result.put("timezoneRulesVersion",java.time.zone.ZoneRulesProvider.getVersions(policy.path("timezone").asText()).lastKey());
        for (String name : List.of("baselineVersion","policyVersion","timezone","slotMinutes","estimator","topologyVersion")) result.set(name,policy.get(name));
        for (String name : List.of("trainingFrom","trainingTo","evaluationFrom","sourceManifestSha256","topologySnapshotSha256")) result.set(name,request.get(name));
        var mask=result.putArray("exclusions"); List<JsonNode> sortedMask=new ArrayList<>(); request.path("exclusions").forEach(sortedMask::add); sortedMask.sort(Comparator.comparing(SeasonalBaselineEstimator::canonical)); sortedMask.forEach(mask::add);
        long expected=Duration.between(start,end).getSeconds()/300*keys.size();
        long excludedCount=excludedSlots*keys.size();
        // Declared exclusions classify grid slots even when no source row was supplied.
        missing+=expected-excludedCount-(identities.size()-excluded);
        result.put("sampleCount",identities.size()).put("expectedCount",expected).put("acceptedCount",accepted).put("excludedCount",excludedCount)
                .put("missingCount",missing).put("invalidCount",invalid);
        var cells=result.putArray("cells");
        List<Group> sortedGroups=new ArrayList<>(groups.values());
        sortedGroups.sort(Comparator.comparing((Group group)->canonical(group.key)).thenComparingInt(group->group.slot).thenComparing(group->group.weekday));
        for (Group group : sortedGroups) {
            int expectedDays=calendar.getOrDefault(group.slot+":"+group.weekday,Set.of()).size();
            double coverage=expectedDays==0?0:(double)group.days.size()/expectedDays;
            if (group.days.size()<policy.path("minimumDistinctDays").intValue() || coverage<policy.path("minimumCoverage").doubleValue()) continue;
            List<Double> values=group.days.values().stream().map(points->median(points.stream().map(Point::value).toList())).toList();
            double center=median(values),mad=median(values.stream().map(value->Math.abs(value-center)).toList());
            cells.addObject().set("key",group.key);
            ObjectNode cell=(ObjectNode) cells.get(cells.size()-1);
            cell.put("slot",group.slot).put("weekdayClass",group.weekday).put("center",center).put("mad",mad).put("coverage",coverage)
                    .put("sampleCount",group.count).put("distinctDays",group.days.size()).put("expectedDays",expectedDays);
        }
        return result.deepCopy();
    }
    private static boolean overlaps(Instant from,Instant to,List<Interval> intervals) {
        return intervals.stream().anyMatch(interval->from.isBefore(interval.to)&&interval.from.isBefore(to));
    }
}
