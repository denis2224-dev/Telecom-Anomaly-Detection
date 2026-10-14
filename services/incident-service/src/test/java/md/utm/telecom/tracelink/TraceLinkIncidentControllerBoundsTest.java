package md.utm.telecom.tracelink;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkIncidentControllerBoundsTest {
    @Test void rejectsUnboundedReads() {
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateBounds(101, 0));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateBounds(0, 0));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateBounds(10, -1));
        assertThrows(IllegalArgumentException.class, () -> TraceLinkIncidentService.validateBounds(10, 100001));
        assertDoesNotThrow(() -> TraceLinkIncidentService.validateBounds(100, 100000));
    }
}
