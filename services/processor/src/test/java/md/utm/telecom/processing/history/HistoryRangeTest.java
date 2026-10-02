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
        assertEquals(range.end(),range.start().plusSeconds(range.minutes()*60));
    }
    @Test void historyConfigurationIsBoundedAndDisabledByDefault() {
        var props=new HistoryProperties(false,null,null);
        assertFalse(props.enabled()); assertEquals(30,props.days()); assertEquals(42L,props.seed());
        assertThrows(IllegalArgumentException.class,()->new HistoryProperties(true,31,42L));
        assertThrows(IllegalArgumentException.class,()->new HistoryProperties(true,30,-1L));
    }
}
