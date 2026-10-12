package md.utm.telecom.notifications;

import java.util.UUID;
import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.notifications.model.NotificationKind;
import md.utm.telecom.notifications.service.IncidentStateSnapshot;
import md.utm.telecom.notifications.service.NotificationTransitionClassifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationTransitionClassifierTest {
    private final NotificationTransitionClassifier classifier = new NotificationTransitionClassifier();
    private final UUID incidentId = UUID.randomUUID();

    private IncidentStateSnapshot state(Severity severity, TechnicalState technicalState) {
        return new IncidentStateSnapshot(incidentId, "episode-1", severity, technicalState, null, 0);
    }

    @Test
    void openingProducesOneItem() {
        assertEquals(NotificationKind.OPEN, classifier.classify(null,
                state(Severity.MEDIUM, TechnicalState.ONGOING), DetectionEvidence.Phase.OPEN).orElseThrow());
    }

    @Test
    void onlyIncreasingOngoingUpdateEscalates() {
        var medium = state(Severity.MEDIUM, TechnicalState.ONGOING);
        var high = state(Severity.HIGH, TechnicalState.ONGOING);
        assertEquals(NotificationKind.ESCALATED,
                classifier.classify(medium, high, DetectionEvidence.Phase.UPDATE).orElseThrow());
        assertTrue(classifier.classify(high, high, DetectionEvidence.Phase.UPDATE).isEmpty());
        assertTrue(classifier.classify(high, medium, DetectionEvidence.Phase.UPDATE).isEmpty());
        assertTrue(classifier.classify(medium,
                state(Severity.HIGH, TechnicalState.UNKNOWN), DetectionEvidence.Phase.UPDATE).isEmpty());
    }

    @Test
    void recoveryIsTechnicalAndRequiresStateChange() {
        var ongoing = state(Severity.HIGH, TechnicalState.ONGOING);
        var recovered = state(Severity.HIGH, TechnicalState.RECOVERED);
        assertEquals(NotificationKind.RECOVERED,
                classifier.classify(ongoing, recovered, DetectionEvidence.Phase.RECOVERY).orElseThrow());
        assertTrue(classifier.classify(recovered, recovered, DetectionEvidence.Phase.RECOVERY).isEmpty());
    }

    @Test
    void unknownAndNonContiguousInputsDoNotNotify() {
        var ongoing = state(Severity.HIGH, TechnicalState.ONGOING);
        assertTrue(classifier.classify(ongoing,
                state(Severity.HIGH, TechnicalState.UNKNOWN), DetectionEvidence.Phase.UNKNOWN).isEmpty());
        assertTrue(classifier.classify(null, ongoing, DetectionEvidence.Phase.UPDATE).isEmpty());
    }
}
