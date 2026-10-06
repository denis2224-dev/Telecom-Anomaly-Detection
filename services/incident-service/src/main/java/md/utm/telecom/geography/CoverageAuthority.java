package md.utm.telecom.geography;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;

/** Captured, immutable incident-db authority for one scope and catalogue version. */
public record CoverageAuthority(String catalogueVersion, String topologyVersion, String service,
                                Set<String> expectedSources, Set<String> authorizedSources) {
    public CoverageAuthority {
        expectedSources = Set.copyOf(expectedSources);
        authorizedSources = Set.copyOf(authorizedSources);
    }

    public static CoverageAuthority load(JdbcTemplate jdbc, String version, String scopeId) {
        var rows = jdbc.query("""
                SELECT v.topology_version, b.service, b.service_source_id
                FROM app.geo_catalogue_versions v
                JOIN app.geo_scope_bindings b ON b.catalogue_version = v.catalogue_version
                WHERE v.catalogue_version = ? AND b.scope_id = ?
                """, (rs, n) -> new Base(rs.getString(1), rs.getString(2), rs.getString(3)),
                version, scopeId);
        GeographyCatalogue.require(rows.size() == 1, "Unknown captured coverage catalogue or scope");
        var base = rows.getFirst();
        var expected = new TreeSet<String>();
        var authorized = new TreeSet<String>();
        expected.add(base.serviceSource());
        authorized.add(base.serviceSource());
        var roles = jdbc.query("""
                SELECT role, source_id FROM app.geo_scope_roles
                WHERE catalogue_version = ? AND scope_id = ?
                """, (rs, n) -> new RoleSource(rs.getString(1), rs.getString(2)), version, scopeId);
        for (var role : roles) {
            GeographyCatalogue.require(authorized.add(role.source()), "Duplicate captured source authority");
            if (!role.role().equals("SMS_TRANSPORT")) expected.add(role.source());
        }
        GeographyCatalogue.require(base.service().equals("VOLTE")
                ? roles.stream().map(RoleSource::role).collect(java.util.stream.Collectors.toSet())
                        .containsAll(Set.of("VOLTE_IMS", "VOLTE_TRANSPORT"))
                : roles.stream().anyMatch(r -> r.role().equals("SMS_SMSC")),
                "Incomplete captured source authority");
        return new CoverageAuthority(version, base.topology(), base.service(), expected, authorized);
    }

    public static CoverageAuthority fromCatalogue(GeographyCatalogue catalogue, String scopeId) {
        var strict = catalogue.strictScope(scopeId);
        GeographyCatalogue.require(strict != null, "Unknown coverage scope");
        return new CoverageAuthority(catalogue.version(), catalogue.topologyVersion(),
                strict.path("service").asText(), catalogue.expected(scopeId), catalogue.authorized(scopeId));
    }

    private record Base(String topology, String service, String serviceSource) {}
    private record RoleSource(String role, String source) {}
}
