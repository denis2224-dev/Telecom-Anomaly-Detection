package md.utm.telecom.processing.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("telecom.geographic-history")
public record GeographicHistoryProperties(boolean enabled, Integer days, Long seed, String jobId) {
    public GeographicHistoryProperties {
        days = days == null ? 2 : days;
        seed = seed == null ? 42L : seed;
        jobId = jobId == null || jobId.isBlank() ? "geographic-demo-v1" : jobId;
        if (days < 1 || days > 2 || seed < 0) {
            throw new IllegalArgumentException("Geographic history days 1..2 and seed >= 0 required");
        }
    }
}
