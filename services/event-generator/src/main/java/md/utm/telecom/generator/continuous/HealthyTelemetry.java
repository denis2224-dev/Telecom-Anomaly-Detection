package md.utm.telecom.generator.continuous;

import java.time.Instant;
import java.util.List;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import org.springframework.stereotype.Component;

/** Shared canonical minute builders for intentional demo history and live telemetry. */
@Component
public class HealthyTelemetry {
    public static final List<String> SCOPES = List.of("VOLTE-MD-CENTRAL", SmsQueueScenario.SCOPE_ID);
    private final VoiceScenario voice;
    private final SmsQueueScenario sms;
    public HealthyTelemetry(VoiceScenario voice, SmsQueueScenario sms) { this.voice = voice; this.sms = sms; }
    public List<String> window(String scope, Instant start, long seed) {
        return switch (scope) {
            case "VOLTE-MD-CENTRAL" -> voice.generateHealthyWindow(start, seed);
            case "SMS-MD-ROUTE-A" -> sms.generateHealthyWindow(start, seed);
            default -> throw new IllegalArgumentException("Unknown continuous scope: " + scope);
        };
    }
}
