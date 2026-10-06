package md.utm.telecom.processing.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** A renewal precedes expiry; a missed continuity bound can never be bridged by the lease. */
@ConfigurationProperties("telecom.monitoring")
public record MonitoringProperties(
        @DefaultValue("10000") long tickMs,
        @DefaultValue("20") int maxContinuityGapSec,
        @DefaultValue("30") int leaseSec) {
    public MonitoringProperties {
        if (tickMs < 1000
                || tickMs > 10000
                || maxContinuityGapSec < 2
                || maxContinuityGapSec > 20
                || tickMs >= maxContinuityGapSec * 1000L
                || leaseSec <= maxContinuityGapSec
                || leaseSec > 120)
            throw new IllegalArgumentException(
                    "Require 1..10s tick < 2..20s continuity gap < lease <=120s");
    }
}
