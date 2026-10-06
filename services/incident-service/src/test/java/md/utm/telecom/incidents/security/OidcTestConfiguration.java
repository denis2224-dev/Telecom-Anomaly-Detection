package md.utm.telecom.incidents.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@TestConfiguration(proxyBeanMethods = false)
public class OidcTestConfiguration {
    /** Cached Spring 7 test contexts may restart; disabled Kafka listeners must stay disabled. */
    @Bean
    static org.springframework.beans.factory.config.BeanPostProcessor kafkaTestLifecycle() {
        return new org.springframework.beans.factory.config.BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof org.springframework.kafka.config.KafkaListenerEndpointRegistry registry)
                    registry.setAlwaysStartAfterRefresh(false);
                return bean;
            }
        };
    }
    private static final String ISSUER = "http://telecom.test:8080/auth/realms/telecom";
    @Bean
    ClientRegistrationRepository clients() {
        return new InMemoryClientRegistrationRepository(
                ClientRegistration.withRegistrationId("keycloak")
                        .clientId("telecom-web").clientSecret("test-only")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("http://telecom.test:8080/login/oauth2/code/keycloak")
                        .scope("openid", "profile")
                        .authorizationUri(ISSUER + "/protocol/openid-connect/auth")
                        .tokenUri(ISSUER + "/protocol/openid-connect/token")
                        .jwkSetUri(ISSUER + "/protocol/openid-connect/certs")
                        .issuerUri(ISSUER).userNameAttributeName("sub").build());
    }
}
