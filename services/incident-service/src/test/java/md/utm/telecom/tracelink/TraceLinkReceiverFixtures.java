package md.utm.telecom.tracelink;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Independent hand-calculated protocol oracle, shared with producer tests. */
final class TraceLinkReceiverFixtures {
    static final ObjectMapper JSON = new ObjectMapper();
    static ObjectNode envelope(int sequence, String phase, String severity) {
        return envelope("TRANSPORT",sequence,phase,severity);
    }
    static ObjectNode envelope(String domain,int sequence,String phase,String severity) {
        try (var input=TraceLinkReceiverFixtures.class.getResourceAsStream("/contracts/fixtures/trace-link/day3-detection-envelopes.json")) {
            if(input==null) throw new IllegalStateException("Missing Day 3 shared oracle");
            for(JsonNode envelope:JSON.readTree(input).required("detections")) {
                JsonNode life=envelope.required("lifecycle");
                if(envelope.required("context").required("domain").asText().equals(domain)
                        && life.required("sequence").asInt()==sequence) {
                    ObjectNode result=(ObjectNode)envelope.deepCopy();
                    result.withObject("lifecycle").put("phase",phase).put("severity",severity)
                            .put("technicalState",phase.equals("UNKNOWN")?"UNKNOWN":phase.equals("RECOVERY")?"RECOVERED":"ONGOING");
                    JsonNode eval=result.required("lifecycle").required("evaluation");
                    result.withObject("lifecycle").put("detectionId",TraceLinkDetectionMessage.hash(life.required("episodeId").asText()+":"+sequence+":"
                            +TraceLinkDetectionMessage.canonical(life.required("window"))+":"+phase+":"+eval.required("policyVersion").asText()));
                    return result;
                }
            }
            throw new IllegalArgumentException("Missing domain/sequence oracle");
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    }

    /** Explicitly synthetic recurring OPEN in the same scope after the original episode recovered. */
    static ObjectNode recurringOpen() {
        var result = envelope(1,"OPEN","HIGH");
        shiftTimes(result,java.time.Duration.ofHours(1));
        var lifecycle = result.withObject("lifecycle");
        var evaluation = lifecycle.required("evaluation");
        var identity = JSON.createObjectNode();
        for (String field : java.util.List.of("datasetId","namespace","domain","scope","anomalyKind","policyVersion"))
            identity.set(field,evaluation.required(field).deepCopy());
        if (evaluation.has("serviceScope")) identity.set("serviceScope",evaluation.required("serviceScope").deepCopy());
        String episode = TraceLinkDetectionMessage.hash(TraceLinkDetectionMessage.hash(TraceLinkDetectionMessage.canonical(identity))
                + ":" + java.time.Instant.parse("2026-10-10T09:35:00Z"));
        lifecycle.put("episodeId",episode).put("detectionId",TraceLinkDetectionMessage.hash(episode + ":1:"
                + TraceLinkDetectionMessage.canonical(lifecycle.required("window")) + ":OPEN:" + evaluation.required("policyVersion").asText()));
        return result;
    }

    private static void shiftTimes(JsonNode value,java.time.Duration shift) {
        if (value.isObject()) {
            for (var field : value.properties()) {
                if (field.getValue().isTextual()) {
                    try { ((ObjectNode)value).put(field.getKey(),java.time.Instant.parse(field.getValue().asText()).plus(shift).toString()); }
                    catch (java.time.format.DateTimeParseException notATimestamp) { /* Keep non-time protocol strings. */ }
                } else shiftTimes(field.getValue(),shift);
            }
        } else if (value.isArray()) value.forEach(item -> shiftTimes(item,shift));
    }
    private TraceLinkReceiverFixtures() { }
}
