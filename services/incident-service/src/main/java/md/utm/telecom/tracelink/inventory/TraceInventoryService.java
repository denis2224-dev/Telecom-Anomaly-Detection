package md.utm.telecom.tracelink.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static md.utm.telecom.tracelink.inventory.InventoryResponses.*;

@Service
public class TraceInventoryService {
    private final TraceInventoryRepository repository;
    private final Clock clock;
    private final md.utm.telecom.tracelink.demo.RoamingDemoService demo;

    public TraceInventoryService(TraceInventoryRepository repository, Clock clock, md.utm.telecom.tracelink.demo.RoamingDemoService demo) {
        this.repository = repository;
        this.clock = clock;
        this.demo = demo;
    }

    public CityList cities(String requestedVersion) {
        if (demo.enabled() && md.utm.telecom.tracelink.demo.RoamingDemoData.CATALOGUE.equals(requestedVersion)) return new CityList(requestedVersion,java.util.List.of());
        var catalogue = resolve(requestedVersion);
        var rows = repository.cities(catalogue.version(), requestedVersion == null);
        requireBounded(rows.size());
        return new CityList(catalogue.version(), rows);
    }

    public LinkList links(String requestedVersion, String cityId, String at) {
        if (cityId != null && !cityId.matches("[a-z0-9-]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cityId");
        }
        if (demo.enabled() && md.utm.telecom.tracelink.demo.RoamingDemoData.CATALOGUE.equals(requestedVersion)) return new LinkList(requestedVersion,java.util.List.of());
        var catalogue = resolve(requestedVersion);
        Instant instant = at == null ? requestedVersion == null ? clock.instant() : null : utc(at);
        var rows = repository.links(catalogue.version(), requestedVersion == null, cityId, instant);
        requireBounded(rows.size());
        return new LinkList(catalogue.version(), rows);
    }

    public PartnerList partners(String requestedVersion, String country, String direction) {
        if (country != null && !country.matches("[A-Z]{2,8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid country");
        }
        if (direction != null && !direction.equals("in") && !direction.equals("out")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid direction");
        }
        if (demo.enabled() && md.utm.telecom.tracelink.demo.RoamingDemoData.CATALOGUE.equals(requestedVersion))
            return new PartnerList(requestedVersion, md.utm.telecom.tracelink.demo.RoamingDemoData.partners().stream()
                    .filter(partner -> (country == null || partner.country().equals(country)) && (direction == null || partner.direction().equals(direction))).toList());
        var catalogue = resolve(requestedVersion);
        var rows = repository.partners(catalogue.version(), requestedVersion == null,
                country, direction);
        requireBounded(rows.size());
        return new PartnerList(catalogue.version(), rows);
    }

    private TraceInventoryRepository.Catalogue resolve(String requestedVersion) {
        if (requestedVersion == null) {
            return repository.activeCatalogue().orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Trace Link catalogue is not active"));
        }
        if (requestedVersion.isBlank() || requestedVersion.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalogue version");
        }
        return repository.catalogue(requestedVersion).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown catalogue version"));
    }

    private static Instant utc(String value) {
        if (!value.endsWith("Z")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UTC timestamp required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp");
        }
    }

    private static void requireBounded(int count) {
        if (count > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inventory result too large");
        }
    }
}
