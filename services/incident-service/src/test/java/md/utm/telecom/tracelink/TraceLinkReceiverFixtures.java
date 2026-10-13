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
    private TraceLinkReceiverFixtures() { }
}
