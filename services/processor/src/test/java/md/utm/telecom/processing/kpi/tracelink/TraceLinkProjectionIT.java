package md.utm.telecom.processing.kpi.tracelink;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.history.TraceLinkDatasetBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static md.utm.telecom.processing.kpi.tracelink.TraceLinkProjection.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkProjectionIT {
    @TempDir Path source;
    private JdbcTemplate jdbc, owner, admin;
    private DriverManagerDataSource runtime;
    private String database, url;
    private String dataset = "trace-link-bounded-fixture-v1";
    private static final Instant START = Instant.parse("2026-09-10T09:00:00Z");

    @BeforeEach void isolatedDatabaseAndArchive() throws Exception {
        database = "projection_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE " + database + " OWNER processing_migrator");
        admin.execute("REVOKE CONNECT ON DATABASE " + database + " FROM PUBLIC");
        admin.execute("GRANT CONNECT ON DATABASE " + database + " TO processing_app");
        url = PostgresFixture.url(database);
        owner = new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        owner.execute("CREATE SCHEMA app"); owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(url,"processing_migrator","test-migrator").schemas("app")
                .defaultSchema("app").createSchemas(false).load().migrate();
        runtime = new DriverManagerDataSource(url,"processing_app","test-runtime"); jdbc = new JdbcTemplate(runtime);
        for (String file : List.of("manifest.json","cities.csv","links.csv","countries.csv","city_kpi_5m.csv","link_kpi_5m.csv","roaming_kpi_5m.csv","scenario_events.csv")) {
            try (var stream = getClass().getResourceAsStream("/contracts/fixtures/trace-link/native-slice/"+file)) {
                assertNotNull(stream); Files.copy(stream,source.resolve(file));
            }
        }
    }
    private void imported() throws Exception { new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),2000).execute(source); }
    private TraceLinkProjectionService projector() throws Exception { return new TraceLinkProjectionService(jdbc,new DataSourceTransactionManager(runtime)); }
    private Request request(int slots) { return Request.city(dataset,"chi",START,START.plusSeconds(300L*slots)); }
    private TraceLinkRollupService.Series series(int slots,String resolution) throws Exception { return new TraceLinkRollupService(jdbc).series(request(slots),resolution); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM app."+table,Integer.class); }

    @Test void actualImportedSlicePreservesPrimitiveAndProvenanceAndHeldDelivery() throws Exception {
        imported();
        var before=jdbc.queryForList("SELECT natural_id,source_row_sha256,payload FROM app.trace_source_native_window ORDER BY natural_id");
        for (String city : List.of("chi","bal","cah","ung","orh","sor","com","edi","hin","cau"))
            assertEquals(1,projector().project(Request.city(dataset,city,START,START.plusSeconds(300))).inserted());
        assertEquals(10,count("trace_metric_projection")); assertEquals(10,count("trace_projection_outbox"));
        assertEquals(before,jdbc.queryForList("SELECT natural_id,source_row_sha256,payload FROM app.trace_source_native_window ORDER BY natural_id"));
        var message=JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.trace_metric_projection WHERE scope_id='chi'",String.class));
        assertEquals(7900,message.path("primitives").path("numerator").asInt());
        assertEquals(7957,message.path("primitives").path("denominator").asInt());
        assertEquals("CHI",message.path("scope").path("contractCityId").asText());
        assertEquals("INSUFFICIENT_EVIDENCE",message.path("quality").path("state").asText());
        assertTrue(message.path("sourceQuality").isNull()); assertTrue(message.path("sourceCoverage").isNull());
        assertEquals(1,message.path("provenance").path("sourceRowNumber").asInt());
        try (var fixture=getClass().getResourceAsStream("/contracts/fixtures/trace-link/primitive-projection-cases-v1.json")) {
            assertNotNull(fixture); assertEquals(JSON.readTree(fixture).path("nativeExample"),message);
        }
        assertEquals(jdbc.queryForObject("SELECT source_row_sha256 FROM app.trace_source_native_window WHERE source_kind='CITY' AND scope_id='chi'",String.class),message.path("provenance").path("sourceRowSha256").asText());
        assertEquals(10,jdbc.queryForObject("SELECT count(*) FROM app.trace_projection_outbox WHERE delivery_state='PENDING_REVIEW'",Integer.class));
        assertEquals(0,count("feature_outbox")); assertEquals(0,count("voice_delivery"));
        var point=series(1,"5m").points().getFirst(); assertEquals(new BigDecimal("7900"),point.numerator());
        assertEquals(100.0*7900/7957,point.value().doubleValue(),1e-12); assertEquals(BigDecimal.ONE,point.coverage());
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-projection-native-example.json").toFile(),message);
    }

    @Test void exactAndConcurrentRetryKeepOneIdentityPayloadAndOutbox() throws Exception {
        imported(); var first=projector().project(request(1)); assertEquals(1,first.inserted());
        var before=jdbc.queryForList("SELECT projection_id,payload_sha256,payload,created_at FROM app.trace_metric_projection");
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> projector().project(request(1))); var b=pool.submit(() -> projector().project(request(1)));
            assertEquals(1,a.get(30,TimeUnit.SECONDS).duplicates()); assertEquals(1,b.get(30,TimeUnit.SECONDS).duplicates());
        }
        assertEquals(before,jdbc.queryForList("SELECT projection_id,payload_sha256,payload,created_at FROM app.trace_metric_projection"));
        assertEquals(1,count("trace_projection_outbox"));
    }

    @Test void outboxWriteFailureRollsBackProjectionAndExactRetrySucceeds() throws Exception {
        imported(); owner.execute("ALTER TABLE app.trace_projection_outbox ADD CONSTRAINT test_reject CHECK (false)");
        assertThrows(DataAccessException.class,() -> projector().project(request(1)));
        assertEquals(0,count("trace_metric_projection")); assertEquals(0,count("trace_projection_outbox"));
        owner.execute("ALTER TABLE app.trace_projection_outbox DROP CONSTRAINT test_reject");
        assertEquals(1,projector().project(request(1)).inserted());
    }

    @Test void realPostgresConnectionLossBeforeCommitRollsBackBothRows() throws Exception {
        imported();
        owner.execute("CREATE FUNCTION app.test_disconnect() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_terminate_backend(pg_backend_pid()); RETURN NEW; END $$");
        owner.execute("CREATE TRIGGER test_disconnect BEFORE INSERT ON app.trace_projection_outbox FOR EACH ROW EXECUTE FUNCTION app.test_disconnect()");
        assertThrows(org.springframework.transaction.TransactionSystemException.class,() -> projector().project(request(1)));
        assertEquals(0,count("trace_metric_projection")); assertEquals(0,count("trace_projection_outbox"));
        owner.execute("DROP TRIGGER test_disconnect ON app.trace_projection_outbox");
        assertEquals(1,projector().project(request(1)).inserted());
    }

    @Test void realDatabaseUnavailableAfterCommitRetainsExactReplay() throws Exception {
        imported();
        var stopped=new TraceLinkProjectionService(jdbc,new DataSourceTransactionManager(runtime),message -> {
            admin.execute("ALTER DATABASE "+database+" ALLOW_CONNECTIONS false");
            jdbc.queryForObject("SELECT 1",Integer.class);
        });
        try { assertThrows(DataAccessException.class,() -> stopped.project(request(1))); }
        finally { admin.execute("ALTER DATABASE "+database+" ALLOW_CONNECTIONS true"); }
        var before=jdbc.queryForList("SELECT projection_id,payload_sha256,payload FROM app.trace_metric_projection");
        assertEquals(1,before.size()); assertEquals(1,count("trace_projection_outbox"));
        assertEquals(1,projector().project(request(1)).duplicates());
        assertEquals(before,jdbc.queryForList("SELECT projection_id,payload_sha256,payload FROM app.trace_metric_projection"));
    }

    @Test void runtimeCannotMutateFinalizedDataPublishOrAccessIncidentDatabase() throws Exception {
        imported(); projector().project(request(1));
        for (String table:List.of("trace_metric_projection","trace_projection_outbox"))
            for (String privilege:List.of("UPDATE","DELETE","TRUNCATE"))
                assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,?)",Boolean.class,"app."+table,privilege));
        assertThrows(DataAccessException.class,() -> jdbc.execute("UPDATE app.trace_metric_projection SET numerator=1"));
        assertThrows(DataAccessException.class,() -> jdbc.execute("UPDATE app.trace_projection_outbox SET delivery_state='PUBLISHED'"));
        assertThrows(DataAccessException.class,() -> jdbc.execute("CREATE TABLE app.forbidden_projection(id int)"));
        assertFalse(jdbc.queryForObject("SELECT has_database_privilege(current_user,'incidents_db','CONNECT')",Boolean.class));
        assertThrows(DataAccessException.class,() -> jdbc.execute("UPDATE app.feature_outbox SET window_end=window_start+interval '5 minutes'"));
    }

    @Test void conflictingPayloadRetryFailsWithoutChangingHeldIntent() throws Exception {
        imported(); projector().project(request(1));
        owner.execute("UPDATE app.trace_metric_projection SET payload=jsonb_set(payload,'{primitives,numerator}','1'),payload_text=jsonb_set(payload,'{primitives,numerator}','1')::text");
        assertThrows(IllegalArgumentException.class,() -> projector().project(request(1)));
        assertEquals(1,count("trace_metric_projection")); assertEquals(1,count("trace_projection_outbox"));
    }

    @Test void duplicateFinalizedNaturalSlotAndConflictingHeldKeyFailClosed() throws Exception {
        imported(); projector().project(request(1));
        // A different digest cannot bypass the finalized natural-slot uniqueness constraint.
        assertThrows(DataAccessException.class,() -> owner.execute("""
                INSERT INTO app.trace_metric_projection
                SELECT (jsonb_populate_record(NULL::app.trace_metric_projection,
                        to_jsonb(p) || jsonb_build_object('projection_id',repeat('0',64)))).*
                FROM app.trace_metric_projection p
                """));
        var before=jdbc.queryForList("SELECT projection_id,payload_text,payload_sha256 FROM app.trace_metric_projection");
        owner.execute("UPDATE app.trace_projection_outbox SET partition_key='conflicting-key'");
        assertThrows(IllegalArgumentException.class,() -> projector().project(request(1)));
        assertEquals(before,jdbc.queryForList("SELECT projection_id,payload_text,payload_sha256 FROM app.trace_metric_projection"));
        assertEquals(1,count("trace_projection_outbox"));
    }

    @Test void partiallyMissingBucketKeepsUnknownQualityAndExactContributingProvenance() throws Exception {
        twelveSlotArchive(); imported(); projector().project(request(1));
        var nativePoint=series(1,"5m").points().getFirst();
        var partial=series(3,"15m").points().getFirst();
        assertEquals(3,partial.expectedSlots()); assertEquals(1,partial.receivedSlots());
        assertEquals(1.0/3,partial.coverage().doubleValue(),1e-15);
        assertEquals("INSUFFICIENT_EVIDENCE",partial.quality());
        assertEquals("SOURCE_QUALITY_UNAVAILABLE",partial.reason());
        assertEquals(nativePoint.projectionIds(),partial.projectionIds());
        assertEquals(nativePoint.numerator(),partial.numerator());
        assertEquals(nativePoint.denominator(),partial.denominator());
        assertEquals(nativePoint.value(),partial.value());
        assertEquals(1,count("trace_metric_projection"));
    }

    @Test void emptyPopulationInPartialBucketIsInsufficientAndStaleRetentionTakesPrecedence() throws Exception {
        twelveSlotArchive(); imported();
        projector().project(Request.city(dataset,"chi",START.plusSeconds(600),START.plusSeconds(900)));
        // Inject metadata into this disposable projection fixture, without rewriting imported sources.
        owner.execute("UPDATE app.trace_metric_projection SET source_quality='VALID',source_coverage=1");
        var empty=series(3,"15m").points().getFirst();
        assertEquals(1,empty.receivedSlots()); assertEquals(3,empty.expectedSlots());
        assertNull(empty.value()); assertEquals("ZERO_DENOMINATOR",empty.nullReason());
        assertEquals("INSUFFICIENT_EVIDENCE",empty.quality());
        owner.execute("UPDATE app.trace_metric_projection SET source_quality='STALE',source_coverage=0");
        var stale=series(3,"15m").points().getFirst();
        assertEquals("STALE",stale.quality()); assertNull(stale.value());
        assertEquals(empty.projectionIds(),stale.projectionIds());
        assertEquals(empty.numerator(),stale.numerator()); assertEquals(empty.denominator(),stale.denominator());
    }

    @Test void primitiveRollupsAndWholeUtcBucketsNeverAveragePercentages() throws Exception {
        twelveSlotArchive(); imported(); projector().project(request(12));
        for (String resolution:List.of("5m","15m","1h","6h")) {
            var s=series(12,resolution); assertEquals(s.effectiveFrom(),s.points().getFirst().from());
            for (var p:s.points()) {
                assertEquals(0,Math.floorMod(p.from().getEpochSecond(),p.to().getEpochSecond()-p.from().getEpochSecond()));
                assertEquals("INSUFFICIENT_EVIDENCE",p.quality());
            }
        }
        var first=series(12,"15m").points().getFirst();
        assertEquals(999,first.numerator().intValueExact()); assertEquals(1010,first.denominator().intValueExact());
        assertEquals(98.91089108910891,first.value().doubleValue(),1e-12); assertEquals(3,first.receivedSlots());
        assertNotEquals(94.5,first.value().doubleValue());
        var zero=series(12,"5m").points().get(2); assertNull(zero.value()); assertEquals("ZERO_DENOMINATOR",zero.nullReason());
        var six=series(12,"6h").points().getFirst(); assertEquals(72,six.expectedSlots()); assertEquals(12,six.receivedSlots());
        assertTrue(six.partial()); assertEquals(START.minusSeconds(3*3600),six.from());
    }

    @Test void missingNativeSlotsAndPartialQueryEdgesStayExplicit() throws Exception {
        twelveSlotArchive(); imported();
        // Controlled source absence using the migration owner; runtime source rows remain immutable.
        owner.update("DELETE FROM app.trace_source_native_window WHERE source_kind='CITY' AND scope_id='chi' AND window_start>=? AND window_start<?",
                Timestamp.from(START.plusSeconds(900)),Timestamp.from(START.plusSeconds(1800)));
        assertEquals(9,projector().project(request(12)).sourceRows());
        var empty=series(12,"15m").points().get(1); assertNull(empty.numerator()); assertNull(empty.denominator());
        assertNull(empty.value()); assertEquals("MISSING",empty.quality()); assertEquals(0,empty.coverage().signum());
        assertEquals("NO_SOURCE_SLOTS",empty.nullReason());
        var edge=Request.city(dataset,"chi",START.plusSeconds(60),START.plusSeconds(840));
        var p=new TraceLinkRollupService(jdbc).series(edge,"15m").points().getFirst();
        assertTrue(p.partial()); assertEquals(1,p.receivedSlots()); assertEquals(9,p.numerator().intValueExact());
        assertEquals(10,p.denominator().intValueExact()); assertEquals(90,p.value().intValueExact());
        assertEquals(1.0/3,p.coverage().doubleValue(),1e-15);
    }

    @Test void mixedVersionsFailBeforeArithmeticAndBoundsCannotInventMeasurements() throws Exception {
        imported(); projector().project(request(1));
        for (String column:List.of("policy_version","topology_version","schema_version")) {
            String original=jdbc.queryForObject("SELECT "+column+" FROM app.trace_metric_projection",String.class);
            owner.execute("UPDATE app.trace_metric_projection SET "+column+"='incompatible-v2'");
            assertThrows(IllegalArgumentException.class,() -> series(1,"5m"));
            owner.update("UPDATE app.trace_metric_projection SET "+column+"=?",original);
        }
        assertThrows(IllegalArgumentException.class,() -> projector().project(request(2)));
        assertThrows(IllegalArgumentException.class,() -> new TraceLinkRollupService(jdbc).series(request(1),"1m"));
        assertThrows(IllegalArgumentException.class,() -> new TraceLinkRollupService(jdbc).series(Request.city(dataset,"chi",START.minusSeconds(1),START.plusSeconds(300)),"5m"));
        owner.execute("DELETE FROM app.trace_source_import_completion");
        assertThrows(IllegalArgumentException.class,() -> projector().project(request(1)));
    }

    @Test void isolatedToolHasNoLiveWorkersOrKafka() throws Exception {
        imported();
        var app=new org.springframework.boot.SpringApplication(TraceLinkProjectionApplication.class);
        app.setAdditionalProfiles("trace-link-projection"); app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (var context=app.run("--PROCESSING_DB_URL="+url,"--PROCESSING_DB_USER=processing_app","--PROCESSING_DB_PASSWORD=test-runtime",
                "--PROCESSING_MIGRATOR_USER=processing_migrator","--PROCESSING_MIGRATOR_PASSWORD=test-migrator")) {
            assertFalse(context.containsBean("observationListener")); assertFalse(context.containsBean("windowFinalizer"));
            assertFalse(context.containsBean("voiceDeliveryScheduler")); assertFalse(context.containsBean("retentionJob"));
            assertEquals(0,context.getBeanNamesForType(org.springframework.kafka.core.KafkaTemplate.class).length);
        }
    }

    @Test void v015UpgradeAndFreshInstallKeepV016GrantsWithV018Shadow() throws Exception {
        var flyway=Flyway.configure().dataSource(url,"processing_migrator","test-migrator").schemas("app").defaultSchema("app").load();
        flyway.validate();
        assertEquals("018",flyway.info().current().getVersion().toString());
        assertTrue(jdbc.queryForObject("SELECT string_agg(pg_get_constraintdef(oid),' ') FROM pg_constraint WHERE conrelid='app.feature_outbox'::regclass",String.class).contains("00:01:00"));
        String upgrade="upgrade_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+upgrade+" OWNER processing_migrator");
        var upgradeOwner=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url(upgrade),"processing_migrator","test-migrator"));
        upgradeOwner.execute("CREATE SCHEMA app"); upgradeOwner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(PostgresFixture.url(upgrade),"processing_migrator","test-migrator")
                .schemas("app").defaultSchema("app").target("15").load().migrate();
        var upgradeRuntime=new DriverManagerDataSource(PostgresFixture.url(upgrade),"processing_app","test-runtime");
        var upgradeJdbc=new JdbcTemplate(upgradeRuntime);
        new TraceLinkDatasetBootstrap(upgradeJdbc,new DataSourceTransactionManager(upgradeRuntime),2000).execute(source);
        var preserved=upgradeJdbc.queryForList("SELECT natural_id,source_row_sha256,payload FROM app.trace_source_native_window ORDER BY natural_id");
        var upgraded=Flyway.configure().dataSource(PostgresFixture.url(upgrade),"processing_migrator","test-migrator")
                .schemas("app").defaultSchema("app").load();
        assertEquals(3,upgraded.migrate().migrationsExecuted); upgraded.validate(); assertEquals(0,upgraded.migrate().migrationsExecuted);
        assertEquals(preserved,upgradeJdbc.queryForList("SELECT natural_id,source_row_sha256,payload FROM app.trace_source_native_window ORDER BY natural_id"));
        assertEquals(1,new TraceLinkProjectionService(upgradeJdbc,new DataSourceTransactionManager(upgradeRuntime)).project(request(1)).inserted());
        String grants="SELECT table_name,privilege_type FROM information_schema.role_table_grants WHERE grantee='processing_app' AND table_schema='app' AND table_name IN ('trace_metric_projection','trace_projection_outbox') ORDER BY table_name,privilege_type";
        assertEquals(owner.queryForList(grants),upgradeOwner.queryForList(grants));
        String columns="SELECT table_name,column_name,data_type,is_nullable FROM information_schema.columns WHERE table_schema='app' AND table_name IN ('trace_metric_projection','trace_projection_outbox') ORDER BY table_name,ordinal_position";
        assertEquals(owner.queryForList(columns),upgradeOwner.queryForList(columns));
    }

    @Test void fullSuppliedArchiveHasPersistedNativeAndThirtyDayRollupParity() throws Exception {
        String root=System.getProperty("traceLink.projectionReferenceRoot");
        Assumptions.assumeTrue(root!=null,"Full archive must be explicitly selected");
        var imported=new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),2000).execute(Path.of(root).resolve("data"));
        dataset=imported.datasetId(); assertEquals(362880,imported.nativeRows());
        for (int day=0;day<30;day++) {
            var from=START.plusSeconds(day*86400L); var req=Request.city(dataset,"chi",from,from.plusSeconds(86400));
            assertEquals(288,projector().project(req).inserted()); assertEquals(288,projector().project(req).duplicates());
        }
        var nativeSeries=series(30*288,"5m"); assertEquals(8640,nativeSeries.points().size());
        var rolled=series(30*288,"6h"); assertEquals(121,rolled.points().size()); // 09:00 history edges are partial UTC 6h buckets.
        assertEquals(8640,count("trace_metric_projection")); assertEquals(8640,count("trace_projection_outbox"));
        for (var point:rolled.points()) {
            var oracle=jdbc.queryForMap("SELECT sum((payload->>'voice_success')::numeric) n,sum((payload->>'voice_attempts')::numeric) d FROM app.trace_source_native_window WHERE dataset_id=? AND source_kind='CITY' AND scope_id='chi' AND window_start>=? AND window_start<?",
                    dataset,Timestamp.from(point.from().isBefore(START)?START:point.from()),Timestamp.from(point.to().isAfter(START.plusSeconds(30L*86400))?START.plusSeconds(30L*86400):point.to()));
            assertEquals(0,point.numerator().compareTo((BigDecimal)oracle.get("n"))); assertEquals(0,point.denominator().compareTo((BigDecimal)oracle.get("d")));
            assertEquals(ratio((BigDecimal)oracle.get("n"),(BigDecimal)oracle.get("d")),point.value());
            assertEquals("INSUFFICIENT_EVIDENCE",point.quality());
        }
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var report=JSON.createObjectNode().put("datasetId",dataset).put("manifestSha256",imported.manifestSha256())
                .put("nativeRows",imported.nativeRows()).put("projectionRows",count("trace_metric_projection"))
                .put("outboxRows",count("trace_projection_outbox")).put("native5mBuckets",nativeSeries.points().size())
                .put("rollup6hBuckets",rolled.points().size()).put("consumerAgreement","PENDING_REVIEW")
                .put("authenticatedSeries","BLOCKED").put("kafkaDelivery","BLOCKED").put("exactRetry","PASS");
        report.set("nativeExample",JSON.readTree(jdbc.queryForObject("SELECT payload::text FROM app.trace_metric_projection ORDER BY window_start LIMIT 1",String.class)));
        report.set("first6hBucket",mapper.valueToTree(rolled.points().getFirst()));
        report.set("firstWhole6hBucket",mapper.valueToTree(rolled.points().get(1)));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/trace-link-projection-full-result.json").toFile(),report);
    }

    private void twelveSlotArchive() throws Exception {
        dataset="trace-link-bounded-fixture-projection-v1";
        var manifest=(ObjectNode)JSON.readTree(source.resolve("manifest.json").toFile());
        manifest.put("dataset_id",dataset).put("generator_version","projection-test-12-slots-v1")
                .put("slots",12).put("to_exclusive",START.plusSeconds(3600).toString());
        for (String file:List.of("city_kpi_5m.csv","link_kpi_5m.csv","roaming_kpi_5m.csv")) {
            var original=Files.readAllLines(source.resolve(file)); var expanded=new ArrayList<String>(); expanded.add(original.getFirst());
            for (int slot=0;slot<12;slot++) for (String line:original.subList(1,original.size())) {
                String[] fields=line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)",-1);
                fields[0]=START.plusSeconds(slot*300L).toString();
                if (file.equals("city_kpi_5m.csv") && fields[1].equals("chi")) {
                    long n=slot==0?990:slot==1?9:0, d=slot==0?1000:slot==1?10:0;
                    fields[2]=Long.toString(d); fields[3]=Long.toString(n); fields[4]="0";
                    fields[8]="\"{"+n+",0,0,0,0,0,0,0,0}\"";
                }
                expanded.add(String.join(",",fields));
            }
            Files.write(source.resolve(file),expanded);
            for (var item:manifest.path("files")) if (item.path("name").asText().equals(file)) {
                ((ObjectNode)item).put("rows",expanded.size()-1).put("bytes",Files.size(source.resolve(file)))
                        .put("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.resolve(file)))));
            }
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(source.resolve("manifest.json").toFile(),manifest);
    }
}
