package md.utm.telecom.processing.outbox;

import java.time.Clock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RejectionPublisherTest {
    @ParameterizedTest @ValueSource(ints = {-1, 0, 1001})
    void rejectsUnboundedBatchConfiguration(int batch) {
        assertThrows(IllegalArgumentException.class, () ->
                new RejectionPublisher(null, null, Clock.systemUTC(), "invalid", "late", batch));
    }
    @ParameterizedTest @ValueSource(strings = {"", "late"})
    void rejectsEmptyOrSharedRoutingTopics(String invalid) {
        assertThrows(IllegalArgumentException.class, () ->
                new RejectionPublisher(null, null, Clock.systemUTC(), invalid, "late", 100));
    }
}
