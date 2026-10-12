package md.utm.telecom.processing.history;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import md.utm.telecom.processing.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDatasetBootstrapIT {
    @TempDir Path source;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource runtime;
    private String url;
    private Flyway flyway;
    private static final String DATASET = "trace-link-bounded-fixture-v1";

    @BeforeEach void databaseAndFixture() throws Exception {
        String name = "trace_import_"+UUID.randomUUID().toString().replace("-","");
        var admin = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE "+name+" OWNER processing_migrator");
        admin.execute("REVOKE CONNECT ON DATABASE "+name+" FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE "+name+" TO processing_app");
        url = PostgresFixture.url(name);
        var owner = new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        owner.execute("CREATE SCHEMA app"); owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        flyway = Flyway.configure().dataSource(url,"processing_migrator","test-migrator").schemas("app").defaultSchema("app").createSchemas(false).load();
        flyway.migrate(); flyway.validate();
        runtime = new DriverManagerDataSource(url,"processing_app","test-runtime"); jdbc = new JdbcTemplate(runtime);
        for (String file : List.of("manifest.json","cities.csv","links.csv","countries.csv","city_kpi_5m.csv","link_kpi_5m.csv","roaming_kpi_5m.csv","scenario_events.csv")) {
            try (var input = getClass().getResourceAsStream("/contracts/fixtures/trace-link/native-slice/"+file)) {
                assertNotNull(input); Files.copy(input,source.resolve(file));
            }
        }
    }
    private TraceLinkDatasetBootstrap bootstrap() { return new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),3); }
    private ObjectNode manifest() throws Exception { return (ObjectNode)TraceLinkArchive.JSON.readTree(source.resolve("manifest.json").toFile()); }
    private void writeManifest(ObjectNode manifest) throws Exception { TraceLinkArchive.JSON.writerWithDefaultPrettyPrinter().writeValue(source.resolve("manifest.json").toFile(),manifest); }

    @Test void firstLoadExactRetryAndNativeSliceAreDurable() throws Exception {
        var first = bootstrap().execute(source);
        assertEquals(42,first.nativeRows()); assertEquals(DATASET,first.datasetId());
        var identities = jdbc.queryForList("SELECT natural_id,source_row_sha256 FROM app.trace_source_native_window ORDER BY natural_id");
        assertEquals(first,bootstrap().execute(source));
        assertEquals(identities,jdbc.queryForList("SELECT natural_id,source_row_sha256 FROM app.trace_source_native_window ORDER BY natural_id"));
        assertEquals(42,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE window_end=window_start+interval '5 minutes' AND source_quality IS NULL AND source_coverage IS NULL",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE direction='b-to-a'",Integer.class));
        assertEquals(24,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_link_direction",Integer.class));
        var slice = jdbc.queryForMap("SELECT payload->>'traffic_gbps' traffic,source_row FROM app.trace_source_native_window WHERE dataset_id=? AND source_kind='LINK' AND scope_id='chi-orh' AND direction='a-to-b' AND window_start>=? AND window_start<?",DATASET,Timestamp.from(java.time.Instant.parse("2026-09-10T09:00:00Z")),Timestamp.from(java.time.Instant.parse("2026-09-10T09:05:00Z")));
        assertEquals("11.680978",slice.get("traffic"));
        assertEquals("CONTRACT_ONLY",jdbc.queryForObject("SELECT activation_status FROM app.trace_source_catalogue",String.class));
    }
    @Test void interruptedCommittedBatchResumesWithNewInstance() throws Exception {
        var interrupted = new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),3,file -> { throw new IllegalStateException("simulated stop after committed batch"); });
        assertThrows(IllegalStateException.class,() -> interrupted.execute(source));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_import_completion",Integer.class));
        var before = jdbc.queryForList("SELECT natural_id,source_row_sha256 FROM app.trace_source_native_window ORDER BY natural_id");
        assertEquals(42,bootstrap().execute(source).nativeRows());
        assertTrue(jdbc.queryForList("SELECT natural_id,source_row_sha256 FROM app.trace_source_native_window ORDER BY natural_id").containsAll(before));
    }
    @Test void checksumAndRowCountFailBeforeAnyWrites() throws Exception {
        Files.writeString(source.resolve("link_kpi_5m.csv"),"corruption",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,() -> bootstrap().execute(source));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_dataset",Integer.class));
    }
    @Test void incorrectRowCountAndConflictingManifestFail() throws Exception {
        var m = manifest(); ((ObjectNode)m.path("files").get(3)).put("rows",9); writeManifest(m);
        assertThrows(IllegalArgumentException.class,() -> bootstrap().execute(source));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_dataset",Integer.class));
        ((ObjectNode)m.path("files").get(3)).put("rows",10); writeManifest(m);
        var first = bootstrap().execute(source);
        m.put("generator_version","conflicting-generator"); writeManifest(m);
        assertThrows(IllegalArgumentException.class,() -> bootstrap().execute(source));
        assertEquals(first.manifestSha256(),jdbc.queryForObject("SELECT manifest_sha256 FROM app.trace_source_dataset",String.class));
        assertEquals(42,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
    }
    @Test void duplicateNaturalSourceRowCannotSatisfyCompleteCount() throws Exception {
        Path file = source.resolve("link_kpi_5m.csv"); var lines = Files.readAllLines(file); lines.set(2,lines.get(1)); Files.write(file,lines);
        var m = manifest();
        for (var f : m.path("files")) if (f.path("name").asText().equals(file.getFileName().toString())) {
            ((ObjectNode)f).put("sha256",TraceLinkArchive.hashFile(file)); ((ObjectNode)f).put("bytes",Files.size(file));
        }
        writeManifest(m);
        assertThrows(IllegalArgumentException.class,() -> bootstrap().execute(source));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_dataset",Integer.class));
    }
    @Test void concurrentExactImportsShareOneCompletion() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> bootstrap().execute(source)); var b = pool.submit(() -> bootstrap().execute(source));
            assertEquals(a.get(45,TimeUnit.SECONDS),b.get(45,TimeUnit.SECONDS));
        }
        assertEquals(42,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_import_completion",Integer.class));
    }
    @Test void rejectedBatchRollsBackRowsAndCheckpointTogether() throws Exception {
        var owner = new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        owner.execute("ALTER TABLE app.trace_source_native_window ADD CONSTRAINT test_batch_failure CHECK (source_row_number <> 2)");
        assertThrows(DataAccessException.class,() -> bootstrap().execute(source));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT sum(rows_imported) FROM app.trace_source_import_checkpoint",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_import_completion",Integer.class));
        owner.execute("ALTER TABLE app.trace_source_native_window DROP CONSTRAINT test_batch_failure");
        assertEquals(42,bootstrap().execute(source).nativeRows());
    }
    @Test void isolatedProfileStartsWithoutKafkaOrLiveWorkers() throws Exception {
        var application = new org.springframework.boot.SpringApplication(TraceLinkBootstrapApplication.class);
        application.setAdditionalProfiles("trace-link-bootstrap");
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (var context = application.run("--PROCESSING_DB_URL="+url,
                "--PROCESSING_DB_USER=processing_app","--PROCESSING_DB_PASSWORD=test-runtime",
                "--PROCESSING_MIGRATOR_USER=processing_migrator","--PROCESSING_MIGRATOR_PASSWORD=test-migrator")) {
            assertFalse(context.containsBean("observationInput"));
            assertFalse(context.containsBean("windowFinalizer"));
            assertFalse(context.containsBean("retentionJob"));
            assertEquals(0,context.getBeanNamesForType(org.springframework.kafka.core.KafkaTemplate.class).length);
            var isolated = new TraceLinkDatasetBootstrap(context.getBean(JdbcTemplate.class),
                    context.getBean(org.springframework.transaction.PlatformTransactionManager.class),3);
            assertEquals(42,isolated.execute(source).nativeRows());
        }
    }
    @Test void sourceRowsAndManifestAreImmutableAndRuntimeHasNoDdl() throws Exception {
        bootstrap().execute(source);
        for (String table : List.of("trace_source_dataset","trace_source_native_window","trace_source_catalogue","trace_source_city","trace_source_link","trace_source_link_direction","trace_source_import_completion"))
            for (String privilege : List.of("UPDATE","DELETE","TRUNCATE"))
                assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"app."+table,privilege));
        assertThrows(DataAccessException.class,() -> jdbc.execute("UPDATE app.trace_source_native_window SET payload='{}'"));
        assertThrows(DataAccessException.class,() -> jdbc.execute("UPDATE app.trace_source_dataset SET manifest='{}'"));
        assertThrows(DataAccessException.class,() -> jdbc.execute("CREATE TABLE app.trace_forbidden(id int)"));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user,'incidents_db','CONNECT')",Boolean.class));
    }
    @Test void enabledRetentionPreservesUnfinishedImportThenCompletedProvenance() throws Exception {
        var interrupted = new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),3,
                file -> { throw new IllegalStateException("stop after committed batch"); });
        assertThrows(IllegalStateException.class, () -> interrupted.execute(source));
        var protectedTables = List.of("trace_source_catalogue", "trace_source_city", "trace_source_link",
                "trace_source_link_direction", "trace_source_dataset", "trace_source_native_window",
                "trace_source_import_checkpoint", "trace_source_import_completion");
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-12-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        var guard = new md.utm.telecom.processing.outbox.RawRetentionGuard(null,"test.retention.raw") {
            @Override public boolean permits(java.time.Duration horizon) { return true; }
        }; // Real deletion SQL; the existing RetentionJobTest independently verifies the live broker guard.
        var retention = new md.utm.telecom.processing.outbox.RetentionJob(jdbc,clock,
                new md.utm.telecom.processing.outbox.RetentionProperties(true,java.time.Duration.ofHours(48),
                        java.time.Duration.ofHours(48),java.time.Duration.ofHours(24),100,java.time.Duration.ofMinutes(1)),guard);
        for (int phase=0; phase<2; phase++) {
            var before = new LinkedHashMap<String,Object>();
            for (var table:protectedTables) before.put(table,jdbc.queryForList("SELECT row_to_json(t)::text FROM app."+table+" t ORDER BY 1"));
            jdbc.update("""
                    INSERT INTO app.rejection_outbox(payload_hash,reason_code,reason_detail,kafka_topic,kafka_partition,
                        kafka_offset,payload_size,payload_truncated,created_at,published_at)
                    VALUES (repeat('a',64),'MALFORMED_JSON','retention test','test.retention.raw',0,?,0,false,
                        '2026-09-01T00:00:00Z','2026-09-01T00:00:00Z')
                    """, phase);
            assertEquals(new md.utm.telecom.processing.outbox.RetentionJob.Result(0,1),retention.poll());
            for (var table:protectedTables) assertEquals(before.get(table),jdbc.queryForList("SELECT row_to_json(t)::text FROM app."+table+" t ORDER BY 1"),table);
            if (phase==0) assertEquals(42,bootstrap().execute(source).nativeRows());
        }
    }
    @Test void v013UpgradeAndFreshInstallHaveSameTablesAndGrants() throws Exception {
        String name = "trace_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var admin = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE "+name+" OWNER processing_migrator");
        String upgradeUrl = PostgresFixture.url(name);
        var owner = new JdbcTemplate(new DriverManagerDataSource(upgradeUrl,"processing_migrator","test-migrator"));
        owner.execute("CREATE SCHEMA app"); owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(upgradeUrl,"processing_migrator","test-migrator").defaultSchema("app").schemas("app").target("13").load().migrate();
        owner.execute("INSERT INTO app.historical_bootstrap VALUES ('preserved','2026-09-01T00:00Z','2026-09-02T00:00Z',42,now())");
        var upgrade = Flyway.configure().dataSource(upgradeUrl,"processing_migrator","test-migrator").defaultSchema("app").schemas("app").load();
        assertEquals(2,upgrade.migrate().migrationsExecuted); upgrade.validate();
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM app.historical_bootstrap WHERE bootstrap_id='preserved'",Integer.class));
        String tables = "SELECT tablename FROM pg_tables WHERE schemaname='app' ORDER BY tablename";
        assertEquals(jdbc.queryForList(tables),owner.queryForList(tables));
        String grants = "SELECT table_name,privilege_type FROM information_schema.table_privileges WHERE grantee='processing_app' AND table_name LIKE 'trace_source_%' ORDER BY table_name,privilege_type";
        var freshOwner = new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        assertEquals(freshOwner.queryForList(grants),owner.queryForList(grants));
    }
    @Test void suppliedFullArchiveImportsOnlyWhenExplicitlySelected() throws Exception {
        String root = System.getProperty("traceLink.referenceRoot");
        Assumptions.assumeTrue(root != null,"Reference archive must be selected explicitly");
        var importer = new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),2000);
        var result = importer.execute(Path.of(root).resolve("data"));
        assertEquals("trace-link-md-30d-v1",result.datasetId()); assertEquals(362880,result.nativeRows());
        assertEquals(result,importer.execute(Path.of(root).resolve("data")));
        assertEquals(8640,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE source_kind='CITY' AND scope_id='chi'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE source_resolution_seconds<>300 OR direction='b-to-a'",Integer.class));
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("datasetId",result.datasetId()); evidence.put("manifestSha256",result.manifestSha256());
        evidence.put("nativeRows",result.nativeRows()); evidence.put("exactRetryEqual",true);
        evidence.put("sourceCounts",jdbc.queryForList("SELECT source_kind,count(*) rows FROM app.trace_source_native_window GROUP BY source_kind ORDER BY source_kind"));
        evidence.put("nativeBounds",jdbc.queryForList("SELECT min(window_start)::text first,max(window_end)::text to_exclusive,min(source_resolution_seconds) source_resolution_seconds FROM app.trace_source_native_window"));
        evidence.put("sourceQualityNotSupplied",jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE source_quality IS NULL AND source_coverage IS NULL",Long.class));
        evidence.put("scenarioTruthRowsVerifiedOnly",18);
        evidence.put("authenticatedBackendRead","NOT_VERIFIED"); evidence.put("receivingOwnerReproduction","PENDING");
        TraceLinkArchive.JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-native-import-result.json").toFile(),evidence);
    }
}
