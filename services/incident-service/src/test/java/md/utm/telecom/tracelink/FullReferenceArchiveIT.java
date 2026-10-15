package md.utm.telecom.tracelink;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import md.utm.telecom.tracelink.metrics.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit external source, real importer and disposable PostgreSQL; no shared runtime. */
class FullReferenceArchiveIT {
    @TempDir Path temporary;
    private static final String DATASET="trace-link-md-30d-v1";
    private static final Instant END=Instant.parse("2026-10-10T09:00:00Z");

    @Test @Timeout(300) void importsRetriesQueriesAndRestoresTheFullPinnedArchive() throws Exception {
        String source=System.getProperty("traceLink.referenceRoot");
        Assumptions.assumeTrue(source!=null,"Select the actual external reference pack explicitly");
        Path root=Path.of("").toAbsolutePath();while(!Files.isRegularFile(root.resolve("scripts/import-trace-link-reference.py")))root=root.getParent();
        var json=new ObjectMapper();
        prepareArchive(root,Path.of(source),temporary);
        try(var pg=new PostgreSQLContainer("postgres:16.4-alpine").withDatabaseName("incidents_db").withInitScript("db/context-test-init.sql")) {
            pg.start();
            var flyway=Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).defaultSchema("app").schemas("app").locations("classpath:db/migration").load();flyway.migrate();
            var jdbc=new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()));
            importArchive(pg,temporary);
            var first=identity(jdbc);
            assertEquals(362880L,jdbc.queryForObject("SELECT count(*) FROM app.trace_reference_window",Long.class));
            var counts=jdbc.queryForList("SELECT source_kind,count(*) rows FROM app.trace_reference_window GROUP BY source_kind ORDER BY source_kind");
            assertEquals(List.of(86400L,103680L,172800L),counts.stream().map(row->row.get("rows")).toList());
            run(pg,"psql","-X","-q","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-f","/tmp/reference/import.sql");
            assertEquals(first,identity(jdbc),"Exact retry must preserve every identity/hash and completion timestamp");
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_catalogue WHERE active",Integer.class));
            var definitions=new MetricDefinitionRegistry(json);var reference=new ReferenceMetricRollupRepository(jdbc,json);
            var service=new MetricQueryService(definitions,new MetricRollupRepository(jdbc),new MetricScopeRepository(jdbc),reference);
            var cardinalities=new LinkedHashMap<Integer,Integer>();
            for(int hours:List.of(24,168,720)) {
                var response=service.series(new TraceQuery(DATASET,"volte","volte.setup.success","CITY","chi",END.minusSeconds(hours*3600L),END,"5m"));
                assertEquals(hours*12,response.points().size());assertTrue(response.points().stream().allMatch(p->p.value()!=null));
                cardinalities.put(hours,response.points().size());
            }
            int comparisons=0;
            for(var definition:definitions.list(null)) {
                if(definition.metric().equals("voice.success_rate"))continue;
                String domain=definition.domain(),scopeType=domain.equals("transport")?"LINK_DIRECTION":domain.equals("roaming")?"PARTNER_DIRECTION":"CITY";
                String scope=domain.equals("transport")?"chi-hin:a-to-b":domain.equals("roaming")?"RO-DEMO-01:"+(definition.metric().equals("roaming.activeInbound")?"in":"out"):"chi";
                for(String resolution:List.of("5m","15m","1h","6h")) {
                    var query=new TraceQuery(DATASET,domain,definition.metric(),scopeType,scope,END.minusSeconds(86400),END,resolution);
                    var response=service.series(query);
                    var sql=jdbc.queryForList("SELECT * FROM app.trace_reference_series(?,?,?,?,?,?)",DATASET,definition.metric(),scope,Timestamp.from(query.from()),Timestamp.from(query.to()),resolution);
                    assertEquals(response.points().size(),sql.size());
                    for(int index=0;index<sql.size();index++) {
                        var expected=response.points().get(index);var actual=sql.get(index);String label=definition.metric()+" "+resolution+" "+index;
                        if(expected.value()==null)assertNull(actual.get("value"),label);
                        else {assertNotNull(actual.get("value"),label);assertTrue(expected.value().subtract((BigDecimal)actual.get("value")).abs().compareTo(new BigDecimal("0.000001"))<=0,label);}
                        assertEquals(expected.quality(),actual.get("quality"),label);
                        assertEquals(0,expected.coverage().compareTo((BigDecimal)actual.get("coverage")),label);
                        comparisons++;
                    }
                }
            }
            run(pg,"pg_dump","-Fc","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-f","/tmp/reference/full.dump");
            run(pg,"createdb","-U",pg.getUsername(),"reference_restored");
            run(pg,"pg_restore","--exit-on-error","--no-owner","-U",pg.getUsername(),"-d","reference_restored","/tmp/reference/full.dump");
            String restoredUrl=pg.getJdbcUrl().replace("/incidents_db","/reference_restored");
            var restored=new JdbcTemplate(new DriverManagerDataSource(restoredUrl,pg.getUsername(),pg.getPassword()));
            assertEquals(first,identity(restored));
            Flyway.configure().dataSource(restoredUrl,pg.getUsername(),pg.getPassword()).defaultSchema("app").schemas("app").locations("classpath:db/migration").load().validate();
            assertFalse(restored.queryForObject("SELECT has_table_privilege('incidents_app','app.trace_reference_window','UPDATE')",Boolean.class));
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("datasetId",DATASET);evidence.put("sourceCounts",counts);evidence.put("nativeRows",362880);evidence.put("exactRetry","PASS_IDENTITIES_HASHES_COMPLETION");
            evidence.put("catalogueActivated",false);evidence.put("cardinalitiesByHours",cardinalities);evidence.put("metricDefinitions",41);evidence.put("resolutions",List.of("5m","15m","1h","6h"));
            evidence.put("backendSqlBucketComparisons",comparisons);evidence.put("tolerance","0.000001");evidence.put("fullArchiveDumpRestore","PASS_IDENTITIES_HASHES_FLYWAY_PRIVILEGES");
            evidence.put("mode","Actual full pinned archive in disposable PostgreSQL; direct backend/SQL calls, not authenticated browser acceptance");
            evidence.put("completeReleaseGatesPassed",false);
            Files.writeString(Path.of("target/trace-link-full-reference-result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }
    static void prepareArchive(Path root,Path source,Path temporary) throws Exception {
        // Execute the production validator/SQL builder without generating any source data.
        String prepare="""
                import csv,importlib.util,pathlib,sys
                spec=importlib.util.spec_from_file_location('reference_import',sys.argv[1])
                module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
                snapshot=pathlib.Path(sys.argv[3]);manifest,checksum=module.validate(pathlib.Path(sys.argv[2]),snapshot)
                with (snapshot/'projection.csv').open('w',newline='') as stream:
                    writer=csv.writer(stream);count=0
                    for row in module.native_rows(snapshot,manifest):writer.writerow(row);count+=1
                assert count==362880
                (snapshot/'import.sql').write_text('SET ROLE incidents_app;\\n'+module.import_sql(manifest,checksum,pathlib.Path('/tmp/reference/projection.csv')))
                """;
        var process=new ProcessBuilder("python3","-c",prepare,root.resolve("scripts/import-trace-link-reference.py").toString(),source.resolve("data").toString(),temporary.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("prepare.log").toFile()).start();
        assertTrue(process.waitFor(90,TimeUnit.SECONDS));
        assertEquals(0,process.exitValue(),()->{try{return Files.readString(temporary.resolve("prepare.log"));}catch(Exception e){return "Archive preparation failed";}});
    }
    static void importArchive(PostgreSQLContainer pg,Path temporary) throws Exception {
        pg.copyFileToContainer(MountableFile.forHostPath(temporary.resolve("projection.csv")),"/tmp/reference/projection.csv");
        pg.copyFileToContainer(MountableFile.forHostPath(temporary.resolve("import.sql")),"/tmp/reference/import.sql");
        run(pg,"psql","-X","-q","-U",pg.getUsername(),"-d",pg.getDatabaseName(),"-f","/tmp/reference/import.sql");
    }
    private static Map<String,Object> identity(JdbcTemplate jdbc) {
        return jdbc.queryForMap("SELECT (SELECT md5(string_agg(scope_id||window_start::text||source_row_sha256,'' ORDER BY source_kind,scope_id,window_start)) FROM app.trace_reference_window) digest,completed_at FROM app.trace_reference_import WHERE dataset_id=?",DATASET);
    }
    private static void run(PostgreSQLContainer pg,String... command) throws Exception {
        var result=pg.execInContainer(command);assertEquals(0,result.getExitCode(),result.getStderr());
    }
}
