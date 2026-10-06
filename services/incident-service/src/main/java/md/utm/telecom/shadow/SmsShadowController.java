package md.utm.telecom.shadow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import md.utm.telecom.incidents.repository.IncidentRepository;
import md.utm.telecom.shared.ServiceType;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Transactional(readOnly=true)
public class SmsShadowController {
    private final SmsShadowStore store;
    private final IncidentRepository incidents;
    public SmsShadowController(SmsShadowStore store,IncidentRepository incidents) { this.store=store; this.incidents=incidents; }
    @GetMapping("/api/services/{scopeId}/ml-shadow")
    public SmsShadowStore.Page service(@PathVariable String scopeId,@RequestParam String from,@RequestParam String to,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        validatePage(page,size);
        if(!scopeId.matches("^[A-Za-z0-9_.:-]{1,96}$")) throw bad("Invalid scopeId");
        Instant start=utc(from),end=utc(to);
        if(!end.isAfter(start) || Duration.between(start,end).compareTo(Duration.ofHours(24))>0) throw bad("Require a positive UTC range of at most 24 hours");
        if(!store.hasScope(scopeId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"SMS shadow scope not found");
        return store.page(scopeId,start,end,page,size,false);
    }
    @GetMapping("/api/incidents/{id}/ml-shadow")
    public SmsShadowStore.Page incident(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        validatePage(page,size);
        var incident=incidents.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Incident not found"));
        if(incident.getService()!=ServiceType.SMS) return new SmsShadowStore.Page(List.of(),0,page,size);
        return store.page(incident.getScopeId(),incident.getFirstObservedAt(),incident.getLastObservedAt(),page,size,true);
    }
    private static Instant utc(String value) {
        if(value==null || !value.endsWith("Z")) throw bad("Require UTC instants ending in Z");
        try { return Instant.parse(value); } catch(java.time.format.DateTimeParseException invalid) { throw bad("Invalid UTC instant"); }
    }
    private static void validatePage(int page,int size) {
        if(page<0 || size<1 || size>100) throw bad("page must be >=0 and size must be 1..100");
    }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
