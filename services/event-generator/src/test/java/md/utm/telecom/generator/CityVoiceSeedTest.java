package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.observation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityVoiceSeedTest {
    @Test void cityAndSeedChangeScenarioMeasurementsButNeverNaturalObservationIdentity() throws Exception {
        var start = Instant.parse("2026-10-08T08:00:00Z");
        var geography = GeographyCatalog.activate(start);
        var json = new ObjectMapper();
        var voice = new VoiceScenario(json, new ObservationValidator(geography));
        var cityMeasurements = new HashSet<String>();
        for (String city : geography.cities().keySet()) {
            var context = GenerationContext.forScope(geography, "VOLTE-MD-" + city);
            for (var profile : VoiceScenario.Profile.values()) {
                var first = voice.generate(start, 42, profile, context);
                var retry = voice.generate(start, 42, profile, context);
                var changedSeed = voice.generate(start, 99, profile, context);
                assertEquals(first, retry);
                assertNotEquals(first, changedSeed, "City scenarios must carry scope-specific seeded measurements");
                for (int i = 0; i < first.size(); i++) {
                    var a = json.readTree(first.get(i));
                    var b = json.readTree(changedSeed.get(i));
                    for (String identity : List.of("eventId", "sourceId", "scopeId", "kind", "windowStart", "windowEnd", "emittedAt"))
                        assertEquals(a.get(identity), b.get(identity));
                    if (a.path("kind").asText().equals("SERVICE")) cityMeasurements.add(a.path("metrics").toString());
                }
            }
        }
        assertTrue(cityMeasurements.size() > 10, "City discriminator must affect measurements as well as scope IDs");
    }
}
