package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import md.utm.telecom.observation.ObservationValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent numeric oracle for Day 1. Not a runtime geographic aggregator. */
class GeographicAggregationCasesTest {
    private final JsonNode suite;

    GeographicAggregationCasesTest() throws Exception {
        suite = ObservationValidator.resource("fixtures/geography/numeric-cases-v1.json", new ObjectMapper());
    }

    @Test void exactCounterWeightedValuesAndUnavailableReasons() {
        for (var test : suite.required("voiceCases")) {
            long successes = 0, denominator = 0;
            var expectedSuccesses = BigDecimal.ZERO;
            boolean complete = true, baselines = true;
            for (var p : test.required("partitions")) {
                if (!p.path("quality").asText().equals("COMPLETE")) { complete = false; continue; }
                long d = p.required("attempts").asLong() - p.required("userOutcomes").asLong();
                assertEquals(d, p.required("technicalSuccesses").asLong() + p.required("technicalFailures").asLong());
                denominator = Math.addExact(denominator, d);
                successes = Math.addExact(successes, p.required("technicalSuccesses").asLong());
                if (p.required("baselinePct").isNull()) baselines = false;
                else expectedSuccesses = expectedSuccesses.add(new BigDecimal(p.path("baselinePct").asText())
                        .multiply(BigDecimal.valueOf(d)).movePointLeft(2));
            }
            var expected = test.required("expected");
            assertEquals(successes, expected.path("numerator").asLong(), test.path("id").asText());
            assertEquals(denominator, expected.path("denominator").asLong());
            assertEquals(complete ? "COMPLETE" : "PARTIAL", expected.path("coverage").asText());
            BigDecimal observed = denominator == 0 ? null : BigDecimal.valueOf(successes).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128);
            String reason = !complete ? "PARTIAL_COVERAGE" : !baselines ? "BASELINE_MISSING"
                    : denominator == 0 ? "ZERO_DENOMINATOR" : null;
            BigDecimal baseline = reason == null ? expectedSuccesses.multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128) : null;
            BigDecimal delta = baseline == null || observed == null ? null : observed.subtract(baseline);
            numeric(expected, "observedPct", observed);
            numeric(expected, "baselinePct", baseline);
            numeric(expected, "deltaPp", delta);
            numeric(expected, "detectorDropPp", delta == null ? null : delta.negate());
            numeric(expected, "extraFailedAttempts", reason == null
                    ? expectedSuccesses.subtract(BigDecimal.valueOf(successes)).max(BigDecimal.ZERO) : null);
            assertTrue(expected.required("uniqueSubscribers").isNull());
            Map<String, String> reasons = new HashMap<>();
            if (observed == null) reasons.put("observedPct", complete ? "ZERO_DENOMINATOR" : "PARTIAL_COVERAGE");
            if (reason != null) for (String name : new String[]{"baselinePct", "deltaPp", "detectorDropPp", "extraFailedAttempts"})
                reasons.put(name, reason);
            assertEquals(new ObjectMapper().valueToTree(reasons), expected.required("nullReasons"));
        }
    }

    @Test void nearestRankRawMergeAndUnmergeableScalarExpectations() {
        for (var test : suite.required("smsCases")) {
            var partitions = test.required("partitions");
            var expected = test.required("expected");
            boolean complete = true, raw = true;
            var samples = new ArrayList<BigDecimal>();
            for (var p : partitions) {
                complete &= p.path("quality").asText().equals("COMPLETE");
                raw &= !p.required("samples").isNull();
                if (p.path("samples").isArray()) p.path("samples").forEach(s -> samples.add(s.decimalValue()));
            }
            if (!complete) {
                assertEquals("PARTIAL_COVERAGE", expected.path("nullReason").asText());
                numeric(expected, "p95DeliveryMs", null);
                assertTrue(expected.path("sampleCount").isNull());
            } else if (!raw && partitions.size() > 1) {
                assertEquals("NOT_AGGREGATABLE", expected.path("nullReason").asText());
                numeric(expected, "p95DeliveryMs", null);
                assertTrue(expected.path("sampleCount").isNull());
            } else if (!raw) {
                numeric(expected, "p95DeliveryMs", partitions.get(0).path("p95DeliveryMs").decimalValue());
                assertEquals(partitions.get(0).path("sampleCount"), expected.path("sampleCount"));
                assertTrue(expected.path("nullReason").isNull());
            } else {
                samples.sort(BigDecimal::compareTo);
                numeric(expected, "p95DeliveryMs", samples.isEmpty() ? null : samples.get((95 * samples.size() + 99) / 100 - 1));
                assertEquals(samples.size(), expected.path("sampleCount").asInt());
                if (samples.isEmpty()) assertEquals("INSUFFICIENT_DATA", expected.path("nullReason").asText());
                else assertTrue(expected.path("nullReason").isNull());
            }
        }
    }

    private static void numeric(JsonNode expected, String field, BigDecimal actual) {
        if (actual == null) assertTrue(expected.required(field).isNull(), field);
        else assertTrue(actual.subtract(expected.required(field).decimalValue()).abs()
                .compareTo(new BigDecimal("0.000000001")) <= 0, field);
    }
}
