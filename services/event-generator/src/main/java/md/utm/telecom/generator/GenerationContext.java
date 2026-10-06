package md.utm.telecom.generator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import md.utm.telecom.observation.GeographyCatalog;
import md.utm.telecom.observation.GeographyCatalog.Role;
import md.utm.telecom.observation.TopologyCatalog;

/** Scope and role authority copied only from the validated, pinned catalogue. */
public final class GenerationContext {
    private final GeographyCatalog geography;
    private final TopologyCatalog.Scope scope;

    private GenerationContext(GeographyCatalog geography, String scopeId) {
        this.geography = Objects.requireNonNull(geography, "geography");
        this.scope = geography.authority().requireScope(scopeId);
    }

    public static GenerationContext forScope(GeographyCatalog geography, String scopeId) {
        return new GenerationContext(geography, scopeId);
    }

    public TopologyCatalog.Scope scope() { return scope; }
    public TopologyCatalog.Node role(Role role) { return geography.resolve(scope.scopeId(), role); }
    public void requireService(String service) {
        if (!scope.service().equals(service)) throw new IllegalArgumentException("Wrong generation service");
    }

    /** Legacy healthy series stays byte compatible. The city discriminator affects measurements only. */
    public long measurementSeed(long seed) {
        if (geography.bindings().get(scope.scopeId()).legacy()) return seed;
        var discriminator = UUID.nameUUIDFromBytes(scope.scopeId().getBytes(StandardCharsets.UTF_8));
        return seed ^ discriminator.getMostSignificantBits() ^ discriminator.getLeastSignificantBits();
    }
}
