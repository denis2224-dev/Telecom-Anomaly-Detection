package md.utm.telecom.geography;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import md.utm.telecom.analysts.service.AnalystAccess;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeographyControllerTest {
    private final GeographyReadRepository reads = mock(GeographyReadRepository.class);
    private final AnalystAccess access = mock(AnalystAccess.class);
    private final Authentication authentication = mock(Authentication.class);
    private final GeographyController controller = new GeographyController(reads,
            Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC), access);

    @Test
    void allReadRoutesRequireAnEnabledAnalyst() {
        when(reads.city(eq("CHI"), any())).thenReturn(java.util.Optional.of(
                new GeographyResponses.CityDetail("CHI", "Chișinău", true,
                        "catalogue", "topology", java.util.List.of(),
                        Instant.parse("2026-10-06T12:00:00Z"), java.util.List.of())));
        controller.cities(authentication);
        controller.city("CHI", authentication);
        controller.history("CHI", "VOLTE", "2026-10-06T10:00:00Z",
                "2026-10-06T11:00:00Z", 0, 20, authentication);
        verify(access, times(3)).requireEnabled(authentication);
    }

    @Test
    void invalidBoundsFailBeforeDatabaseRead() {
        var failure = assertThrows(ResponseStatusException.class, () -> controller.history(
                "CHI", "SMS", "2026-10-05T10:00:00Z", "2026-10-06T11:00:00Z",
                0, 20, authentication));
        assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        verifyNoInteractions(reads);
    }
}
