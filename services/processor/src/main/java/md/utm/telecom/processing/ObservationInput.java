package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import md.utm.telecom.observation.ObservationValidator;
import org.springframework.stereotype.Component;

/** Pure validation boundary for a future listener; no receipts, state updates or acknowledgment. */
@Component
public class ObservationInput {
    private final ObservationValidator validator;

    public ObservationInput(ObservationValidator validator) { this.validator = validator; }

    public void validate(JsonNode observation) { validator.validate(observation); }
}
