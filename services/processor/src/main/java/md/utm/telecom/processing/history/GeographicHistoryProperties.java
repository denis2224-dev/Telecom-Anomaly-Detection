package md.utm.telecom.processing.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("telecom.geographic-history")
public record GeographicHistoryProperties(boolean enabled, Integer days, Long seed, String jobId, Integer minutes) {
    public GeographicHistoryProperties(boolean enabled, Integer days, Long seed, String jobId) {
        this(enabled, days, seed, jobId, null);
    }
    @ConstructorBinding
    public GeographicHistoryProperties {
        days = days == null ? 2 : days;
        seed = seed == null ? 42L : seed;
        jobId = jobId == null ? "geographic-demo-v1" : jobId;
        if (days < 1 || days > 2 || seed < 0) {
            throw new IllegalArgumentException("Geographic history days 1..2 and seed >= 0 required");
        }
        if (minutes != null && (minutes < 1 || minutes > 2880)) {
            throw new IllegalArgumentException("Geographic history minutes must be 1..2880");
        }
        if (!jobId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}") || jobId.equals("initial-demo-v1")) {
            throw new IllegalArgumentException("Geographic job-id must be a distinct safe identifier, not initial-demo-v1");
        }
    }
    public int requestedMinutes() { return minutes == null ? days * 1440 : minutes; }
}
