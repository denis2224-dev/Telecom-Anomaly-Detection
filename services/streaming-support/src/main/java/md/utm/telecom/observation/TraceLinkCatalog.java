package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reference inventory only. This class does not grant legacy runtime source authority. */
public final class TraceLinkCatalog {
    public static final String NAMESPACE = "trace-link-reference-v1";
    private final JsonNode geography;
    private final JsonNode topology;
    private final Map<String, JsonNode> cities = new LinkedHashMap<>();
    private final Map<String, JsonNode> links = new LinkedHashMap<>();
    private final Map<String, JsonNode> metrics = new LinkedHashMap<>();

    public TraceLinkCatalog() throws IOException {
        var mapper = new ObjectMapper();
        geography = ObservationValidator.resource("geography/trace-link-geography-v2.json", mapper);
        topology = ObservationValidator.resource("topology/trace-link-links-v1.json", mapper);
        for (var c : geography.path("cities")) cities.put(c.path("referenceCityId").asText(), c);
        for (var l : topology.path("links")) links.put(l.path("linkId").asText(), l);
        for (var m : ObservationValidator.resource("kpi/trace-link-kpi-definitions-v1.json", mapper).path("metrics"))
            metrics.put(m.path("metricId").asText(), m);
    }

    public JsonNode geography() { return geography.deepCopy(); }
    public JsonNode topology() { return topology.deepCopy(); }
    public JsonNode city(String referenceId) { return required(cities, referenceId, "reference city").deepCopy(); }
    public JsonNode link(String id) { return required(links, id, "link").deepCopy(); }
    public JsonNode metric(String id) { return required(metrics, id, "metric").deepCopy(); }

    private static JsonNode required(Map<String, JsonNode> entries, String id, String kind) {
        var value = entries.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown " + kind + ": " + id);
        return value;
    }
}
