package md.utm.telecom.notifications;

import java.util.List;
import md.utm.telecom.notifications.model.CauseStatus;
import md.utm.telecom.notifications.service.CausePresentationPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CausePresentationPolicyTest {
    private final CausePresentationPolicy policy = new CausePresentationPolicy();

    @Test
    void supportedEvidenceRemainsAnExplicitHypothesis() {
        var result = policy.present(false, true, "  Mapped transport impairment  ",
                "SUPPORTED", List.of("LINK_PACKET_LOSS"));
        assertEquals(CauseStatus.HYPOTHESIS, result.status());
        assertEquals("Mapped transport impairment", result.probableCause());
        assertEquals("SUPPORTED", result.confidence());
    }

    @Test
    void unknownOrUnusablePrimaryMetricSuppressesCause() {
        undetermined(policy.present(true, true, "Transport impairment", "SUPPORTED",
                List.of("LINK_PACKET_LOSS")));
        undetermined(policy.present(false, false, "Transport impairment", "SUPPORTED",
                List.of("LINK_PACKET_LOSS")));
    }

    @Test
    void absentBlankAndExplicitlyUndeterminedCausesAreSuppressed() {
        undetermined(policy.present(false, true, null, null, List.of("LINK_PACKET_LOSS")));
        undetermined(policy.present(false, true, " ", null, List.of("LINK_PACKET_LOSS")));
        undetermined(policy.present(false, true, "Cause undetermined", null,
                List.of("LINK_PACKET_LOSS")));
    }

    @Test
    void emptyOrWorkerOnlyEvidenceIsNotCausal() {
        undetermined(policy.present(false, true, "Transport impairment", "SUPPORTED", List.of()));
        undetermined(policy.present(false, true, "Transport impairment", "SUPPORTED",
                List.of("PING_RESULT", "PROBE_WORKER_HEALTH")));
    }

    private static void undetermined(CausePresentationPolicy.CausePresentation result) {
        assertEquals(CauseStatus.UNDETERMINED, result.status());
        assertNull(result.probableCause());
        assertNull(result.confidence());
    }
}
