package md.utm.telecom.services.controller;

import java.time.Clock;
import java.util.Optional;
import md.utm.telecom.geography.GeographyCatalogue;
import md.utm.telecom.services.repository.ServiceKpiWindowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ServiceOverviewControllerTest {
    @Test
    void activeCatalogueExposesTwentyCityScopesAndKeepsLegacySeparateWithoutInventingMeasurements() throws Exception {
        var windows = mock(ServiceKpiWindowRepository.class);
        when(windows.findFirstByScopeIdOrderByWindowStartDescReceivedAtDescWindowIdDesc(anyString())).thenReturn(Optional.empty());
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(0L);
        var active = new ServiceOverviewController(windows, jdbc, new ObjectMapper(), Clock.systemUTC(),
                new GeographyCatalogue("2026-10-06T12:00:00Z")).overview();
        assertEquals(22, active.size());
        assertEquals(22, active.stream().map(item -> item.scope().scopeId()).distinct().count());
        assertTrue(active.stream().allMatch(item -> item.latestWindow() == null && item.freshness().equals("MISSING")));
        var chi = active.stream().filter(item -> item.scope().scopeId().equals("VOLTE-MD-CHI")).findFirst().orElseThrow();
        assertEquals("Chișinău", chi.scope().region());
        assertEquals(java.util.List.of("IMS-MD-CHI-01", "TRANSPORT-MD-CHI-01"), chi.scope().dependencyIds());
        assertEquals(2, new ServiceOverviewController(windows, jdbc, new ObjectMapper(), Clock.systemUTC(),
                new GeographyCatalogue("")).overview().size());
    }
}
