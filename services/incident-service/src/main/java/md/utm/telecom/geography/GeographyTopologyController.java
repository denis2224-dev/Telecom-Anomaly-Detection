package md.utm.telecom.geography;

import java.time.Clock;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static md.utm.telecom.geography.GeographyTopologyResponses.*;

@RestController
@RequestMapping("/api/geography/cities")
public class GeographyTopologyController {
    private final GeographyTopologyRepository reads;
    private final Clock clock;
    private final AnalystAccess access;

    public GeographyTopologyController(GeographyTopologyRepository reads, Clock clock, AnalystAccess access) {
        this.reads = reads;
        this.clock = clock;
        this.access = access;
    }

    @GetMapping("/{cityId}/topology")
    public TopologyPage topology(@PathVariable String cityId,
            @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String catalogueVersion,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, Authentication authentication) {
        access.requireEnabled(authentication);
        if (!cityId.matches("[A-Z]{3}") || page < 0 || size < 1 || size > 100
                || (long) page * size > Integer.MAX_VALUE
                || (parentId != null && !parentId.matches("[A-Za-z0-9_.:-]{1,96}"))
                || (catalogueVersion != null && !catalogueVersion.matches("[A-Za-z0-9_.:-]{1,128}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topology request");
        }
        return reads.topology(cityId, parentId, catalogueVersion, page, size, clock.instant());
    }
}
