package md.utm.telecom.services.controller;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record ServiceKpiPage(
        List<JsonNode> items,
        long total,
        int page,
        int size,
        Instant observedAt
) {
}