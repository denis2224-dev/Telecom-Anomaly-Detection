package md.utm.telecom.incidents.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CurrentSession(UUID analystId, String displayName,
                            List<String> roles, Instant expiresAt) {}
