package md.utm.telecom.incidents.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import md.utm.telecom.incidents.model.IncidentStatus;

public record ChangeStatusRequest(
        @NotNull IncidentStatus status,
        @NotNull @PositiveOrZero Long version,
        @Size(max = 2000) String resolutionNote
) {}