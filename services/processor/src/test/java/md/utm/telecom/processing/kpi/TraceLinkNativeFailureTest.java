package md.utm.telecom.processing.kpi;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.history.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.DockerClientFactory;
import static org.junit.jupiter.api.Assertions.*;

@Isolated("Pauses only the disposable PostgresFixture server")
@Timeout(90)
class TraceLinkNativeFailureTest extends TraceLinkPrimitiveTestSupport {
    @TempDir Path output;

    @Test void realDatabaseOutageRejectsImportAndReadOnlyOutputThenRecoversExactly() throws Exception {
        String name = "trace_outage_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"), "test_admin", "test-admin"));
        admin.execute("CREATE DATABASE " + name + " OWNER processing_migrator");
        admin.execute("REVOKE CONNECT ON DATABASE " + name + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + name + " TO processing_app");
        String url = PostgresFixture.url(name) + "&connectTimeout=2&socketTimeout=2";
        var owner = new JdbcTemplate(new DriverManagerDataSource(url, "processing_migrator", "test-migrator"));
        owner.execute("CREATE SCHEMA app"); owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(url, "processing_migrator", "test-migrator").schemas("app").defaultSchema("app").createSchemas(false).load().migrate();
        var runtime = new DriverManagerDataSource(url, "processing_app", "test-runtime");
        var importing = new JdbcTemplate(runtime); importing.setQueryTimeout(3);
        var bootstrap = new TraceLinkDatasetBootstrap(importing, new DataSourceTransactionManager(runtime), 3);
        Path source = Path.of("../../contracts/fixtures/trace-link/native-slice");
        String completedUrl;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) { completedUrl = connection.getMetaData().getURL(); }
        var boundedRead = new JdbcTemplate(new DriverManagerDataSource(completedUrl + "&connectTimeout=2&socketTimeout=2", "processing_app", "test-runtime"));
        var registry = new SimpleMeterRegistry();
        try {
            var producer = new TraceLinkDomainProducer(new TraceLinkNativeReader(boundedRead), registry);
            var before = jdbc.queryForList("SELECT natural_id,source_row_sha256,source_row,payload::text FROM app.trace_source_native_window ORDER BY natural_id");
            Path existing = output.resolve("existing.jsonl"), absent = output.resolve("absent.jsonl");
            producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), existing);
            byte[] bytes = Files.readAllBytes(existing);
            var docker = DockerClientFactory.instance().client(); String id = PostgresFixture.DB.getContainerId();
            long began = System.nanoTime();
            docker.pauseContainerCmd(id).exec();
            try {
                assertTrue(docker.inspectContainerCmd(id).exec().getState().getPaused());
                assertThrows(org.springframework.transaction.CannotCreateTransactionException.class, () -> bootstrap.execute(source));
                assertThrows(DataAccessException.class, () -> producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), absent));
                assertThrows(DataAccessException.class, () -> producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), existing));
                assertFalse(Files.exists(absent)); assertArrayEquals(bytes, Files.readAllBytes(existing));
            } finally { docker.unpauseContainerCmd(id).exec(); }
            long outageMillis = (System.nanoTime() - began) / 1_000_000;
            long recoveryStart = System.nanoTime();
            assertEquals(0, importing.queryForObject("SELECT count(*) FROM app.trace_source_dataset", Integer.class));
            var imported = bootstrap.execute(source); assertEquals(42, imported.nativeRows()); assertEquals(imported, bootstrap.execute(source));
            producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), existing);
            producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), absent);
            assertArrayEquals(bytes, Files.readAllBytes(existing)); assertArrayEquals(bytes, Files.readAllBytes(absent));
            assertEquals(before, jdbc.queryForList("SELECT natural_id,source_row_sha256,source_row,payload::text FROM app.trace_source_native_window ORDER BY natural_id"));
            var evidence = JSON.createObjectNode().put("dependency", "real disposable PostgreSQL paused/unpaused")
                    .put("outageDrillMillis", outageMillis).put("recoveryAndImportMillis", (System.nanoTime() - recoveryStart) / 1_000_000)
                    .put("nativeRows", 42).put("sourceUnchanged", true).put("existingArtifactUnchanged", true)
                    .put("publicationStatus", "LOCAL_ONLY; Kafka DISABLED");
            Files.writeString(Path.of("target/trace-link-native-failure.json"), evidence.toPrettyString() + "\n");
        } finally { registry.close(); }
    }

    @Test void corruptPersistedSourceFailsBeforeCreatingOrReplacingArtifact() throws Exception {
        String url;
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) { url = connection.getMetaData().getURL(); }
        var owner = new JdbcTemplate(new DriverManagerDataSource(url, "processing_migrator", "test-migrator"));
        var registry = new SimpleMeterRegistry();
        try {
            var producer = new TraceLinkDomainProducer(reader, registry); var path = output.resolve("valid.jsonl");
            producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), path); var bytes = Files.readAllBytes(path);
            owner.update("UPDATE app.trace_source_native_window SET source_row=source_row||' ' WHERE natural_id=?", normal.naturalId());
            try {
                assertThrows(IllegalArgumentException.class, () -> producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), path));
                assertThrows(IllegalArgumentException.class, () -> producer.write(dataset.id(), normal.scope(), START, START.plusSeconds(300), output.resolve("corrupt.jsonl")));
                assertArrayEquals(bytes, Files.readAllBytes(path)); assertFalse(Files.exists(output.resolve("corrupt.jsonl")));
            } finally { owner.update("UPDATE app.trace_source_native_window SET source_row=? WHERE natural_id=?", normal.raw(), normal.naturalId()); }
        } finally { registry.close(); }
    }

    @Test void eligibleWindowAndMeasuredZeroControlsCannotPromoteMissingPartnerEvidence() throws Exception {
        var scope = new TraceLinkNativeReader.Scope("ROAMING", "RO-DEMO-01", "out", "RO");
        var row = reader.read(dataset, scope, START, START.plusSeconds(300)).getFirst();
        assertThrows(IllegalArgumentException.class, () -> produce(control(row, START, Map.of("reg_attempts", 0, "reg_success", 1)), "roaming.registration.success"));
        var zero = produce(control(row, START, Map.of("reg_attempts", 10, "reg_success", 0)), "roaming.registration.success");
        assertEquals(0, zero.path("value").asInt()); assertFalse(zero.path("value").isNull());
        assertThrows(IllegalArgumentException.class, () -> builder.build(dataset, scope, START.plusSeconds(300), "roaming.registration.success", row));
        var missing = builder.build(dataset, scope, START, "roaming.registration.success", null);
        var healthyOther = produce(reader.read(dataset, new TraceLinkNativeReader.Scope("ROAMING", "RO-DEMO-01", "in", "RO"), START, START.plusSeconds(300)).getFirst(), "roaming.registration.success");
        assertEquals("MISSING", missing.path("observation").path("quality").asText()); assertTrue(missing.path("value").isNull());
        assertThrows(IllegalArgumentException.class, () -> builder.rollup(List.of(missing, healthyOther), START, START.plusSeconds(300)));
    }
}
