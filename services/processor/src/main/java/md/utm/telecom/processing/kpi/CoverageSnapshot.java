package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import md.utm.telecom.observation.CoverageContract;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.ObservationValidator;

/** Builds measurement facts only from the exact accepted receipt snapshot held by finalization. */
public final class CoverageSnapshot {
    private final GeographyCatalog geography;
    private final ObservationValidator observations;
    private final ObjectMapper json = new ObjectMapper();

    public CoverageSnapshot(GeographyCatalog geography) {
        this.geography = geography;
        try { observations = new ObservationValidator(geography.authority()); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }

    public ObjectNode build(String scopeId, Instant start, Instant end, JsonNode feature, List<JsonNode> receipts) {
        if (!CoverageContract.windowId(scopeId,start).equals(feature.path("windowId").asText())
                || !scopeId.equals(feature.path("scopeId").asText())
                || !start.toString().equals(feature.path("windowStart").asText())
                || !end.toString().equals(feature.path("windowEnd").asText()))
            throw new IllegalArgumentException("Coverage must match the finalized feature window");
        var scope = geography.authority().requireScope(scopeId);
        var expected = geography.expectedSourceIds(scopeId);
        var received = new TreeSet<String>();
        var usable = new TreeSet<String>();
        var issues = new TreeMap<String,String>();
        for (var receipt : receipts) {
            if (receipt.path("kind").asText().equals("HEARTBEAT")) continue;
            observations.validate(receipt);
            if (!scopeId.equals(receipt.path("scopeId").asText())
                    || !start.toString().equals(receipt.path("windowStart").asText())
                    || !end.toString().equals(receipt.path("windowEnd").asText()))
                throw new IllegalArgumentException("Foreign receipt in finalization snapshot");
            String source = receipt.path("sourceId").asText();
            if (!received.add(source)) throw new IllegalArgumentException("Duplicate measurement source in snapshot");
            String issue = switch (receipt.path("quality").asText()) {
                case "MISSING" -> "REPORTED_MISSING";
                case "INCOMPLETE" -> "INCOMPLETE";
                case "COMPLETE" -> hasRoleMeasurements(scopeId, receipt) ? null : "MEASUREMENT_MISSING";
                default -> throw new IllegalArgumentException("Invalid measurement quality");
            };
            if (issue == null) usable.add(source); else issues.put(source,issue);
        }
        for (String source : expected) if (!received.contains(source)) issues.put(source,"NOT_RECEIVED");
        var result = json.createObjectNode().put("schemaVersion",1)
                .put("coverageId",CoverageContract.coverageId(scopeId,start,geography.authority().topologyVersion(),geography.catalogueVersion()))
                .put("windowId",feature.path("windowId").asText()).put("scopeId",scopeId).put("service",scope.service())
                .put("windowStart",start.toString()).put("windowEnd",end.toString())
                .put("topologyVersion",geography.authority().topologyVersion()).put("catalogueVersion",geography.catalogueVersion());
        result.set("expectedSourceIds",json.valueToTree(expected));
        result.set("receivedSourceIds",json.valueToTree(received));
        result.set("usableSourceIds",json.valueToTree(usable));
        var reasons = result.putArray("sourceIssues");
        issues.forEach((source,reason) -> reasons.addObject().put("sourceId",source).put("reason",reason));
        result.put("synthetic",true);
        try { CoverageContract.validate(result,geography); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        return result;
    }

    private boolean hasRoleMeasurements(String scopeId, JsonNode receipt) {
        // COMPLETE SERVICE structure and exact counters/samples were checked by ObservationValidator.
        if (receipt.path("kind").asText().equals("SERVICE")) return true;
        var metrics = receipt.path("metrics");
        for (var binding : geography.bindings().get(scopeId).roles().entrySet()) {
            if (!binding.getValue().sourceId().equals(receipt.path("sourceId").asText())) continue;
            var required = switch (binding.getKey()) {
                case VOLTE_IMS -> List.of("cpuPct");
                case VOLTE_TRANSPORT, SMS_TRANSPORT -> List.of("packetLossRatio");
                case SMS_SMSC -> List.of("queueDepth","oldestPendingAgeSeconds");
            };
            return required.stream().allMatch(name -> metrics.path(name).isNumber());
        }
        throw new IllegalArgumentException("Measurement has no pinned role");
    }
}
