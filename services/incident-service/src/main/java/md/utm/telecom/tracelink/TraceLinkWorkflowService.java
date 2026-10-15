package md.utm.telecom.tracelink;

import java.util.Map;
import java.util.UUID;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.incidents.exception.WorkflowProblem;
import md.utm.telecom.incidents.stream.IncidentChanged;
import md.utm.telecom.notifications.service.NotificationEventService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Analyst workflow remains separate from immutable five-minute technical evidence. */
@Service
@ConditionalOnProperty(name="telecom.trace-link.enabled",havingValue="true")
public class TraceLinkWorkflowService {
    private final JdbcTemplate jdbc;private final AnalystAccess access;private final ObjectMapper json;
    private final NotificationEventService notifications;private final ApplicationEventPublisher events;
    private final TraceLinkIncidentService incidents;
    public TraceLinkWorkflowService(JdbcTemplate jdbc,AnalystAccess access,ObjectMapper json,
            NotificationEventService notifications,ApplicationEventPublisher events,TraceLinkIncidentService incidents) {
        this.jdbc=jdbc;this.access=access;this.json=json;this.notifications=notifications;this.events=events;this.incidents=incidents;
    }
    public record Workflow(UUID id,String episodeId,String status,UUID assigneeId,long version,String resolutionNote) {}
    @Transactional(readOnly=true)
    public Workflow read(String episode,Authentication actor) {access.requireEnabled(actor);return view(row(episode,false));}
    @Transactional(readOnly=true)
    public TraceLinkIncidentView byId(UUID id,Authentication actor) {
        access.requireEnabled(actor);
        String episode=jdbc.query("SELECT episode_id FROM app.trace_link_incidents WHERE public_id=?",(r,n)->r.getString(1),id).stream()
                .findFirst().orElseThrow(()->problem(HttpStatus.NOT_FOUND,"NOT_FOUND","Incident not found."));
        return incidents.detail(episode);
    }
    @Transactional
    public Workflow assign(String episode,UUID target,long version,Authentication auth) {
        UUID actor=access.requireEnabled(auth).getId();var before=row(episode,true);checkVersion(before,version);
        if("RESOLVED".equals(before.get("workflow_status")))throw problem(HttpStatus.CONFLICT,"ASSIGNMENT_CONFLICT","Resolved incidents cannot be reassigned.");
        var enabled=jdbc.query("SELECT enabled FROM app.analysts WHERE id=?",(r,n)->r.getBoolean(1),target);
        if(enabled.isEmpty())throw problem(HttpStatus.NOT_FOUND,"NOT_FOUND","Analyst not found.");
        if(!enabled.getFirst())throw problem(HttpStatus.FORBIDDEN,"FORBIDDEN","The target analyst is disabled.");
        if(!privileged(auth)&&(before.get("assignee_id")!=null||!actor.equals(target)))throw problem(HttpStatus.FORBIDDEN,"FORBIDDEN","Only a supervisor/admin may reassign work.");
        if(target.equals(before.get("assignee_id")))throw problem(HttpStatus.CONFLICT,"ASSIGNMENT_CONFLICT","Incident is already assigned to this analyst.");
        jdbc.update("UPDATE app.trace_link_incidents SET assignee_id=?,version=version+1 WHERE episode_id=?",target,episode);
        var after=row(episode,false);audit(episode,actor,"ASSIGN",before,after,null);notifications.nativeAssignment(episode,((Number)after.get("version")).longValue(),target);
        publish(after);return view(after);
    }
    @Transactional
    public Workflow status(String episode,String target,long version,String note,Authentication auth) {
        UUID actor=access.requireEnabled(auth).getId();var before=row(episode,true);checkVersion(before,version);owner(before,actor,auth);
        String current=before.get("workflow_status").toString();note=note==null?null:note.strip();
        if("INVESTIGATING".equals(target)&&"OPEN".equals(current)) {
            if(note!=null)throw problem(HttpStatus.BAD_REQUEST,"BAD_REQUEST","A resolution note is only valid when resolving.");
        } else if("RESOLVED".equals(target)&&"INVESTIGATING".equals(current)) {
            if(!"RECOVERED".equals(before.get("technical_state")))throw problem(HttpStatus.CONFLICT,"INVALID_TRANSITION","Technical recovery is required before resolution.");
            if(note==null||note.isBlank()||note.length()>2000)throw problem(HttpStatus.BAD_REQUEST,"BAD_REQUEST","A resolution note of 1 to 2000 characters is required.");
        } else throw problem(HttpStatus.CONFLICT,"INVALID_TRANSITION","Only OPEN to INVESTIGATING and INVESTIGATING to RESOLVED are allowed.");
        jdbc.update("UPDATE app.trace_link_incidents SET workflow_status=?,resolution_note=?,version=version+1 WHERE episode_id=?",target,note,episode);
        var after=row(episode,false);audit(episode,actor,target,before,after,note);publish(after);return view(after);
    }
    @Transactional
    public Workflow comment(String episode,long version,String note,Authentication auth) {
        UUID actor=access.requireEnabled(auth).getId();var before=row(episode,true);checkVersion(before,version);owner(before,actor,auth);
        if(note==null||note.isBlank()||note.strip().length()>2000)throw problem(HttpStatus.BAD_REQUEST,"BAD_REQUEST","A comment of 1 to 2000 characters is required.");
        jdbc.update("UPDATE app.trace_link_incidents SET version=version+1 WHERE episode_id=?",episode);
        var after=row(episode,false);audit(episode,actor,"COMMENT",before,after,note.strip());publish(after);return view(after);
    }
    @Transactional(readOnly=true)
    public java.util.List<Audit> audit(String episode,int limit,int offset,Authentication actor) {
        access.requireEnabled(actor);TraceLinkIncidentService.validateBounds(limit,offset);row(episode,false);
        return jdbc.query("SELECT action,version,note,occurred_at FROM app.trace_link_workflow_audit WHERE episode_id=? ORDER BY version DESC LIMIT ? OFFSET ?",
                (r,n)->new Audit(r.getString(1),r.getLong(2),r.getString(3),r.getTimestamp(4).toInstant()),episode,limit,offset);
    }
    public record Audit(String action,long version,String note,java.time.Instant occurredAt) {}
    private Map<String,Object> row(String episode,boolean lock) {
        TraceLinkIncidentService.validateEpisode(episode);
        var rows=jdbc.queryForList("SELECT episode_id,public_id,workflow_status,assignee_id,version,resolution_note,technical_state FROM app.trace_link_incidents WHERE episode_id=?"+(lock?" FOR UPDATE":""),episode);
        if(rows.isEmpty())throw problem(HttpStatus.NOT_FOUND,"NOT_FOUND","Incident not found.");return rows.getFirst();
    }
    private Workflow view(Map<String,Object> row){return new Workflow((UUID)row.get("public_id"),row.get("episode_id").toString(),row.get("workflow_status").toString(),(UUID)row.get("assignee_id"),((Number)row.get("version")).longValue(),(String)row.get("resolution_note"));}
    private void checkVersion(Map<String,Object> row,long version){if(((Number)row.get("version")).longValue()!=version)throw problem(HttpStatus.CONFLICT,"STALE_VERSION","The incident changed. Refresh and retry.");}
    private void owner(Map<String,Object> row,UUID actor,Authentication auth){if(row.get("assignee_id")==null)throw problem(HttpStatus.CONFLICT,"INVALID_TRANSITION","Assign the incident before investigation.");
        if(!privileged(auth)&&!actor.equals(row.get("assignee_id")))throw problem(HttpStatus.FORBIDDEN,"FORBIDDEN","Only the assignee or a supervisor/admin may change this incident.");}
    private boolean privileged(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_SUPERVISOR")||a.getAuthority().equals("ROLE_ADMIN"));}
    private void publish(Map<String,Object> row){events.publishEvent(new IncidentChanged((UUID)row.get("public_id"),((Number)row.get("version")).longValue()));}
    private void audit(String episode,UUID actor,String action,Map<String,Object> before,Map<String,Object> after,String note){
        jdbc.update("INSERT INTO app.trace_link_workflow_audit(id,episode_id,actor_id,version,action,before_state,after_state,note) VALUES(?,?,?,?,?,?::jsonb,?::jsonb,?)",
                UUID.randomUUID(),episode,actor,after.get("version"),action,json.writeValueAsString(view(before)),json.writeValueAsString(view(after)),note);
    }
    private static WorkflowProblem problem(HttpStatus status,String code,String message){return new WorkflowProblem(status,code,message);}
}
