package md.utm.telecom.evidence.controller;

import java.time.Instant;
import java.util.UUID;
import md.utm.telecom.incidents.model.ActorKind;
import tools.jackson.databind.JsonNode;

public record AuditEvent(
        UUID id,
        UUID incidentId,
        ActorKind actorKind,
        UUID actorId,
        String action,
        Instant occurredAt,
        UUID requestId,
        JsonNode before,
        JsonNode after,
        String note
) {}