package md.utm.telecom.generator.tracelink;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static md.utm.telecom.generator.tracelink.TraceLinkScenarioCatalogue.*;

/** Strict command file parser. No JSON coercion, unknown fields or duplicate properties. */
public final class TraceLinkScenarioCommandCodec {
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public Command read(String content) throws java.io.IOException {
        JsonNode n = json.readTree(content); fields(n, "type", "version", "scope", "seed", "start", "parameters");
        var s = n.path("scope"); fields(s, "domain", "id", "direction", "country", "procedure", "topologyVersion");
        var p = n.path("parameters"); fields(p, "normalUtilizationBp", "degradedUtilizationBp", "capacityGbps", "attempts", "healthySuccessBp", "degradedSuccessBp", "missingMinutes");
        require(n.path("seed").isIntegralNumber() && n.path("seed").canConvertToLong(), "Invalid seed");
        var missing = new TreeSet<Integer>(); require(p.path("missingMinutes").isArray() && p.path("missingMinutes").size() <= 8, "Invalid missing minutes");
        for (var m : p.path("missingMinutes")) { require(m.isIntegralNumber() && m.canConvertToInt() && missing.add(m.intValue()), "Invalid/duplicate missing minute"); }
        require(text(n,"start").endsWith("Z"), "Start must be UTC Z");
        var c = new Command(Type.valueOf(text(n,"type")), text(n,"version"), new Scope(text(s,"domain"), text(s,"id"), nullable(s,"direction"),
                nullable(s,"country"), nullable(s,"procedure"), text(s,"topologyVersion")), n.path("seed").longValue(), Instant.parse(text(n,"start")),
                new Parameters(integer(p,"normalUtilizationBp"),integer(p,"degradedUtilizationBp"),integer(p,"capacityGbps"),integer(p,"attempts"),
                        integer(p,"healthySuccessBp"),integer(p,"degradedSuccessBp"),missing));
        new TraceLinkScenarioCatalogue().validate(c); return c;
    }
    public ObjectNode write(Command c) {
        var n = json.createObjectNode().put("type", c.type().name()).put("version", c.version()).put("seed", c.seed()).put("start", c.start().toString());
        n.set("scope", json.valueToTree(c.scope())); n.set("parameters", json.valueToTree(c.parameters())); return n;
    }
    private static int integer(JsonNode n, String f) { require(n.path(f).isIntegralNumber() && n.path(f).canConvertToInt(), "Invalid integer: " + f); return n.path(f).intValue(); }
    private static String text(JsonNode n, String f) { require(n.path(f).isTextual() && !n.path(f).asText().isBlank(), "Invalid text: " + f); return n.path(f).asText(); }
    private static String nullable(JsonNode n, String f) { return n.path(f).isNull() ? null : text(n,f); }
    private static void fields(JsonNode n, String... fields) { require(n != null && n.isObject(), "Expected object"); var actual = new HashSet<String>(); n.fieldNames().forEachRemaining(actual::add); require(actual.equals(Set.of(fields)), "Unexpected/missing command fields"); }
}
