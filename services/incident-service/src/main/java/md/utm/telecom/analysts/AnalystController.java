package md.utm.telecom.analysts;

import java.util.List;
import java.util.UUID;
import md.utm.telecom.analysts.repository.AnalystRepository;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalystController {
    public record Summary(UUID id, String displayName, boolean enabled) {}
    private final AnalystRepository analysts;
    private final AnalystAccess access;
    public AnalystController(AnalystRepository analysts, AnalystAccess access) {
        this.analysts = analysts;
        this.access = access;
    }

    @GetMapping("/api/analysts")
    @Transactional(readOnly = true)
    public List<Summary> list(Authentication authentication,
                              @RequestParam(defaultValue = "true") boolean enabled) {
        access.requireEnabled(authentication);
        return analysts.findAllByEnabledOrderByDisplayNameAscIdAsc(enabled).stream()
                .map(analyst -> new Summary(analyst.getId(), analyst.getDisplayName(), analyst.isEnabled())).toList();
    }
}
