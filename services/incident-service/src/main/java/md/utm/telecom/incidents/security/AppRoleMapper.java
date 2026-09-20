package md.utm.telecom.incidents.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

public final class AppRoleMapper implements GrantedAuthoritiesMapper {
    private static final Set<String> ALLOWED = Set.of("ANALYST", "SUPERVISOR", "ADMIN");

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
            Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> result = new HashSet<>();
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                Object claim = oidc.getIdToken().getClaims().get("app_roles");
                if (claim instanceof Collection<?> roles) {
                    for (Object role : roles) {
                        if (role instanceof String name && ALLOWED.contains(name)) {
                            result.add(new SimpleGrantedAuthority("ROLE_" + name));
                        }
                    }
                }
            }
        }
        return result;
    }
}