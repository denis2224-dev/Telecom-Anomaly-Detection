package md.utm.telecom.generator.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScenarioControllerTest {
    @Test void privateRoutesBindExecutionOnlyBodyAndReturnSchedule() throws Exception {
        var service = mock(ScenarioExecutionService.class);
        var json = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc = MockMvcBuilders.standaloneSetup(new ScenarioController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        UUID id = UUID.randomUUID();
        Instant start = Instant.parse("2026-09-28T10:00:00Z");
        var status = new ScenarioExecutionService.Snapshot(id, "VOLTE_IMS_OVERLOAD", "VOLTE-MD-CENTRAL",
                42, start, start.plusSeconds(480), "SCHEDULED", 0, null);
        when(service.start(eq(id), any())).thenReturn(status);
        when(service.status(id)).thenReturn(status);
        when(service.stop(id)).thenReturn(status);
        String body = """
                {"scenarioType":"VOLTE_IMS_OVERLOAD","scopeId":"VOLTE-MD-CENTRAL","seed":42,
                 "scheduledStartAt":"2026-09-28T10:00:00Z","scheduledEndAt":"2026-09-28T10:08:00Z"}
                """;
        mvc.perform(put("/internal/scenario-runs/{runId}", id).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.scheduledEndAt").value("2026-09-28T10:08:00Z"));
        mvc.perform(get("/internal/scenario-runs/{runId}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
        mvc.perform(post("/internal/scenario-runs/{runId}/stop", id))
                .andExpect(status().isOk());
        verify(service).start(eq(id), argThat(command -> command.seed() == 42L
                && command.scheduledStartAt().equals(start)));
        mvc.perform(put("/internal/scenario-runs/{runId}", id).contentType("application/json")
                .content(body.replace("\"seed\":42", "\"seed\":42,\"requestId\":\"unexpected\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_COMMAND"));
    }

    @Test void unknownAndConflictHaveStableErrorCodes() throws Exception {
        var service = mock(ScenarioExecutionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ScenarioController(service)).build();
        UUID id = UUID.randomUUID();
        when(service.status(id)).thenThrow(new ScenarioExecutionService.ApiFailure(
                HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run is unknown in this generator process"));
        when(service.stop(id)).thenThrow(new ScenarioExecutionService.ApiFailure(
                HttpStatus.CONFLICT, "RUN_TERMINAL", "Completed or failed run cannot be stopped"));
        mvc.perform(get("/internal/scenario-runs/{runId}", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(post("/internal/scenario-runs/{runId}/stop", id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_TERMINAL"));
    }
}
