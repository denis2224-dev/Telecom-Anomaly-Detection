package md.utm.telecom.migrations;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public final class TraceLinkMigrations {
    private static final String FOUNDATION_SCRIPT = "V009__trace_link_domain_incidents.sql";

    private TraceLinkMigrations() {}

    public static Flyway forDatabase(Flyway flyway) {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement("SELECT to_regclass('app.flyway_schema_history')" );
                 var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getString(1) == null) return flyway;
            }
            try (var statement = connection.prepareStatement(
                    "SELECT script FROM app.flyway_schema_history WHERE version IN ('009','9') AND success");
                 var rows = statement.executeQuery()) {
                if (rows.next() && FOUNDATION_SCRIPT.equals(rows.getString(1))) {
                    return foundationHistory(flyway);
                }
            }
            return flyway;
        } catch (SQLException unavailable) {
            throw new IllegalStateException("Cannot identify the existing incident migration history", unavailable);
        }
    }

    public static Flyway foundationHistory(Flyway flyway) {
        return Flyway.configure().configuration(flyway.getConfiguration())
                .resourceProvider(new FoundationResources()).load();
    }

    private static final class FoundationResources implements ResourceProvider {
        private final List<LoadableResource> resources = new ArrayList<>();

        private FoundationResources() {
            var resolver = new PathMatchingResourcePatternResolver();
            try {
                for (var resource : resolver.getResources("classpath*:db/migration/*.sql")) {
                    String name = resource.getFilename();
                    if (name != null && !name.startsWith("V009__") && !name.startsWith("V010__")
                            && !name.startsWith("V011__") && !name.startsWith("V012__")) {
                        resources.add(new SqlResource(resource));
                    }
                }
                for (var resource : resolver.getResources("classpath*:db/compatibility/foundation/*.sql")) {
                    resources.add(new SqlResource(resource));
                }
            } catch (IOException unavailable) {
                throw new UncheckedIOException(unavailable);
            }
        }

        @Override
        public LoadableResource getResource(String name) {
            return resources.stream().filter(resource -> resource.getFilename().equals(name)
                    || resource.getRelativePath().equals(name)).findFirst().orElse(null);
        }

        @Override
        public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
            return resources.stream().filter(resource -> resource.getFilename().startsWith(prefix)
                    && Arrays.stream(suffixes).anyMatch(resource.getFilename()::endsWith)).toList();
        }
    }

    private static final class SqlResource extends LoadableResource {
        private final Resource resource;

        private SqlResource(Resource resource) {
            this.resource = resource;
        }

        @Override
        public Reader read() {
            try {
                return new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (IOException unavailable) {
                throw new UncheckedIOException(unavailable);
            }
        }

        @Override
        public String getAbsolutePath() {
            try {
                return resource.getURL().toExternalForm();
            } catch (IOException unavailable) {
                throw new UncheckedIOException(unavailable);
            }
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return getAbsolutePath();
        }

        @Override
        public String getFilename() {
            return resource.getFilename();
        }

        @Override
        public String getRelativePath() {
            return resource.getFilename();
        }
    }
}
