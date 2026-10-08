package md.utm.telecom.processing.history;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryRangeTest {
    @Test void exactThirtyDaysAndIncompleteMinuteExcluded() {
        var range=HistoricalTelemetryBootstrap.Range.endingAt(Instant.parse("2026-10-01T09:34:27Z"),30);
        assertEquals(Instant.parse("2026-09-01T09:34:00Z"),range.start());
        assertEquals(Instant.parse("2026-10-01T09:34:00Z"),range.end());
        assertEquals(43200,range.minutes());
        assertEquals(0,Math.floorMod(range.start().getEpochSecond(),60));
    }

    @Test void geographicHistoryRangeIsBoundedToAtMostTwoDays() {
        var range = GeographicHistoricalBootstrap.Range.endingAt(Instant.parse("2026-10-01T09:34:27Z"), 2);
        assertEquals(Instant.parse("2026-09-29T09:34:00Z"), range.start());
        assertEquals(Instant.parse("2026-10-01T09:34:00Z"), range.end());
        assertEquals(2880, range.minutes());
        assertEquals(0, Math.floorMod(range.start().getEpochSecond(), 60));
        assertEquals(range.end(), range.start().plusSeconds(range.minutes() * 60));

        assertThrows(IllegalArgumentException.class,
                () -> GeographicHistoricalBootstrap.Range.endingAt(Instant.parse("2026-10-01T09:34:27Z"), 3));
        assertThrows(IllegalArgumentException.class,
                () -> GeographicHistoricalBootstrap.Range.endingAt(Instant.parse("2026-10-01T09:34:27Z"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new GeographicHistoricalBootstrap.Range(range.start(), range.start().plus(3, java.time.temporal.ChronoUnit.DAYS)));
    }

    @Test void geographicHistoryPropertiesBoundedAndDisabledByDefault() {
        var props = new GeographicHistoryProperties(false, null, null, null);
        assertFalse(props.enabled());
        assertEquals(2, props.days());
        assertEquals(42L, props.seed());
        assertEquals("geographic-demo-v1", props.jobId());

        assertThrows(IllegalArgumentException.class, () -> new GeographicHistoryProperties(true, 3, 42L, "job"));
        assertThrows(IllegalArgumentException.class, () -> new GeographicHistoryProperties(true, 2, -1L, "job"));
    }

    @Test void emptyAndFractionalMinuteRangesRejectedBeforeAnyWrite() {
        var start = Instant.parse("2026-10-01T09:34:00Z");
        assertThrows(IllegalArgumentException.class, () -> new GeographicHistoricalBootstrap.Range(start, start));
        assertThrows(IllegalArgumentException.class, () -> new GeographicHistoricalBootstrap.Range(start.plusSeconds(1), start.plusSeconds(61)));
    }
}
