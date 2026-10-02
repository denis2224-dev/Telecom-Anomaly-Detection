package md.utm.telecom.processing.history;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("telecom.history")
public record HistoryProperties(boolean enabled, Integer days, Long seed) {
    public HistoryProperties {
        days = days == null ? 30 : days;
        seed = seed == null ? 42L : seed;
        if (days < 1 || days > 30 || seed < 0) throw new IllegalArgumentException("History days 1..30 and seed >= 0 required");
    }
}
