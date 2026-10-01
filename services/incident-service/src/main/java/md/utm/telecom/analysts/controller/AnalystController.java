package md.utm.telecom.analysts.controller;

import java.util.List;
import java.util.UUID;
import md.utm.telecom.analysts.repository.AnalystRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Transactional(readOnly = true)
public class AnalystController {
    public record AnalystSummary(UUID id, String displayName, boolean enabled) {}

    private final AnalystRepository analysts;

    public AnalystController(AnalystRepository analysts) {
        this.analysts = analysts;
    }

    @GetMapping("/api/analysts")
    public List<AnalystSummary> list(@RequestParam(defaultValue = "true") boolean enabled) {
        return analysts.findAllByEnabledOrderByDisplayNameAsc(enabled).stream()
                .map(analyst -> new AnalystSummary(analyst.getId(),
                        analyst.getDisplayName(), analyst.isEnabled()))
                .toList();
    }
}
