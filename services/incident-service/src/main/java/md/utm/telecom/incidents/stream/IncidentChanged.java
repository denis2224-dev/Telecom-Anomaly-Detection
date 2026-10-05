package md.utm.telecom.incidents.stream;

import java.util.UUID;

public record IncidentChanged(UUID id, long version) {}
