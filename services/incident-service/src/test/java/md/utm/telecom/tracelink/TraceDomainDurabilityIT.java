package md.utm.telecom.tracelink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import md.utm.telecom.observation.TraceLinkMeasurement;
import md.utm.telecom.tracelink.metrics.TraceDomainIngestionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static md.utm.telecom.observation.TraceLinkMeasurement.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real commits, concurrent retries, rollback and PostgreSQL process restart. No Kafka claim. */
@Testcontainers
class TraceDomainDurabilityIT {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer("postgres:16.4-alpine")
            .withDatabaseName("incidents_db").withInitScript("db/context-test-init.sql");
    static JdbcTemplate admin,jdbc;
    static DriverManagerDataSource adminSource,runtimeSource;
    static TransactionTemplate transactions;
    static TraceDomainIngestionService service;
    @BeforeAll static void setup() {
        Flyway.configure().dataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword())
                .defaultSchema("app").schemas("app").cleanDisabled(true).load().migrate();
        adminSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        admin=new JdbcTemplate(adminSource);
        runtimeSource=new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()) {
            @Override public Connection getConnection() throws SQLException {
                var connection=super.getConnection();try(var statement=connection.createStatement()){statement.execute("SET ROLE incidents_app");}return connection;
            }
        };
        jdbc=new JdbcTemplate(runtimeSource);transactions=new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));service=new TraceDomainIngestionService(jdbc);
    }
    static void register(TraceLinkMeasurement m) {
        var o=m.observation();var e=m.envelope();var p=e.path("provenance");
        admin.update("""
                INSERT INTO app.trace_domain_source(dataset_id,source_id,source_kind,manifest_sha256,generator_version,seed,synthetic,
                    catalogue_version,topology_version,available_from,available_to,source_completed,registration_status)
                VALUES (?,?,?,?,?,?,TRUE,?,?,?,?,TRUE,'ISOLATED_TEST_ONLY') ON CONFLICT DO NOTHING
                """,o.path("datasetId").asText(),o.path("sourceId").asText(),e.path("sourceKind").asText(),p.path("manifestSha256").asText(),
                p.path("generatorVersion").asText(),p.path("seed").longValue(),o.path("catalogueVersion").asText(),o.path("topologyVersion").asText(),
                Timestamp.from(Instant.parse(o.path("windowStart").asText()).minusSeconds(3600)),Timestamp.from(Instant.parse(o.path("windowEnd").asText()).plusSeconds(86400)));
        admin.update("INSERT INTO app.trace_domain_scope VALUES (?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                o.path("datasetId").asText(),o.path("sourceId").asText(),m.scopeKey(),scope(o).toString());
    }
    static TraceLinkMeasurement fixture(int index) throws Exception {
        var raw=JSON.readTree(Files.readAllBytes(Path.of("../../contracts/fixtures/trace-link/domain-primitive-results-v1.json"))).get(index);
        return wrap(write(raw),Clock.systemUTC());
    }
    @Test void committedMeasurementSurvivesActualPostgresRestartAndNewReceivingInstance() throws Exception {
        var m=fixture(0);register(m);
        assertEquals(TraceDomainIngestionService.Result.INSERTED,transactions.execute(status->service.ingest(m.bytes())));
        int originalPort=DATABASE.getMappedPort(5432);
        DATABASE.getDockerClient().restartContainerCmd(DATABASE.getContainerId()).exec();
        // Docker Desktop can assign a new ephemeral host port after a direct Docker restart.
        // Testcontainers' original inspection is cached: read the actual binding before reconnecting.
        var inspection=DATABASE.getDockerClient().inspectContainerCmd(DATABASE.getContainerId()).exec();
        assertTrue(Boolean.TRUE.equals(inspection.getState().getRunning()));
        var bindings=inspection.getNetworkSettings().getPorts().getBindings()
                .get(com.github.dockerjava.api.model.ExposedPort.tcp(5432));
        assertNotNull(bindings);assertTrue(bindings.length>0);
        int restartedPort=Integer.parseInt(bindings[0].getHostPortSpec());
        String restartedUrl="jdbc:postgresql://"+DATABASE.getHost()+":"+restartedPort+"/"+DATABASE.getDatabaseName();
        adminSource.setUrl(restartedUrl);runtimeSource.setUrl(restartedUrl);
        var evidence=JSON.createObjectNode().put("originalHostPort",originalPort).put("restartedHostPort",restartedPort)
                .put("containerRunning",true).put("eventId",m.id()).put("sourcePayloadSha256",m.envelope().path("sourcePayloadSha256").asText());
        Files.createDirectories(Path.of("target/contract-resolution"));
        Files.write(Path.of("target/contract-resolution/postgres-restart.json"),write(evidence));
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(250))
                .ignoreException(org.springframework.jdbc.CannotGetJdbcConnectionException.class).untilAsserted(()->
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement WHERE event_id=?",Integer.class,m.id())));
        var restarted=new TraceDomainIngestionService(jdbc);
        assertEquals(TraceDomainIngestionService.Result.IDENTICAL_RETRY,transactions.execute(status->restarted.ingest(m.bytes())));
        assertArrayEquals(m.sourceBytes(),jdbc.queryForObject("SELECT source_payload_bytes FROM app.trace_domain_measurement WHERE event_id=?",byte[].class,m.id()));
    }
    @Test void concurrentIdenticalReceiptsCommitOneLogicalMeasurement() throws Exception {
        var m=fixture(5);register(m);var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<TraceDomainIngestionService.Result> call=()->{gate.await();return transactions.execute(status->service.ingest(m.bytes()));};
            var first=executor.submit(call);var second=executor.submit(call);gate.countDown();
            assertEquals(Set.of(TraceDomainIngestionService.Result.INSERTED,TraceDomainIngestionService.Result.IDENTICAL_RETRY),
                    Set.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement WHERE event_id=?",Integer.class,m.id()));
    }
    @Test void detectionConflictRollsBackPreviouslyInsertedMeasurements() throws Exception {
        var results=JSON.readTree(Files.readAllBytes(Path.of("../../contracts/fixtures/trace-link/live-consumer-results-v1.json")));
        var result=results.get(2);var inputs=md.utm.telecom.observation.TraceLinkLiveContract.result(result,Clock.systemUTC());
        // All transport metrics share a scope: register it, then pre-store a conflicting final capacity.
        inputs.forEach(TraceDomainDurabilityIT::register);
        var capacity=inputs.stream().filter(m->m.observation().path("metricId").asText().equals("transport.effectiveCapacity")).findFirst().orElseThrow();
        var changed=(com.fasterxml.jackson.databind.node.ObjectNode)capacity.observation();
        changed.withObject("primitives").put("snapshotAt",Instant.parse(changed.path("windowStart").asText()).plusSeconds(1).toString());
        var conflict=wrap(write(changed),Clock.systemUTC());
        transactions.execute(status->service.ingest(conflict.bytes()));
        assertThrows(IllegalArgumentException.class,()->transactions.execute(status->service.ingestDetection(write(result))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_detection WHERE detection_id=?",Integer.class,result.path("detectionId").asText()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement WHERE event_id=?",Integer.class,inputs.getFirst().id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_domain_measurement WHERE event_id=?",Integer.class,capacity.id()));
    }
}
