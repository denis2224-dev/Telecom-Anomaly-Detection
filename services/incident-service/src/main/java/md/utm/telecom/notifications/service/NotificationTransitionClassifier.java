package md.utm.telecom.notifications.service;

import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.notifications.model.NotificationKind;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NotificationTransitionClassifier {
    public Optional<NotificationKind> classify(IncidentStateSnapshot before,
                                                IncidentStateSnapshot after,
                                                DetectionEvidence.Phase phase) {
        if (before == null && phase == DetectionEvidence.Phase.OPEN) {
            return Optional.of(NotificationKind.OPEN);
        }
        if (before == null) {
            return Optional.empty();
        }
        if (phase == DetectionEvidence.Phase.UPDATE
                && after.technicalState() == TechnicalState.ONGOING
                && after.severity().ordinal() > before.severity().ordinal()) {
            return Optional.of(NotificationKind.ESCALATED);
        }
        if (phase == DetectionEvidence.Phase.RECOVERY
                && before.technicalState() != TechnicalState.RECOVERED
                && after.technicalState() == TechnicalState.RECOVERED) {
            return Optional.of(NotificationKind.RECOVERED);
        }
        return Optional.empty();
    }
}
