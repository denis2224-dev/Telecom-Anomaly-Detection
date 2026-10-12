package md.utm.telecom.tracelink.inventory;

import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static md.utm.telecom.tracelink.inventory.InventoryResponses.*;

@RestController
@RequestMapping("/api/v2/inventory")
public class TraceInventoryController {
    private final TraceInventoryService service;
    private final AnalystAccess access;

    public TraceInventoryController(TraceInventoryService service, AnalystAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/cities")
    public CityList cities(@RequestParam(required = false) String catalogueVersion,
                           Authentication authentication) {
        access.requireEnabled(authentication);
        return service.cities(catalogueVersion);
    }

    @GetMapping("/links")
    public LinkList links(@RequestParam(required = false) String catalogueVersion,
                          @RequestParam(required = false) String cityId,
                          @RequestParam(required = false) String at,
                          Authentication authentication) {
        access.requireEnabled(authentication);
        return service.links(catalogueVersion, cityId, at);
    }

    @GetMapping("/partners")
    public PartnerList partners(@RequestParam(required = false) String catalogueVersion,
                                @RequestParam(required = false) String country,
                                @RequestParam(required = false) String direction,
                                Authentication authentication) {
        access.requireEnabled(authentication);
        return service.partners(catalogueVersion, country, direction);
    }
}
