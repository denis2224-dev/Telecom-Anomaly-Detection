package md.utm.telecom.incidents.controller;

import md.utm.telecom.incidents.model.IncidentStatus;
import md.utm.telecom.incidents.model.Severity;
import md.utm.telecom.incidents.model.TechnicalState;
import md.utm.telecom.shared.ServiceType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String episodeId,
        ServiceType service,
        String scopeId,
        IncidentStatus status,
        TechnicalState technicalState,
        Severity severity,
        UUID assigneeId,
        String resolutionNote,
        Instant firstObservedAt,
        Instant detectedAt,
        Instant lastObservedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        long latestSequence,
        JsonNode latestDetection
) {
}