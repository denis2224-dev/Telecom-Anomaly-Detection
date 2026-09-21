package md.utm.telecom.incidents.controller;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record DetectionPage(
        List<JsonNode> items,
        long total,
        int page,
        int size
) {
}