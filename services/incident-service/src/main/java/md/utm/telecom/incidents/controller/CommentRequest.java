package md.utm.telecom.incidents.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CommentRequest(
        @NotBlank @Size(max = 2000) String text,
        @NotNull @PositiveOrZero Long version,
        @NotNull UUID requestId
) {}
