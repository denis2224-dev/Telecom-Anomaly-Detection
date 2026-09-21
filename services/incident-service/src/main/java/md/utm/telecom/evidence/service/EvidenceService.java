package md.utm.telecom.evidence.service;

import md.utm.telecom.evidence.model.DetectionEvidence;
import md.utm.telecom.evidence.messaging.ServiceDetectionMessage;
import md.utm.telecom.evidence.repository.DetectionEvidenceRepository;
import md.utm.telecom.incidents.model.ActorKind;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.incidents.model.IncidentAudit;
import md.utm.telecom.incidents.repository.IncidentAuditRepository;
import md.utm.telecom.incidents.repository.IncidentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class EvidenceService {
    private final DetectionEvidenceRepository evidence;
    private final IncidentRepository incidents;
    private final IncidentAuditRepository audits;
    private final ObjectMapper json;

    public EvidenceService(
            DetectionEvidenceRepository evidence,
            IncidentRepository incidents,
            IncidentAuditRepository audits,
            ObjectMapper json
    ) {
        this.evidence = evidence;
        this.incidents = incidents;
        this.audits = audits;
        this.json = json;
    }

    @Transactional
    public IngestResult ingest(String kafkaKey, String rawPayload) {
        ServiceDetectionMessage message = ServiceDetectionMessage.parse(json, rawPayload);
        if (!message.episodeId().equals(kafkaKey)) {
            throw new IllegalArgumentException("Kafka key must equal episodeId");
        }

        int inserted = evidence.insertIfAbsent(
                message.detectionId(),
                message.episodeId(),
                message.sequence(),
                message.phase().name(),
                message.service().name(),
                message.scopeId(),
                message.windowStart(),
                message.windowEnd(),
                message.detectedAt(),
                message.canonicalPayload());

        if (inserted == 0) {
            assertExactReplay(message);
            long latest = incidents.findByEpisodeId(message.episodeId())
                    .map(Incident::getLatestSequence)
                    .orElse(0L);
            return new IngestResult(Disposition.DUPLICATE, 0, latest);
        }

        Incident incident = incidents.findByEpisodeId(message.episodeId()).orElse(null);
        int applied = 0;

        if (incident == null) {
            DetectionEvidence opening = evidence
                    .findByEpisodeIdAndSequence(message.episodeId(), 1)
                    .orElse(null);
            if (opening == null) {
                return new IngestResult(Disposition.STORED_PENDING_GAP, 0, 0);
            }

            ServiceDetectionMessage openingMessage = parseStored(opening);
            incident = incidents.save(new Incident(
                    opening,
                    openingMessage.severity(),
                    openingMessage.firstObservedAt()));
            audit(incident, opening, null, snapshot(incident));
            applied = 1;
        }

        while (true) {
            long nextSequence = incident.getLatestSequence() + 1;
            DetectionEvidence next = evidence
                    .findByEpisodeIdAndSequence(message.episodeId(), nextSequence)
                    .orElse(null);
            if (next == null) {
                break;
            }

            ServiceDetectionMessage nextMessage = parseStored(next);
            if (!nextMessage.firstObservedAt().equals(incident.getFirstObservedAt())) {
                throw new IllegalArgumentException(
                        "Detection episode anchor changed within the episode");
            }
            String before = snapshot(incident);
            incident.setLatestEvidence(next);
            incident.setTechnicalState(nextMessage.technicalState());
            if (nextMessage.phase() != DetectionEvidence.Phase.UNKNOWN) {
                incident.setSeverity(nextMessage.severity());
            }
            audit(incident, next, before, snapshot(incident));
            applied++;
        }

        Disposition disposition = applied == 0
                ? Disposition.STORED_PENDING_GAP
                : Disposition.APPLIED;
        return new IngestResult(
                disposition, applied, incident.getLatestSequence());
    }

    private void assertExactReplay(ServiceDetectionMessage incoming) {
        DetectionEvidence existing = evidence.findById(incoming.detectionId())
                .orElseGet(() -> evidence
                        .findByEpisodeIdAndSequence(
                                incoming.episodeId(), incoming.sequence())
                        .orElseThrow(() -> new IllegalStateException(
                                "Detection insert conflicted without a visible stored row")));

        if (!existing.getDetectionId().equals(incoming.detectionId())
                || !json.readTree(existing.getPayload())
                .equals(json.readTree(incoming.canonicalPayload()))) {
            throw new IllegalArgumentException(
                    "detectionId or episode sequence was reused with different content");
        }
    }

    private ServiceDetectionMessage parseStored(DetectionEvidence stored) {
        ServiceDetectionMessage parsed = ServiceDetectionMessage.parse(
                json, stored.getPayload());
        if (!parsed.detectionId().equals(stored.getDetectionId())
                || !parsed.episodeId().equals(stored.getEpisodeId())
                || parsed.sequence() != stored.getSequence()) {
            throw new IllegalStateException(
                    "Stored evidence identity does not match its payload");
        }
        return parsed;
    }

    private void audit(
            Incident incident,
            DetectionEvidence detection,
            String before,
            String after
    ) {
        UUID requestId = UUID.nameUUIDFromBytes(
                detection.getDetectionId().getBytes(StandardCharsets.UTF_8));
        audits.insert(new IncidentAudit(
                incident,
                ActorKind.SYSTEM,
                null,
                detection.getPhase().name(),
                requestId,
                detection,
                before,
                after,
                null));
    }

    private String snapshot(Incident incident) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("episodeId", incident.getEpisodeId());
        state.put("status", incident.getStatus().name());
        state.put("technicalState", incident.getTechnicalState().name());
        state.put("severity", incident.getSeverity().name());
        state.put("latestSequence", incident.getLatestSequence());
        return json.writeValueAsString(state);
    }
}
