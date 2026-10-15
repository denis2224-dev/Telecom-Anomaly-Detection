package md.utm.telecom.migrations;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TraceLinkMigrationConfiguration {
    @Bean
    FlywayMigrationStrategy traceLinkMigrationStrategy() {
        return flyway -> TraceLinkMigrations.forDatabase(flyway).migrate();
    }
}
