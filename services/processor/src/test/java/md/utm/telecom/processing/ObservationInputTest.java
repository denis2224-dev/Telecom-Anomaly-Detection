package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import md.utm.telecom.observation.ObservationValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationInputTest {
    @Test
    void validatesAtTheProcessingBoundaryWithoutPretendingToPersist() throws Exception {
        var input = new ObservationInput(new ObservationValidator());
        var normal = (ObjectNode) ObservationValidator.resource("fixtures/observations/normal-volte.json", new ObjectMapper());
        assertDoesNotThrow(() -> input.validate(normal));
        ((ObjectNode) normal.get("metrics")).put("technicalSuccesses", 99999);
        assertThrows(IllegalArgumentException.class, () -> input.validate(normal));
    }
}
