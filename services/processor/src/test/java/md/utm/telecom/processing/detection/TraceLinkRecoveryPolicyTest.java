package md.utm.telecom.processing.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.processing.correlation.TraceLinkRecoveryPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkRecoveryPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode result(int slot, String verdict, String severity) throws Exception {
        var fixtures = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
                "../../contracts/fixtures/trace-link/detection-cases.json")));
        var r = (ObjectNode) new TransportRule(TraceLinkDomainPolicy.load()).evaluate(
                fixtures.required("cases").get(0).required("context")).get(0);
        var from = java.time.Instant.parse("2026-10-10T08:00:00Z").plusSeconds(slot * 300L);
        r.putObject("window").put("id", "w" + slot).put("from", from.toString()).put("to", from.plusSeconds(300).toString()).put("sourceResolutionSeconds",300);
        r.put("evaluatedAt", from.plusSeconds(301).toString());
        r.put("verdict", verdict);
        if (severity == null) r.putNull("severity"); else r.put("severity", severity);
        return r;
    }
    private ObjectNode backlog(boolean drained) { return json.createObjectNode().put("required", true).put("drained", drained); }
    private ObjectNode mobileResult(int slot, String kind, String verdict) throws Exception {
        var fixtures = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(
                "../../contracts/fixtures/trace-link/detection-cases.json")));
        String id = kind.equals("MOBILE_DATA_ACTIVATION") == verdict.equals("BREACH")
                ? "mobile-activation-only" : "mobile-dns-only";
        JsonNode context = null;
        for (JsonNode item : fixtures.required("cases"))
            if (item.path("id").asText().equals(id)) context = item.required("context");
        assertNotNull(context, id);
        var evaluation = (ObjectNode) new MobileDataRule(TraceLinkDomainPolicy.load()).evaluate(context).stream()
                .filter(item -> item.path("anomalyKind").asText().equals(kind)).findFirst().orElseThrow();
        assertEquals(verdict, evaluation.path("verdict").asText());
        assertEquals(context.required("scope"), evaluation.required("scope"));
        var from = java.time.Instant.parse("2026-10-10T08:00:00Z").plusSeconds(slot * 300L);
        evaluation.putObject("window").put("id", "mobile-w" + slot).put("from", from.toString())
                .put("to", from.plusSeconds(300).toString()).put("sourceResolutionSeconds", 300);
        evaluation.put("evaluatedAt", from.plusSeconds(301).toString());
        return evaluation;
    }
    private JsonNode step(TraceLinkRecoveryPolicy policy,ObjectNode state,int slot,String verdict,String severity,
                          ObjectNode backlog,ArrayNode trajectory) throws Exception {
        var input=result(slot,verdict,severity);
        var transition=policy.advance(state,input,backlog);
        var record=trajectory.addObject();
        record.set("window",input.required("window").deepCopy());
        record.set("result",input.deepCopy());record.set("backlog",backlog.deepCopy());
        if(transition==null)record.putNull("transition");else record.set("transition",transition.deepCopy());
        record.set("state",state.deepCopy());
        return transition;
    }

    @Test void missingGrayBacklogAndGapsNeverCompleteRecoveryAndReplayIsStable() throws Exception {
        var policy = new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        var state = json.createObjectNode();
        var publication=json.createObjectNode().put("schemaVersion","trace-link-lifecycle-evidence-v1")
                .put("policyVersion","trace-link-domain-rules-v1")
                .put("purpose","Pure lifecycle transition test; verdict/time sequence is test input, not persisted source telemetry.");
        var trajectory=publication.putArray("trajectory");
        assertNull(step(policy,state,0,"BREACH","HIGH",backlog(true),trajectory));
        assertEquals("CANDIDATE", state.path("phase").asText());
        var open = step(policy,state,1,"BREACH","HIGH",backlog(true),trajectory);
        assertEquals("OPEN", open.path("phase").asText());
        var saved = state.deepCopy();
        assertNull(step(policy,state,1,"BREACH","HIGH",backlog(true),trajectory));
        assertEquals(saved, state);
        var escalation = step(policy,state,2,"BREACH","CRITICAL",backlog(true),trajectory);
        assertEquals("CRITICAL", escalation.path("severity").asText());
        step(policy,state,3,"HEALTHY",null,backlog(false),trajectory);
        assertEquals(0, state.path("healthy").asInt());
        assertEquals("UNKNOWN",step(policy,state,5,"HEALTHY",null,backlog(true),trajectory).path("phase").asText());
        assertEquals(0, state.path("healthy").asInt());
        step(policy,state,6,"HEALTHY",null,backlog(true),trajectory);
        step(policy,state,7,"UNKNOWN",null,backlog(true),trajectory);
        step(policy,state,8,"HEALTHY",null,backlog(true),trajectory);
        step(policy,state,9,"GRAY",null,backlog(true),trajectory);
        step(policy,state,10,"HEALTHY",null,backlog(true),trajectory);
        step(policy,state,11,"HEALTHY",null,backlog(true),trajectory);
        var recovered = step(policy,state,12,"HEALTHY",null,backlog(true),trajectory);
        assertEquals("RECOVERY", recovered.path("phase").asText());
        assertEquals("CRITICAL", recovered.path("severity").asText());
        assertFalse(state.path("active").asBoolean());
        step(policy,state,13,"BREACH","HIGH",backlog(true),trajectory);
        var recurrence = step(policy,state,14,"BREACH","HIGH",backlog(true),trajectory);
        assertNotEquals(open.path("episodeId"), recurrence.path("episodeId"));
        assertEquals("HIGH", recurrence.path("severity").asText());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/trace-link-lifecycle.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(publication)+"\n");
    }

    @Test void fullIdentityCannotBeMixedAndCanonicalScopeOrderDoesNotChangeIds() throws Exception {
        var policy = new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        var a = json.createObjectNode();
        var b = json.createObjectNode();
        var one = result(0, "BREACH", "HIGH");
        var two = one.deepCopy();
        var reordered = json.createObjectNode();
        var fields = new java.util.TreeMap<String, com.fasterxml.jackson.databind.JsonNode>(java.util.Comparator.reverseOrder());
        one.path("scope").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
        fields.forEach(reordered::set);
        two.set("scope", reordered);
        policy.advance(a, one, backlog(true));
        policy.advance(b, two, backlog(true));
        assertEquals(a.path("key"), b.path("key"));
        two.put("anomalyKind", "TRANSPORT_LATENCY");
        assertThrows(IllegalArgumentException.class, () -> policy.advance(a, two, backlog(true)));
    }

    @Test void conflictingOldCommittedPayloadIsRejectedAfterNewerWindows() throws Exception {
        var policy = new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        var state = json.createObjectNode();
        policy.advance(state, result(0, "BREACH", "HIGH"), backlog(true));
        policy.advance(state, result(1, "BREACH", "HIGH"), backlog(true));
        var changed = result(0, "BREACH", "CRITICAL");
        var saved = state.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> policy.advance(state, changed, backlog(true)));
        assertEquals(saved, state);
    }

    @Test void unknownBacklogDoesNotRecoverAndUnseenOlderWindowDoesNotMutateState() throws Exception {
        var policy = new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        var state = json.createObjectNode();
        policy.advance(state, result(1, "BREACH", "HIGH"), backlog(true));
        policy.advance(state, result(2, "BREACH", "HIGH"), backlog(true));
        var unknownDrain = json.createObjectNode().put("required", true).putNull("drained");
        assertEquals("UNKNOWN", policy.advance(state, result(3, "HEALTHY", null), unknownDrain).path("phase").asText());
        assertEquals(0, state.path("healthy").asInt());
        var saved = state.deepCopy();
        assertNull(policy.advance(state, result(0, "HEALTHY", null), backlog(true)));
        assertEquals(saved, state);
    }

    @Test void interleavedActivationAndDnsEpisodesRecoverOnlyFromTheirOwnHealthyWindows() throws Exception {
        var policy = new TraceLinkRecoveryPolicy(TraceLinkDomainPolicy.load());
        var activation = json.createObjectNode();
        var dns = json.createObjectNode();
        String activationKind = "MOBILE_DATA_ACTIVATION";
        String dnsKind = "MOBILE_DATA_DNS";
        policy.advance(activation, mobileResult(0, activationKind, "BREACH"), backlog(true));
        policy.advance(dns, mobileResult(0, dnsKind, "BREACH"), backlog(true));
        var activationOpen = policy.advance(activation, mobileResult(1, activationKind, "BREACH"), backlog(true));
        var dnsOpen = policy.advance(dns, mobileResult(1, dnsKind, "BREACH"), backlog(true));
        assertEquals("OPEN", activationOpen.path("phase").asText());
        assertEquals("OPEN", dnsOpen.path("phase").asText());
        assertEquals(activationOpen.path("evaluation").path("scope"), dnsOpen.path("evaluation").path("scope"));
        assertNotEquals(activation.path("key"), dns.path("key"));
        assertNotEquals(activationOpen.path("episodeId"), dnsOpen.path("episodeId"));
        var dnsHealthy = mobileResult(2, dnsKind, "HEALTHY");
        var committedActivation = activation.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> policy.advance(activation, dnsHealthy, backlog(true)));
        assertEquals(committedActivation, activation);

        for (int slot = 2; slot <= 4; slot++) {
            var savedDns = dns.deepCopy();
            var activationTransition = policy.advance(activation, mobileResult(slot, activationKind, "HEALTHY"), backlog(true));
            assertEquals(savedDns, dns, "Healthy EPS evaluations must not mutate the DNS episode");
            assertEquals(slot == 4 ? "RECOVERY" : "UPDATE", activationTransition.path("phase").asText());
            var savedActivation = activation.deepCopy();
            var dnsTransition = policy.advance(dns, mobileResult(slot, dnsKind, "BREACH"), backlog(true));
            assertEquals(savedActivation, activation);
            assertEquals("UPDATE", dnsTransition.path("phase").asText());
            assertEquals(dnsOpen.path("episodeId"), dnsTransition.path("episodeId"));
            assertTrue(dns.path("active").asBoolean());
            assertEquals(0, dns.path("healthy").asInt());
        }
        assertFalse(activation.path("active").asBoolean());

        for (int slot = 5; slot <= 7; slot++) {
            var savedDns = dns.deepCopy();
            var activationTransition = policy.advance(activation, mobileResult(slot, activationKind, "BREACH"), backlog(true));
            assertEquals(savedDns, dns);
            if (slot == 5) assertNull(activationTransition);
            else assertEquals(slot == 6 ? "OPEN" : "UPDATE", activationTransition.path("phase").asText());
            var savedActivation = activation.deepCopy();
            var dnsTransition = policy.advance(dns, mobileResult(slot, dnsKind, "HEALTHY"), backlog(true));
            assertEquals(savedActivation, activation, "Healthy DNS evaluations must not mutate the EPS episode");
            assertEquals(slot == 7 ? "RECOVERY" : "UPDATE", dnsTransition.path("phase").asText());
            assertEquals(dnsOpen.path("episodeId"), dnsTransition.path("episodeId"));
        }
        assertFalse(dns.path("active").asBoolean());
        assertTrue(activation.path("active").asBoolean());
        assertEquals(0, activation.path("healthy").asInt());
    }
}
