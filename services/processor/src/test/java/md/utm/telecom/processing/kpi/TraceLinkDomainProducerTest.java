package md.utm.telecom.processing.kpi;

import java.nio.file.*;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import md.utm.telecom.processing.history.TraceLinkNativeReader.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TraceLinkDomainProducerTest extends TraceLinkPrimitiveTestSupport {
    @TempDir Path output;
    @Test void artifactsRetryWithoutInflatingProducedCountAndConflictsNeverOverwrite() throws Exception {
        var registry=new SimpleMeterRegistry();
        try {
            var producer=new TraceLinkDomainProducer(reader,registry); Path target=output.resolve("transport.jsonl");
            assertEquals(5,producer.write(dataset.id(),normal.scope(),START,START.plusSeconds(300),target));
            byte[] original=Files.readAllBytes(target);
            assertEquals(5,producer.write(dataset.id(),normal.scope(),START,START.plusSeconds(300),target)); assertArrayEquals(original,Files.readAllBytes(target));
            assertEquals(5,registry.get("telecom.trace_link.producer.observations").tags("domain","transport","outcome","produced").counter().count());
            assertEquals(5,registry.get("telecom.trace_link.producer.observations").tags("domain","transport","outcome","duplicate").counter().count());
            Files.writeString(target,"conflicting artifact");
            assertThrows(IllegalArgumentException.class,()->producer.write(dataset.id(),normal.scope(),START,START.plusSeconds(300),target));
            assertEquals("conflicting artifact",Files.readString(target));
            assertThrows(IllegalArgumentException.class,()->producer.calculate("not-complete",normal.scope(),START,START.plusSeconds(300)));
            assertEquals(2,registry.get("telecom.trace_link.producer.observations").tags("domain","transport","outcome","invalid").counter().count());
            for(var meter:registry.getMeters()) for(var tag:meter.getId().getTags()) assertTrue(Set.of("domain","outcome").contains(tag.getKey()));
            assertEquals(4,registry.get("telecom.trace_link.primitive.duration").tag("domain","transport").timer().count());
            try(var files=Files.list(output)) { assertEquals(1,files.count()); }
        } finally { registry.close(); }
    }
    @Test void exportNativeExamplesAndReadOnlySourceMembership() throws Exception {
        var registry=new SimpleMeterRegistry();
        try {
            var producer=new TraceLinkDomainProducer(reader,registry); var examples=JSON.createArrayNode();
            for(var scope:List.of(normal.scope(),new Scope("ROAMING","RO-DEMO-01","out","RO"),new Scope("CITY","chi","",""),new Scope("LINK","chi-orh","b-to-a","")))
                producer.calculate(dataset.id(),scope,START,START.plusSeconds(300)).forEach(examples::add);
            Path destination=Path.of("target/trace-link-domain-primitives.json"); Files.createDirectories(destination.getParent());
            byte[] bytes=(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(examples).replace("\r\n","\n")+"\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(destination,bytes);
            Path fixture=Path.of("../../contracts/fixtures/trace-link/domain-primitive-results-v1.json");
            if(Files.exists(fixture)) assertArrayEquals(Files.readAllBytes(fixture),bytes,"Versioned producer fixture changed");
            assertEquals(42,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window WHERE direction='b-to-a'",Integer.class));
        } finally { registry.close(); }
    }
    @Test void isolatedEntrypointUsesCompletedImportWithoutStartingLiveWorkers() throws Exception {
        Path target=output.resolve("entrypoint.jsonl");
        String url;
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) { url=connection.getMetaData().getURL(); }
        md.utm.telecom.processing.history.TraceLinkDomainProducerApplication.run(new String[]{
                "--spring.datasource.url="+url,"--spring.datasource.username=processing_app","--spring.datasource.password=test-runtime",
                "--telecom.trace-link.producer.kind=LINK","--telecom.trace-link.producer.scope=chi-orh","--telecom.trace-link.producer.direction=a-to-b",
                "--telecom.trace-link.producer.dataset="+dataset.id(),"--telecom.trace-link.producer.from="+START,
                "--telecom.trace-link.producer.to="+START.plusSeconds(300),"--telecom.trace-link.producer.output="+target});
        assertEquals(5,Files.readAllLines(target).size()); assertEquals(42,jdbc.queryForObject("SELECT count(*) FROM app.trace_source_native_window",Integer.class));
    }
}
