package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import md.utm.telecom.observation.ObservationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class ObservationGeneratorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObservationValidator validator = new ObservationValidator();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T08:03:42Z"), ZoneOffset.UTC);

    ObservationGeneratorTest() throws Exception {}

    private ObservationGenerator generator(long seed, Clock logicalClock) throws Exception {
        return new ObservationGenerator(new GeneratorProperties(seed,
                new ClassPathResource("seeded-intervals-v2.json"), 10, null, false), logicalClock, validator, mapper);
    }

    @Test
    void tenPayloadsReproduceBytesOrderAndIdsWithSameSeedAndInjectedClock() throws Exception {
        var firstGenerator = generator(15092026, clock);
        List<String> first = firstGenerator.generate(10);
        List<String> second = generator(15092026, clock).generate(10);
        assertEquals(10, first.size());
        assertEquals(first, second); // Exact serialized JSON, including order and UUIDs.
        assertEquals(first, firstGenerator.generate(10)); // Technical retry, no random/clock drift.
        var ids = new HashSet<String>();
        Instant previous = Instant.MIN;
        for (int i = 0; i < 10; i++) {
            var payload = mapper.readTree(first.get(i));
            validator.validate(payload); // Real Draft 2020-12 schema, formats and semantics.
            assertEquals(payload.get("eventId"), mapper.readTree(second.get(i)).get("eventId"));
            assertTrue(ids.add(payload.get("eventId").asText()));
            Instant start = Instant.parse(payload.get("windowStart").asText());
            assertFalse(start.isBefore(previous));
            assertFalse(Instant.parse(payload.get("windowEnd").asText()).isAfter(clock.instant()));
            previous = start;
            for (String metadata : List.of("seed", "scenarioName", "runId", "profileVersion"))
                assertFalse(payload.has(metadata));
        }
    }

    @Test
    void seedChangesMeasurementsWhileIdentityRemainsLogicalAndMinuteChangesIdentity() throws Exception {
        var first = generator(1, clock).generate(10);
        var otherSeed = generator(2, clock).generate(10);
        assertNotEquals(first, otherSeed);
        for (int i = 0; i < 10; i++) assertEquals(mapper.readTree(first.get(i)).get("eventId"),
                mapper.readTree(otherSeed.get(i)).get("eventId"));
        var nextMinute = generator(1, Clock.offset(clock, java.time.Duration.ofMinutes(1))).generate(10);
        assertNotEquals(mapper.readTree(first.getFirst()).get("eventId"),
                mapper.readTree(nextMinute.getFirst()).get("eventId"));
    }

    @Test
    void completePlanIncludesMissingnessHeartbeatAndRepeatsWithoutDuplicateIntervals() throws Exception {
        var generated = generator(15092026, clock).generate(24);
        assertEquals(generator(15092026, clock).generate(12), generated.subList(12, 24));
        assertTrue(generated.stream().anyMatch(p -> p.contains("MISSING")));
        assertTrue(generated.stream().anyMatch(p -> p.contains("HEARTBEAT")));
        var batch = new md.utm.telecom.observation.ObservationBatch(validator);
        for (var json : generated) assertEquals(md.utm.telecom.observation.ObservationBatch.Result.ACCEPTED,
                batch.accept(mapper.readTree(json)));
        assertThrows(IllegalArgumentException.class, () -> generator(1, clock).generate(0));
        assertThrows(IllegalArgumentException.class, () -> generator(1, clock).generate(10001));
    }
}
