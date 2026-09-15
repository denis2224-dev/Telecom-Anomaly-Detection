package md.utm.telecom.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import md.utm.telecom.observation.ObservationBatch;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.stereotype.Component;

/** Replays bounded seeded fixture intervals; no scheduling, publication or run ledger. */
@Component
public final class ObservationGenerator {
    private record Entry(int minuteOffset, ObjectNode observation) {}
    private final List<Entry> entries;
    private final int spanMinutes;
    private final long seed;
    private final Clock clock;
    private final ObservationValidator validator;

    public ObservationGenerator(GeneratorProperties properties,
                                @org.springframework.beans.factory.annotation.Qualifier("generationClock") Clock clock,
                                ObservationValidator validator,
                                ObjectMapper mapper) throws IOException {
        this.seed = properties.seed();
        this.clock = clock;
        this.validator = validator;
        var loaded = new ArrayList<Entry>();
        try (var input = properties.fixtures().getInputStream()) {
            var plan = mapper.readTree(input);
            if (!"2-baseline".equals(plan.path("profileVersion").asText())
                    || !plan.path("entries").isArray() || plan.path("entries").isEmpty())
                throw new IllegalArgumentException("Expected nonempty versioned fixture plan");
            int previous = -1;
            for (var entry : plan.get("entries")) {
                if (!entry.path("minuteOffset").isIntegralNumber())
                    throw new IllegalArgumentException("minuteOffset must be an integer");
                int offset = entry.get("minuteOffset").intValue();
                if (offset < 0 || offset > 10000 || offset < previous)
                    throw new IllegalArgumentException("Fixture offsets must be bounded and ordered");
                String file = entry.path("fixture").asText();
                if (!file.matches("[a-z0-9-]+\\.json"))
                    throw new IllegalArgumentException("Expected fixture filename");
                var observation = (ObjectNode) ObservationValidator.resource("fixtures/observations/" + file, mapper);
                validator.validate(observation);
                loaded.add(new Entry(offset, observation));
                previous = offset;
            }
        }
        entries = List.copyOf(loaded);
        spanMinutes = entries.getLast().minuteOffset() + 1;
        // Check the entire supplied plan, including records beyond a requested preview count.
        generate(entries.size());
    }

    public List<String> generate(int count) {
        if (count < 1 || count > 10000) throw new IllegalArgumentException("count must be 1..10000");
        Instant completedEnd = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        long cycles = (count + entries.size() - 1L) / entries.size();
        var start = completedEnd.minus(cycles * spanMinutes, ChronoUnit.MINUTES);
        var result = new ArrayList<String>(count);
        var batch = new ObservationBatch(validator);
        for (int i = 0; i < count; i++) {
            var entry = entries.get(i % entries.size());
            var event = entry.observation().deepCopy();
            var windowStart = start.plus((long) (i / entries.size()) * spanMinutes + entry.minuteOffset(),
                    ChronoUnit.MINUTES);
            var windowEnd = windowStart.plus(1, ChronoUnit.MINUTES);
            event.put("windowStart", windowStart.toString());
            event.put("windowEnd", windowEnd.toString());
            event.put("emittedAt", windowEnd.toString());
            event.put("eventId", stableId(event));
            // Overlapping requests for the same interval must not change its measured values.
            var identity = UUID.fromString(event.get("eventId").asText());
            varyMeasuredValues(event, new Random(seed ^ identity.getMostSignificantBits()
                    ^ identity.getLeastSignificantBits()));
            if (batch.accept(event) != ObservationBatch.Result.ACCEPTED)
                throw new IllegalArgumentException("Fixture plan repeats a natural interval");
            result.add(event.toString());
        }
        return List.copyOf(result);
    }

    private static String stableId(JsonNode event) {
        // Seed, runId, quality and measurements do not change logical interval identity.
        String key = String.join("|", "telecom-observation-v2", event.get("sourceId").asText(),
                event.get("scopeId").asText(), event.get("kind").asText(), event.get("windowStart").asText());
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void varyMeasuredValues(ObjectNode event, Random random) {
        if (!event.has("metrics") || !event.path("service").asText().equals("VOLTE")) return;
        var metrics = (ObjectNode) event.get("metrics");
        int extraSuccessfulAttempts = random.nextInt(10);
        metrics.put("attempts", metrics.get("attempts").longValue() + extraSuccessfulAttempts);
        metrics.put("technicalSuccesses", metrics.get("technicalSuccesses").longValue() + extraSuccessfulAttempts);
    }
}
