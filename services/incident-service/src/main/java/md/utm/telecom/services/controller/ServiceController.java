package md.utm.telecom.services.controller;

import md.utm.telecom.services.model.ServiceKpiWindow;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/services")
@Transactional(readOnly = true)
public class ServiceController {
    private static final Pattern SCOPE =
            Pattern.compile("^[A-Za-z0-9_.:-]{1,96}$");
    private static final Duration MAX_RANGE = Duration.ofHours(24);
    private static final int MAX_PAGE_SIZE = 100;

    private final ServiceKpiWindowRepository windows;
    private final ObjectMapper json;
    private final Clock clock;

    public ServiceController(
            ServiceKpiWindowRepository windows,
            ObjectMapper json,
            Clock clock
    ) {
        this.windows = windows;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/{scopeId}/kpis")
    public ServiceKpiPage history(
            @PathVariable String scopeId,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validateScope(scopeId);
        validatePage(page, size);
        Instant start = parseUtc(from, "from");
        Instant end = parseUtc(to, "to");
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "to must be after from and the range must not exceed 24 hours");
        }

        ServiceKpiWindow latest = windows
                .findFirstByScopeIdOrderByWindowStartDescWindowIdDesc(scopeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Service scope not found"));

        Page<ServiceKpiWindow> result = windows.findCanonicalHistory(
                latest.getService(), scopeId, start, end, PageRequest.of(page, size));
        return new ServiceKpiPage(
                result.getContent().stream()
                        .map(item -> json.readTree(item.getPayload()))
                        .toList(),
                result.getTotalElements(), page, size, clock.instant());
    }

    private static Instant parseUtc(String value, String name) {
        if (value == null || !value.endsWith("Z")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, name + " must be a UTC instant ending in Z");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, name + " must be a valid UTC instant", exception);
        }
    }

    private static void validateScope(String scopeId) {
        if (!SCOPE.matcher(scopeId).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "scopeId is invalid");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100");
        }
    }
}
