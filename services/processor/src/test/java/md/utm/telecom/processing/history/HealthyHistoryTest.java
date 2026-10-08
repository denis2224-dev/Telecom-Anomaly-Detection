package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.generator.VoiceScenario;
import md.utm.telecom.generator.scenarios.SmsQueueScenario;
import md.utm.telecom.generator.continuous.HealthyTelemetry;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.baseline.BaselineRegistry;
import md.utm.telecom.processing.detection.DetectionPolicy;
import md.utm.telecom.processing.ingestion.PayloadCodec;
import md.utm.telecom.processing.kpi.ServiceFeatureBuilder;
import md.utm.telecom.processing.topology.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthyHistoryTest {
    @Test void healthyContextAcrossEveryUtcHourOfWeek() throws Exception {
        var json=new ObjectMapper(); var topology=TopologyCatalog.load(); var validator=new ObservationValidator(topology);
        var healthy=new HealthyTelemetry(new VoiceScenario(json,validator),new SmsQueueScenario(json,validator));
        var scopes=new ScopeRegistry(topology); var policy=new DetectionPolicy();
        var builder=new ServiceFeatureBuilder(new BaselineRegistry(),scopes,new PayloadCodec(),new EvidenceJoiner(scopes));
        var start=Instant.parse("2026-09-07T00:00:00Z");
        for(long seed:List.of(42L,99L)) {
            var identities=new HashSet<String>();
            for(int hour=0;hour<168;hour++) {
                for(String scope:HealthyTelemetry.SCOPES) {
                    var observations=new ArrayList<JsonNode>();
                    for(String raw:healthy.window(scope,start.plusSeconds(hour*3600L),seed)) {
                        var event=json.readTree(raw); validator.validate(event); observations.add(event);
                        assertTrue(identities.add(event.path("eventId").asText()));
                    }
                    var service=observations.stream().filter(e -> e.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
                    var nodes=observations.stream().filter(e -> e.path("kind").asText().equals("NODE")).toList();
                    var feature=builder.build(service,nodes); assertTrue(feature.path("mlEligible").asBoolean());
                    var kpis=new HashMap<String,JsonNode>(); feature.path("kpis").forEach(k -> kpis.put(k.path("name").asText(),k));
                    if(scope.startsWith("VOLTE")) {
                        var cssr=kpis.get("cssrPct");
                        assertTrue(cssr.path("baseline").asDouble()-cssr.path("observed").asDouble() <= policy.voice("recoveryDropPpAtMost").doubleValue());
                        assertTrue(kpis.get("eligibleAttempts").path("observed").asDouble()>=policy.voice("minAttempts").doubleValue());
                    } else {
                        var delay=kpis.get("p95DeliveryMs");
                        assertTrue(delay.path("observed").asDouble()<=policy.sms("recoveryP95DelayMsAtMost").doubleValue());
                        assertTrue(delay.path("observed").asDouble()/delay.path("baseline").asDouble()<=policy.sms("recoveryBaselineMultiplierAtMost").doubleValue());
                        assertEquals(0,kpis.get("queueDepth").path("observed").asInt());
                    }
                }
            }
        }
    }

    @Test void healthyGeographicHistoryAcrossAllTwentyCityScopes() throws Exception {
        var json = new ObjectMapper();
        var start = Instant.parse("2026-10-01T08:00:00Z");
        var geography = GeographyCatalog.activate(start);
        var validator = new ObservationValidator(geography.authority());
        var voice = new VoiceScenario(json, validator);
        var sms = new SmsQueueScenario(json, validator);
        var scopes = new ScopeRegistry(geography.authority(), geography);
        var policy = new DetectionPolicy();
        var baselines = new BaselineRegistry(Optional.of(geography));
        var codec = new PayloadCodec();
        var builder = new ServiceFeatureBuilder(baselines, scopes, codec, new EvidenceJoiner(scopes));

        var cityScopes = geography.bindings().values().stream()
                .filter(b -> !b.legacy())
                .map(GeographyCatalog.Binding::scopeId)
                .sorted()
                .toList();
        assertEquals(20, cityScopes.size());

        for (long seed : List.of(42L, 99L)) {
            var identities = new HashSet<String>();
            for (String scope : cityScopes) {
                var context = md.utm.telecom.generator.GenerationContext.forScope(geography, scope);
                var rawList = context.scope().service().equals("VOLTE")
                        ? voice.generateHealthyWindow(start, seed, context)
                        : sms.generateHealthyWindow(start, seed, context);

                var observations = new ArrayList<JsonNode>();
                for (String raw : rawList) {
                    var event = json.readTree(raw);
                    validator.validate(event);
                    observations.add(event);
                    assertTrue(identities.add(event.path("eventId").asText()));
                }

                var service = observations.stream().filter(e -> e.path("kind").asText().equals("SERVICE")).findFirst().orElseThrow();
                var nodes = observations.stream().filter(e -> e.path("kind").asText().equals("NODE")).toList();
                var feature = builder.build(service, nodes);
                assertTrue(feature.path("mlEligible").asBoolean());

                var kpis = new HashMap<String, JsonNode>();
                feature.path("kpis").forEach(k -> kpis.put(k.path("name").asText(), k));

                if (scope.startsWith("VOLTE")) {
                    var cssr = kpis.get("cssrPct");
                    assertTrue(cssr.path("baseline").asDouble() - cssr.path("observed").asDouble() <= policy.voice("recoveryDropPpAtMost").doubleValue());
                    assertTrue(kpis.get("eligibleAttempts").path("observed").asDouble() >= policy.voice("minAttempts").doubleValue());
                } else {
                    var delay = kpis.get("p95DeliveryMs");
                    assertTrue(delay.path("observed").asDouble() <= policy.sms("recoveryP95DelayMsAtMost").doubleValue());
                    assertTrue(delay.path("observed").asDouble() / delay.path("baseline").asDouble() <= policy.sms("recoveryBaselineMultiplierAtMost").doubleValue());
                    assertEquals(0, kpis.get("queueDepth").path("observed").asInt());
                }
            }
        }
    }
}
