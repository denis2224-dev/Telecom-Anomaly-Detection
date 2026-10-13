package md.utm.telecom.tracelink;

import java.time.Instant;
import md.utm.telecom.tracelink.metrics.TraceQuery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TraceQueryTest {
    private static final Instant END = Instant.parse("2026-10-10T09:00:00Z");

    private TraceQuery query(Instant from, Instant to, String resolution) {
        return new TraceQuery("trace-link-md-30d-v1", "volte", "voice.success_rate",
                "CITY", "hin", from, to, resolution);
    }

    @Test
    void alignedRangesHaveExpectedPointCounts() {
        assertEquals(288, query(END.minusSeconds(86400), END, "5m").pointCount());
        assertEquals(2016, query(END.minusSeconds(7 * 86400), END, "5m").pointCount());
        assertEquals(8640, query(END.minusSeconds(30 * 86400), END, "5m").pointCount());
        assertEquals(96, query(END.minusSeconds(86400), END, "15m").pointCount());
        assertEquals(24, query(END.minusSeconds(86400), END, "1h").pointCount());
        assertEquals(5, query(END.minusSeconds(86400), END, "6h").pointCount());
    }

    @Test
    void partialEdgesUseUtcEpochBoundaries() {
        var query = query(END.minusSeconds(300).plusNanos(1), END.plusNanos(1), "5m");
        assertEquals(END.minusSeconds(300), query.effectiveFrom());
        assertEquals(END.plusSeconds(300), query.effectiveTo());
        assertEquals(2, query.pointCount());
    }

    @Test
    void invalidRangeResolutionAndScopeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> query(END, END, "5m"));
        assertThrows(IllegalArgumentException.class, () -> query(END, END.minusSeconds(300), "5m"));
        assertThrows(IllegalArgumentException.class,
                () -> query(END.minusSeconds(30 * 86400 + 1), END, "5m"));
        assertThrows(IllegalArgumentException.class,
                () -> query(END.minusSeconds(300), END, "1m"));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceQuery("dataset", "volte", "voice.success_rate", "CITY",
                        "bad scope", END.minusSeconds(300), END, "5m"));
    }
}
