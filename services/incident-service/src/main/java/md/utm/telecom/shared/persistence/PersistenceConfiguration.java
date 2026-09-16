package md.utm.telecom.shared.persistence;

import md.utm.telecom.IncidentServiceApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
// Scan every feature and the shared repository fragments from the application root.
@EnableJpaRepositories(basePackageClasses = IncidentServiceApplication.class)
public class PersistenceConfiguration {
}
