package md.utm.telecom.incidents.security;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class AppRoleMapperTest {
    @Test
    void mapsOnlyAllowedRolesFromTheIdToken() {
        var token = new OidcIdToken("test-token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "test-subject", "app_roles", List.of("ANALYST", "realm-admin", "ANALYST")));
        var result = new AppRoleMapper().mapAuthorities(List.of(new OidcUserAuthority(token),
                new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(result).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ANALYST");
    }

    @Test
    void missingOrMalformedClaimGrantsNoRoles() {
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of("sub", "test-subject"), Map.of("sub", "test-subject", "app_roles", "ADMIN"))) {
            var token = new OidcIdToken("test-token", Instant.now(), Instant.now().plusSeconds(300), claims);
            assertThat(new AppRoleMapper().mapAuthorities(List.of(new OidcUserAuthority(token)))).isEmpty();
        }
    }
}