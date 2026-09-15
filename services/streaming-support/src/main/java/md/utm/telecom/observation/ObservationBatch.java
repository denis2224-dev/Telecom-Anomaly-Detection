package md.utm.telecom.observation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finite generation/reference batch only; no persistence or Kafka acknowledgment semantics. */
public final class ObservationBatch {
    public enum Result { ACCEPTED, DUPLICATE }
    private final ObservationValidator validator;
    private final Map<String, JsonNode> byId = new HashMap<>();
    private final Map<List<String>, JsonNode> byInterval = new HashMap<>();

    public ObservationBatch(ObservationValidator validator) { this.validator = validator; }

    public Result accept(JsonNode event) {
        validator.validate(event);
        var id = event.get("eventId").asText();
        var key = List.of(event.get("sourceId").asText(), event.get("scopeId").asText(),
                event.get("kind").asText(), event.get("windowStart").asText());
        var idPrior = byId.get(id);
        var intervalPrior = byInterval.get(key);
        rejectConflict(idPrior, event);
        rejectConflict(intervalPrior, event);
        if (idPrior != null || intervalPrior != null) return Result.DUPLICATE;
        var snapshot = event.deepCopy();
        byId.put(id, snapshot);
        byInterval.put(key, snapshot);
        return Result.ACCEPTED;
    }

    private static void rejectConflict(JsonNode prior, JsonNode event) {
        // JSON numeric values compare by magnitude, matching the Python reference.
        if (prior != null && !prior.equals((left, right) -> {
            if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue());
            return left.equals(right) ? 0 : 1;
        }, event)) throw new IllegalArgumentException("CONFLICT: eventId or natural interval content changed");
    }
}
