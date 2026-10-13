package md.utm.telecom.processing.kpi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import md.utm.telecom.observation.*;
import md.utm.telecom.processing.PostgresFixture;
import md.utm.telecom.processing.history.*;
import md.utm.telecom.processing.history.TraceLinkNativeReader.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class TraceLinkPrimitiveTestSupport {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Instant START=Instant.parse("2026-09-10T09:00:00Z");
    static TraceLinkNativeReader reader;
    static Dataset dataset;
    static TraceLinkDomainPrimitiveBuilder builder;
    static Window normal;
    static JdbcTemplate jdbc;
    @BeforeAll static void persistedNativeArchive() throws Exception {
        String name="trace_primitives_"+UUID.randomUUID().toString().replace("-","");
        var admin=new JdbcTemplate(new DriverManagerDataSource(PostgresFixture.url("postgres"),"test_admin","test-admin"));
        admin.execute("CREATE DATABASE "+name+" OWNER processing_migrator");
        admin.execute("REVOKE CONNECT ON DATABASE "+name+" FROM PUBLIC"); admin.execute("GRANT CONNECT ON DATABASE "+name+" TO processing_app");
        String url=PostgresFixture.url(name);
        var owner=new JdbcTemplate(new DriverManagerDataSource(url,"processing_migrator","test-migrator"));
        owner.execute("CREATE SCHEMA app"); owner.execute("GRANT USAGE ON SCHEMA app TO processing_app");
        Flyway.configure().dataSource(url,"processing_migrator","test-migrator").schemas("app").defaultSchema("app").createSchemas(false).load().migrate();
        var runtime=new DriverManagerDataSource(url,"processing_app","test-runtime"); jdbc=new JdbcTemplate(runtime);
        Path root=Path.of("../../contracts/fixtures/trace-link/native-slice").toAbsolutePath();
        new TraceLinkDatasetBootstrap(jdbc,new DataSourceTransactionManager(runtime),20).execute(root);
        reader=new TraceLinkNativeReader(jdbc); dataset=reader.dataset("trace-link-bounded-fixture-v1"); builder=new TraceLinkDomainPrimitiveBuilder();
        normal=reader.read(dataset,new Scope("LINK","chi-orh","a-to-b",""),START,START.plusSeconds(300)).getFirst();
    }
    static Window control(Window original, Instant start, Map<String,Object> changes) throws Exception {
        var manifest=(ObjectNode)original.dataset().manifest(); manifest.put("dataset_id","trace-link-primitive-controls-v1");
        manifest.put("to_exclusive",START.plusSeconds(900).toString()); manifest.put("slots",3);
        var d=new Dataset(manifest.path("dataset_id").asText(),DomainObservationValidator.sha256(manifest.toString()),manifest);
        var payload=(ObjectNode)original.payload(); payload.put("ts",start.toString());
        changes.forEach((k,v)->payload.set(k,JSON.valueToTree(v)));
        var layout=ObservationValidator.resource("datasets/trace-link-archive-layout-v1.json",JSON).path(original.file());
        var fields=new ArrayList<String>();
        for(var field:layout) {
            var value=payload.path(field.asText());
            String text=value.isArray()?"{"+String.join(",",java.util.stream.StreamSupport.stream(value.spliterator(),false).map(v->v.asText()).toList())+"}":value.asText();
            fields.add(text.contains(",")?"\""+text+"\"":text);
        }
        String raw=String.join(",",fields); var s=original.scope(); String partner=s.kind().equals("ROAMING")?s.id():"";
        String natural=DomainObservationValidator.sha256(String.join("|",d.id(),TraceLinkCatalog.NAMESPACE,s.kind(),s.id(),s.direction(),s.country(),partner,start.toString(),"300","trace-link-native-source-v1"));
        return new Window(d,s,start,natural,original.file(),original.rowNumber(),DomainObservationValidator.sha256(raw),raw,payload,null,null);
    }
    static ObjectNode produce(Window w,String metric) throws Exception { return builder.build(w.dataset(),w.scope(),w.start(),metric,w); }
}
