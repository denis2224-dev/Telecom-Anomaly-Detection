package md.utm.telecom.processing.kpi;

import java.nio.file.*;
import java.util.UUID;
import md.utm.telecom.observation.DomainObservationValidator;
import md.utm.telecom.processing.PostgresFixture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import md.utm.telecom.processing.history.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkV016CompatibilityTest {
    @Test
    @EnabledIfSystemProperty(named="trace.link.v016.directory",matches=".+")
    void frozenV016MigratesAndRemainsHeldWithoutAnyProducerSchemaChange(@TempDir Path artifacts) throws Exception {
        Path directory=Path.of(System.getProperty("trace.link.v016.directory"));
        String expected=System.getProperty("trace.link.v016.sha256"); assertNotNull(expected);
        assertEquals(expected,DomainObservationValidator.sha256(Files.readString(directory.resolve("V016__trace_link_domain_windows.sql"))));
        String database="trace_v016_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE "+database+" OWNER processing_migrator");
        var owner=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url(database),"processing_migrator","test-migrator")); owner.execute("CREATE SCHEMA app");
        var flyway=Flyway.configure().dataSource(PostgresFixture.url(database),"processing_migrator","test-migrator")
                .schemas("app").defaultSchema("app").createSchemas(false).locations("classpath:db/migration","filesystem:"+directory.toAbsolutePath()).load();
        flyway.migrate(); flyway.validate(); assertEquals("016",flyway.info().current().getVersion().toString());
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM app.trace_projection_outbox",Integer.class));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM app.trace_metric_projection",Integer.class));
        assertTrue(owner.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='app.trace_projection_outbox'::regclass AND contype='c'",String.class).contains("PENDING_REVIEW"));
        owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        var runtime=new DriverManagerDataSource(PostgresFixture.url(database),"processing_app","test-runtime");
        var jdbc=new JdbcTemplate(runtime);
        new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),20).execute(Path.of("../../contracts/fixtures/trace-link/native-slice"));
        String heldId=DomainObservationValidator.sha256("frozen-v016-compatibility-held-row"), hash=DomainObservationValidator.sha256("{}");
        // Minimal VoLTE-only SQL fixture tests held storage, not consumer payload approval.
        owner.update("""
                INSERT INTO app.trace_metric_projection(projection_id,dataset_id,source_natural_id,source_namespace,domain,metric_id,
                  scope_type,scope_id,contract_city_id,window_start,window_end,source_resolution_seconds,schema_version,policy_version,
                  topology_version,unit,aggregation_type,numerator,denominator,source_quality,source_coverage,revision,finalized,payload,payload_text,payload_sha256)
                SELECT ?,dataset_id,natural_id,scope_namespace,'VOLTE','volte.setup.success','CITY',scope_id,contract_city_id,
                  window_start,window_end,300,'trace-link-volte-setup-projection-v1','trace-link-kpi-v1','trace-link-links-v1',
                  'PERCENT','RATIO_OF_SUMS',(payload->>'voice_success')::numeric,(payload->>'voice_attempts')::numeric,NULL,NULL,0,true,'{}'::jsonb,'{}',?
                FROM app.trace_source_native_window WHERE dataset_id='trace-link-bounded-fixture-v1' AND source_kind='CITY' AND scope_id='chi'
                """,heldId,hash);
        owner.update("INSERT INTO app.trace_projection_outbox(projection_id,payload_sha256,partition_key) VALUES (?,?,?)",heldId,hash,"frozen-compatibility");
        var before=owner.queryForList("SELECT * FROM app.trace_projection_outbox");
        var registry=new SimpleMeterRegistry();
        try {
            new TraceLinkDomainProducer(new TraceLinkNativeReader(jdbc),registry).write("trace-link-bounded-fixture-v1",
                    new TraceLinkNativeReader.Scope("LINK","chi-orh","a-to-b",""),java.time.Instant.parse("2026-09-10T09:00:00Z"),
                    java.time.Instant.parse("2026-09-10T09:05:00Z"),artifacts.resolve("transport.jsonl"));
        } finally { registry.close(); }
        assertEquals(before,owner.queryForList("SELECT * FROM app.trace_projection_outbox"));
        assertEquals(1,owner.queryForObject("SELECT count(*) FROM app.trace_metric_projection",Integer.class));
        assertEquals(hash,owner.queryForObject("SELECT payload_sha256 FROM app.trace_metric_projection WHERE projection_id=?",String.class,heldId));
    }
}
