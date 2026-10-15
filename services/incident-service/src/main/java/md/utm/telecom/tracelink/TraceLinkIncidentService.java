package md.utm.telecom.tracelink;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import md.utm.telecom.evidence.service.EpisodeLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable evidence and ordered projections live in the same database transaction. */
@Service
@ConditionalOnProperty(name = "telecom.trace-link.enabled", havingValue = "true")
public class TraceLinkIncidentService {
    private final EntityManager db;
    private final EpisodeLock locks;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public TraceLinkIncidentService(EntityManager db, EpisodeLock locks, ObjectMapper json, ApplicationEventPublisher events) {
        this.db = db; this.locks = locks; this.json = json; this.events = events;
    }

    @Transactional
    public IngestResult ingest(String key, String raw) {
        JsonNode envelope = TraceLinkDetectionMessage.validate(json.readTree(raw));
        JsonNode transition = envelope.required("lifecycle");
        String episode = transition.required("episodeId").asText();
        if (!episode.equals(key)) throw new IllegalArgumentException("Kafka key must equal episodeId");
        locks.acquire("trace-link:" + episode);
        String fingerprint = TraceLinkDetectionMessage.fingerprint(envelope);
        int inserted = db.createNativeQuery("""
                INSERT INTO app.trace_link_detection_evidence
                  (detection_id,episode_id,sequence,phase,window_start,window_end,payload,fingerprint)
                VALUES (:id,:episode,:sequence,:phase,:start,:end,CAST(:payload AS jsonb),:fingerprint)
                ON CONFLICT DO NOTHING
                """)
                .setParameter("id", transition.required("detectionId").asText())
                .setParameter("episode", episode).setParameter("sequence", transition.required("sequence").asLong())
                .setParameter("phase", transition.required("phase").asText())
                .setParameter("start", Instant.parse(transition.required("window").required("from").asText()))
                .setParameter("end", Instant.parse(transition.required("window").required("to").asText()))
                .setParameter("payload", TraceLinkDetectionMessage.canonical(envelope))
                .setParameter("fingerprint", fingerprint).executeUpdate();
        if (inserted == 0) {
            List<?> existing = db.createNativeQuery("""
                    SELECT fingerprint FROM app.trace_link_detection_evidence
                    WHERE detection_id=:id OR (episode_id=:episode AND sequence=:sequence)
                    """)
                    .setParameter("id", transition.required("detectionId").asText())
                    .setParameter("episode", episode).setParameter("sequence", transition.required("sequence").asLong())
                    .getResultList();
            if (existing.size() != 1 || !fingerprint.equals(existing.getFirst().toString()))
                throw new IllegalArgumentException("Conflicting Trace Link detection replay");
        }
        return applyContiguous(episode, inserted == 0);
    }

    @Transactional
    public IngestResult reconcile(String episode) {
        validateEpisode(episode); locks.acquire("trace-link:" + episode);
        return applyContiguous(episode, false);
    }

