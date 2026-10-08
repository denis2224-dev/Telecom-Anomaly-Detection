package md.utm.telecom.geography;

import java.time.Clock;
import md.utm.telecom.analysts.service.AnalystAccess;
import md.utm.telecom.geography.PriorityResponses.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/operations")
public class PriorityController {
    private final PriorityProjectionRepository reads;
    private final AnalystAccess access;
    private final Clock clock;

    public PriorityController(PriorityProjectionRepository reads, AnalystAccess access, Clock clock) {
        this.reads = reads;
        this.access = access;
        this.clock = clock;
    }

    @GetMapping("/priority")
    public Page priority(@RequestParam(required = false) String cityId,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String technicalState,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        access.requireEnabled(authentication);
        if ((cityId != null && !cityId.matches("[A-Z]{3}"))
                || (service != null && !service.equals("VOLTE") && !service.equals("SMS"))
                || (technicalState != null && !technicalState.equals("ONGOING")
                    && !technicalState.equals("UNKNOWN") && !technicalState.equals("RECOVERED"))
                || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid priority request");
        }
        return reads.page(cityId, service, technicalState, page, size, clock.instant());
    }
}
