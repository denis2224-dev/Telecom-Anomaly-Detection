package md.utm.telecom.notifications.service;

import md.utm.telecom.notifications.model.CauseStatus;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CausePresentationPolicy {
    private static final Set<String> NON_CAUSAL = Set.of("PING_RESULT", "PROBE_WORKER_HEALTH");

    public CausePresentation present(boolean unknownPhase, boolean primaryMetricUsable,
                                     String probableCause, String confidence,
                                     List<String> evidenceCodes) {
        boolean unusable = unknownPhase || !primaryMetricUsable
                || probableCause == null || probableCause.isBlank()
                || probableCause.toLowerCase(java.util.Locale.ROOT).contains("undetermined")
                || evidenceCodes == null || evidenceCodes.isEmpty()
                || evidenceCodes.stream().allMatch(NON_CAUSAL::contains);
        if (unusable) {
            return new CausePresentation(CauseStatus.UNDETERMINED, null, null);
        }
        return new CausePresentation(CauseStatus.HYPOTHESIS, probableCause.trim(), confidence);
    }

    public record CausePresentation(CauseStatus status, String probableCause, String confidence) {}
}
