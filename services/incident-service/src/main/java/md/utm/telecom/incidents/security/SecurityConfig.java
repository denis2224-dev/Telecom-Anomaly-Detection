package md.utm.telecom.incidents.security;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    Clock sessionClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ClientRegistrationRepository clients, ApiSecurityErrors errors,
                                            Clock sessionClock, @Value("${app.public-origin}") String publicOrigin)
            throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clients, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        var logout = new OidcClientInitiatedLogoutSuccessHandler(clients);
        logout.setPostLogoutRedirectUri(publicOrigin + "/signed-out");
        logout.setDefaultTargetUrl(publicOrigin + "/signed-out");

        var trust = new AuthenticationTrustResolverImpl();
        AccessDeniedHandler denied = (request, response, exception) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!trust.isAuthenticated(authentication)) {
                errors.write(response, 401, "UNAUTHENTICATED", "Sign in to continue.");
            } else if (exception instanceof CsrfException) {
                errors.write(response, 403, "CSRF_INVALID", "A valid CSRF token is required.");
            } else {
                errors.write(response, 403, "FORBIDDEN", "You do not have permission.");
            }
        };

        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf",
                                "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/oauth2/authorization/keycloak",
                                "/login/oauth2/code/keycloak", "/error").permitAll()
                        .requestMatchers("/api/**").hasAnyRole("ANALYST", "SUPERVISOR", "ADMIN")
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errors.write(response, 401, "UNAUTHENTICATED", "Sign in to continue."))
                        .accessDeniedHandler(denied))
                .oauth2Login(oauth -> oauth
                        .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(new AppRoleMapper()))
                        .successHandler((request, response, authentication) -> {
                            request.getSession().setAttribute(SessionDeadlineFilter.EXPIRES_AT,
                                    sessionClock.instant().plus(Duration.ofMinutes(30)));
                            response.sendRedirect(publicOrigin + "/dashboard");
                        })
                        .failureHandler((request, response, exception) ->
                                errors.write(response, 400, "LOGIN_FAILED", "Login could not be completed.")))
                .logout(config -> config
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logout))
                .addFilterAfter(new SessionDeadlineFilter(sessionClock), SecurityContextHolderFilter.class);

        return http.build();
    }
}