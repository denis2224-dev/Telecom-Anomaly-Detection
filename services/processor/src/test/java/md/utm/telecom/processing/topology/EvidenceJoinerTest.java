package md.utm.telecom.processing.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog;
import md.utm.telecom.observation.GeographyCatalog;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceJoinerTest {
    @Test void foreignCitySourceCannotJoinEvenWithClaimedLocalScopeAndMissingMeasurementsStayMissing() throws Exception {
        var candidate = new EvidenceJoiner(new ScopeRegistry(GeographyCatalog.load().authority()));
        var foreign = node("VOLTE-MD-CHI","IMS-MD-BAL-01","IMS-MD-BAL-01",start,"COMPLETE","cpuPct",99);
        var rejected = candidate.join("VOLTE-MD-CHI",start,end,List.of(foreign));
        assertTrue(rejected.accepted().isEmpty());
        assertEquals(EvidenceJoiner.IgnoreReason.UNAPPROVED_DEPENDENCY,rejected.ignored().getFirst().reason());
        var local = node("VOLTE-MD-CHI","IMS-MD-CHI-01","IMS-MD-CHI-01",start,"COMPLETE",null,null);
        var absent = candidate.join("VOLTE-MD-CHI",start,end,List.of(local));
        assertTrue(absent.accepted().isEmpty());
        assertEquals(EvidenceJoiner.IgnoreReason.MEASUREMENT_MISSING,absent.ignored().getFirst().reason());
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private EvidenceJoiner joiner;
    private final Instant start = Instant.parse("2026-09-15T08:00:00Z");
    private final Instant end = Instant.parse("2026-09-15T08:01:00Z");
    private static final String SCOPE = "VOLTE-MD-CENTRAL";

    @BeforeEach
    void setUp() throws Exception {
        TopologyCatalog topology = TopologyCatalog.load();
        ScopeRegistry scopes = new ScopeRegistry(topology);
        joiner = new EvidenceJoiner(scopes);
    }

    @Test
    void allCityDependenciesJoinOnlyTheirOwnAlignedCompleteEvidence() throws Exception {
        var geo = GeographyCatalog.load();
        var candidate = new EvidenceJoiner(new ScopeRegistry(geo.authority()));
        var events = ObservationValidator.resource("fixtures/geography/complete-city-observations-v1.json", mapper);
        for (var binding : geo.bindings().values()) {
            if (binding.legacy()) continue;
            var receipts = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (var event : events) if (binding.scopeId().equals(event.path("scopeId").asText())
                    && event.path("kind").asText().equals("NODE")) receipts.add(event);
            var joined = candidate.join(binding.scopeId(), start, end, receipts);
            assertEquals(geo.authority().requireScope(binding.scopeId()).service().equals("VOLTE") ? 2 : 1,
                    joined.accepted().size());
            assertTrue(joined.ignored().isEmpty());
        }
        var foreign = events.get(6); // BAL IMS, not a CHI receipt.
        var wrongCity = candidate.join("VOLTE-MD-CHI", start, end, List.of(foreign));
        assertTrue(wrongCity.accepted().isEmpty());
        var missing = ((ObjectNode) events.get(1)).deepCopy().put("quality", "MISSING");
        missing.remove("metrics");
        var ignored = candidate.join("VOLTE-MD-CHI", start, end, List.of(missing));
        assertEquals(EvidenceJoiner.IgnoreReason.QUALITY_INELIGIBLE, ignored.ignored().getFirst().reason());
    }

    private ObjectNode node(String scopeId, String nodeId, String sourceId, Instant winStart,
                             String quality, String metricName, Number metricValue) {
        var n = mapper.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("kind", "NODE");
        n.put("scopeId", scopeId);
        n.put("nodeId", nodeId);
        n.put("sourceId", sourceId);
        n.put("windowStart", winStart.toString());
        n.put("windowEnd", winStart.plusSeconds(60).toString());
        n.put("quality", quality);
        var metrics = n.putObject("metrics");
        if (metricName != null && metricValue != null) {
            metrics.put(metricName, metricValue.doubleValue());
        }
        return n;
    }

    @Test
    void approvedDependencyWithExactIntervalIsAccepted() {
        var ims = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", "cpuPct", 42.5);
        var result = joiner.join(SCOPE, start, end, List.of(ims));

        assertEquals(1, result.accepted().size());
        assertTrue(result.ignored().isEmpty());
        var joined = result.accepted().getFirst();
        assertEquals("IMS-A", joined.nodeId());
        assertEquals("IMS-A", joined.sourceId());
        assertEquals(ims.get("eventId").asText(), joined.eventId());
        assertEquals(42.5, joined.metrics().get("cpuPct").doubleValue(), 0.0001);
    }

    @Test
    void approvedDependencyFromPreviousMinuteIsIgnoredAsWrongInterval() {
        var oldIms = node(SCOPE, "IMS-A", "IMS-A", start.minusSeconds(60), "COMPLETE", "cpuPct", 42.5);
        var result = joiner.join(SCOPE, start, end, List.of(oldIms));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.ignored().size());
        assertEquals(EvidenceJoiner.IgnoreReason.WRONG_INTERVAL, result.ignored().getFirst().reason());
        assertEquals(oldIms.get("eventId").asText(), result.ignored().getFirst().eventId());
    }

    @Test
    void sameTimeNodeFromAnotherScopeIsIgnoredWithReason() {
        var otherScopeNode = node("SMS-MD-ROUTE-A", "TRANSPORT-A", "TRANSPORT-A", start, "COMPLETE", "packetLossRatio", 0.01);
        var result = joiner.join(SCOPE, start, end, List.of(otherScopeNode));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.ignored().size());
        assertEquals(EvidenceJoiner.IgnoreReason.WRONG_SCOPE, result.ignored().getFirst().reason());
    }

    @Test
    void unrelatedOrUnapprovedNodeDependencyIsIgnoredWithReason() {
        var unapprovedRole = node(SCOPE, "ROGUE-NODE", "ROGUE-SOURCE", start, "COMPLETE", "cpuPct", 10.0);
        var unapprovedSource = node(SCOPE, "IMS-A", "FAKE-IMS-SOURCE", start, "COMPLETE", "cpuPct", 10.0);

        var result = joiner.join(SCOPE, start, end, List.of(unapprovedRole, unapprovedSource));

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.ignored().size());
        assertEquals(EvidenceJoiner.IgnoreReason.UNAPPROVED_DEPENDENCY, result.ignored().get(0).reason());
        assertEquals(EvidenceJoiner.IgnoreReason.UNAPPROVED_DEPENDENCY, result.ignored().get(1).reason());
    }

    @Test
    void incompleteOrMissingNodeMustNotProvideHealthyMeasurement() {
        var incomplete = node(SCOPE, "IMS-A", "IMS-A", start, "INCOMPLETE", "cpuPct", 0.0);
        var missing = node(SCOPE, "TRANSPORT-A", "TRANSPORT-A", start, "MISSING", "packetLossRatio", 0.0);

        var result = joiner.join(SCOPE, start, end, List.of(incomplete, missing));

        assertTrue(result.accepted().isEmpty());
        assertEquals(2, result.ignored().size());
        assertEquals(EvidenceJoiner.IgnoreReason.QUALITY_INELIGIBLE, result.ignored().get(0).reason());
        assertEquals(EvidenceJoiner.IgnoreReason.QUALITY_INELIGIBLE, result.ignored().get(1).reason());
    }

    @Test
    void missingRelevantMetricRemainsAbsentForTheFeatureBuilder() {
        var noCpu = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", "otherMetric", 99.0);
        var result = joiner.join(SCOPE, start, end, List.of(noCpu));

        assertEquals(1, result.accepted().size());
        assertTrue(result.ignored().isEmpty());
        assertNull(result.getNode("IMS-A").metrics().get("cpuPct"));
    }

    @Test
    void noNumericMeasurementIsIgnoredWithAnObservableBoundedReason() {
        var noMetric = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", null, null);
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EvidenceJoiner.class);
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        var messages = new ListAppender<ILoggingEvent>();
        messages.start();
        logger.addAppender(messages);
        try {
            var result = joiner.join(SCOPE, start, end, List.of(noMetric));
            assertEquals(EvidenceJoiner.IgnoreReason.MEASUREMENT_MISSING, result.ignored().getFirst().reason());
            assertTrue(messages.list.stream().anyMatch(e -> e.getFormattedMessage().contains("MEASUREMENT_MISSING")));
            assertTrue(messages.list.stream().noneMatch(e -> e.getFormattedMessage().contains(noMetric.get("eventId").asText())));
        } finally {
            logger.detachAppender(messages);
            messages.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void canonicalSmsNodeKeepsObservationAgeWithoutFeatureExtraction() throws Exception {
        var sms = ObservationValidator.resource("fixtures/observations/degraded-smsc.json", mapper);
        var result = joiner.join("SMS-MD-ROUTE-A", start, end, List.of(sms));
        assertEquals(1, result.accepted().size());
        assertTrue(result.getNode("SMSC-A").metrics().get("oldestPendingAgeSeconds").isNumber());
        assertNull(result.getNode("SMSC-A").metrics().get("oldestPendingAgeSec"));
    }

    @Test
    void matchingValidNodesRetainRealSourceEventIds() {
        var id1 = UUID.randomUUID().toString();
        var id2 = UUID.randomUUID().toString();
        var ims = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", "cpuPct", 35.0);
        ims.put("eventId", id1);
        var transport = node(SCOPE, "TRANSPORT-A", "TRANSPORT-A", start, "COMPLETE", "packetLossRatio", 0.02);
        transport.put("eventId", id2);

        var result = joiner.join(SCOPE, start, end, List.of(ims, transport));

        assertEquals(2, result.accepted().size());
        assertEquals(id1, result.getNode("IMS-A").eventId());
        assertEquals(id2, result.getNode("TRANSPORT-A").eventId());
    }

    @Test
    void resultOrderingIsDeterministicRegardlessOfInputOrder() {
        var ims = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", "cpuPct", 35.0);
        var transport = node(SCOPE, "TRANSPORT-A", "TRANSPORT-A", start, "COMPLETE", "packetLossRatio", 0.02);

        var result1 = joiner.join(SCOPE, start, end, List.of(ims, transport));
        var result2 = joiner.join(SCOPE, start, end, List.of(transport, ims));

        assertEquals(result1.accepted().size(), result2.accepted().size());
        assertEquals("IMS-A", result1.accepted().get(0).nodeId());
        assertEquals("TRANSPORT-A", result1.accepted().get(1).nodeId());
        assertEquals("IMS-A", result2.accepted().get(0).nodeId());
        assertEquals("TRANSPORT-A", result2.accepted().get(1).nodeId());
    }

    @Test
    void nonNodeKindIsIgnoredWithReason() {
        var heartbeat = node(SCOPE, "IMS-A", "IMS-A", start, "COMPLETE", "cpuPct", 35.0);
        heartbeat.put("kind", "HEARTBEAT");
        var result = joiner.join(SCOPE, start, end, List.of(heartbeat));

        assertTrue(result.accepted().isEmpty());
        assertEquals(1, result.ignored().size());
        assertEquals(EvidenceJoiner.IgnoreReason.NOT_NODE, result.ignored().getFirst().reason());
    }
}
