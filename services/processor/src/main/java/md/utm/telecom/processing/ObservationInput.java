package md.utm.telecom.processing;

import com.fasterxml.jackson.databind.JsonNode;
import md.utm.telecom.observation.ObservationValidator;
import md.utm.telecom.observation.TopologyCatalog.Scope;
import md.utm.telecom.processing.topology.ScopeRegistry;
import org.springframework.stereotype.Component;

/** Shared pure validation boundary; persistence and acknowledgment belong to ingestion. */
@Component
public class ObservationInput {
    private final ObservationValidator validator;
    private final ScopeRegistry scopes;

    public ObservationInput(ObservationValidator validator, ScopeRegistry scopes) {
        this.validator = validator;
        this.scopes = scopes;
    }

    /** Returns the canonical immutable scope only after all observation checks pass. */
    public Scope validate(JsonNode observation) {
        validator.validate(observation);
        return scopes.requireScope(observation.get("scopeId").asText());
    }
}
