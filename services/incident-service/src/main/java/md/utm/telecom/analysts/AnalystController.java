package md.utm.telecom.analysts;

import java.util.List;
import java.util.UUID;
import md.utm.telecom.analysts.repository.AnalystRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalystController {
    public record Summary(UUID id, String displayName, boolean enabled) {}
    private final AnalystRepository analysts;
    public AnalystController(AnalystRepository analysts) { this.analysts = analysts; }

    @GetMapping("/api/analysts")
    @Transactional(readOnly = true)
    public List<Summary> list(@RequestParam(defaultValue = "true") boolean enabled) {
        return analysts.findAllByEnabledOrderByDisplayNameAsc(enabled).stream()
                .map(analyst -> new Summary(analyst.getId(), analyst.getDisplayName(), analyst.isEnabled())).toList();
    }
}
