package md.utm.telecom.notifications.service;

import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import java.util.UUID;

public record IncidentStateSnapshot(UUID id, String episodeId, Severity severity,
                                    TechnicalState technicalState, UUID assigneeId, long version) {
    public static IncidentStateSnapshot from(Incident incident) {
        return new IncidentStateSnapshot(incident.getId(), incident.getEpisodeId(),
                incident.getSeverity(), incident.getTechnicalState(),
                incident.getAssignee() == null ? null : incident.getAssignee().getId(),
                incident.getVersion() == null ? 0 : incident.getVersion());
    }
}
