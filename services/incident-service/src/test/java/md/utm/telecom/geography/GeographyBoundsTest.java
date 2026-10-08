package md.utm.telecom.geography;

import java.time.Clock;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeographyBoundsTest {
    private final GeographyReadRepository reads = mock(GeographyReadRepository.class);
    private final Authentication authentication = mock(Authentication.class);
    private final GeographyController controller = new GeographyController(reads, Clock.systemUTC(), mock(AnalystAccess.class));

    @Test
    void invalidRangesUnitsAndUtcFailBeforeAnyQuery() {
        String[][] cases = {
                {"CHI", "SMS", "2026-10-05T00:00:00Z", "2026-10-07T00:00:00Z"},
                {"CHI", "SMS", "2026-10-05T00:00:00Z", "2026-10-06T00:00:00.001Z"},
                {"CHI", "SMS", "2026-10-06T00:00:00Z", "2026-10-06T00:00:00Z"},
                {"CHI", "SMS", "2026-10-06T00:00:00Z", "2026-10-05T00:00:00Z"},
                {"CHI", "SMS", "2026-10-05T00:00:00+00:00", "2026-10-06T00:00:00Z"},
                {"CHI", "SMS", "invalidZ", "2026-10-06T00:00:00Z"},
                {"CHI", "DATA", "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z"},
                {"bad", "SMS", "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z"}
        };
        for (var c : cases) assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.history(c[0], c[1], c[2], c[3], 0, 100, authentication)).getStatusCode());
        verifyNoInteractions(reads);
    }

    @Test
    void invalidPageSizesAndOffsetOverflowFailBeforeAnyQuery() {
        for (var p : new int[][]{{-1, 20}, {0, 0}, {0, 101}, {Integer.MAX_VALUE, 100}})
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> controller.history("CHI", "VOLTE", "2026-10-05T00:00:00Z",
                            "2026-10-06T00:00:00Z", p[0], p[1], authentication)).getStatusCode());
        verifyNoInteractions(reads);
    }
}
