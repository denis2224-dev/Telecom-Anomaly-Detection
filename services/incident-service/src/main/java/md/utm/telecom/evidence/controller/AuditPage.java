package md.utm.telecom.evidence.controller;

import java.util.List;

public record AuditPage(
        List<AuditEvent> items,
        long total,
        int page,
        int size
) {}