    private IngestResult applyContiguous(String episode, boolean duplicate) {
        List<?> rows = db.createNativeQuery("""
                SELECT latest_sequence,projection::text,version FROM app.trace_link_incidents WHERE episode_id=:episode
                """).setParameter("episode", episode).getResultList();
        long latest = rows.isEmpty() ? 0 : ((Number)((Object[]) rows.getFirst())[0]).longValue();
        long version = rows.isEmpty() ? 0 : ((Number)((Object[]) rows.getFirst())[2]).longValue();
        JsonNode previous = rows.isEmpty() ? null : json.readTree(((Object[]) rows.getFirst())[1].toString());
        int applied = 0;
        while (true) {
            List<?> pending = db.createNativeQuery("""
                    SELECT payload::text FROM app.trace_link_detection_evidence WHERE episode_id=:episode AND sequence=:sequence
                    """).setParameter("episode", episode).setParameter("sequence", latest + 1).getResultList();
            if (pending.isEmpty()) break;
            JsonNode next = TraceLinkDetectionMessage.validate(json.readTree(pending.getFirst().toString()));
            JsonNode lifecycle = next.required("lifecycle");
            if (previous != null) validateSuccessor(previous, next);
            Instant end = Instant.parse(lifecycle.required("window").required("to").asText());
            if (latest == 0) {
                db.createNativeQuery("""
                        INSERT INTO app.trace_link_incidents
                        (episode_id,latest_sequence,technical_state,severity,first_observed_at,detected_at,last_observed_at,projection,version)
                        VALUES (:episode,:sequence,:state,:severity,:start,:detected,:end,CAST(:payload AS jsonb),1)
                        """).setParameter("episode", episode).setParameter("sequence", latest + 1)
                        .setParameter("state", lifecycle.required("technicalState").asText())
                        .setParameter("severity", lifecycle.required("severity").asText())
                        .setParameter("start", TraceLinkDetectionMessage.openingAnchor(next))
                        .setParameter("detected", Instant.parse(next.required("context").required("evaluatedAt").asText()))
                        .setParameter("end", end)
                        .setParameter("payload", TraceLinkDetectionMessage.canonical(next)).executeUpdate();
            } else {
                db.createNativeQuery("""
                        UPDATE app.trace_link_incidents SET latest_sequence=:sequence,technical_state=:state,
                        severity=:severity,last_observed_at=:end,projection=CAST(:payload AS jsonb),version=version+1
                        WHERE episode_id=:episode
                        """).setParameter("episode", episode).setParameter("sequence", latest + 1)
                        .setParameter("state", lifecycle.required("technicalState").asText())
                        .setParameter("severity", lifecycle.required("severity").asText())
                        .setParameter("end", end).setParameter("payload", TraceLinkDetectionMessage.canonical(next)).executeUpdate();
            }
            db.createNativeQuery("""
                    INSERT INTO app.trace_link_incident_audit(episode_id,detection_id,sequence,phase)
                    VALUES (:episode,:id,:sequence,:phase)
                    """).setParameter("episode", episode).setParameter("id", lifecycle.required("detectionId").asText())
                    .setParameter("sequence", latest + 1).setParameter("phase", lifecycle.required("phase").asText()).executeUpdate();
            events.publishEvent(new NotificationTransition(episode, previous, next));
            latest++; version++; applied++; previous = next;
        }
        if (applied > 0) events.publishEvent(new TraceLinkIncidentChanged(episode, version));
        return new IngestResult(applied > 0 ? "APPLIED" : duplicate ? "DUPLICATE" : "STORED_PENDING_GAP", applied, latest);
    }

    static void validateSuccessor(JsonNode previous, JsonNode next) {
        JsonNode before = previous.required("lifecycle"), after = next.required("lifecycle");
        if ("RECOVERED".equals(before.required("technicalState").asText()))
            throw new IllegalArgumentException("Recovered episode cannot reopen");
        for (String field : List.of("datasetId", "namespace", "domain", "scope"))
            if (!previous.required("context").required(field).equals(next.required("context").required(field)))
                throw new IllegalArgumentException("Episode context changed: " + field);
        for (String field : List.of("anomalyKind", "policyVersion", "serviceScope"))
            if (!Objects.equals(before.required("evaluation").get(field), after.required("evaluation").get(field)))
                throw new IllegalArgumentException("Episode identity changed: " + field);
        if (Instant.parse(after.required("window").required("from").asText())
                .isBefore(Instant.parse(before.required("window").required("to").asText())))
            throw new IllegalArgumentException("Episode windows overlap or move backwards");
        if ("CRITICAL".equals(before.required("severity").asText()) && !"CRITICAL".equals(after.required("severity").asText()))
            throw new IllegalArgumentException("Episode severity cannot decrease");
    }

