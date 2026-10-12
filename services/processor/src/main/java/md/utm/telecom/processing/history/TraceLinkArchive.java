package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TraceLinkCatalog;
import md.utm.telecom.observation.DomainObservationValidator;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Validated private snapshot prevents source-file changes between preflight and resumed batches. */
final class TraceLinkArchive implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final List<String> NATIVE_FILES = List.of("city_kpi_5m.csv", "link_kpi_5m.csv", "roaming_kpi_5m.csv");
    final JsonNode manifest;
    final String manifestHash;
    final TraceLinkCatalog catalog = new TraceLinkCatalog();
    final Instant from;
    final Instant to;
    final Path snapshot;
    final Map<String, JsonNode> files = new LinkedHashMap<>();
    final JsonNode layout = ObservationValidator.resource("datasets/trace-link-archive-layout-v1.json", JSON);
    static final Set<String> TEXT = Set.of("ts", "city_id", "link_id", "country_id", "direction", "partner_id");
    static final Set<String> DECIMAL = Set.of("voice_holding_seconds", "voice_baseline_erlang", "voice_setup_elapsed_seconds",
            "sms_oldest_seconds", "user_throughput_mbps", "traffic_gbps", "effective_capacity_gbps", "loss_pct", "rtt_ms", "jitter_ms");

    private TraceLinkArchive(Path root) throws Exception {
        Path manifestFile = root.resolve("manifest.json");
        require(Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS) && Files.size(manifestFile) <= 65536, "Invalid/oversized manifest file");
        byte[] bytes = Files.readAllBytes(manifestFile);
        require(bytes.length <= 65536, "Manifest too large");
        manifest = JSON.readTree(bytes);
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                ObservationValidator.resource("datasets/trace-link-reference-manifest-v1.schema.json", JSON),
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        require(schema.validate(manifest).isEmpty(), "Invalid manifest schema");
        manifestHash = hash(bytes);
        from = Instant.parse(manifest.path("from").asText());
        to = Instant.parse(manifest.path("to_exclusive").asText());
        require(from.getNano() == 0 && Math.floorMod(from.getEpochSecond(), 300) == 0
                && Duration.between(from, to).equals(Duration.ofSeconds(300L * manifest.path("slots").asLong())), "Manifest bounds/slots");
        boolean full = manifest.path("dataset_id").asText().equals("trace-link-md-30d-v1");
        if (full) require(manifest.equals(ObservationValidator.resource("datasets/trace-link-md-30d-v1.manifest.json", JSON)),
                "Reference manifest differs from pinned archive");
        else require(manifest.path("slots").asInt() <= 12, "Bounded fixtures may cover at most one hour");
        for (var f : manifest.path("files")) require(files.putIfAbsent(f.path("name").asText(), f) == null, "Duplicate manifest file");
        require(files.keySet().equals(new HashSet<>(list(layout.fieldNames()))), "Missing manifest file");
        snapshot = Files.createTempDirectory("trace-link-source-");
        try {
            for (var entry : files.entrySet()) {
                Path source = root.resolve(entry.getKey());
                require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "Regular source file required");
                require(Files.size(source) == entry.getValue().path("bytes").asLong(), "Source byte count mismatch: " + entry.getKey());
                Files.copy(source, snapshot.resolve(entry.getKey()));
                require(hashFile(snapshot.resolve(entry.getKey())).equals(entry.getValue().path("sha256").asText()), "Checksum mismatch: " + entry.getKey());
                validateFile(entry.getKey(), full);
            }
        } catch (Exception invalid) { close(); throw invalid; }
    }

    static TraceLinkArchive open(Path root) throws Exception { return new TraceLinkArchive(root.toAbsolutePath().normalize()); }

    private void validateFile(String file, boolean full) throws IOException {
        long rows = 0;
        var identities = new HashSet<String>();
        try (var input = Files.newBufferedReader(snapshot.resolve(file), UTF_8)) {
            require(parse(input.readLine()).equals(header(file)), "Unexpected CSV header: " + file);
            String line;
            while ((line = input.readLine()) != null) {
                rows++;
                var fields = parse(line);
                require(fields.size() == header(file).size(), "CSV field count: " + file);
                if (NATIVE_FILES.contains(file)) {
                    var row = nativeRow(file, rows, line);
                    require(identities.add(row.naturalId()), "Duplicate native source identity");
                } else validateInventory(file, fields, identities);
            }
        }
        require(rows == files.get(file).path("rows").asLong(), "Source row count mismatch: " + file);
        long slots = manifest.path("slots").asLong();
        long expected = switch (file) {
            case "cities.csv", "countries.csv" -> 10;
            case "links.csv" -> 12;
            case "city_kpi_5m.csv" -> slots * 10;
            case "link_kpi_5m.csv" -> slots * 12;
            case "roaming_kpi_5m.csv" -> slots * 20;
            default -> full ? 18 : 0;
        };
        require(rows == expected, "Incomplete native series/inventory: " + file);
    }

    private void validateInventory(String file, List<String> f, Set<String> seen) {
        if (file.equals("scenario_events.csv")) return; // Verified opaque evaluator data, never detector input.
        require(seen.add(f.getFirst()), "Duplicate inventory identity");
        switch (file) {
            case "cities.csv" -> {
                var c = catalog.city(f.getFirst());
                require(c.path("displayName").asText().equals(f.get(1)), "City display name differs");
                for (int i = 2; i < 5; i++) equalNumber(c.path(List.of("latitude","longitude","demandShare").get(i-2)), f.get(i));
            }
            case "links.csv" -> {
                var l = catalog.link(f.getFirst());
                require(catalog.city(f.get(1)).path("contractCityId").equals(l.path("endpointAContractCityId"))
                        && catalog.city(f.get(2)).path("contractCityId").equals(l.path("endpointBContractCityId")), "Link endpoints differ");
                equalNumber(l.path("nominalCapacityGbps"), f.get(3));
            }
            case "countries.csv" -> require(country(f.getFirst()) && f.get(2).equals(f.getFirst()+"-DEMO-01"), "Unknown country/partner");
            default -> throw new IllegalArgumentException("Unknown inventory file");
        }
    }

    record NativeRow(String naturalId, String kind, String scope, String contractCity, String direction,
                     String country, String partner, Instant start, String file, long ordinal,
                     String checksum, String raw, String payload) {}

    NativeRow nativeRow(String file, long ordinal, String raw) {
        var fields = parse(raw);
        var header = header(file);
        require(fields.size() == header.size(), "CSV field count");
        ObjectNode p = JSON.createObjectNode();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i), value = fields.get(i);
            if (TEXT.contains(key)) p.put(key, value);
            else if (key.endsWith("_hist")) {
                require(value.startsWith("{") && value.endsWith("}"), "Invalid finite histogram");
                var a = p.putArray(key);
                for (String count : value.substring(1, value.length()-1).split(",", -1)) a.add(count(count));
                require(a.size() == 9, "Expected nine histogram bins");
            } else if (DECIMAL.contains(key)) {
                var number = new BigDecimal(value);
                require(number.signum() >= 0, "Negative source measurement");
                p.put(key, number);
            } else p.put(key, count(value));
        }
        Instant start = Instant.parse(p.path("ts").asText());
        require(start.getNano() == 0 && Math.floorMod(start.getEpochSecond(),300) == 0
                && !start.isBefore(from) && start.isBefore(to), "Native row outside aligned manifest bounds");
        String kind, scope, contractCity = null, direction = "", partner = "", country = "";
        switch (file) {
            case "city_kpi_5m.csv" -> {
                kind = "CITY"; scope = p.path("city_id").asText();
                contractCity = catalog.city(scope).path("contractCityId").asText();
                for (String prefix : List.of("voice", "data", "dns", "ho")) subset(p, prefix+"_success", prefix+"_attempts");
                subset(p, "voice_drops", "voice_success");
                require(histogramTotal(p.path("voice_setup_hist")) == p.path("voice_success").longValue(), "Voice histogram cohort differs");
                require(histogramTotal(p.path("sms_delay_hist")) == p.path("sms_delivered").longValue(), "SMS histogram cohort differs");
            }
            case "link_kpi_5m.csv" -> {
                kind = "LINK"; scope = p.path("link_id").asText(); catalog.link(scope); direction = "a-to-b";
                require(p.path("effective_capacity_gbps").decimalValue().signum() > 0, "Absent effective capacity");
                require(p.path("loss_pct").decimalValue().compareTo(BigDecimal.valueOf(100)) <= 0, "Invalid source loss percentage");
            }
            case "roaming_kpi_5m.csv" -> {
                kind = "ROAMING"; scope = p.path("partner_id").asText(); partner = scope;
                direction = p.path("direction").asText(); country = p.path("country_id").asText();
                require(country(country) && partner.equals(country+"-DEMO-01") && Set.of("in","out").contains(direction), "Unknown roaming scope");
                for (String prefix : List.of("reg","data","voice","sms")) subset(p, prefix+"_success", prefix+"_attempts");
            }
            default -> throw new IllegalArgumentException("Not a native measurement file");
        }
        String id = DomainObservationValidator.sha256(String.join("|", manifest.path("dataset_id").asText(),
                TraceLinkCatalog.NAMESPACE, kind, scope, direction, country, partner, start.toString(), "300", "trace-link-native-source-v1"));
        return new NativeRow(id,kind,scope,contractCity,direction,country,partner,start,file,ordinal,
                DomainObservationValidator.sha256(raw),raw,p.toString());
    }

    List<String> header(String file) {
        var result = new ArrayList<String>(); layout.path(file).forEach(n -> result.add(n.asText())); return result;
    }

    /** Strict single-record CSV, including quoted histograms and escaped quotes. Multiline cells are unsupported. */
    static List<String> parse(String line) {
        require(line != null && !line.isEmpty(), "Empty CSV record");
        var fields = new ArrayList<String>(); var field = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i+1 < line.length() && line.charAt(i+1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else field.append(c);
            } else if (c == ',') { fields.add(field.toString()); field.setLength(0); closed = false; }
            else if (c == '"') { require(field.isEmpty() && !closed, "Malformed CSV quote"); quoted = true; }
            else { require(!closed, "Characters after CSV quote"); field.append(c); }
        }
        require(!quoted, "Unterminated CSV quote"); fields.add(field.toString()); return fields;
    }
    private static long count(String value) {
        require(value.matches("[0-9]+"), "Invalid source counter");
        long n = Long.parseLong(value); require(n <= 9_007_199_254_740_991L, "Unsafe source counter"); return n;
    }
    private static void subset(JsonNode p, String numerator, String denominator) {
        require(p.path(numerator).longValue() <= p.path(denominator).longValue(), "Success exceeds attempts: " + numerator);
    }
    private static long histogramTotal(JsonNode bins) {
        long total = 0; for (var bin : bins) total = Math.addExact(total,bin.longValue()); return total;
    }
    private static boolean country(String id) { return Set.of("RO","IT","DE","GB","FR","UA","ES","TR","PL","BG").contains(id); }
    private static void equalNumber(JsonNode expected, String actual) { require(expected.decimalValue().compareTo(new BigDecimal(actual)) == 0, "Inventory number differs"); }
    private static List<String> list(Iterator<String> iterator) { var a = new ArrayList<String>(); iterator.forEachRemaining(a::add); return a; }
    static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hashFile(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    @Override public void close() throws IOException {
        for (String file : files.keySet()) Files.deleteIfExists(snapshot.resolve(file));
        Files.deleteIfExists(snapshot);
    }
}
