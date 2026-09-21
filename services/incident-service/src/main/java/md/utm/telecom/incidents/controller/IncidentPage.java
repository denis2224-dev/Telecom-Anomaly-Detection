package md.utm.telecom.incidents.controller;

import java.util.List;

public record IncidentPage(
        List<IncidentResponse> items,
        long total,
        int page,
        int size
) {
}