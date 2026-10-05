package md.utm.telecom.generator.continuous;

import java.time.Instant;
import java.util.List;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import org.springframework.stereotype.Component;
import md.utm.telecom.generator.GenerationContext;
import md.utm.telecom.observation.GeographyCatalog;

/** Shared canonical minute builders for intentional demo history and live telemetry. */
@Component
public class HealthyTelemetry {
    public static final List<String> SCOPES = List.of("VOLTE-MD-CENTRAL", SmsQueueScenario.SCOPE_ID);
    private final VoiceScenario voice;
    private final SmsQueueScenario sms;
    private final GeographyCatalog geography;
    private final boolean legacyEnabled;
    public HealthyTelemetry(VoiceScenario voice, SmsQueueScenario sms) { this(voice, sms, (GeographyCatalog) null, true); }
    @org.springframework.beans.factory.annotation.Autowired
    public HealthyTelemetry(VoiceScenario voice, SmsQueueScenario sms,
            java.util.Optional<GeographyCatalog> geography,
            @org.springframework.beans.factory.annotation.Value("${telecom.continuous.legacy-enabled:true}") boolean legacyEnabled) {
        this(voice, sms, geography.orElse(null), legacyEnabled);
    }
    public HealthyTelemetry(VoiceScenario voice, SmsQueueScenario sms, GeographyCatalog geography, boolean legacyEnabled) {
        if (geography != null && !geography.activation().status().equals("ACTIVE"))
            throw new IllegalArgumentException("Continuous geography must be explicitly activated");
        this.voice = voice; this.sms = sms; this.geography = geography; this.legacyEnabled = legacyEnabled;
    }

    public List<String> scopes(Instant start) {
        var result = new java.util.ArrayList<String>();
        if (geography != null && !start.isBefore(geography.activation().effectiveFrom()))
            geography.bindings().values().stream().filter(b -> !b.legacy()).map(GeographyCatalog.Binding::scopeId)
                    .sorted().forEach(result::add);
        if (legacyEnabled) result.addAll(SCOPES);
        return List.copyOf(result);
    }
    public boolean geographic(String scope) {
        return geography != null && geography.bindings().containsKey(scope) && !geography.bindings().get(scope).legacy();
    }
    public List<String> window(String scope, Instant start, long seed) {
        if (geography != null && geography.bindings().containsKey(scope)
                && !geography.bindings().get(scope).legacy()) {
            if (start.isBefore(geography.activation().effectiveFrom()))
                throw new IllegalArgumentException("Geography is not effective for this minute");
            var context = GenerationContext.forScope(geography, scope);
            return context.scope().service().equals("VOLTE") ? voice.generateHealthyWindow(start, seed, context)
                    : sms.generateHealthyWindow(start, seed, context);
        }
        return switch (scope) {
            case "VOLTE-MD-CENTRAL" -> voice.generateHealthyWindow(start, seed);
            case "SMS-MD-ROUTE-A" -> sms.generateHealthyWindow(start, seed);
            default -> throw new IllegalArgumentException("Unknown continuous scope: " + scope);
        };
    }
}