    public record Filter(String datasetId,String domain,String cityId,String linkDirectionId,String countryId,String roamingDirection,Instant from,Instant to) {
        public Filter {
            if(datasetId!=null&&!datasetId.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid dataset");
            if(domain!=null&&!List.of("all","volte","transport","roaming","sms","mobile-data").contains(domain))throw new IllegalArgumentException("Invalid domain");
            for(String id:new String[]{cityId,linkDirectionId,countryId})if(id!=null&&!id.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid scope");
            if(roamingDirection!=null&&!List.of("both","in","out").contains(roamingDirection))throw new IllegalArgumentException("Invalid direction");
            if((from==null)!=(to==null)||from!=null&&(!from.isBefore(to)||java.time.Duration.between(from,to).compareTo(java.time.Duration.ofDays(30))>0))throw new IllegalArgumentException("Invalid half-open interval");
        }
    }
    @Transactional(readOnly = true)
    public Page<TraceLinkIncidentView> list(int limit,int offset) {return list(limit,offset,new Filter(null,null,null,null,null,null,null,null));}
    @Transactional(readOnly = true)
    public Page<TraceLinkIncidentView> list(int limit,int offset,Filter filter) {
        validateBounds(limit,offset);
        String where="""
                FROM app.trace_link_incidents WHERE
                  (CAST(:dataset AS text) IS NULL OR projection#>>'{context,datasetId}'=CAST(:dataset AS text))
                  AND (CAST(:domain AS text) IS NULL OR :domain='all' OR replace(lower(projection#>>'{context,domain}'),'_','-')=:domain)
                  AND (CAST(:city AS text) IS NULL OR :city='all' OR projection#>>'{context,domain}'='ROAMING' OR projection#>'{context,scope,cityIds}' @> jsonb_build_array(upper(CAST(:city AS text))))
                  AND (CAST(:link AS text) IS NULL OR projection#>>'{context,domain}'<>'TRANSPORT' OR projection#>>'{context,scope,id}'=:link)
                  AND (CAST(:country AS text) IS NULL OR :country='all' OR projection#>>'{context,domain}'<>'ROAMING' OR split_part(projection#>>'{context,scope,partnerId}','-',1)=:country)
                  AND (CAST(:direction AS text) IS NULL OR :direction='both' OR projection#>>'{context,domain}'<>'ROAMING' OR projection#>>'{context,scope,directionId}'=:direction)
                  AND (CAST(:from AS timestamptz) IS NULL OR last_observed_at>CAST(:from AS timestamptz))
                  AND (CAST(:to AS timestamptz) IS NULL OR first_observed_at<CAST(:to AS timestamptz))
                """;
        var data=db.createNativeQuery("SELECT "+VIEW_COLUMNS+" "+where+" ORDER BY first_observed_at DESC,episode_id DESC");
        var count=db.createNativeQuery("SELECT count(*) "+where);
        for(var query:List.of(data,count))query.setParameter("dataset",filter.datasetId()).setParameter("domain",filter.domain())
                .setParameter("city",filter.cityId()).setParameter("link",filter.linkDirectionId()).setParameter("country",filter.countryId())
                .setParameter("direction",filter.roamingDirection()).setParameter("from",filter.from()).setParameter("to",filter.to());
        var values=data.setFirstResult(offset).setMaxResults(limit).getResultList();
        return new Page<>(values.stream().map(this::view).toList(),((Number)count.getSingleResult()).longValue(),limit,offset);
    }

    @Transactional(readOnly = true)
    public TraceLinkIncidentView detail(String episode) {
        validateEpisode(episode);
        List<?> rows = db.createNativeQuery("SELECT " + VIEW_COLUMNS + " FROM app.trace_link_incidents WHERE episode_id=:episode")
                .setParameter("episode", episode).getResultList();
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Incident not found");
        return view(rows.getFirst());
    }

    @Transactional(readOnly = true)
    public Page<JsonNode> evidence(String episode, int limit, int offset) {
        validateBounds(limit, offset); detail(episode);
        List<?> rows = db.createNativeQuery("SELECT payload::text FROM app.trace_link_detection_evidence WHERE episode_id=:episode ORDER BY sequence")
                .setParameter("episode", episode).setFirstResult(offset).setMaxResults(limit).getResultList();
        long total = ((Number)db.createNativeQuery("SELECT count(*) FROM app.trace_link_detection_evidence WHERE episode_id=:episode")
                .setParameter("episode", episode).getSingleResult()).longValue();
        return new Page<>(rows.stream().map(v -> json.readTree(v.toString())).toList(), total, limit, offset);
    }

    private static final String VIEW_COLUMNS = "episode_id,first_observed_at,detected_at,last_observed_at,latest_sequence,technical_state,severity,projection::text";

    private TraceLinkIncidentView view(Object value) {
        Object[] row = (Object[]) value;
        return new TraceLinkIncidentView(row[0].toString(),instant(row[1]),instant(row[2]),instant(row[3]),
                ((Number)row[4]).longValue(),row[5].toString(),row[6].toString(),json.readTree(row[7].toString()));
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("Unsupported database timestamp type: " + value.getClass().getName());
    }

    public static void validateBounds(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("limit must be 1..100 and offset 0..100000");
    }
    static void validateEpisode(String episode) {
        if (episode == null || !episode.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid episodeId");
    }
    public record Page<T>(List<T> items, long total, int limit, int offset) { }
    public record IngestResult(String disposition, int appliedCount, long latestSequence) { }
    public record NotificationTransition(String episodeId, JsonNode previous, JsonNode next) {}
    public record TraceLinkIncidentChanged(String episodeId, long version) { }
}
