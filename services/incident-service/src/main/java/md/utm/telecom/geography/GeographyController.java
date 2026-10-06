package md.utm.telecom.geography;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import static md.utm.telecom.geography.GeographyResponses.*;

/** Existing /api/** session security protects these read routes. */
@RestController
@RequestMapping("/api/geography/cities")
public class GeographyController {
    private final GeographyReadRepository reads;
    private final Clock clock;
    private final AnalystAccess access;

    public GeographyController(GeographyReadRepository reads, Clock clock, AnalystAccess access) {
        this.reads = reads;
        this.clock = clock;
        this.access = access;
    }

    @GetMapping
    public CityList cities(Authentication authentication) {
        access.requireEnabled(authentication);
        return reads.cities(clock.instant());
    }

    @GetMapping("/{cityId}")
    public CityDetail city(@PathVariable String cityId, Authentication authentication) {
        access.requireEnabled(authentication);
        cityId(cityId);
        return reads.city(cityId, clock.instant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown city"));
    }

    @GetMapping("/{cityId}/kpis")
    public KpiPage history(@PathVariable String cityId, @RequestParam String service,
                           @RequestParam String from, @RequestParam String to,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size,
                           Authentication authentication) {
        access.requireEnabled(authentication);
        cityId(cityId);
        if (!service.equals("VOLTE") && !service.equals("SMS"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service");
        if (page < 0 || size < 1 || size > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        Instant start = utc(from), end = utc(to);
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid range");
        return reads.history(cityId, service, start, end, page, size);
    }

    private static void cityId(String cityId) {
        if (!cityId.matches("[A-Z]{3}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cityId");
    }

    private static Instant utc(String value) {
        if (value == null || !value.endsWith("Z"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UTC timestamp required");
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp", invalid);
        }
    }
}
