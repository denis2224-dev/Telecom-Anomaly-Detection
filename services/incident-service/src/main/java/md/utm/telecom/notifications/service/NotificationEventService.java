package md.utm.telecom.notifications.service;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.evidence.messaging.ServiceDetectionMessage;
import md.utm.telecom.incidents.model.Incident;
import md.utm.telecom.notifications.config.NotificationProperties;
import md.utm.telecom.notifications.model.CauseStatus;
import md.utm.telecom.notifications.model.NotificationKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationEventService {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final NotificationProperties properties;
    private final NotificationTransitionClassifier classifier;
    private final NotificationSummaryFactory summaries;
    private final CausePresentationPolicy causes;
    private final ObjectMapper json;
    private final org.springframework.context.ApplicationEventPublisher events;

    public NotificationEventService(JdbcTemplate jdbc, EntityManager entityManager,
            NotificationProperties properties, NotificationTransitionClassifier classifier,
            NotificationSummaryFactory summaries, CausePresentationPolicy causes,
            ObjectMapper json, org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.properties = properties;
        this.classifier = classifier;
        this.summaries = summaries;
        this.causes = causes;
        this.json = json;
        this.events = events;
    }

    public void detection(IncidentStateSnapshot before, Incident incident,
                          ServiceDetectionMessage message) {
        if (!properties.enabled()) return;
        classifier.classify(before, IncidentStateSnapshot.from(incident), message.phase())
                .ifPresent(kind -> {
                    JsonNode payload = json.readTree(message.canonicalPayload());
                    List<String> codes = new ArrayList<>();
                    payload.path("evidence").forEach(item -> codes.add(item.path("code").asText()));
                    String primary = message.service().name().equals("VOLTE")
                            ? "cssrPct" : "deliverySrPct";
                    boolean usable = false;
                    for (JsonNode kpi : payload.path("kpis")) {
                        if (primary.equals(kpi.path("name").asText())) {
                            usable = kpi.path("observed").isNumber()
                                    && kpi.path("denominator").isNumber()
                                    && kpi.path("denominator").asDouble() > 0;
                        }
                    }
                    var cause = causes.present(false, usable,
                            payload.path("probableCause").asText(null),
                            payload.path("causeConfidence").asText(null), codes);
                    String key = kind == NotificationKind.OPEN
                            ? "OPEN:" + message.episodeId()
                            : kind.name() + ":" + message.detectionId();
                    write(key, incident, message.detectionId(), null, kind,
                            message.detectedAt(), cause, null);
                });
    }

    public void assignment(Incident incident) {
        if (!properties.enabled()) return;
        if (incident.getAssignee() == null || incident.getVersion() == null) {
            throw new IllegalStateException("Assignment must be flushed before notification");
        }
        write("ASSIGNED:" + incident.getId() + ":" + incident.getVersion(),
                incident, null, incident.getVersion(), NotificationKind.ASSIGNED,
                java.time.Instant.now(),
                new CausePresentationPolicy.CausePresentation(CauseStatus.UNDETERMINED, null, null),
                incident.getAssignee().getId());
    }

    @org.springframework.context.event.EventListener
    public void nativeTransition(md.utm.telecom.tracelink.TraceLinkIncidentService.NotificationTransition event) {
        var after=event.next().required("lifecycle");
        String phase=after.required("phase").asText(),kind=null;
        if(event.previous()==null && phase.equals("OPEN"))kind="OPEN";
        else if(event.previous()!=null && phase.equals("UPDATE")
                && after.required("technicalState").asText().equals("ONGOING")
                && after.required("severity").asText().equals("CRITICAL")
                && !event.previous().required("lifecycle").required("severity").asText().equals("CRITICAL"))kind="ESCALATED";
        else if(event.previous()!=null && phase.equals("RECOVERY")
                && !event.previous().required("lifecycle").required("technicalState").asText().equals("RECOVERED"))kind="RECOVERED";
        var incident=jdbc.queryForMap("SELECT public_id,version FROM app.trace_link_incidents WHERE episode_id=?",event.episodeId());
        // Existing stream listeners publish only after commit, even for transitions without a notification.
        events.publishEvent(new md.utm.telecom.incidents.stream.IncidentChanged((UUID)incident.get("public_id"),((Number)incident.get("version")).longValue()));
        if(!properties.enabled() || kind==null)return;
        nativeWrite(event.episodeId(),after.required("detectionId").asText(),null,kind,
                after.required("severity").asText(),event.next().required("context").required("domain").asText(),
                event.next().required("context").required("scope").required("id").asText(),
                java.time.Instant.parse(event.next().required("context").required("evaluatedAt").asText()),null);
    }
    public void nativeAssignment(String episode,long version,UUID target) {
        if(!properties.enabled())return;
        var row=jdbc.queryForMap("SELECT severity,projection::text FROM app.trace_link_incidents WHERE episode_id=?",episode);
        var context=json.readTree(row.get("projection").toString()).required("context");
        nativeWrite(episode,null,version,"ASSIGNED",row.get("severity").toString(),context.required("domain").asText(),
                context.required("scope").required("id").asText(),java.time.Instant.now(),target);
    }
    private void nativeWrite(String episode,String detection,Long version,String kind,String severity,String domain,String scope,
                             java.time.Instant at,UUID target) {
        UUID publicId=jdbc.queryForObject("SELECT public_id FROM app.trace_link_incidents WHERE episode_id=?",UUID.class,episode);
        UUID id=UUID.randomUUID();String key="TRACE:"+kind+":"+(kind.equals("OPEN")?episode:kind.equals("ASSIGNED")?episode+":"+version:detection);
        int inserted=jdbc.update("""
                INSERT INTO app.notification_event(id,event_key,native_episode_id,native_detection_id,incident_version,
                    kind,severity,service,scope_id,summary,occurred_at,cause_status,incident_url)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,'UNDETERMINED',?) ON CONFLICT(event_key) DO NOTHING
                """,id,key,episode,detection,version,kind,severity,domain.replace('_','-'),scope,
                summaries.summary(NotificationKind.valueOf(kind),domain.replace('_','-')),Timestamp.from(at),"/incidents/"+publicId);
        if(inserted==0)return;
        jdbc.update("""
                INSERT INTO app.notification_recipient(id,event_id,analyst_id,occurred_at)
                SELECT gen_random_uuid(),?,id,? FROM app.analysts WHERE enabled AND (?::uuid IS NULL OR id=?)
                ON CONFLICT(event_id,analyst_id) DO NOTHING
                """,id,Timestamp.from(at),target,target);
        if(properties.pushEnabled())queuePush(id);
    }
    private void queuePush(UUID id) {
        jdbc.update("""
                INSERT INTO app.push_delivery(id,recipient_id,subscription_id,state,attempt_count,next_attempt_at)
                SELECT gen_random_uuid(),r.id,s.id,'PENDING',0,CURRENT_TIMESTAMP
                FROM app.notification_recipient r JOIN app.push_subscription s ON s.analyst_id=r.analyst_id
                JOIN app.analysts a ON a.id=r.analyst_id
                WHERE r.event_id=? AND a.enabled AND s.enabled AND (s.expiration_time IS NULL OR s.expiration_time>CURRENT_TIMESTAMP)
                ON CONFLICT(recipient_id,subscription_id) DO NOTHING
                """,id);
    }

    private void write(String key, Incident incident, String detectionId, Long version,
            NotificationKind kind, java.time.Instant at,
            CausePresentationPolicy.CausePresentation cause, UUID target) {
        entityManager.flush();
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO app.notification_event
                    (id, event_key, incident_id, detection_id, incident_version,
                     kind, severity, service, scope_id, summary, occurred_at,
                     cause_status, probable_cause, cause_confidence, incident_url)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                """, eventId, key, incident.getId(), detectionId, version,
                kind.name(), incident.getSeverity().name(), incident.getService().name(),
                incident.getScopeId(), summaries.summary(kind, incident.getService().name()),
                Timestamp.from(at), cause.status().name(), cause.probableCause(),
                cause.confidence(), "/incidents/" + incident.getId());
        if (inserted == 0) return;
        List<UUID> recipients = target == null
                ? jdbc.query("SELECT id FROM app.analysts WHERE enabled ORDER BY id",
                        (rs, row) -> rs.getObject(1, UUID.class))
                : jdbc.query("SELECT id FROM app.analysts WHERE enabled AND id = ?",
                        (rs, row) -> rs.getObject(1, UUID.class), target);
        for (UUID analyst : recipients) {
            jdbc.update("""
                    INSERT INTO app.notification_recipient (id, event_id, analyst_id, occurred_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT (event_id, analyst_id) DO NOTHING
                    """, UUID.randomUUID(), eventId, analyst, Timestamp.from(at));
        }
        if (properties.pushEnabled()) queuePush(eventId);
    }
}
