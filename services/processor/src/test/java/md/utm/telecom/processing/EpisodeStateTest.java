package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import md.utm.telecom.processing.correlation.RecoveryPolicy;
import md.utm.telecom.processing.detection.DetectionPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpisodeStateTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String base = "2026-09-15T08:";

    private String advance(RecoveryPolicy policy, com.fasterxml.jackson.databind.node.ObjectNode state,
                           int minute, RecoveryPolicy.Verdict verdict) {
        return policy.advance(state, base + "%02d:00Z".formatted(minute),
                base + "%02d:00Z".formatted(minute + 1), verdict);
    }

    @Test void grayAndMissingWindowsResetThreeHealthyWindowRecovery() throws Exception {
        var policy = new RecoveryPolicy(new DetectionPolicy());
        var state = json.createObjectNode();
        assertNull(advance(policy, state, 0, RecoveryPolicy.Verdict.BREACH));
        assertEquals("OPEN", advance(policy, state, 1, RecoveryPolicy.Verdict.BREACH));
        assertEquals("UPDATE", advance(policy, state, 2, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("UPDATE", advance(policy, state, 3, RecoveryPolicy.Verdict.GRAY));
        assertEquals("UPDATE", advance(policy, state, 4, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("UNKNOWN", advance(policy, state, 5, RecoveryPolicy.Verdict.UNKNOWN));
        assertEquals("UPDATE", advance(policy, state, 6, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("UPDATE", advance(policy, state, 7, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("RECOVERY", advance(policy, state, 8, RecoveryPolicy.Verdict.HEALTHY));
        assertFalse(state.get("active").asBoolean());
    }

    @Test void gapDoesNotCompleteRecoveryAndRecurrenceGetsNewAnchor() throws Exception {
        var policy = new RecoveryPolicy(new DetectionPolicy());
        var state = json.createObjectNode();
        advance(policy, state, 0, RecoveryPolicy.Verdict.BREACH);
        advance(policy, state, 1, RecoveryPolicy.Verdict.BREACH);
        advance(policy, state, 2, RecoveryPolicy.Verdict.HEALTHY);
        assertEquals("UNKNOWN", advance(policy, state, 4, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("UPDATE", advance(policy, state, 5, RecoveryPolicy.Verdict.HEALTHY));
        assertEquals("RECOVERY", advance(policy, state, 6, RecoveryPolicy.Verdict.HEALTHY));
        assertNull(advance(policy, state, 7, RecoveryPolicy.Verdict.BREACH));
        assertEquals("OPEN", advance(policy, state, 8, RecoveryPolicy.Verdict.BREACH));
        assertEquals(base + "07:00Z", state.get("first").asText());
    }

    @Test void missingEvidenceResetsPendingOpening() throws Exception {
        var policy = new RecoveryPolicy(new DetectionPolicy());
        var state = json.createObjectNode();
        assertNull(advance(policy, state, 0, RecoveryPolicy.Verdict.BREACH));
        assertNull(advance(policy, state, 1, RecoveryPolicy.Verdict.UNKNOWN));
        assertNull(advance(policy, state, 2, RecoveryPolicy.Verdict.BREACH));
        assertEquals("OPEN", advance(policy, state, 3, RecoveryPolicy.Verdict.BREACH));
        assertEquals(base + "02:00Z", state.get("first").asText());
    }
}
